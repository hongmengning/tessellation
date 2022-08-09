package org.tessellation.sdk.infrastructure.consensus

import cats.effect._
import cats.kernel.Next
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Order, Show}

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.node.NodeState.{Observing, Ready}
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait ConsensusManager[F[_], Key, Artifact] {

  def startObservingAfter(lastKey: Key): F[Unit]
  def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact]): F[Unit]
  private[consensus] def facilitateOnEvent: F[Unit]
  private[consensus] def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]
  private[sdk] def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit]

}

object ConsensusManager {

  def make[F[_]: Async: Clock, Event, Key: Show: Order: Next: TypeTag: ClassTag, Artifact <: AnyRef: Show: TypeTag](
    timeTriggerInterval: FiniteDuration,
    consensusStorage: ConsensusStorage[F, Event, Key, Artifact],
    consensusStateUpdater: ConsensusStateUpdater[F, Key, Artifact],
    nodeStorage: NodeStorage[F]
  ): ConsensusManager[F, Key, Artifact] = new ConsensusManager[F, Key, Artifact] {

    private val logger = Slf4jLogger.getLoggerFromClass[F](ConsensusManager.getClass)

    def startObservingAfter(lastKey: Key): F[Unit] =
      Spawn[F].start {
        val nextKey = lastKey.next

        consensusStorage.setLastKey(lastKey) >>
          consensusStorage
            .getResources(nextKey)
            .flatMap { resources =>
              logger.debug(s"Trying to observe consensus {key=${nextKey.show}}") >>
                consensusStateUpdater.tryObserveConsensus(nextKey, lastKey, resources).flatMap {
                  case Some(_) =>
                    internalCheckForStateUpdate(nextKey, resources)
                  case None => Applicative[F].unit
                }
            }
            .handleErrorWith(logger.error(_)(s"Error observing consensus {key=${nextKey.show}}"))
      }.void

    def facilitateOnEvent: F[Unit] =
      Spawn[F].start {
        internalFacilitateWith(EventTrigger.some)
          .handleErrorWith(logger.error(_)(s"Error facilitating consensus with event trigger"))
      }.void

    def startFacilitatingAfter(lastKey: Key, lastArtifact: Signed[Artifact]): F[Unit] =
      consensusStorage.setLastKeyAndArtifact(lastKey, lastArtifact) >>
        scheduleFacility

    private def scheduleFacility: F[Unit] =
      Clock[F].monotonic.map(_ + timeTriggerInterval).flatMap { nextTimeValue =>
        consensusStorage.setTimeTrigger(nextTimeValue) >>
          Spawn[F].start {
            val condTriggerWithTime = for {
              maybeTimeTrigger <- consensusStorage.getTimeTrigger
              currentTime <- Clock[F].monotonic
              _ <- Applicative[F]
                .whenA(maybeTimeTrigger.exists(currentTime >= _))(internalFacilitateWith(TimeTrigger.some))
            } yield ()

            Temporal[F].sleep(timeTriggerInterval) >> condTriggerWithTime
              .handleErrorWith(logger.error(_)(s"Error triggering consensus with time trigger"))
          }.void
      }

    def checkForStateUpdate(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
      Spawn[F].start {
        internalCheckForStateUpdate(key, resources)
          .handleErrorWith(logger.error(_)(s"Error checking for consensus state update {key=${key.show}}"))
      }.void

    def checkForStateUpdateSync(key: Key)(resources: ConsensusResources[Artifact]): F[Unit] =
      internalCheckForStateUpdate(key, resources)

    private def internalFacilitateWith(
      trigger: Option[ConsensusTrigger]
    ): F[Unit] =
      consensusStorage.getLastKeyAndArtifact.flatMap { maybeLastKeyAndArtifact =>
        maybeLastKeyAndArtifact.traverse {
          case (lastKey, Some(lastArtifact)) =>
            val nextKey = lastKey.next

            consensusStorage
              .getResources(nextKey)
              .flatMap { resources =>
                logger.debug(s"Trying to facilitate consensus {key=${nextKey.show}, trigger=${trigger.show}}") >>
                  consensusStateUpdater.tryFacilitateConsensus(nextKey, lastKey, lastArtifact, trigger, resources).flatMap {
                    case Some(_) =>
                      internalCheckForStateUpdate(nextKey, resources)
                    case None => Applicative[F].unit
                  }
              }
          case _ => Applicative[F].unit
        }.void
      }

    private def internalCheckForStateUpdate(
      key: Key,
      resources: ConsensusResources[Artifact]
    ): F[Unit] =
      consensusStateUpdater.tryUpdateConsensus(key, resources).flatMap {
        case Some(state) =>
          state.status match {
            case Finished(signedArtifact, majorityTrigger) =>
              consensusStorage
                .tryUpdateLastKeyAndArtifactWithCleanup(state.lastKey, key, signedArtifact)
                .ifM(
                  afterConsensusFinish(majorityTrigger),
                  logger.info("Skip triggering another consensus")
                )
            case CollectingProposals(_, None) =>
              nodeStorage.tryModifyState(Observing, Ready) >>
                internalCheckForStateUpdate(key, resources)
            case _ =>
              internalCheckForStateUpdate(key, resources)
          }
        case None => Applicative[F].unit
      }

    private def afterConsensusFinish(majorityTrigger: ConsensusTrigger): F[Unit] =
      majorityTrigger match {
        case EventTrigger => afterEventTrigger
        case TimeTrigger  => afterTimeTrigger
      }

    private def afterEventTrigger: F[Unit] =
      for {
        maybeTimeTrigger <- consensusStorage.getTimeTrigger
        currentTime <- Clock[F].monotonic
        containsTriggerEvent <- consensusStorage.containsTriggerEvent
        _ <-
          if (maybeTimeTrigger.exists(currentTime >= _))
            internalFacilitateWith(TimeTrigger.some)
          else if (containsTriggerEvent)
            internalFacilitateWith(EventTrigger.some)
          else if (maybeTimeTrigger.isEmpty)
            internalFacilitateWith(none) // when there's no time trigger scheduled yet, trigger again with nothing
          else
            Applicative[F].unit
      } yield ()

    private def afterTimeTrigger: F[Unit] =
      scheduleFacility >> consensusStorage.containsTriggerEvent
        .ifM(internalFacilitateWith(EventTrigger.some), Applicative[F].unit)
  }

}
