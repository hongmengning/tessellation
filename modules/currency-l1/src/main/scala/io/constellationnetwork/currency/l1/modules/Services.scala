package io.constellationnetwork.currency.l1.modules

import cats.data.NonEmptySet
import cats.effect.kernel.Async

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL1Service
import io.constellationnetwork.currency.l1.http.p2p.P2PClient
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.dag.l1.config.types.AppConfig
import io.constellationnetwork.dag.l1.domain.block.BlockService
import io.constellationnetwork.dag.l1.domain.transaction.TransactionService
import io.constellationnetwork.dag.l1.modules.{Services => BaseServices, Validators}
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.collateral.Collateral
import io.constellationnetwork.node.shared.modules.SharedServices
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

object Services {
  def make[F[_]: Async: HasherSelector: SecurityProvider](
    storages: Storages[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    globalL0Cluster: L0ClusterStorage[F],
    validators: Validators[F],
    sharedServices: SharedServices[F],
    p2PClient: P2PClient[F],
    cfg: AppConfig,
    maybeDataApplication: Option[BaseDataApplicationL1Service[F]],
    maybeMajorityPeerIds: Option[NonEmptySet[PeerId]],
    txHasher: Hasher[F]
  ): Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] =
    new Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] {

      val localHealthcheck = sharedServices.localHealthcheck
      val block = BlockService.make[F](
        BlockAcceptanceManager.make[F](validators.block, txHasher),
        storages.address,
        storages.block,
        storages.transaction,
        lastGlobalSnapshotStorage,
        cfg.collateral.amount,
        txHasher
      )
      val cluster = sharedServices.cluster
      val gossip = sharedServices.gossip
      val globalL0 =
        GlobalL0Service
          .make[F](p2PClient.l0GlobalSnapshot, globalL0Cluster, lastGlobalSnapshotStorage, None, maybeMajorityPeerIds)
      val session = sharedServices.session
      val transaction = TransactionService.make[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
        storages.transaction,
        storages.lastSnapshot,
        validators.transaction
      )
      val collateral = Collateral.make[F](cfg.collateral, storages.lastSnapshot)
      val dataApplication = maybeDataApplication
    }
}

sealed abstract class Services[F[_], P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]] extends BaseServices[F, P, S, SI] {
  val dataApplication: Option[BaseDataApplicationL1Service[F]]
}
