package io.constellationnetwork.currency.l0

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import io.constellationnetwork.currency.l0.cli.method
import io.constellationnetwork.currency.l0.cli.method._
import io.constellationnetwork.currency.l0.config.types._
import io.constellationnetwork.currency.l0.http.p2p.P2PClient
import io.constellationnetwork.currency.l0.modules._
import io.constellationnetwork.currency.l0.node.L0NodeContext
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.infrastructure.statechannel.StateChannelAllowanceLists
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
import io.constellationnetwork.node.shared.{NodeSharedOrSharedRegistrationIdRange, nodeSharedKryoRegistrar}
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security.{Hasher, HasherSelector, SecurityProvider}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._

trait OverridableL0 extends TessellationIOApp[Run] {
  def dataApplication: Option[Resource[IO, BaseDataApplicationL0Service[IO]]] = None

  def rewards(
    implicit sp: SecurityProvider[IO]
  ): Option[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]] = None
}

abstract class CurrencyL0App(
  name: String,
  header: String,
  clusterId: ClusterId,
  tessellationVersion: TessellationVersion,
  metagraphVersion: MetagraphVersion
) extends TessellationIOApp[Run](name, header, clusterId, version = tessellationVersion)
    with OverridableL0 {

  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = NodeSharedOrSharedRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    nodeSharedKryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- ConfigSource.default.loadF[IO, AppConfigReader]().asResource
      cfg = method.appConfig(cfgR, sharedConfig)

      dataApplicationService <- dataApplication.sequence

      hasherSelectorAlwaysCurrent = HasherSelector.forSyncAlwaysCurrent[IO](hasherSelector.getCurrent)

      queues <- Queues.make[IO](sharedQueues).asResource
      storages <- Storages
        .make[IO](sharedStorages, cfg.snapshot, method.globalL0Peer, dataApplicationService, hasherSelectorAlwaysCurrent)
        .asResource
      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session)
      maybeAllowanceList = StateChannelAllowanceLists.get(cfg.environment)
      validators = Validators.make[IO](seedlist, maybeAllowanceList, Hasher.forKryo[IO])
      implicit0(nodeContext: L0NodeContext[IO]) = L0NodeContext.make[IO](storages.snapshot, hasherSelectorAlwaysCurrent)
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        nodeShared.prioritySeedlist,
        sharedConfig.priorityPeerIds,
        cfg.environment
      ).asResource
      services <- Services
        .make[IO](
          p2pClient,
          sharedServices,
          storages,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          nodeShared.nodeId,
          keyPair,
          cfg,
          dataApplicationService,
          rewards,
          validators.signedValidator,
          sharedServices.globalSnapshotContextFns,
          maybeMajorityPeerIds,
          hasherSelectorAlwaysCurrent
        )
        .asResource
      programs = Programs.make[IO](
        keyPair,
        nodeShared.nodeId,
        cfg.globalL0Peer,
        sharedPrograms,
        storages,
        services,
        p2pClient,
        services.snapshotContextFunctions,
        dataApplicationService.zip(storages.calculatedStateStorage)
      )
      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        services.consensus.handler
      _ <- Daemons
        .start(storages, services, programs, queues, services.dataApplication, cfg, hasherSelectorAlwaysCurrent)
        .asResource

      api = HttpApi
        .make[IO](
          validators,
          storages,
          queues,
          services,
          programs,
          keyPair.getPrivate,
          cfg.environment,
          nodeShared.nodeId,
          tessellationVersion,
          cfg.http,
          services.dataApplication,
          metagraphVersion.some
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      program <- (method match {
        case m: CreateGenesis =>
          hasherSelectorAlwaysCurrent.withCurrent { implicit hasher =>
            programs.genesis.create(dataApplicationService)(
              m.genesisBalancesPath,
              keyPair
            )
          } >> nodeShared.stopSignal.set(true)

        case other =>
          for {
            _ <- StateChannel.performGlobalL0PeerDiscovery[IO](storages, programs)

            innerProgram <- other match {
              case rv: RunValidator =>
                storages.identifier.setInitial(rv.identifier) >>
                  gossipDaemon.startAsRegularValidator >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)

              case rr: RunRollback =>
                storages.identifier.setInitial(rr.identifier) >>
                  storages.node.tryModifyState(
                    NodeState.Initial,
                    NodeState.RollbackInProgress,
                    NodeState.RollbackDone
                  )(hasherSelector.withCurrent(implicit hasher => programs.rollback.rollback)) >>
                  gossipDaemon.startAsInitialValidator >>
                  services.cluster.createSession >>
                  services.session.createSession >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.setNodeState(NodeState.Ready) >>
                  restartMethodR.set(
                    RunValidator(
                      rr.keyStore,
                      rr.alias,
                      rr.password,
                      rr.httpConfig,
                      rr.environment,
                      rr.seedlistPath,
                      rr.prioritySeedlistPath,
                      rr.collateralAmount,
                      rr.globalL0Peer,
                      rr.identifier,
                      rr.trustRatingsPath
                    ).some
                  )

              case m: RunGenesis =>
                storages.node.tryModifyState(
                  NodeState.Initial,
                  NodeState.LoadingGenesis,
                  NodeState.GenesisReady
                )(hasherSelector.withCurrent(implicit hasher => programs.genesis.accept(dataApplicationService)(m.genesisPath))) >>
                  gossipDaemon.startAsInitialValidator >>
                  services.cluster.createSession >>
                  services.session.createSession >>
                  programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
                  storages.node.setNodeState(NodeState.Ready) >>
                  storages.identifier.get.flatMap { identifier =>
                    restartMethodR.set(
                      RunValidator(
                        m.keyStore,
                        m.alias,
                        m.password,
                        m.httpConfig,
                        m.environment,
                        m.seedlistPath,
                        m.prioritySeedlistPath,
                        m.collateralAmount,
                        m.globalL0Peer,
                        identifier,
                        m.trustRatingsPath
                      ).some
                    )
                  }

              case _ => IO.unit
            }
            _ <- StateChannel
              .run[IO](services, storages, programs)
              .compile
              .drain
          } yield innerProgram
      }).asResource

    } yield program
  }
}
