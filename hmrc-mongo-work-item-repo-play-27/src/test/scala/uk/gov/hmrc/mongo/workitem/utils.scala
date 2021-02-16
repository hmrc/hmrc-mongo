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

package uk.gov.hmrc.mongo.workitem

import java.time.{Duration, Instant}
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

import org.bson.types.ObjectId
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.TestSuite
import play.api.libs.json.Json
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

trait TimeSource {
  val timeSource = new {
    private val nowRef =
      new AtomicReference[Instant](Instant.now())

    def now: Instant =
      nowRef.get()

    def advanceADay() =
      setAndGet(now.plus(1, ChronoUnit.DAYS))

    def advance(duration: Duration) =
      setAndGet(now.plus(duration))

    def retreat1Day() =
      setAndGet(now.minus(1, ChronoUnit.DAYS))

    def retreatAlmostDay() =
      setAndGet(now.minus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.MINUTES))

    private def setAndGet(newNow: Instant) = {
      nowRef.set(newNow)
      newNow
    }
  }
}

trait WithWorkItemRepositoryModule
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItemWithModule]]
  with TimeSource {
    this: TestSuite =>

  implicit val eif = ExampleItemWithModule.formats

  override lazy val repository = new WorkItemModuleRepository[ExampleItemWithModule](
    collectionName = "items",
    moduleName     = "testModule",
    mongoComponent = mongoComponent,
  ) {
    override val inProgressRetryAfter: Duration =
      Duration.ofHours(1)

    override def now(): Instant =
      timeSource.now
  }
}

trait WithWorkItemRepository
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItem]]
  with IntegrationPatience
  with TimeSource {
    this: TestSuite =>

  import uk.gov.hmrc.mongo.play.json.formats.MongoFormats.Implicits.objectIdFormats
  import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.Implicits.jatInstantFormats
  implicit val eif = ExampleItem.formats

  def exampleItemRepository(collectionName: String) = {
    val workItemFields =
      WorkItemFieldNames(
        id           = "_id",
        receivedAt   = "receivedAt",
        updatedAt    = "updatedAt",
        availableAt  = "availableAt",
        status       = "status",
        failureCount = "failureCount",
        item         = "item"
      )

    new WorkItemRepository[ExampleItem, ObjectId](
      collectionName = collectionName,
      mongoComponent = mongoComponent,
      itemFormat     = WorkItem.formatForFields[ExampleItem](workItemFields),
      workItemFields = workItemFields
    ) {
      override lazy val inProgressRetryAfter: Duration =
        Duration.ofHours(1)

      override def now(): Instant =
        timeSource.now
    }
  }

  override lazy val collectionName = "items"

  override lazy val repository = exampleItemRepository(collectionName)

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
  implicit val formats = Json.format[ExampleItem]
}


case class ExampleItemWithModule(
  _id      : ObjectId,
  updatedAt: Instant,
  value    : String
)

object ExampleItemWithModule {
  implicit val formats = {
    implicit val instantReads = uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.instantFormats
    implicit val objectIdFormats = uk.gov.hmrc.mongo.play.json.formats.MongoFormats.objectIdFormats
    Json.format[ExampleItemWithModule]
  }
}
