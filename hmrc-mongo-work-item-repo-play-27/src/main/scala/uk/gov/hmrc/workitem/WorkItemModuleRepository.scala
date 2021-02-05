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

import com.typesafe.config.Config
import org.joda.time.DateTime
import play.api.libs.json._
import reactivemongo.api.DB
import reactivemongo.bson._
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats

import scala.concurrent.{ExecutionContext, Future}

/** If you have multiple lifecycles on a WorkItem, you can use the WorkItemModuleRepository
  * to interact with those lifecycles.
  * It will namespace the lifecycle fields with the provided moduleName.
  * It assumes creation of WorkItems are made through another view (e.g. a standard [[WorkItemRepository]]), it will
  * only allow interacting with the WorkItem lifecycle, and will throw runtime exceptions if `pushNew` is called.
  */
abstract class WorkItemModuleRepository[T](collectionName: String,
                                           moduleName: String,
                                           mongo: () => DB,
                                           config: Config
                                          )(implicit tmf: Manifest[T], trd: Reads[T])
  extends WorkItemRepository[T, BSONObjectID](
    collectionName,
    mongo,
    WorkItemModuleRepository.formatsOf[T](moduleName),
    config
  ) {

  def protectFromWrites = throw new IllegalStateException("The model object cannot be created via the work item module repository")

  override def pushNew(item: T, receivedAt: DateTime)(implicit ec: ExecutionContext): Future[WorkItem[T]] = protectFromWrites

  override def pushNew(item: T, receivedAt: DateTime, initialState: (T) => ProcessingStatus)(implicit ec: ExecutionContext): Future[WorkItem[T]] = protectFromWrites

  override def pushNew(items: Seq[T], receivedAt: DateTime)(implicit ec: ExecutionContext): Future[Seq[WorkItem[T]]] = protectFromWrites

  override def pushNew(items: Seq[T], receivedAt: DateTime, initialState: (T) => ProcessingStatus)(implicit ec: ExecutionContext): Future[Seq[WorkItem[T]]] = protectFromWrites

  override lazy val workItemFields: WorkItemFieldNames = WorkItemModuleRepository.workItemFieldNames(moduleName)

  override lazy val metricPrefix: String = moduleName

}

object WorkItemModuleRepository {

  import play.api.libs.functional.syntax._

  implicit val dateReads: Reads[DateTime] = ReactiveMongoFormats.dateTimeRead

  private val updatedAtProperty   : String = "updatedAt"
  private val createdAtProperty   : String = "createdAt"
  private val failureCountProperty: String = "failureCount"
  private val statusProperty      : String = "status"

  def workItemFieldNames(moduleName: String) = new WorkItemFieldNames {
    override val availableAt : String = s"$moduleName.$createdAtProperty"
    override val updatedAt   : String = s"$moduleName.$updatedAtProperty"
    override val failureCount: String = s"$moduleName.$failureCountProperty"
    override val status      : String = s"$moduleName.$statusProperty"
    override val receivedAt  : String = availableAt
    override val id          : String = "_id"
  }

  def upsertModuleQuery(moduleName: String, time: DateTime) = {
    implicit val dateWrites: Writes[DateTime] = ReactiveMongoFormats.dateTimeWrite

    val fieldNames = workItemFieldNames(moduleName)
    Json.obj(
      "$setOnInsert" -> Json.obj(fieldNames.availableAt -> time),
      "$set" -> Json.obj(fieldNames.updatedAt -> time, fieldNames.status -> ToDo, fieldNames.failureCount -> 0)
    )
  }


  def formatsOf[T](moduleName:String)(implicit trd:Reads[T]): Format[WorkItem[T]] = {
    val reads: Reads[WorkItem[T]] =
      ( (__ \ "_id").read[BSONObjectID]
      ~ (__ \ moduleName \ s"$createdAtProperty").read[DateTime]
      ~ (__ \ moduleName \ s"$updatedAtProperty").read[DateTime]
      ~ (__ \ moduleName \ s"$createdAtProperty").read[DateTime]
      ~ (__ \ moduleName \ s"$statusProperty").read[ProcessingStatus]
      ~ (__ \ moduleName \ s"$failureCountProperty").read[Int].orElse(Reads.pure(0))
      ~ __.read[T]
      )(WorkItem.apply[T] _)

    val writes: Writes[WorkItem[T]] = new Writes[WorkItem[T]] {
      override def writes(o: WorkItem[T]): JsValue = throw new IllegalStateException("A work item module is not supposed to be written")
    }

    Format(reads, writes)
  }
}
