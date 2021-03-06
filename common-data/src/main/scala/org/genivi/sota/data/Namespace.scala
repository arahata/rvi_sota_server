/**
 * Copyright: Copyright (C) 2016, ATS Advanced Telematic Systems GmbH
 * License: MPL-2.0
 */

package org.genivi.sota.data

final case class Namespace(get: String) extends AnyVal

object Namespace {

  import io.circe._

  implicit val EncoderInstance = Encoder.encodeString.contramap[Namespace](_.get)
  implicit val DecoderInstance = Decoder.decodeString.map(Namespace.apply)

}
