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

import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.mongodb.scala.model._
import java.time.Instant
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoFormats.Implicits.objectIdFormats
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.Implicits.jatInstantFormats

import scala.concurrent.{ExecutionContext, Future}

/** If you have multiple lifecycles on a WorkItem, you can use the WorkItemModuleRepository
  * to interact with those lifecycles.
  * It will namespace the lifecycle fields with the provided moduleName.
  * It assumes creation of WorkItems are made through another view (e.g. a standard [[WorkItemRepository]]), it will
  * only allow interacting with the WorkItem lifecycle, and will throw runtime exceptions if `pushNew` or `pushNewBatch` are called.
  */
abstract class WorkItemModuleRepository[T](
  collectionName: String,
  moduleName    : String,
  mongoComponent: MongoComponent,
  replaceIndexes: Boolean = true
)(implicit
  trd: Reads[T],
  ec : ExecutionContext
) extends WorkItemRepository[T, ObjectId](
  collectionName = collectionName,
  mongoComponent = mongoComponent,
  itemFormat     = WorkItemModuleRepository.formatsOf[T](moduleName),
  workItemFields = WorkItemModuleRepository.workItemFieldNames(moduleName),
  replaceIndexes = replaceIndexes
) {
  private def protectFromWrites =
    throw new IllegalStateException("The model object cannot be created via the work item module repository")

  override def pushNew(item: T, availableAt: Instant, initialState: T => ProcessingStatus): Future[WorkItem[T]] =
    protectFromWrites

  override def pushNewBatch(items: Seq[T], availableAt: Instant, initialState: T => ProcessingStatus): Future[Seq[WorkItem[T]]] =
    protectFromWrites

  override lazy val metricPrefix: String =
    moduleName
}

object WorkItemModuleRepository {
  def workItemFieldNames(moduleName: String) = {
    val availableAt = s"$moduleName.createdAt"
    WorkItemFieldNames(
      id           = "_id",
      receivedAt   = availableAt,
      updatedAt    = s"$moduleName.updatedAt",
      availableAt  = availableAt,
      status       = s"$moduleName.status",
      failureCount = s"$moduleName.failureCount",
      item         = ""
    )
  }

  def upsertModuleQuery(moduleName: String, time: Instant): Bson = {
    val fieldNames = workItemFieldNames(moduleName)
    Updates.combine(
      Updates.setOnInsert(fieldNames.availableAt, time),
      Updates.set(fieldNames.updatedAt, time),
      Updates.set(fieldNames.status, ProcessingStatus.toBson(ProcessingStatus.ToDo)),
      Updates.set(fieldNames.failureCount, 0)
    )
  }

  def formatsOf[T](moduleName: String)(implicit trd: Reads[T]): Format[WorkItem[T]] = {

    val writes: Writes[T] = new Writes[T] {
      override def writes(o: T): JsValue =
        throw new IllegalStateException("A work item module is not supposed to be written")
    }

    implicit val format: Format[T] = Format(trd, writes)

    WorkItem.formatForFields[T](workItemFieldNames(moduleName))
  }
}
