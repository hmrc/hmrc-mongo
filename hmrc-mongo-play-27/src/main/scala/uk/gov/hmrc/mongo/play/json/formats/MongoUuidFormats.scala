/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.mongo.play.json.formats

import play.api.libs.json._

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID

trait MongoUuidFormats {
  outer =>

  private val encoder = Base64.getEncoder
  private val decoder = Base64.getDecoder

  private val UuidOldSubType = "03"
  private val UuidNewSubtype = "04"

  private def readBase64(str: String, byteOrder: ByteOrder): UUID = {
    val bytes     = decoder.decode(str)
    val buffer    = ByteBuffer.wrap(bytes).order(byteOrder)
    val highBytes = buffer.getLong()
    val lowBytes  = buffer.getLong()
    new UUID(highBytes, lowBytes)
  }

  private def writeBase64(uuid: UUID, byteOrder: ByteOrder): String = {
    val empty = new Array[Byte](16)
    val bytes = ByteBuffer.wrap(empty).order(byteOrder)
    bytes.putLong(uuid.getMostSignificantBits)
    bytes.putLong(uuid.getLeastSignificantBits)
    encoder.encodeToString(bytes.array())
  }

  final val uuidReads: Reads[UUID] =
    Reads.at[String](__ \ "$binary" \ "subType").flatMap {
      case `UuidOldSubType` =>
        Reads
          .at[String](__ \ "$binary" \ "base64")
          .map(readBase64(_, ByteOrder.LITTLE_ENDIAN))
      case `UuidNewSubtype` =>
        Reads
          .at[String](__ \ "$binary" \ "base64")
          .map(readBase64(_, ByteOrder.BIG_ENDIAN))
      case other =>
        Reads.failed(s"Invalid BSON binary subtype for UUID: '$other'")
    }

  final val uuidWrites: Writes[UUID] = Writes { uuid =>
    Json.obj(
      "$binary" -> Json.obj(
        "base64"  -> writeBase64(uuid, ByteOrder.BIG_ENDIAN),
        "subType" -> UuidNewSubtype
      )
    )
  }

  final val uuidFormat: Format[UUID] =
    Format(uuidReads, uuidWrites)

  trait Implicits {
    implicit val uuidFormat: Format[UUID] = outer.uuidFormat
  }

  object Implicits extends Implicits
}

object MongoUuidFormats extends MongoUuidFormats
