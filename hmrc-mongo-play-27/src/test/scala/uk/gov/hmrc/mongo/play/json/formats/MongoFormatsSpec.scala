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

import org.bson.BSONException
import org.bson.UuidRepresentation
import org.bson.codecs.EncoderContext
import org.bson.codecs.UuidCodec
import org.bson.codecs.configuration.CodecRegistries
import org.bson.json.JsonMode
import org.bson.json.JsonWriter
import org.bson.json.JsonWriterSettings
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import play.api.libs.json.JsError
import play.api.libs.json.JsValue
import play.api.libs.json.Json

import java.util.UUID
import java.{time => jat}

class MongoFormatsSpec extends AnyWordSpecLike with Matchers with ScalaCheckDrivenPropertyChecks with EitherValues {

  import org.scalacheck.Shrink.shrinkAny // disable shrinking here - will just generate invalid numbers

  "formats" when {
    def codecWrite[A](a: A, uuidRep: UuidRepresentation = UuidRepresentation.STANDARD)(implicit
      clazz: Class[A]
    ): JsValue = {
      val writer         = new java.io.StringWriter
      val writerSettings = JsonWriterSettings.builder.outputMode(JsonMode.EXTENDED).build
      val registry = CodecRegistries.fromRegistries(
        CodecRegistries.fromCodecs(new UuidCodec(uuidRep)),
        DEFAULT_CODEC_REGISTRY
      )
      registry.get(clazz).encode(new JsonWriter(writer, writerSettings), a, EncoderContext.builder.build)
      Json.parse(writer.toString)
    }

    "encoding java.time values" should {

      "be compatible with default java.time.Instant codec" in {
        forAll(epochMillisGen) { epochMillis =>
          val javaInstant = jat.Instant.ofEpochMilli(epochMillis)
          MongoJavatimeFormats.instantFormat.writes(javaInstant) shouldBe codecWrite(javaInstant)(classOf[jat.Instant])
        }
      }

      "be compatible with default java.time.LocalDateTime codec" in {
        forAll(epochMillisGen) { epochMillis =>
          val javaLocalDateTime = jat.Instant.ofEpochMilli(epochMillis).atZone(jat.ZoneOffset.UTC).toLocalDateTime
          MongoJavatimeFormats.localDateTimeFormat.writes(javaLocalDateTime) shouldBe codecWrite(javaLocalDateTime)(
            classOf[jat.LocalDateTime]
          )
        }
      }

      "be compatible with default java.time.LocalDate codec" in {
        forAll(epochMillisGen) { epochMillis =>
          val javaLocalDate = jat.Instant.ofEpochMilli(epochMillis).atZone(jat.ZoneOffset.UTC).toLocalDate
          MongoJavatimeFormats.localDateFormat.writes(javaLocalDate) shouldBe codecWrite(javaLocalDate)(
            classOf[jat.LocalDate]
          )
        }
      }
    }

    "encoding UUIDs in standard Mongo UUID encoding" should {

      "be compatible with UuidRepresentation.STANDARD java.util.UUID codec" in {
        forAll { uuid: UUID =>
          MongoUuidFormats.uuidFormat.writes(uuid) shouldBe codecWrite(uuid, UuidRepresentation.STANDARD)(classOf[UUID])
        }
      }

      "allow reading UUID written with UuidRepresentation.STANDARD" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.STANDARD)(classOf[UUID])
          MongoUuidFormats.uuidReads.reads(jsValue).asOpt should contain(uuid)
        }
      }

      "not allow reading UUID written with UuidRepresentation.JAVA_LEGACY" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.JAVA_LEGACY)(classOf[UUID])
          assertThrows[BSONException] {
            MongoUuidFormats.uuidReads.reads(jsValue)
          }
        }
      }

      "fail when reading data written with generic binary subtype as UUID" in {
        forAll { bytes: Array[Byte] =>
          MongoUuidFormats.uuidReads.reads(codecWrite(bytes)(classOf[Array[Byte]])) shouldBe JsError(
            "Invalid BSON binary subtype for UUID: '00'"
          )
        }
      }

    }

    "encoding UUIDs in legacy Mongo UUID encoding" should {

      "be compatible with UuidRepresentation.LEGACY java.util.UUID codec" in {
        forAll { uuid: UUID =>
          MongoLegacyUuidFormats.uuidFormat.writes(uuid) shouldBe codecWrite(uuid, UuidRepresentation.JAVA_LEGACY)(
            classOf[UUID]
          )
        }
      }

      "allow reading UUID written with UuidRepresentation.JAVA_LEGACY" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.JAVA_LEGACY)(classOf[UUID])
          MongoLegacyUuidFormats.uuidReads.reads(jsValue).asOpt should contain(uuid)
        }
      }

      "allow reading UUID written with UuidRepresentation.STANDARD" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.STANDARD)(classOf[UUID])
          MongoLegacyUuidFormats.uuidReads.reads(jsValue).asOpt should contain(uuid)
        }
      }

      "fail when reading data written with generic binary subtype as UUID" in {
        forAll { bytes: Array[Byte] =>
          MongoLegacyUuidFormats.uuidReads.reads(codecWrite(bytes)(classOf[Array[Byte]])) shouldBe JsError(
            "Invalid BSON binary subtype for UUID: '00'"
          )
        }
      }
    }
  }

  def epochMillisGen =
    Gen.choose(0L, System.currentTimeMillis * 2) // Keep Dates within range (ArithmeticException for any Long.MAX_VALUE)
}
