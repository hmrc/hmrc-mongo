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

import java.time._

import play.api.libs.json._

import scala.util.{Success, Try}

trait MongoJavatimeFormats {
  outer =>

  val instantWrites: Writes[Instant] = new Writes[Instant] {
    def writes(datetime: Instant): JsValue = Json.obj("$date" -> datetime.toEpochMilli)
  }

  val instantReads: Reads[Instant] =
    (__ \ "$date").read[Long].map(Instant.ofEpochMilli)

  val instantFormats: Format[Instant] = Format(instantReads, instantWrites)

  val localDateAsStringWrites: Writes[LocalDate] = new Writes[LocalDate] {
    def writes(d: LocalDate): JsValue =
      JsString(d.toString)
  }

  val localDateAsStringReads: Reads[LocalDate] = new Reads[LocalDate] {

    def reads(json: JsValue): JsResult[LocalDate] = json match {
      case JsString(s) =>
        Try(LocalDate.parse(s)) match {
          case Success(d) => JsSuccess(d)
          case _          => JsError(__, "error.expected.jodadate.format")
        }
      case _ => JsError(__, "error.expected.date")
    }
  }

  val localDateAsStringFormats = Format(localDateAsStringReads, localDateAsStringWrites)

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
    implicit val instantFormats: Format[Instant]             = outer.instantFormats
    implicit val localDateFormats: Format[LocalDate]         = outer.localDateFormats
    implicit val localDateTimeFormats: Format[LocalDateTime] = outer.localDateTimeFormats
  }

  object Implicits extends Implicits
}

object MongoJavatimeFormats extends MongoJavatimeFormats
