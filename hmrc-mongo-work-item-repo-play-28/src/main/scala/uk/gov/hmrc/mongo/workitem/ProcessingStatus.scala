/*
 * Copyright 2022 HM Revenue & Customs
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

import org.bson.{BsonString, BsonValue}
import play.api.libs.json._


sealed trait ProcessingStatus {
  def name: String
}

sealed trait ResultStatus extends ProcessingStatus

object ProcessingStatus {
  outer =>

  case object ToDo              extends ProcessingStatus { override val name = "todo"               }
  case object InProgress        extends ProcessingStatus { override val name = "in-progress"        }
  case object Succeeded         extends ResultStatus     { override val name = "succeeded"          }
  case object Deferred          extends ResultStatus     { override val name = "deferred"           }
  case object Failed            extends ResultStatus     { override val name = "failed"             }
  case object PermanentlyFailed extends ResultStatus     { override val name = "permanently-failed" }
  case object Ignored           extends ResultStatus     { override val name = "ignored"            }
  case object Duplicate         extends ResultStatus     { override val name = "duplicate"          }
  case object Cancelled         extends ResultStatus     { override val name = "cancelled"          }

  val values: Set[ProcessingStatus] =
    Set(
      ToDo,
      InProgress,
      Succeeded,
      Failed,
      PermanentlyFailed,
      Ignored,
      Duplicate,
      Deferred,
      Cancelled
    )

  val cancellable: Set[ProcessingStatus] =
    Set(
      ToDo,
      Failed,
      PermanentlyFailed,
      Ignored,
      Duplicate,
      Deferred
    )

  private val nameToStatus: Map[String, ProcessingStatus] =
    values.map(s => (s.name, s)).toMap

  val reads: Reads[ProcessingStatus] =
    Reads[ProcessingStatus] { json =>
      json.validate[String]
        .flatMap(n => nameToStatus.get(n) match {
          case Some(s) => JsSuccess(s)
          case None    => JsError(s"Could not convert to ProcessingStatus from $n")
        })
    }

  val writes: Writes[ProcessingStatus] =
    new Writes[ProcessingStatus] {
      override def writes(status: ProcessingStatus): JsValue =
        JsString(status.name)
    }

  val format: Format[ProcessingStatus] =
    Format(reads, writes)

  object Implicits {
    implicit val format: Format[ProcessingStatus] = outer.format
  }

  def toBson(status: ProcessingStatus): BsonValue =
    new BsonString(status.name)
}
