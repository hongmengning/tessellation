package org.tessellation.currency.l1.modules

import cats.effect.Async
import cats.effect.std.Random

import org.tessellation.currency.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.modules.{Programs => BasePrograms}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.programs.L0PeerDiscovery
import org.tessellation.node.shared.modules.SharedPrograms
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.security.SecurityProvider

object Programs {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider: Random,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    sharedPrograms: SharedPrograms[F],
    p2pClient: P2PClient[F],
    storages: Storages[F, P, S, SI],
    snapshotProcessorProgram: SnapshotProcessor[F, P, S, SI]
  ): Programs[F, P, S, SI] = {
    val l0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.l0Cluster)
    val globalL0PeerDiscoveryProgram = L0PeerDiscovery.make(p2pClient.l0Cluster, storages.globalL0Cluster)

    new Programs[F, P, S, SI] {
      val peerDiscovery = sharedPrograms.peerDiscovery
      val l0PeerDiscovery = l0PeerDiscoveryProgram
      val globalL0PeerDiscovery = globalL0PeerDiscoveryProgram
      val joining = sharedPrograms.joining
      val snapshotProcessor = snapshotProcessorProgram
    }
  }
}

trait Programs[F[_], P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]] extends BasePrograms[F, P, S, SI] {
  val globalL0PeerDiscovery: L0PeerDiscovery[F]
}
