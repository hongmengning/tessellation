package org.tessellation.currency.l1

import java.util.UUID

import org.tessellation.BuildInfo
import org.tessellation.schema.cluster.ClusterId

object Main
    extends CurrencyL1App(
      s"Currency-l1",
      s"Currency L1 node",
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      version = BuildInfo.version
    ) {}
