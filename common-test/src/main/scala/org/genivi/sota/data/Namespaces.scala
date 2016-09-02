/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */
package org.genivi.sota.data

import eu.timepit.refined.api.Refined
import org.genivi.sota.data.Namespace._
import org.scalacheck.Gen


trait Namespaces {

  /**
    * For property based testing purposes, we need to explain how to
    * randomly generate namespaces.
    *
    * @see [[https://www.scalacheck.org/]]
    */
  val NamespaceGen: Gen[Namespace] = {
    // TODO: for now, just use simple identifiers
    Gen.identifier.map(Namespace.apply)
  }

  val defaultNs: Namespace = Namespace("default")
}

object Namespaces extends Namespaces
