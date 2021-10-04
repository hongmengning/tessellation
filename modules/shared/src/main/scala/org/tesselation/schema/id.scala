package org.tesselation.schema

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.macros.newtype

object ID {

  @derive(decoder, encoder, eqv, show, order)
  @newtype
  case class Id(hex: String)
}