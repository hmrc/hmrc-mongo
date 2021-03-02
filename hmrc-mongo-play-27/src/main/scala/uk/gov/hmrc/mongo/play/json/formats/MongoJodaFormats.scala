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

import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalDateTime}
import play.api.libs.json._

trait MongoJodaFormats {
  outer =>

  // LocalDate

  final val localDateReads: Reads[LocalDate] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(date =>
        new LocalDate(date.toLong, DateTimeZone.UTC)
      )

  final val localDateWrites: Writes[LocalDate] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap[LocalDate](_.toDateTimeAtStartOfDay(DateTimeZone.UTC).getMillis.toString)

  final val localDateFormat: Format[LocalDate] =
    Format(localDateReads, localDateWrites)

  // LocalDateTime

  final val localDateTimeReads: Reads[LocalDateTime] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(dateTime => new LocalDateTime(dateTime.toLong, DateTimeZone.UTC))

  final val localDateTimeWrites: Writes[LocalDateTime] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap[LocalDateTime](_.toDateTime(DateTimeZone.UTC).getMillis.toString)

  final val localDateTimeFormat: Format[LocalDateTime] =
    Format(localDateTimeReads, localDateTimeWrites)

  // DateTime

  final val dateTimeReads: Reads[DateTime] =
    Reads.at[String](__ \ "$date" \ "$numberLong")
      .map(dateTime => new DateTime(dateTime.toLong, DateTimeZone.UTC))

  final val dateTimeWrites: Writes[DateTime] =
    Writes.at[String](__ \ "$date" \ "$numberLong")
      .contramap[DateTime](_.getMillis.toString)

  final val dateTimeFormat: Format[DateTime] =
    Format(dateTimeReads, dateTimeWrites)

  trait Implicits {
    implicit val jotLocalDateFormat    : Format[LocalDate]     = outer.localDateFormat
    implicit val jotLocalDateTimeFormat: Format[LocalDateTime] = outer.localDateTimeFormat
    implicit val jotDateTimeFormat     : Format[DateTime]      = outer.dateTimeFormat
  }

  object Implicits extends Implicits
}

object MongoJodaFormats extends MongoJodaFormats
