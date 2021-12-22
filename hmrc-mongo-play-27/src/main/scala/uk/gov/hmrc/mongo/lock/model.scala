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

package uk.gov.hmrc.mongo.lock

import java.time.Instant

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

case class Lock(
  id         : String,
  owner      : String,
  timeCreated: Instant,
  expiryTime : Instant
)

object Lock {

  implicit val format: Format[Lock] = {
    implicit val dtf: Format[Instant] = MongoJavatimeFormats.instantFormat
    ( (__ \ "_id"        ).format[String]
    ~ (__ \ "owner"      ).format[String]
    ~ (__ \ "timeCreated").format[Instant]
    ~ (__ \ "expiryTime" ).format[Instant]
    )(Lock.apply, unlift(Lock.unapply))
  }

  val id          = "_id"
  val owner       = "owner"
  val timeCreated = "timeCreated"
  val expiryTime  = "expiryTime"
}
