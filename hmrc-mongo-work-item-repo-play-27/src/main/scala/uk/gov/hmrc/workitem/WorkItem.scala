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

package uk.gov.hmrc.workitem

import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.libs.functional.syntax._
import play.api.libs.json._
import org.bson.types.ObjectId
import scala.util.{Success => TrySuccess, Failure => TryFailure}

import scala.util.Try

case class WorkItem[T](
  id          : ObjectId,
  receivedAt  : DateTime,
  updatedAt   : DateTime,
  availableAt : DateTime,
  status      : ProcessingStatus,
  failureCount: Int,
  item        : T
)

object WorkItem {

  private val dateTimeFormat = ISODateTimeFormat.dateTime.withZoneUTC

  implicit def workItemMongoFormat[T](implicit tFormat: Format[T]): Format[WorkItem[T]] = {
    import uk.gov.hmrc.mongo.json.ReactiveMongoFormats._
    mongoEntity(workItemFormat[T])
  }

  implicit def workItemRestFormat[T](implicit tFormat: Format[T]): Format[WorkItem[T]] = {
    implicit val dateTimeRead: Reads[DateTime] = new Reads[DateTime] {
      override def reads(json: JsValue): JsResult[DateTime] = {
        json match {
          case JsString(s) => Try {
            JsSuccess(dateTimeFormat.parseDateTime(s))
          }.getOrElse {
            JsError(s"Could not parse $s as a DateTime with format ${dateTimeFormat.toString}")
          }
          case _ => JsError(s"Expected value to be a string, was actually $json")
        }
      }
    }

    implicit val dateTimeWrite: Writes[DateTime] = new Writes[DateTime] {
      def writes(dateTime: DateTime): JsValue = JsString(dateTimeFormat.print(dateTime))
    }

    implicit val bsonIdFormat: Format[ObjectId] = Format(
      Reads.StringReads.map(stringObjectId => Try(new ObjectId(stringObjectId)) match {
        case TrySuccess(objectId)  => objectId
        case TryFailure(exception) => throw new RuntimeException(s"'$stringObjectId' is not a valid ObjectId: $exception")
      }),
      Writes(id => JsString(id.toString))
    )

    workItemFormat[T]
  }

  def workItemFormat[T](implicit bsonIdFormat: Format[ObjectId],
                        dateTimeFormat: Format[DateTime],
                        tFormat: Format[T]): Format[WorkItem[T]] = {
    val reads =
      ( (__ \ "id"          ).read[ObjectId]
      ~ (__ \ "receivedAt"  ).read[DateTime]
      ~ (__ \ "updatedAt"   ).read[DateTime]
      ~ ((__ \ "availableAt").read[DateTime] or (__ \ "receivedAt").read[DateTime])
      ~ (__ \ "status"      ).read[ProcessingStatus]
      ~ (__ \ "failureCount").read[Int]
      ~ (__ \ "item"        ).read[T]
      )(WorkItem.apply[T] _)

    val writes =
      ( (__ \ "id"          ).write[ObjectId]
      ~ (__ \ "receivedAt"  ).write[DateTime]
      ~ (__ \ "updatedAt"   ).write[DateTime]
      ~ (__ \ "availableAt" ).write[DateTime]
      ~ (__ \ "status"      ).write[ProcessingStatus]
      ~ (__ \ "failureCount").write[Int]
      ~ (__ \ "item"        ).write[T]
      )(unlift(WorkItem.unapply[T]))

    Format(reads, writes)
  }
}
