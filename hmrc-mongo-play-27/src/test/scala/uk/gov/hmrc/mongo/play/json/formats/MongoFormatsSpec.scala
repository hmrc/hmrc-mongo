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

import java.{time => jat}

import play.api.libs.json.{Json, JsValue}
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.bson.json.JsonWriterSettings
import org.bson.json.JsonMode
import org.bson.json.JsonWriter
import org.bson.codecs.EncoderContext


class MongoFormatsSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalaCheckDrivenPropertyChecks
    with EitherValues {

  import org.scalacheck.Shrink.shrinkAny // disable shrinking here - will just generate invalid numbers

  "formats" should {
    def codecWrite[A](a: A)(implicit clazz: Class[A]): JsValue = {
      val writer = new java.io.StringWriter
      val writerSettings = JsonWriterSettings.builder.outputMode(JsonMode.EXTENDED).build
      DEFAULT_CODEC_REGISTRY.get(clazz).encode(new JsonWriter(writer, writerSettings), a, EncoderContext.builder.build)
      Json.parse(writer.toString)
    }

    "be compatible with default java.time.Instant codec" in {
      forAll(epochMilisGen) { epochMillis =>
        val javaInstant = jat.Instant.ofEpochMilli(epochMillis)
        MongoJavatimeFormats.instantFormat.writes(javaInstant) shouldBe codecWrite(javaInstant)(classOf[jat.Instant])
      }
    }

    "be compatible with default java.time.LocalDateTime codec" in {
      forAll(epochMilisGen) { epochMillis =>
        val javaLocalDateTime = jat.Instant.ofEpochMilli(epochMillis).atZone(jat.ZoneOffset.UTC).toLocalDateTime
        MongoJavatimeFormats.localDateTimeFormat.writes(javaLocalDateTime) shouldBe codecWrite(javaLocalDateTime)(classOf[jat.LocalDateTime])
      }
    }

    "be compatible with default java.time.LocalDate codec" in {
      forAll(epochMilisGen) { epochMillis =>
        val javaLocalDate = jat.Instant.ofEpochMilli(epochMillis).atZone(jat.ZoneOffset.UTC).toLocalDate
        MongoJavatimeFormats.localDateFormat.writes(javaLocalDate) shouldBe codecWrite(javaLocalDate)(classOf[jat.LocalDate])
      }
    }
  }

  def epochMilisGen =
    Gen.choose(0L, System.currentTimeMillis * 2) // Keep Dates within range (ArithmeticException for any Long.MAX_VALUE)
}
