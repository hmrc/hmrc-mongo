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

import play.api.libs.json._
import reactivemongo.bson._

sealed trait ProcessingStatus {
  val name = toString.toLowerCase
}
sealed trait ResultStatus extends ProcessingStatus

case object ToDo extends ProcessingStatus
case object InProgress extends ProcessingStatus { override val name = "in-progress" }
case object Succeeded extends ResultStatus
case object Deferred extends ResultStatus
case object Failed extends ResultStatus
case object PermanentlyFailed extends ResultStatus {override val name = "permanently-failed"}
case object Ignored extends ResultStatus
case object Duplicate extends ResultStatus
case object Cancelled extends ResultStatus

object ProcessingStatus {
  val processingStatuses: Set[ProcessingStatus] = Set(ToDo, InProgress, Succeeded, Failed, PermanentlyFailed, Ignored, Duplicate, Deferred, Cancelled)
  val nameToStatus:Map[String,ProcessingStatus] = processingStatuses.map(s => (s.name, s)).toMap

  implicit val read: Reads[ProcessingStatus] = new Reads[ProcessingStatus] {
    override def reads(json: JsValue): JsResult[ProcessingStatus] = json match {
      case JsString(status) if nameToStatus.contains(status) => JsSuccess(nameToStatus(status))
      case other => JsError("Could not convert to ProcessingStatus from " + other)
    }
  }

  implicit val write: Writes[ProcessingStatus] = new Writes[ProcessingStatus] {
    override def writes(p: ProcessingStatus): JsValue = JsString(p.name)
  }

  implicit val bsonReader: BSONReader[BSONString, ProcessingStatus] = new BSONReader[BSONString, ProcessingStatus] {
    def read(bson: BSONString) = nameToStatus(bson.value)
  }

  implicit val variantBsonWriter: VariantBSONWriter[ProcessingStatus, BSONString] = new VariantBSONWriter[ProcessingStatus, BSONString] {
    def write(t: ProcessingStatus) = BSONString(t.name)
  }

  implicit val bsonWriter: BSONWriter[ProcessingStatus, _ <: BSONValue] = DefaultBSONHandlers.findWriter(variantBsonWriter)
}
