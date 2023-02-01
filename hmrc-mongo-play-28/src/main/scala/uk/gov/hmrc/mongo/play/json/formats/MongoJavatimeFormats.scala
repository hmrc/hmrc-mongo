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

import java.time.{Instant, LocalDate, ZoneOffset}

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

  final val instantFormat: Format[Instant] =
    Format(instantReads, instantWrites)

  // LocalDate

  final val localDateReads: Reads[LocalDate] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(date => Instant.ofEpochMilli(date.toLong).atZone(ZoneOffset.UTC).toLocalDate)

  final val localDateWrites: Writes[LocalDate] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap(_.atStartOfDay(ZoneOffset.UTC).toInstant.toEpochMilli.toString)

  final val localDateFormat: Format[LocalDate] =
    Format(localDateReads, localDateWrites)

  trait Implicits {
    implicit val jatInstantFormat  : Format[Instant]   = outer.instantFormat
    implicit val jatLocalDateFormat: Format[LocalDate] = outer.localDateFormat
  }

  object Implicits extends Implicits
}

object MongoJavatimeFormats extends MongoJavatimeFormats
