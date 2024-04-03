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

import org.bson.types.ObjectId
import org.mongodb.scala.model.{IndexOptions, IndexModel, Indexes}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.TestSuite
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Duration, Instant, ZoneId}
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.reflectiveCalls

class TimeSource {
  private val clock =
    Clock.tickMillis(ZoneId.systemDefault())

  private val nowRef =
    new AtomicReference[Instant](Instant.now(clock))

  def now(): Instant =
    nowRef.get()

  def advanceADay() =
    setAndGet(now().plus(1, ChronoUnit.DAYS))

  def advance(duration: Duration) =
    setAndGet(now().plus(duration))

  def retreat1Day() =
    setAndGet(now().minus(1, ChronoUnit.DAYS))

  def retreatAlmostDay() =
    setAndGet(now().minus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES))

  private def setAndGet(newNow: Instant) = {
    nowRef.set(newNow)
    newNow
  }
}

trait TimeSourceProvider {
  val timeSource: TimeSource = new TimeSource
}

trait WithWorkItemRepositoryModule
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItemWithModule]]
  with TimeSourceProvider { this: TestSuite =>

  implicit val eif: Format[ExampleItemWithModule] = ExampleItemWithModule.formats

  override val repository: WorkItemModuleRepository[ExampleItemWithModule] =
    new WorkItemModuleRepository[ExampleItemWithModule](
      collectionName = "items",
      moduleName     = "testModule",
      mongoComponent = mongoComponent,
      extraIndexes   = Seq(IndexModel(Indexes.ascending("testModule.updatedAt"), IndexOptions().expireAfter(24 * 60 * 60, TimeUnit.SECONDS)))
    ) {
      override val inProgressRetryAfter: Duration =
        Duration.ofHours(1)

      override def now(): Instant =
        timeSource.now()
    }
}

trait WithWorkItemRepository
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItem]]
  with IntegrationPatience
  with TimeSourceProvider { this: TestSuite =>

  def exampleItemRepository(collectionName: String) = {
    val workItemFields =
      WorkItemFields(
        id           = "_id",
        receivedAt   = "receivedAt",
        updatedAt    = "updatedAt",
        availableAt  = "availableAt",
        status       = "status",
        failureCount = "failureCount",
        item         = "item"
      )

    new WorkItemRepository[ExampleItem](
      collectionName = collectionName,
      mongoComponent = mongoComponent,
      itemFormat     = ExampleItem.formats,
      workItemFields = workItemFields,
      extraIndexes   = Seq(IndexModel(Indexes.ascending("updatedAt"), IndexOptions().expireAfter(24 * 60 * 60, TimeUnit.SECONDS)))
    ) {
      override lazy val inProgressRetryAfter: Duration =
        Duration.ofHours(1)

      override def now(): Instant =
        timeSource.now()
    }
  }

  override lazy val collectionName = "items"

  override val repository: WorkItemRepository[ExampleItem] =
    exampleItemRepository(collectionName)

  val item1 = ExampleItem("id1")
  val item2 = item1.copy(id = "id2")
  val item3 = item1.copy(id = "id3")
  val item4 = item1.copy(id = "id4")
  val item5 = item1.copy(id = "id5")
  val item6 = item1.copy(id = "id6")

  val allItems = Seq(item1, item2, item3, item4, item5, item6)
}

case class ExampleItem(id: String)

object ExampleItem {
  implicit val formats: Format[ExampleItem] = Json.format[ExampleItem]
}


case class ExampleItemWithModule(
  _id      : ObjectId,
  updatedAt: Instant,
  value    : String
)

object ExampleItemWithModule {
  implicit val formats: Format[ExampleItemWithModule] = {
    implicit val instantReads  : Format[Instant]  = uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.instantFormat
    implicit val objectIdFormat: Format[ObjectId] = uk.gov.hmrc.mongo.play.json.formats.MongoFormats.objectIdFormat
    Json.format[ExampleItemWithModule]
  }
}
