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

import java.time.Instant

import org.bson.types.ObjectId
import play.api.libs.functional.syntax._
import play.api.libs.json._

import scala.util.Try


/** Defines the internal fields for [[WorkItem]].
  * e.g.
  * {{{
  * WorkItemFieldNames(
  *   id           = "_id",
  *   receivedAt   = "receivedAt",
  *   updatedAt    = "updatedAt",
  *   availableAt  = "receivedAt",
  *   status       = "status",
  *   failureCount = "failureCount"
  * )
  * }}}
  */

case class WorkItemFieldNames(
  id          : String,
  receivedAt  : String,
  updatedAt   : String,
  availableAt : String,
  status      : String,
  failureCount: String
)

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

  implicit def workItemMongoFormat[T](implicit tFormat: Format[T]): Format[WorkItem[T]] =
    workItemFormat[T](
      objectIdFormat = uk.gov.hmrc.mongo.play.json.formats.MongoFormats.objectIdFormats,
      instantFormat  = uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.instantFormats,
      tFormat        = tFormat
    )


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

  implicit def workItemRestFormat[T](implicit tFormat: Format[T]): Format[WorkItem[T]] =
    workItemFormat[T](
      objectIdFormat = restObjectIdFormat,
      instantFormat  = Format(restInstantReads, restInstantWrites),
      tFormat        = tFormat
    )

  def workItemFormat[T](
    implicit
    objectIdFormat: Format[ObjectId],
    instantFormat : Format[Instant],
    tFormat       : Format[T]
  ): Format[WorkItem[T]] = {
    implicit val psf = ProcessingStatus.format

    ( (__ \ "_id"         ).format[ObjectId]
    ~ (__ \ "receivedAt"  ).format[Instant]
    ~ (__ \ "updatedAt"   ).format[Instant]
    // for backward compatibility before availableAt field was added? Can probably be removed now?
    // it's also used for the REST api...
    ~ OFormat(
      (__ \ "availableAt").read[Instant] or (__ \ "receivedAt").read[Instant],
      (__ \ "availableAt" ).write[Instant]
    )
    ~ (__ \ "status"      ).format[ProcessingStatus]
    ~ (__ \ "failureCount").format[Int]
    ~ (__ \ "item"        ).format[T]
    )(WorkItem.apply[T] _, unlift(WorkItem.unapply[T]))
  }

  /*
  def formatsOf[T](fieldNames: WorkItemFieldNames)(implicit trd: Reads[T]): Format[WorkItem[T]] = {
    implicit val psf = ProcessingStatus.format
    def asPath(fieldName: String): JsPath =
      fieldName.split(".").foldLeft[JsPath](__)(_ \ _)
    val reads: Reads[WorkItem[T]] =
      ( asPath(fieldNames.id          ).read[ObjectId]
      ~ asPath(fieldNames.receivedAt  ).read[Instant]
      ~ asPath(fieldNames.updatedAt   ).read[Instant]
      ~ asPath(fieldNames.availableAt ).read[Instant]
      ~ asPath(fieldNames.status      ).read[ProcessingStatus]
      ~ asPath(fieldNames.failureCount).read[Int].orElse(Reads.pure(0))
      ~ __.read[T]
      )(WorkItem.apply[T] _)

    val writes: Writes[WorkItem[T]] = new Writes[WorkItem[T]] {
      override def writes(o: WorkItem[T]): JsValue = throw new IllegalStateException("A work item module is not supposed to be written")
    }

    Format(reads, writes)
  }*/
}
