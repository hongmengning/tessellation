package org.tessellation.currency.l1.domain.snapshot.programs

import cats.Applicative
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalSnapshotInfo, IncrementalGlobalSnapshot, SnapshotReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.InvalidSignatureForHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed abstract class CurrencySnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider]
    extends SnapshotProcessor[F, CurrencyTransaction, CurrencyBlock, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]

object CurrencySnapshotProcessor {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    identifier: Address,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, CurrencyBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, IncrementalGlobalSnapshot, GlobalSnapshotInfo],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    transactionStorage: TransactionStorage[F, CurrencyTransaction]
  ): CurrencySnapshotProcessor[F] =
    new CurrencySnapshotProcessor[F] {
      def process(
        snapshot: Either[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo), Hashed[IncrementalGlobalSnapshot]]
      ): F[SnapshotProcessingResult] =
        snapshot match {
          case Left((globalSnapshot, globalState)) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case None =>
                val setGlobalSnapshot = lastGlobalSnapshotStorage
                  .setInitial(globalSnapshot, globalState)
                  .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

                processCurrencySnapshots(globalSnapshot, globalState, globalSnapshotReference, setGlobalSnapshot)
              case _ => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
          case Right(globalSnapshot) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case Some((lastGlobalSnapshot, lastGlobalState)) =>
                Validator.compare(lastGlobalSnapshot, globalSnapshot.signed.value) match {
                  case _: Validator.Next =>
                    applyGlobalSnapshotFn(lastGlobalState, lastGlobalSnapshot, globalSnapshot.signed).flatMap { state =>
                      val setGlobalSnapshot = lastGlobalSnapshotStorage
                        .set(globalSnapshot, state)
                        .as[SnapshotProcessingResult](Aligned(globalSnapshotReference, Set.empty))

                      processCurrencySnapshots(globalSnapshot, state, globalSnapshotReference, setGlobalSnapshot)
                    }

                  case Validator.NotNext =>
                    Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
                }
              case None => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
        }

      def applyGlobalSnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: IncrementalGlobalSnapshot,
        snapshot: Signed[IncrementalGlobalSnapshot]
      ): F[GlobalSnapshotInfo] = ???

      def applySnapshotFn(
        lastState: CurrencySnapshotInfo,
        lastSnapshot: CurrencyIncrementalSnapshot,
        snapshot: Signed[CurrencyIncrementalSnapshot]
      ): F[CurrencySnapshotInfo] = ???

      private def processCurrencySnapshots(
        globalSnapshot: Hashed[IncrementalGlobalSnapshot],
        globalState: GlobalSnapshotInfo,
        globalSnapshotReference: SnapshotReference,
        setGlobalSnapshot: F[SnapshotProcessingResult]
      ): F[SnapshotProcessingResult] =
        fetchCurrencySnapshots(globalSnapshot).flatMap {
          case Some(Validated.Valid(hashedSnapshots)) =>
            prepareIntermediateStorages(addressStorage, blockStorage, lastCurrencySnapshotStorage, transactionStorage).flatMap {
              case (as, bs, lcss, ts) =>
                type SuccessT = NonEmptyList[Alignment]
                type LeftT = (NonEmptyList[Hashed[CurrencyIncrementalSnapshot]], List[Alignment])
                type RightT = Option[SuccessT]

                lastCurrencySnapshotStorage.getCombined.flatMap {
                  case None =>
                    val snapshotToDownload = hashedSnapshots.last
                    globalState.lastCurrencySnapshots.get(identifier) match {
                      case Some((_, stateToDownload)) =>
                        val toPass = (snapshotToDownload, stateToDownload).asLeft[Hashed[CurrencyIncrementalSnapshot]]

                        checkAlignment(toPass, bs, lcss).map { alignment =>
                          NonEmptyList.one(alignment).some
                        }
                      case None => (new Throwable("unexpected state")).raiseError[F, Option[SuccessT]]
                    }

                  case Some((_, _)) =>
                    (hashedSnapshots, List.empty[Alignment]).tailRecM {
                      case (NonEmptyList(snapshot, nextSnapshots), agg) =>
                        val toPass = snapshot.asRight[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]

                        checkAlignment(toPass, bs, lcss).flatMap {
                          case _: Ignore =>
                            Applicative[F].pure(none[SuccessT].asRight[LeftT])
                          case alignment =>
                            processAlignment(alignment, bs, ts, lcss, as).as {
                              val updatedAgg = agg :+ alignment

                              NonEmptyList.fromList(nextSnapshots) match {
                                case Some(next) =>
                                  (next, updatedAgg).asLeft[RightT]
                                case None =>
                                  NonEmptyList
                                    .fromList(updatedAgg)
                                    .asRight[LeftT]
                              }
                            }
                        }

                    }
                }
            }.flatMap {
              case Some(alignments) =>
                alignments.traverse { alignment =>
                  processAlignment(
                    alignment,
                    blockStorage,
                    transactionStorage,
                    lastCurrencySnapshotStorage,
                    addressStorage
                  )
                }.flatMap { results =>
                  setGlobalSnapshot
                    .map(BatchResult(_, results))
                }
              case None => Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
            }

          case Some(Validated.Invalid(_)) =>
            Slf4jLogger
              .getLogger[F]
              .warn(s"Not all currency snapshots are signed correctly! Ignoring global snapshot: $globalSnapshotReference")
              .as(SnapshotIgnored(globalSnapshotReference))

          case None =>
            setGlobalSnapshot
        }

      private def prepareIntermediateStorages(
        addressStorage: AddressStorage[F],
        blockStorage: BlockStorage[F, CurrencyBlock],
        lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
        transactionStorage: TransactionStorage[F, CurrencyTransaction]
      ): F[
        (
          AddressStorage[F],
          BlockStorage[F, CurrencyBlock],
          LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
          TransactionStorage[F, CurrencyTransaction]
        )
      ] = {
        val bs = blockStorage.getState().flatMap(BlockStorage.make[F, CurrencyBlock](_))
        val lcss =
          lastCurrencySnapshotStorage.getCombined.flatMap(LastSnapshotStorage.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](_))
        val as = addressStorage.getState.flatMap(AddressStorage.make(_))
        val ts =
          transactionStorage.getState().flatMap { case (lastTxs, waitingTxs) => TransactionStorage.make(lastTxs, waitingTxs) }

        (as, bs, lcss, ts).mapN((_, _, _, _))
      }

      // We are extracting all currency snapshots, but we don't assume that all the state channel binaries need to be
      // currency snapshots. Binary that fails to deserialize as currency snapshot are ignored here.
      private def fetchCurrencySnapshots(
        globalSnapshot: IncrementalGlobalSnapshot
      ): F[Option[ValidatedNel[InvalidSignatureForHash[CurrencyIncrementalSnapshot], NonEmptyList[Hashed[CurrencyIncrementalSnapshot]]]]] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier)
          .map {
            _.toList.flatMap(binary =>
              KryoSerializer[F].deserialize[Signed[CurrencyIncrementalSnapshot]](binary.content).toOption
            ) // TODO: deserialization as full or incremental snapshot
          }
          .flatMap(NonEmptyList.fromList)
          .map(_.traverse(_.toHashedWithSignatureCheck))
          .sequence
          .map(_.map(_.traverse(_.toValidatedNel)))
    }
}
