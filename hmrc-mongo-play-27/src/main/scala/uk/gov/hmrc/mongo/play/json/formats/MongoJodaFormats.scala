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
    Reads.at[Long](__ \ "$date")
      .map(date => new LocalDate(date, DateTimeZone.UTC))

  final val localDateWrites: Writes[LocalDate] =
    Writes.at[Long](__ \ "$date")
      .contramap(_.toDateTimeAtStartOfDay(DateTimeZone.UTC).getMillis)

  final val localDateFormat = Format(localDateReads, localDateWrites)

  // LocalDateTime

  final val localDateTimeReads: Reads[LocalDateTime] =
    Reads.at[Long](__ \ "$date")
      .map(dateTime => new LocalDateTime(dateTime, DateTimeZone.UTC))

  final val localDateTimeWrites: Writes[LocalDateTime] =
    Writes.at[Long](__ \ "$date")
      .contramap(_.toDateTime(DateTimeZone.UTC).getMillis)

  final val localDateTimeFormat = Format(localDateTimeReads, localDateTimeWrites)

  // DateTime

  final val dateTimeReads: Reads[DateTime] =
    Reads.at[Long](__ \ "$date")
      .map(dateTime => new DateTime(dateTime, DateTimeZone.UTC))

  final val dateTimeWrites: Writes[DateTime] =
    Writes.at[Long](__ \ "$date")
      .contramap(_.getMillis)

  final val dateTimeFormat = Format(dateTimeReads, dateTimeWrites)

  trait Implicits {
    implicit val jotLocalDateFormat: Format[LocalDate]         = outer.localDateFormat
    implicit val jotLocalDateTimeFormat: Format[LocalDateTime] = outer.localDateTimeFormat
    implicit val jotDateTimeFormat: Format[DateTime]           = outer.dateTimeFormat
  }

  object Implicits extends Implicits
}

object MongoJodaFormats extends MongoJodaFormats
