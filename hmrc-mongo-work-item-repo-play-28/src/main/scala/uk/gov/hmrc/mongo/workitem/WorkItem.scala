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

package uk.gov.hmrc.mongo.workitem

import java.time.Instant

import org.bson.types.ObjectId
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.Try
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats


/** Defines the internal fields for [[WorkItem]], allowing customisation. */
case class WorkItemFields(
  id          : String,
  receivedAt  : String,
  updatedAt   : String,
  availableAt : String,
  status      : String,
  failureCount: String,
  item        : String
)

object WorkItemFields {
  lazy val default =
    WorkItemFields(
      id           = "_id",
      receivedAt   = "receivedAt",
      updatedAt    = "updatedAt",
      availableAt  = "receivedAt",
      status       = "status",
      failureCount = "failureCount",
      item         = "item"
    )
}

case class WorkItem[T](
  id          : ObjectId,
  receivedAt  : Instant,
  updatedAt   : Instant,
  availableAt : Instant,
  status      : ProcessingStatus,
  failureCount: Int,
  item        : T
)

object WorkItem {

  /** Creates json format for [[WorkItem]] for serialising in Mongo.
    * It requires [[WorkItemFields]] which should keep it aligned with queries.
    */
  def formatForFields[T](
    fieldNames: WorkItemFields
  )(implicit
    tFormat : Format[T]
  ): Format[WorkItem[T]] = {
    import play.api.libs.functional.syntax._

    def asPath(fieldName: String): JsPath =
      if (fieldName.isEmpty) __
      else fieldName.split("\\.").foldLeft[JsPath](__)(_ \ _)

    implicit val psf      = ProcessingStatus.format
    implicit val oif      = MongoFormats.objectIdFormat
    implicit val instantF = MongoJavatimeFormats.instantFormat

    ( asPath(fieldNames.id          ).format[ObjectId]
    ~ asPath(fieldNames.receivedAt  ).format[Instant]
    ~ asPath(fieldNames.updatedAt   ).format[Instant]
    ~ asPath(fieldNames.availableAt ).format[Instant]
    ~ asPath(fieldNames.status      ).format[ProcessingStatus]
    ~ asPath(fieldNames.failureCount).format[Int]
    ~ asPath(fieldNames.item        ).format[T]
    )(WorkItem.apply[T] _, unlift(WorkItem.unapply[T]))
  }

  private lazy val restInstantReads: Reads[Instant] =
    new Reads[Instant] {
      override def reads(json: JsValue): JsResult[Instant] = {
        json match {
          case JsString(s) => Try(Instant.parse(s))
                                .fold(
                                  _ => JsError(s"Could not parse $s as an ISO Instant"),
                                  JsSuccess.apply(_)
                                )
          case _ => JsError(s"Expected value to be a string, was actually $json")
        }
      }
    }

  private lazy val restInstantWrites: Writes[Instant] =
    new Writes[Instant] {
      private val restDateTimeFormat =
        // preserving millis which Instant.toString doesn't when 000
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(java.time.ZoneOffset.UTC)

      def writes(instant: Instant): JsValue =
       JsString(restDateTimeFormat.format(instant))
    }

  private lazy val restObjectIdFormat: Format[ObjectId] =
    Format(
      Reads.StringReads.map(stringObjectId =>
        Try(new ObjectId(stringObjectId))
          .fold(
            exception => throw new RuntimeException(s"'$stringObjectId' is not a valid ObjectId: $exception"),
            identity
          )),
      Writes(id => JsString(id.toString))
    )

  /** Creates a json format for [[WorkItem]] appropriate for serialising through a REST endpoint. */
  def workItemRestFormat[T](implicit tFormat: Format[T]): Format[WorkItem[T]] = {
    implicit val objectIdFormat = restObjectIdFormat
    implicit val instantFormat  = Format(restInstantReads, restInstantWrites)
    implicit val psf            = ProcessingStatus.format

    ( (__ \ "_id"         ).format[ObjectId]
    ~ (__ \ "receivedAt"  ).format[Instant]
    ~ (__ \ "updatedAt"   ).format[Instant]
    // for backward compatibility, some REST requests may not have `receivedAt` field
    ~ OFormat(
      (__ \ "availableAt").read[Instant] or (__ \ "receivedAt").read[Instant],
      (__ \ "availableAt" ).write[Instant]
    )
    ~ (__ \ "status"      ).format[ProcessingStatus]
    ~ (__ \ "failureCount").format[Int]
    ~ (__ \ "item"        ).format[T]
    )(WorkItem.apply[T] _, unlift(WorkItem.unapply[T]))
  }
}
