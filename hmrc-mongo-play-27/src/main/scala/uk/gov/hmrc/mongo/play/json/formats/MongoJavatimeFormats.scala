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

import java.time.{Instant, LocalDate, LocalDateTime, ZoneId, ZoneOffset}

import play.api.libs.json._

trait MongoJavatimeFormats {
  outer =>

  // Instant

  val instantWrites: Writes[Instant] = new Writes[Instant] {
    def writes(datetime: Instant): JsValue = Json.obj("$date" -> datetime.toEpochMilli)
  }

  val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  // LocalDate

  private val msInDay = 24 * 60 * 60 * 1000

  val localDateRead: Reads[LocalDate] =
    (__ \ "$date")
      .read[Long]
      .map(date => LocalDate.ofEpochDay(date / msInDay))

  val localDateWrite: Writes[LocalDate] = new Writes[LocalDate] {
    def writes(localDate: LocalDate): JsValue =
      Json.obj("$date" -> msInDay * localDate.toEpochDay)
  }

  val localDateFormats = Format(localDateRead, localDateWrite)

  // LocalDateTime

  val localDateTimeRead: Reads[LocalDateTime] =
    (__ \ "$date")
      .read[Long]
      .map(dateTime => LocalDateTime.ofInstant(Instant.ofEpochMilli(dateTime), ZoneId.of("Z")))

  val localDateTimeWrite: Writes[LocalDateTime] = new Writes[LocalDateTime] {
    def writes(dateTime: LocalDateTime): JsValue =
      Json.obj("$date" -> dateTime.toInstant(ZoneOffset.UTC).toEpochMilli)
  }

  val localDateTimeFormats = Format(localDateTimeRead, localDateTimeWrite)

  trait Implicits {
    implicit val jatInstantFormats: Format[Instant]             = outer.instantFormats
    implicit val jatLocalDateFormats: Format[LocalDate]         = outer.localDateFormats
    implicit val jatLocalDateTimeFormats: Format[LocalDateTime] = outer.localDateTimeFormats
  }

  object Implicits extends Implicits
}

object MongoJavatimeFormats extends MongoJavatimeFormats
