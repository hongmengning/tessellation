package org.tesselation.infrastructure.cluster

import cats.effect.{Async, Ref}
import cats.syntax.functor._

import org.tesselation.domain.cluster.ClusterStorage
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}

object ClusterStorage {

  def make[F[_]: Async: Ref.Make]: F[ClusterStorage[F]] =
    Ref[F].of[Set[Peer]](Set.empty).map { peers =>
      new ClusterStorage[F] {

        def getPeers: F[Set[Peer]] =
          peers.get

        def addPeer(peer: Peer): F[Unit] =
          peers.update(_ + peer)

        def hasPeerId(id: PeerId): F[Boolean] =
          peers.get.map(_.exists(_.id == id))

        def hasPeerHostPort(host: Host, p2pPort: Port): F[Boolean] =
          peers.get.map(_.exists(peer => peer.ip == host && peer.p2pPort == p2pPort))
      }
    }

}
