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

import org.bson.BsonBinarySubType
import org.bson.UuidRepresentation
import org.bson.internal.UuidHelper
import play.api.libs.json._

import java.util.Base64
import java.util.UUID

abstract class MongoUuids(uuidRep: UuidRepresentation) {
  outer =>

  private val encoder = Base64.getEncoder
  private val decoder = Base64.getDecoder

  private val UuidOldSubtype = BsonBinarySubType.UUID_LEGACY.getValue
  private val UuidNewSubtype = BsonBinarySubType.UUID_STANDARD.getValue

  private val chosenSubType =
    if (uuidRep == UuidRepresentation.STANDARD) UuidNewSubtype else UuidOldSubtype

  private def readBase64(str: String, subType: Byte): UUID =
    UuidHelper.decodeBinaryToUuid(
      decoder.decode(str),
      subType,
      uuidRep
    )

  private def writeBase64(uuid: UUID): String =
    encoder.encodeToString(
      UuidHelper.encodeUuidToBinary(uuid, uuidRep)
    )

  final val uuidReads: Reads[UUID] =
    Reads
      .at[String](__ \ "$binary" \ "subType")
      .map(_.toByte)
      .flatMap {
        case `UuidOldSubtype` =>
          Reads
            .at[String](__ \ "$binary" \ "base64")
            .map(readBase64(_, UuidOldSubtype))
        case `UuidNewSubtype` =>
          Reads
            .at[String](__ \ "$binary" \ "base64")
            .map(readBase64(_, UuidNewSubtype))
        case other =>
          Reads.failed(f"Invalid BSON binary subtype for UUID: '$other%02x'")
      }

  final val uuidWrites: Writes[UUID] = Writes { uuid =>
    Json.obj(
      "$binary" -> Json.obj(
        "base64"  -> writeBase64(uuid),
        "subType" -> f"$chosenSubType%02x"
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

abstract class MongoUuidFormats extends MongoUuids(UuidRepresentation.STANDARD)

object MongoUuidFormats extends MongoUuidFormats

abstract class MongoLegacyUuidFormats extends MongoUuids(UuidRepresentation.JAVA_LEGACY)

object MongoLegacyUuidFormats extends MongoLegacyUuidFormats
