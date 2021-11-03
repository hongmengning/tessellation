package org.tesselation.wallet

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._

import org.tesselation.ext.crypto._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.schema._
import org.tesselation.schema.address.Address
import org.tesselation.schema.transaction._
import org.tesselation.security.key.ops._
import org.tesselation.security.signature.Signed
import org.tesselation.security.{SecureRandom, SecurityProvider}

import eu.timepit.refined.auto._
import io.estatico.newtype.ops._

package object transaction {

  implicit class TransactionOrdinalOps(ordinal: TransactionOrdinal) {

    def next: TransactionOrdinal =
      TransactionOrdinal(ordinal.coerce |+| BigInt(1))
  }

  def createTransaction[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    destination: Address,
    prevTx: Option[Signed[Transaction]],
    fee: TransactionFee,
    amount: TransactionAmount
  ): F[Signed[Transaction]] =
    for {
      source <- keyPair.getPublic.toAddress.pure[F]

      parent <- prevTx
        .map(_.value)
        .map(tx => tx.hashF.map(TransactionReference(_, tx.parent.ordinal.next)))
        .getOrElse(TransactionReference.empty.pure[F])

      salt <- SecureRandom
        .get[F]
        .map(_.nextLong())
        .map(TransactionSalt.apply)

      tx = Transaction(source, destination, amount, fee, parent, salt)
      signedTx <- tx.sign(keyPair)

    } yield signedTx
}
