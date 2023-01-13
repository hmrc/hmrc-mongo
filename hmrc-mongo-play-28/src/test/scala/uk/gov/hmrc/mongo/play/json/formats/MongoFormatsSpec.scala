/*
 * Copyright 2023 HM Revenue & Customs
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

import akka.util.ByteString
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

import java.nio.ByteBuffer
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

    "encoding byte arrays in standard Mongo binary encoding" should {
      "round trip binary data" in {
        forAll { bytes: Array[Byte] =>
          val jsValue = MongoBinaryFormats.byteArrayFormat.writes(bytes)
          MongoBinaryFormats.byteArrayFormat.reads(jsValue).asOpt should contain(bytes)
        }
      }

      "be compatible with byte array codec" in {
        forAll { bytes: Array[Byte] =>
          MongoBinaryFormats.byteArrayFormat.writes(bytes) shouldBe codecWrite(bytes)(classOf[Array[Byte]])
        }
      }

      "allow reading bytes written by byte array codec" in {
        forAll { bytes: Array[Byte] =>
          val jsValue = codecWrite(bytes)(classOf[Array[Byte]])
          MongoBinaryFormats.byteArrayFormat.reads(jsValue).asOpt should contain(bytes)
        }
      }

      "not allow reading bytes written with the wrong binary subtype" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.JAVA_LEGACY)(classOf[UUID])
          MongoBinaryFormats.byteArrayFormat.reads(jsValue) shouldBe JsError(
            "Invalid BSON binary subtype for generic binary data: '03'"
          )
        }
      }
    }

    "encoding byte buffers in standard Mongo binary encoding" should {
      "round trip binary data" in {
        forAll { bytes: Array[Byte] =>
          val jsValue = MongoBinaryFormats.byteBufferFormat.writes(ByteBuffer.wrap(bytes))
          MongoBinaryFormats.byteBufferFormat.reads(jsValue).asOpt should contain(ByteBuffer.wrap(bytes))
        }
      }

      "be compatible with byte array codec" in {
        forAll { bytes: Array[Byte] =>
          MongoBinaryFormats.byteBufferFormat.writes(ByteBuffer.wrap(bytes)) shouldBe codecWrite(bytes)(classOf[Array[Byte]])
        }
      }

      "allow reading bytes written by byte array codec" in {
        forAll { bytes: Array[Byte] =>
          val jsValue = codecWrite(bytes)(classOf[Array[Byte]])
          MongoBinaryFormats.byteBufferFormat.reads(jsValue).asOpt should contain(ByteBuffer.wrap(bytes))
        }
      }

      "not allow reading bytes written with the wrong binary subtype" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.JAVA_LEGACY)(classOf[UUID])
          MongoBinaryFormats.byteBufferFormat.reads(jsValue) shouldBe JsError(
            "Invalid BSON binary subtype for generic binary data: '03'"
          )
        }
      }
    }

    "encoding Akka ByteString in standard Mongo binary encoding" should {
      "round trip binary data" in {
        forAll { bytes: Array[Byte] =>
          val jsValue = MongoBinaryFormats.byteStringFormat.writes(ByteString(bytes))
          MongoBinaryFormats.byteStringFormat.reads(jsValue).asOpt should contain(ByteString(bytes))
        }
      }

      "be compatible with byte array codec" in {
        forAll { bytes: Array[Byte] =>
          MongoBinaryFormats.byteStringFormat.writes(ByteString(bytes)) shouldBe codecWrite(bytes)(classOf[Array[Byte]])
        }
      }

      "allow reading bytes written by byte array codec" in {
        forAll { bytes: Array[Byte] =>
          val jsValue = codecWrite(bytes)(classOf[Array[Byte]])
          MongoBinaryFormats.byteStringFormat.reads(jsValue).asOpt should contain(ByteString(bytes))
        }
      }

      "not allow reading bytes written with the wrong binary subtype" in {
        forAll { uuid: UUID =>
          val jsValue = codecWrite(uuid, UuidRepresentation.STANDARD)(classOf[UUID])
          MongoBinaryFormats.byteStringFormat.reads(jsValue) shouldBe JsError(
            "Invalid BSON binary subtype for generic binary data: '04'"
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
