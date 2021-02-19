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

  final val instantReads: Reads[Instant] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(s => Instant.ofEpochMilli(s.toLong))

  final val instantWrites: Writes[Instant] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap(_.toEpochMilli.toString)

  final val instantFormat: Format[Instant] = Format(instantReads, instantWrites)

  // LocalDate

  private val msInDay = 24 * 60 * 60 * 1000

  final val localDateReads: Reads[LocalDate] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(date => LocalDate.ofEpochDay(date.toLong / msInDay))

  final val localDateWrites: Writes[LocalDate] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap(localDate => (msInDay * localDate.toEpochDay).toString)

  final val localDateFormat = Format(localDateReads, localDateWrites)

  // LocalDateTime

  final val localDateTimeReads: Reads[LocalDateTime] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(dateTime => LocalDateTime.ofInstant(Instant.ofEpochMilli(dateTime.toLong), ZoneId.of("Z")))

  final val localDateTimeWrites: Writes[LocalDateTime] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap(_.toInstant(ZoneOffset.UTC).toEpochMilli.toString)

  final val localDateTimeFormat = Format(localDateTimeReads, localDateTimeWrites)

  trait Implicits {
    implicit val jatInstantFormat: Format[Instant]             = outer.instantFormat
    implicit val jatLocalDateFormat: Format[LocalDate]         = outer.localDateFormat
    implicit val jatLocalDateTimeFormat: Format[LocalDateTime] = outer.localDateTimeFormat
  }

  object Implicits extends Implicits
}

object MongoJavatimeFormats extends MongoJavatimeFormats
