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

  final val instantWrites: Writes[Instant] =
    (datetime: Instant) =>
      Json.obj("$date" -> datetime.toEpochMilli)

  final val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  final val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  // LocalDate

  private val msInDay = 24 * 60 * 60 * 1000

  final val localDateRead: Reads[LocalDate] =
    (__ \ "$date")
      .read[Long]
      .map(date => LocalDate.ofEpochDay(date / msInDay))

  final val localDateWrite: Writes[LocalDate] =
    (localDate: LocalDate) =>
      Json.obj("$date" -> msInDay * localDate.toEpochDay)

  final val localDateFormats = Format(localDateRead, localDateWrite)

  // LocalDateTime

  final val localDateTimeRead: Reads[LocalDateTime] =
    (__ \ "$date")
      .read[Long]
      .map(dateTime => LocalDateTime.ofInstant(Instant.ofEpochMilli(dateTime), ZoneId.of("Z")))

  final val localDateTimeWrite: Writes[LocalDateTime] =
    (dateTime: LocalDateTime) =>
      Json.obj("$date" -> dateTime.toInstant(ZoneOffset.UTC).toEpochMilli)

  final val localDateTimeFormats = Format(localDateTimeRead, localDateTimeWrite)

  trait Implicits {
    implicit val jatInstantFormats: Format[Instant]             = outer.instantFormats
    implicit val jatLocalDateFormats: Format[LocalDate]         = outer.localDateFormats
    implicit val jatLocalDateTimeFormats: Format[LocalDateTime] = outer.localDateTimeFormats
  }

  object Implicits extends Implicits
}

object MongoJavatimeFormats extends MongoJavatimeFormats
