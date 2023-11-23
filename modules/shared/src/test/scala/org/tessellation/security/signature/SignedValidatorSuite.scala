package org.tessellation.security.signature

import cats.data.{NonEmptySet, Validated}
import cats.effect.{IO, Resource}
import cats.syntax.validated._

import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import weaver.MutableIOSuite

object SignedValidatorSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, SignedValidatorSuite.Res] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar.union(Map[Class[_], KryoRegistrationId[Interval.Closed[5000, 5001]]](classOf[TestObject] -> 5000)))
      .flatMap { ks =>
        SecurityProvider.forAsync[IO].map((ks, _))
      }

  test("should succeed when object is signed only by one peer from seedlist") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = PeerId.fromPublic(keyPair1.getPublic)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId2 = PeerId.fromPublic(keyPair2.getPublic)
      input = TestObject("Test")
      signedInput <- forAsyncKryo(input, keyPair1)
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(Some(Set(peerId1, peerId2)), signedInput)
    } yield expect.same(Validated.Valid(signedInput), result)
  }

  test("should succeed when all signers are on the seedlist") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = PeerId.fromPublic(keyPair1.getPublic)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId2 = PeerId.fromPublic(keyPair2.getPublic)
      input = TestObject("Test")
      signedInput <- forAsyncKryo(input, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(Some(Set(peerId1, peerId2)), signedInput)
    } yield expect.same(Validated.Valid(signedInput), result)
  }

  test("should succeed when there is no seedlist provided") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      input = TestObject("Test")
      signedInput <- forAsyncKryo(input, keyPair)
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(None, signedInput)
    } yield expect.same(Validated.Valid(signedInput), result)
  }

  test("should fail when there is at least one signature not in seedlist") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = PeerId.fromPublic(keyPair1.getPublic)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId2 = PeerId.fromPublic(keyPair2.getPublic)
      input = TestObject("Test")
      signedInput <- forAsyncKryo(input, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(Some(Set(peerId1)), signedInput)
    } yield expect.same(SignedValidator.SignersNotInSeedlist(NonEmptySet.one(peerId2.toId)).invalidNec, result)
  }

  private def mkValidator()(
    implicit S: SecurityProvider[IO],
    K: KryoSerializer[IO]
  ) = SignedValidator.make[IO]

  case class TestObject(value: String)

}