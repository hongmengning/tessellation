package org.tessellation.sdk.modules

import cats.data.NonEmptySet
import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.BlockValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

import eu.timepit.refined.types.numeric.PosLong

object SdkValidators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    l0Seedlist: Option[Set[SeedlistEntry]],
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    maxBinarySizeInBytes: PosLong
  ): SdkValidators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)
    val currencyTransactionChainValidator = TransactionChainValidator.make[F]
    val currencyTransactionValidator = TransactionValidator.make[F](signedValidator)
    val currencyBlockValidator = BlockValidator
      .make[F](signedValidator, currencyTransactionChainValidator, currencyTransactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)
    val stateChannelValidator = StateChannelValidator.make[F](signedValidator, l0Seedlist, stateChannelAllowanceLists, maxBinarySizeInBytes)

    new SdkValidators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      currencyTransactionChainValidator,
      currencyTransactionValidator,
      blockValidator,
      currencyBlockValidator,
      rumorValidator,
      stateChannelValidator
    ) {}
  }
}

sealed abstract class SdkValidators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val currencyTransactionChainValidator: TransactionChainValidator[F],
  val currencyTransactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F],
  val currencyBlockValidator: BlockValidator[F],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F]
)
