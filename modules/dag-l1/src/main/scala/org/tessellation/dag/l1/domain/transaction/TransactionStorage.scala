package org.tessellation.dag.l1.domain.transaction

import cats.Order
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.set._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.annotation.tailrec
import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.transaction.TransactionStorage._
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.sdk.domain.transaction.filter.Consecutive
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash

import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TransactionStorage[F[_]: Async, T <: Transaction: Order: Ordering](
  lastAccepted: MapRef[F, Address, Option[LastTransactionReferenceState]],
  waitingTransactions: MapRef[F, Address, Option[NonEmptySet[Hashed[T]]]]
) {

  private val logger = Slf4jLogger.getLogger[F]
  private val transactionLogger = Slf4jLogger.getLoggerFromName[F](transactionLoggerName)

  def getLastAcceptedReference(source: Address): F[TransactionReference] =
    lastAccepted(source).get.map(_.map(_.ref).getOrElse(TransactionReference.empty))

  def setLastAccepted(lastTxRefs: Map[Address, TransactionReference]): F[Unit] =
    lastAccepted.clear >>
      lastTxRefs.toList.traverse {
        case (address, reference) => lastAccepted(address).set(Majority(reference).some)
      }.void

  def accept(hashedTx: Hashed[T]): F[Unit] = {
    val parent = hashedTx.signed.value.parent
    val source = hashedTx.signed.value.source
    val reference = TransactionReference(hashedTx.signed.value.ordinal, hashedTx.hash)

    lastAccepted(source)
      .modify[Either[TransactionAcceptanceError, Unit]] { lastAccepted =>
        if (lastAccepted.exists(_.ref == parent) || (lastAccepted.isEmpty && reference.ordinal == TransactionOrdinal.first))
          (Accepted(reference).some, ().asRight)
        else
          (lastAccepted, ParentNotAccepted(source, lastAccepted.map(_.ref), reference).asLeft)
      }
      .flatMap(_.liftTo[F])
  }

  def markMajority(txRefsToMarkMajority: Map[Address, TransactionReference]): F[Unit] =
    txRefsToMarkMajority.toList.traverse {
      case (source, txRef) =>
        lastAccepted(source)
          .modify[Either[MarkingTransactionReferenceAsMajorityError, Unit]] {
            case current @ Some(Accepted(ref)) if ref.ordinal > txRef.ordinal =>
              (current, ().asRight)
            case Some(Accepted(ref)) if ref == txRef =>
              (Majority(txRef).some, ().asRight)
            case other =>
              (other, UnexpectedStateWhenMarkingTxRefAsMajority(source, txRef, other).asLeft)
          }
          .flatMap(_.liftTo[F])
    }.void

  def put(transaction: Hashed[T]): F[Unit] = put(Set(transaction))

  def put(transactions: Set[Hashed[T]]): F[Unit] =
    transactions
      .groupBy(_.signed.value.source)
      .toList
      .traverse {
        case (source, txs) =>
          waitingTransactions(source).modify { current =>
            val updated = current match {
              case Some(waiting) =>
                NonEmptySet.fromSet(SortedSet.from(waiting.toNonEmptyList.toList ++ txs.toList))
              case None =>
                NonEmptySet.fromSet(SortedSet.from(txs.toList))
            }

            (updated, ())
          }
      }
      .void

  def countAllowedForConsensus: F[Int] =
    for {
      lastAccepted <- lastAccepted.toMap
      addresses <- waitingTransactions.keys
      txs <- addresses.traverse { address =>
        waitingTransactions(address).get.map {
          case Some(waiting) =>
            val lastTxState = lastAccepted.getOrElse(address, Majority(TransactionReference.empty))
            val consecutiveTxs = Consecutive.take(waiting.toList, lastTxState.ref)

            pullForAddress(lastTxState, consecutiveTxs)
          case None =>
            List.empty[Hashed[T]]
        }
      }.map(_.flatten)
    } yield txs.size

  def pull(count: NonNegLong): F[Option[NonEmptyList[Hashed[T]]]] =
    for {
      lastAccepted <- lastAccepted.toMap
      addresses <- waitingTransactions.keys.map(_.sorted)
      allPulled <- addresses.traverse { address =>
        val pulledM = for {
          (maybeWaiting, setter) <- waitingTransactions(address).access

          pulled <- maybeWaiting.traverse { waiting =>
            val lastTxState = lastAccepted.getOrElse(address, Majority(TransactionReference.empty))
            val consecutiveTxs = Consecutive.take(waiting.toList, lastTxState.ref)
            val pulled = pullForAddress(lastTxState, consecutiveTxs)
            val maybeStillWaiting = SortedSet.from(waiting.toList.diff(pulled)).toNes
            val maybeStillWaitingAboveOrdinal = maybeStillWaiting.flatMap(_.filter(_.ordinal > lastTxState.ref.ordinal).toNes)
            val expiredBelowOrdinal = maybeStillWaiting.flatMap(_.filter(_.ordinal <= lastTxState.ref.ordinal).toNes)

            setter(maybeStillWaitingAboveOrdinal).flatTap { _ =>
              transactionLogger.info(
                s"Expired transaction with ordinal lower or equal ${lastTxState.ref.ordinal}: ${expiredBelowOrdinal.map(_.map(_.hash)).show}"
              )
            }
              .ifM(
                NonEmptyList.fromList(pulled).pure[F],
                logger.debug("Concurrent update occurred while trying to pull transactions") >>
                  none[NonEmptyList[Hashed[T]]].pure[F]
              )
          }.map(_.flatten)
        } yield pulled

        pulledM.handleErrorWith {
          logger.warn(_)(s"Error while pulling transactions for address=${address.show} from waiting pool.") >>
            none[NonEmptyList[Hashed[T]]].pure[F]
        }
      }.map(_.flatten)

      selected = takeFirstNHighestFeeTxs(allPulled, count)
      toReturn = allPulled.flatMap(_.toList).toSet.diff(selected.toSet)
      _ <- logger.debug(s"Pulled transactions to return: ${toReturn.size}")
      _ <- transactionLogger.debug(s"Pulled transactions to return: ${toReturn.size}, returned: ${toReturn.map(_.hash).show}")
      _ <- put(toReturn)
      _ <- logger.debug(s"Pulled ${selected.size} transaction(s) for consensus")
      _ <- transactionLogger.debug(s"Pulled ${selected.size} transaction(s) for consensus, pulled: ${selected.map(_.hash).show}")
    } yield NonEmptyList.fromList(selected)

  private def pullForAddress(
    lastTxState: LastTransactionReferenceState,
    consecutiveTxs: List[Hashed[T]]
  ): List[Hashed[T]] =
    (lastTxState, consecutiveTxs.headOption) match {
      case (_: Majority, Some(tx)) if tx.fee == TransactionFee.zero =>
        List(tx)
      case (_, _) =>
        consecutiveTxs.takeWhile(_.fee != TransactionFee.zero)
    }

  private def takeFirstNHighestFeeTxs(
    txs: List[NonEmptyList[Hashed[T]]],
    count: NonNegLong
  ): List[Hashed[T]] = {
    @tailrec
    def go(
      txs: SortedSet[NonEmptyList[Hashed[T]]],
      acc: List[Hashed[T]]
    ): List[Hashed[T]] =
      if (acc.size == count.value)
        acc.reverse
      else {
        txs.headOption match {
          case Some(txsNel) =>
            val updatedAcc = txsNel.head :: acc

            NonEmptyList.fromList(txsNel.tail) match {
              case Some(remainingTxs) => go(txs.tail + remainingTxs, updatedAcc)
              case None               => go(txs.tail, updatedAcc)
            }

          case None => acc.reverse
        }
      }

    val order: Order[NonEmptyList[Hashed[T]]] =
      Order.whenEqual(Order.by(-_.head.fee.value.value), Order[NonEmptyList[Hashed[T]]])
    val sortedTxs = SortedSet.from(txs)(order.toOrdering)

    go(sortedTxs, List.empty)
  }

  def find(hash: Hash): F[Option[Hashed[T]]] =
    waitingTransactions.toMap.map(_.view.values.toList.flatMap(_.toList)).map {
      _.find(_.hash === hash)
    }

}

