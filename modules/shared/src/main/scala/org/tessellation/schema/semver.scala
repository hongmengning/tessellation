package org.tessellation.schema

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.boolean.And
import eu.timepit.refined.cats._
import eu.timepit.refined.generic.Equal
import eu.timepit.refined.string.MatchesRegex
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object semver {

  private type SemVer = MatchesRegex[
    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
  ]

  @derive(encoder, decoder, show, order)
  @newtype
  case class SnapshotVersion(version: String Refined (SemVer And Equal["0.0.1"]))
}
