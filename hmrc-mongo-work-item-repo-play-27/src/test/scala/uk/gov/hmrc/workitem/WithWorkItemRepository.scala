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

import com.typesafe.config.ConfigFactory
import org.bson.types.ObjectId
import org.joda.time.chrono.ISOChronology
import org.joda.time.{DateTime, Duration, LocalDate}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.TestSuite
import play.api.libs.json.Json
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import scala.concurrent.ExecutionContext.Implicits.global

trait TimeSource {

  val timeSource = new {
    var now: DateTime = DateTime.now(ISOChronology.getInstanceUTC)

    def advanceADay() = setNowTo(now.plusDays(1))

    def advance(duration: Duration) = setNowTo(now.plus(duration))

    def retreat1Day() = setNowTo(now.minusDays(1))

    def retreatAlmostDay() = setNowTo(now.minusDays(1).plusMinutes(1))

    def setNowTo(newNow: DateTime) = {
      now = newNow
      now
    }
  }
}

trait WithWorkItemRepositoryModule
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItemWithModule]]
  with TimeSource {
    this: TestSuite =>

  val appConf = ConfigFactory.load("application.test.conf")

  implicit val eif = uk.gov.hmrc.workitem.ExampleItemWithModule.formats

  override lazy val repository = new WorkItemModuleRepository[ExampleItemWithModule](
    collectionName = "items",
    moduleName     = "testModule",
    mongoComponent = mongoComponent,
    config         = appConf
  ) {
    override val inProgressRetryAfterProperty: String = "retryAfterSeconds"
    override lazy val inProgressRetryAfter: Duration = Duration.standardHours(1)

    override def now: DateTime = timeSource.now
  }
}

trait WithWorkItemRepository
  extends ScalaFutures
  with DefaultPlayMongoRepositorySupport[WorkItem[ExampleItem]]
  with IntegrationPatience
  with TimeSource {
    this: TestSuite =>

  implicit val eif = uk.gov.hmrc.workitem.ExampleItem.formats

  val appConf = ConfigFactory.load("application.test.conf")

  def exampleItemRepository(collectionName: String) =
    new WorkItemRepository[ExampleItem, ObjectId](
      collectionName = collectionName,
      mongoComponent = mongoComponent,
      itemFormat     = WorkItem.workItemMongoFormat[ExampleItem],
      config         = appConf,
      workItemFields = new WorkItemFieldNames {
                         val receivedAt   = "receivedAt"
                         val updatedAt    = "updatedAt"
                         val availableAt  = "availableAt"
                         val status       = "status"
                         val id           = "_id"
                         val failureCount = "failureCount"
                       }
    ) {

    override lazy val inProgressRetryAfter: Duration = Duration.standardHours(1)

    def inProgressRetryAfterProperty: String = "retryAfterSeconds"

    def now: DateTime = timeSource.now
  }

  override lazy val collectionName = "items"

  override lazy val repository = exampleItemRepository(collectionName)

  val today = LocalDate.now(ISOChronology.getInstanceUTC)
  val yesterday = today.minusDays(1)

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


case class ExampleItemWithModule(_id: ObjectId, updatedAt: DateTime, value: String)

object ExampleItemWithModule {
  implicit val dateReads = uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats.dateTimeRead
  implicit val dateWrites = uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats.dateTimeWrite
  implicit val objectIdFormats = uk.gov.hmrc.mongo.play.json.formats.MongoFormats.objectIdFormats
  implicit val formats = Json.format[ExampleItemWithModule]
}
