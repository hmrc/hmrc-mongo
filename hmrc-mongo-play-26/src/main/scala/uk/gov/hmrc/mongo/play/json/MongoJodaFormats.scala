/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.play.json

import org.joda.time.{DateTime, DateTimeZone, LocalDate, LocalDateTime}
import play.api.libs.json._

import scala.util.{Success, Try}

trait MongoJodaFormats {
  outer =>

  // LocalDate

  val localDateRead: Reads[LocalDate] =
    (__ \ "$date")
      .read[Long]
      .map(date => new LocalDate(date, DateTimeZone.UTC))

  val localDateWrite: Writes[LocalDate] = new Writes[LocalDate] {
    def writes(localDate: LocalDate): JsValue =
      Json.obj("$date" -> localDate.toDateTimeAtStartOfDay(DateTimeZone.UTC).getMillis)
  }

  val localDateFormats = Format(localDateRead, localDateWrite)

  // LocalDateTime

  val localDateTimeRead: Reads[LocalDateTime] =
    (__ \ "$date")
      .read[Long]
      .map(dateTime => new LocalDateTime(dateTime, DateTimeZone.UTC))

  val localDateTimeWrite: Writes[LocalDateTime] = new Writes[LocalDateTime] {
    def writes(dateTime: LocalDateTime): JsValue =
      Json.obj("$date" -> dateTime.toDateTime(DateTimeZone.UTC).getMillis)
  }

  val localDateTimeFormats = Format(localDateTimeRead, localDateTimeWrite)

  // DateTime

  val dateTimeRead: Reads[DateTime] =
    (__ \ "$date")
      .read[Long]
      .map(dateTime => new DateTime(dateTime, DateTimeZone.UTC))

  val dateTimeWrite: Writes[DateTime] = new Writes[DateTime] {
    def writes(dateTime: DateTime): JsValue =
      Json.obj("$date" -> dateTime.getMillis)
  }

  val dateTimeFormats = Format(dateTimeRead, dateTimeWrite)

  trait Implicits {
    implicit val jotLocalDateFormats: Format[LocalDate]         = outer.localDateFormats
    implicit val jotLocalDateTimeFormats: Format[LocalDateTime] = outer.localDateTimeFormats
    implicit val jotDateTimeFormats: Format[DateTime]           = outer.dateTimeFormats
  }

  object Implicits extends Implicits
}

object MongoJodaFormats extends MongoJodaFormats