object TransactionStorage {

  def make[F[_]: Async: KryoSerializer, T <: Transaction: Order: Ordering]: F[TransactionStorage[F, T]] =
    for {
      lastAccepted <- MapRef.ofConcurrentHashMap[F, Address, LastTransactionReferenceState]()
      waitingTransactions <- MapRef.ofConcurrentHashMap[F, Address, NonEmptySet[Hashed[T]]]()
    } yield new TransactionStorage[F, T](lastAccepted, waitingTransactions)

  sealed trait TransactionAcceptanceError extends NoStackTrace
  case class ParentNotAccepted(
    source: Address,
    lastAccepted: Option[TransactionReference],
    attempted: TransactionReference
  ) extends TransactionAcceptanceError {
    override def getMessage: String =
      s"Transaction not accepted in the correct order. source=${source.show} current=${lastAccepted.show} attempted=${attempted.show}"
  }

  sealed trait MarkingTransactionReferenceAsMajorityError extends NoStackTrace
  case class UnexpectedStateWhenMarkingTxRefAsMajority(
    source: Address,
    toMark: TransactionReference,
    got: Option[LastTransactionReferenceState]
  ) extends MarkingTransactionReferenceAsMajorityError {
    override def getMessage: String =
      s"Unexpected state encountered when marking transaction reference=$toMark for source address=$source as majority. Got: $got"
  }

  sealed trait LastTransactionReferenceState { val ref: TransactionReference }
  case class Accepted(ref: TransactionReference) extends LastTransactionReferenceState
  case class Majority(ref: TransactionReference) extends LastTransactionReferenceState
}
