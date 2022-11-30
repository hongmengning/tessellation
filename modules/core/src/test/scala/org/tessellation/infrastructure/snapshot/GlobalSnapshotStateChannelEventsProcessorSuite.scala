package org.tessellation.infrastructure.snapshot

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.validated._

import scala.collection.immutable.SortedMap

import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.snapshot.{GlobalSnapshotInfo, StateChannelSnapshotBinary}
import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.shared.sharedKryoRegistrar

import weaver.MutableIOSuite

object GlobalSnapshotStateChannelEventsProcessorSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, GlobalSnapshotStateChannelEventsProcessorSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sharedKryoRegistrar)).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  def mkProcessor(failed: Option[(Address, StateChannelValidator.StateChannelValidationError)] = None) = {
    val validator = new StateChannelValidator[IO] {
      def validate(output: StateChannelOutput) =
        IO.pure(failed.filter(f => f._1 == output.address).map(_._2.invalidNec).getOrElse(output.validNec))
    }
    GlobalSnapshotStateChannelEventsProcessor.make[IO](validator)
  }

  test("return new sc event") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      output <- mkStateChannelOutput(keyPair)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor()
      expected = (SortedMap((output.address, NonEmptyList.one(output.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output :: Nil)
    } yield expect.same(expected, result)

  }

  test("return two dependent sc events") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      output1 <- mkStateChannelOutput(keyPair)
      output2 <- mkStateChannelOutput(keyPair, Some(Hash.fromBytes(output1.snapshot.content)))
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor()
      expected = (SortedMap((output1.address, NonEmptyList.of(output2.snapshot, output1.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc event when last state channel snapshot is correct") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      output1 <- mkStateChannelOutput(keyPair)
      output2 <- mkStateChannelOutput(keyPair, Some(Hash.fromBytes(output1.snapshot.content)))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((output1.address, Hash.fromBytes(output1.snapshot.content))))
      service = mkProcessor()
      expected = (SortedMap((output1.address, NonEmptyList.of(output2.snapshot))), Set.empty)
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return no sc events when last state channel snapshot hash is incorrect") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      output1 <- mkStateChannelOutput(keyPair)
      output2 <- mkStateChannelOutput(keyPair, Some(Hash.fromBytes("incorrect".getBytes())))
      snapshotInfo = mkGlobalSnapshotInfo(SortedMap((output1.address, Hash.fromBytes(output1.snapshot.content))))
      service = mkProcessor()
      expected = (SortedMap.empty[Address, NonEmptyList[StateChannelSnapshotBinary]], Set.empty)
      result <- service.process(snapshotInfo, output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return sc events for different addresses") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor()
      expected = (
        SortedMap((output1.address, NonEmptyList.of(output1.snapshot)), (output2.address, NonEmptyList.of(output2.snapshot))),
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  test("return only valid sc events") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      output1 <- mkStateChannelOutput(keyPair1)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      output2 <- mkStateChannelOutput(keyPair2)
      snapshotInfo = mkGlobalSnapshotInfo()
      service = mkProcessor(Some(keyPair1.getPublic().toAddress -> StateChannelValidator.NotSignedExclusivelyeByStateChannelOwner))
      expected = (
        SortedMap((output2.address, NonEmptyList.of(output2.snapshot))),
        Set.empty
      )
      result <- service.process(snapshotInfo, output1 :: output2 :: Nil)
    } yield expect.same(expected, result)

  }

  def mkStateChannelOutput(keyPair: KeyPair, hash: Option[Hash] = None)(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    content <- Random.scalaUtilRandom[IO].flatMap(_.nextString(10))
    binary <- StateChannelSnapshotBinary(hash.getOrElse(Hash.empty), content.getBytes).pure[IO]
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

  def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty) =
    GlobalSnapshotInfo(lastStateChannelSnapshotHashes, SortedMap.empty, SortedMap.empty)

}
