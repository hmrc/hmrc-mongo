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

import org.bson.codecs.Codec
import org.bson.conversions.Bson
import org.mongodb.scala.model._
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent

import java.time.Instant
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
  replaceIndexes: Boolean = true,
  extraIndexes  : Seq[IndexModel] = Seq.empty,
  extraCodecs   : Seq[Codec[_]]   = Seq.empty
)(implicit
  trd: Reads[T],
  ec : ExecutionContext
) extends WorkItemRepository[T](
  collectionName = collectionName,
  mongoComponent = mongoComponent,
  itemFormat     = WorkItemModuleRepository.readonlyFormat[T](trd),
  workItemFields = WorkItemModuleRepository.workItemFields(moduleName),
  replaceIndexes = replaceIndexes,
  extraIndexes   = extraIndexes,
  extraCodecs    = extraCodecs
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
  def workItemFields(moduleName: String) = {
    val availableAt = s"$moduleName.createdAt"
    WorkItemFields(
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
    val fieldNames = workItemFields(moduleName)
    Updates.combine(
      Updates.setOnInsert(fieldNames.availableAt, time),
      Updates.set(fieldNames.updatedAt, time),
      Updates.set(fieldNames.status, ProcessingStatus.ToDo),
      Updates.set(fieldNames.failureCount, 0)
    )
  }

  def readonlyFormat[T](trd: Reads[T]): Format[T] =
    Format(
      trd,
      (o: T) => throw new IllegalStateException("A work item module is not supposed to be written")
    )
}
