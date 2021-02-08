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

import org.bson.types.ObjectId
import org.joda.time.DateTime
import org.mongodb.scala.model._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Writes
import uk.gov.hmrc.mongo.play.json.Codecs

import scala.concurrent.ExecutionContext.Implicits.global

class WorkItemModuleRepositorySpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with BeforeAndAfterEach
     with IntegrationPatience
     with WithWorkItemRepositoryModule {

  implicit val formats = ExampleItemWithModule.formats
  implicit val dateWrites: Writes[DateTime] = uk.gov.hmrc.mongo.play.json.formats.MongoJodaFormats.dateTimeWrite

  "WorkItemModuleRepository" should {
    "read the work item fields" in {
      val _id = new ObjectId()
      val documentCreationTime = timeSource.now
      val workItemModuleCreationTime = documentCreationTime.plusHours(1)

      repository.collection.updateOne(
        filter  = Filters.equal("_id", _id),
        update  = Updates.combine(
                    Updates.set("_id", _id),
                    Updates.set("updatedAt", Codecs.toBson(documentCreationTime)), // why updatedt? its covered by upsertModuleQuery
                    Updates.set("value", "test"),
                    WorkItemModuleRepository.upsertModuleQuery("testModule", workItemModuleCreationTime)
                  ),
        options = UpdateOptions().upsert(true)
      ).toFuture
       .map(res => Some(res.getUpsertedId).isDefined shouldBe true)
       .futureValue

      repository.pullOutstanding(documentCreationTime.plusHours(2), documentCreationTime.plusHours(2)).
        futureValue shouldBe Some(WorkItem[ExampleItemWithModule](
          id           = _id,
          receivedAt   = workItemModuleCreationTime,
          updatedAt    = timeSource.now,
          availableAt  = workItemModuleCreationTime,
          status       = InProgress,
          failureCount = 0,
          item         = ExampleItemWithModule(_id, documentCreationTime, "test")
        )
      )
    }

   "never update T" in {
      intercept[IllegalStateException] {
        repository.pushNew(ExampleItemWithModule(new ObjectId(), timeSource.now, "test"), timeSource.now)
      }.getMessage shouldBe "The model object cannot be created via the work item module repository"

      intercept[IllegalStateException] {
        val m = ExampleItemWithModule(new ObjectId(), timeSource.now, "test")
        WorkItemModuleRepository.formatsOf[ExampleItemWithModule]("testModule").writes(WorkItem(new ObjectId(), timeSource.now, timeSource.now, timeSource.now, ToDo, 0, m))
      }.getMessage shouldBe "A work item module is not supposed to be written"

    }

    "use the module name as the gauge name" in {
      repository.metricPrefix should be ("testModule")
    }

    "change state successfully" in {
      val _id = new ObjectId()
      val documentCreationTime = timeSource.now
      val workItemModuleCreationTime = documentCreationTime.plusHours(1)

      repository.collection.updateOne(
        filter  = Filters.equal("_id", _id),
        update  = Updates.combine(
                    Updates.set("_id"      , _id),
                    Updates.set("updatedAt", Codecs.toBson(documentCreationTime)),
                    Updates.set("value"    , "test"),
                    WorkItemModuleRepository.upsertModuleQuery("testModule", workItemModuleCreationTime)
                  ),
        options = UpdateOptions().upsert(true)
      ).toFuture
       .map(res => Some(res.getUpsertedId).isDefined shouldBe true)
       .futureValue

      repository.markAs(_id, Succeeded).futureValue shouldBe true

      val Some(workItem: WorkItem[ExampleItemWithModule]) =
        repository.collection.find(
          filter = Filters.equal("_id", _id)
        ).toFuture
         .map(_.headOption)
         .futureValue
      workItem.id shouldBe _id
      workItem.status shouldBe Succeeded
    }
  }
}
