package io.constellationnetwork.dag.l0.modules

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l0.config.types.AppConfig
import io.constellationnetwork.dag.l0.domain.cell.L0Cell
import io.constellationnetwork.dag.l0.domain.statechannel.StateChannelService
import io.constellationnetwork.dag.l0.infrastructure.rewards._
import io.constellationnetwork.dag.l0.infrastructure.snapshot._
import io.constellationnetwork.dag.l0.infrastructure.trust.TrustStorageUpdater
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.cluster.services.{Cluster, Session}
import io.constellationnetwork.node.shared.domain.collateral.Collateral
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.healthcheck.LocalHealthcheck
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.domain.snapshot.services.AddressService
import io.constellationnetwork.node.shared.infrastructure.collateral.Collateral
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.services.AddressService
import io.constellationnetwork.node.shared.modules.{SharedServices, SharedValidators}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: JsonSerializer: HasherSelector: SecurityProvider: Metrics: Supervisor](
    sharedServices: SharedServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: SharedValidators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig,
    txHasher: Hasher[F]
  ): F[Services[F]] =
    for {
      rewards <- Rewards
        .make[F](
          cfg.rewards,
          ProgramsDistributor.make,
          FacilitatorDistributor.make
        )
        .pure[F]

      consensus <- GlobalSnapshotConsensus
        .make[F](
          sharedServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.globalSnapshot,
          validators,
          sharedServices,
          cfg,
          stateChannelPullDelay = cfg.stateChannel.pullDelay,
          stateChannelPurgeDelay = cfg.stateChannel.purgeDelay,
          stateChannelAllowanceLists,
          feeConfigs = cfg.shared.feeConfigs,
          client,
          session,
          rewards,
          txHasher
        )
      addressService = AddressService.make[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo](storages.globalSnapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.globalSnapshot)
      stateChannelService = StateChannelService
        .make[F](
          L0Cell.mkL0Cell(queues.l1Output, queues.stateChannelOutput),
          validators.stateChannelValidator
        )
      getOrdinal = storages.globalSnapshot.headSnapshot.map(_.map(_.ordinal))
      trustUpdaterService = TrustStorageUpdater.make(getOrdinal, sharedServices.gossip, storages.trust)
    } yield
      new Services[F](
        localHealthcheck = sharedServices.localHealthcheck,
        cluster = sharedServices.cluster,
        session = sharedServices.session,
        gossip = sharedServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService,
        rewards = rewards,
        stateChannel = stateChannelService,
        trustStorageUpdater = trustUpdaterService
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: GlobalSnapshotConsensus[F],
  val address: AddressService[F, GlobalIncrementalSnapshot],
  val collateral: Collateral[F],
  val rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent],
  val stateChannel: StateChannelService[F],
  val trustStorageUpdater: TrustStorageUpdater[F]
)
