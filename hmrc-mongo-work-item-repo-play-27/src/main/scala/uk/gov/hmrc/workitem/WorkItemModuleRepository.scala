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
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.play.json.Codecs // TODO can remove Codecs.toJson when switch from Joda to Javatime

import uk.gov.hmrc.mongo.play.json.formats.MongoFormats.Implicits.objectIdFormats

import scala.concurrent.{ExecutionContext, Future}

/** If you have multiple lifecycles on a WorkItem, you can use the WorkItemModuleRepository
  * to interact with those lifecycles.
  * It will namespace the lifecycle fields with the provided moduleName.
  * It assumes creation of WorkItems are made through another view (e.g. a standard [[WorkItemRepository]]), it will
  * only allow interacting with the WorkItem lifecycle, and will throw runtime exceptions if `pushNew` is called.
  */
abstract class WorkItemModuleRepository[T](
  collectionName: String,
  moduleName    : String,
  mongoComponent: MongoComponent,
  config        : Config,
  replaceIndexes: Boolean = true
)(implicit
  trd: Reads[T],
  ec : ExecutionContext
) extends WorkItemRepository[T, ObjectId](
  collectionName = collectionName,
  mongoComponent = mongoComponent,
  itemFormat     = WorkItemModuleRepository.formatsOf[T](moduleName),
  config         = config,
  workItemFields = WorkItemModuleRepository.workItemFieldNames(moduleName),
  replaceIndexes = replaceIndexes
) {

  def protectFromWrites =
    throw new IllegalStateException("The model object cannot be created via the work item module repository")

  override def pushNew(item: T, receivedAt: DateTime): Future[WorkItem[T]] =
    protectFromWrites

  override def pushNew(item: T, receivedAt: DateTime, initialState: (T) => ProcessingStatus): Future[WorkItem[T]] =
    protectFromWrites

  override def pushNew(items: Seq[T], receivedAt: DateTime): Future[Seq[WorkItem[T]]] =
    protectFromWrites

  override def pushNew(items: Seq[T], receivedAt: DateTime, initialState: (T) => ProcessingStatus): Future[Seq[WorkItem[T]]] =
    protectFromWrites

  override lazy val metricPrefix: String =
    moduleName
}

object WorkItemModuleRepository {

  import play.api.libs.functional.syntax._

  implicit val dateTimeWrites: Format[DateTime] = uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats.dateTimeFormats

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

  def upsertModuleQuery(moduleName: String, time: DateTime): Bson = {
    val fieldNames = workItemFieldNames(moduleName)
    Updates.combine(
      Updates.setOnInsert(fieldNames.availableAt, Codecs.toBson(time)),
      Updates.set(fieldNames.updatedAt, Codecs.toBson(time)),
      Updates.set(fieldNames.status, Codecs.toBson(ToDo)),
      Updates.set(fieldNames.failureCount, 0)
    )
  }


  def formatsOf[T](moduleName:String)(implicit trd: Reads[T]): Format[WorkItem[T]] = {
    val reads: Reads[WorkItem[T]] =
      ( (__ \ "_id"                                ).read[ObjectId]
      ~ (__ \ moduleName \ s"$createdAtProperty"   ).read[DateTime]
      ~ (__ \ moduleName \ s"$updatedAtProperty"   ).read[DateTime]
      ~ (__ \ moduleName \ s"$createdAtProperty"   ).read[DateTime]
      ~ (__ \ moduleName \ s"$statusProperty"      ).read[ProcessingStatus]
      ~ (__ \ moduleName \ s"$failureCountProperty").read[Int].orElse(Reads.pure(0))
      ~ __.read[T]
      )(WorkItem.apply[T] _)

    val writes: Writes[WorkItem[T]] = new Writes[WorkItem[T]] {
      override def writes(o: WorkItem[T]): JsValue = throw new IllegalStateException("A work item module is not supposed to be written")
    }

    Format(reads, writes)
  }
}
