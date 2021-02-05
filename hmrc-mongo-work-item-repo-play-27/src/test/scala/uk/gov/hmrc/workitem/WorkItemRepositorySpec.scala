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
import org.scalatest.{LoneElement, Matchers, WordSpec}
import play.api.libs.json.Json
import org.mongodb.scala.model._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import org.joda.time.DateTime
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

class WorkItemRepositorySpec extends WordSpec
  with Matchers
  with WithWorkItemRepository
  with LoneElement {

  def createWorkItemsWith(statuses: Seq[ProcessingStatus]) = {
    Future.traverse(statuses) { status =>
      for {
        item <- repository.pushNew(item1, DateTime.now)
        _    <- repository.markAs(item.id, status)
      } yield ()
    }.futureValue
  }

  "The work item repo as metrics source" should {
    "return the counts for the all processing statuses as map of metrics" in {
      createWorkItemsWith(ProcessingStatus.processingStatuses.toSeq)

      repository.metrics.futureValue should contain only (ProcessingStatus.processingStatuses.toSeq.
        map(status => (s"$collectionName.${status.name}", 1)): _*)
    }

    "return different map of metric counts for different processing statuses" in {
      def metricKey(status: ProcessingStatus) = s"$collectionName.${status.name}"

      createWorkItemsWith(Seq(ToDo, ToDo, InProgress))

      repository.metrics.futureValue should contain allOf(
        metricKey(ToDo) -> 2,
        metricKey(InProgress) -> 1,
        metricKey(Succeeded) -> 0
      )
    }
  }

  "The work item repo" should {
    "be able to save and reload a item" in {
      val returnedItem = repository.pushNew(item1, timeSource.now).futureValue
      val savedItem = findAll().futureValue.loneElement

      returnedItem should have(
        'item (item1),
        'status (ToDo),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )

      returnedItem should equal(savedItem)
    }

    "be able to save and reload a bulk set of items" in {
      val items = Seq(item1, item2, item3, item4, item5, item6)

      val returnedItems = repository.pushNew(items, timeSource.now).futureValue
      val savedItems = findAll().futureValue

      every(savedItems) should have(
        'status (ToDo),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )
      savedItems.map(_.item) should contain theSameElementsAs items
      returnedItems should equal(savedItems)
    }

    "pull ToDo items which were received before the requested time" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "mark a item as done" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, Succeeded).futureValue should be(true)
      findAll().futureValue.loneElement should have(
        'status (Succeeded),
        'failureCount (0),
        'item (item1))
    }

    "return false trying to mark a non-existent item as done" in {
      repository.markAs(new ObjectId(), Succeeded).futureValue should be(false)
      findAll().futureValue should be(empty)
    }

    "never pull a permanently failed notification" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, PermanentlyFailed).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(60), availableBefore = timeSource.now.plusDays(60)).futureValue should be(None)
    }

    "pull nothing when there are no notifications which were received before the requested time" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.minusDays(60)).futureValue should be(None)
    }

    "pull timed out Failed items" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, Failed).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.plusSeconds(1), availableBefore = timeSource.now.plusSeconds(1)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (1)
      )
    }

    "pull timed out In progress items" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      timeSource.advance(repository.inProgressRetryAfter.plus(1))
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "pull nothing if no items exist" in {
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue should be(None)
    }

    "not pull in progress items" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue should be(None)
    }

    "not pull items failed after the failedBefore date" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, Failed).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.minusSeconds(1), availableBefore = timeSource.now).futureValue should be(None)
    }

    "complete a item as Succeded if it is in progress" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      val id = findAll().futureValue.head.id
      repository.complete(id, Succeeded).futureValue should be(true)
      repository.findById(id).futureValue.get should have(
        'status (Succeeded),
        'failureCount (0)
      )
    }

    "increment the failure count when completing a item as Failed" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      val id = findAll().futureValue.head.id
      repository.complete(id, Failed).futureValue should be(true)
      repository.findById(id).futureValue.get should have(
        'status (Failed),
        'failureCount (1)
      )
    }

    "not complete a item if it is not in progress" in {
      repository.pushNew(item1, timeSource.now).futureValue
      val id = findAll().futureValue.head.id
      repository.complete(id, Failed).futureValue should be(false)
      repository.findById(id).futureValue.get should have(
        'status (ToDo),
        'failureCount (0)
      )
    }

    "not complete a item if it cannot be found" in {
      repository.complete(new ObjectId(), Succeeded).futureValue should be(false)
    }

    "be able to save a single item in a custom initial status" in {
      def defer(item: ExampleItem): ProcessingStatus = Deferred
      val returnedItem = repository.pushNew(item1, timeSource.now, defer _).futureValue
      val savedItem = findAll().futureValue.loneElement

      returnedItem should have(
        'item (item1),
        'status (Deferred),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )

      returnedItem should equal(savedItem)
    }

    "be able to selectively save an item in a custom initial status" in {
      def maybeDefer(item: ExampleItem): ProcessingStatus = item match {
        case i if i.id == "id1" => Deferred
        case _ => ToDo
      }
      val returnedItems = repository.pushNew(Seq(item1, item2), timeSource.now, maybeDefer _).futureValue
      exactly(1, returnedItems) should have(
        'item (item1),
        'status (Deferred),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )
      exactly(1, returnedItems) should have(
        'item (item2),
        'status (ToDo),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )

      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue.get.item should be(item2)
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue should be(None)
    }

    "create an item with a specified time for future processing" in {
      repository.pushNew(item = item1, receivedAt = timeSource.now, availableAt = timeSource.now.plusDays(10)).futureValue
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(10)).futureValue should be(None)
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(11)).futureValue.get.item should be(item1)
    }

    "mark an item ToDo with a specified time for future processing" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ToDo, availableAt = Some(timeSource.now.plusDays(10))).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(1)).futureValue should be(None)
    }

    "pull an item deferred for future processing as ToDo" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ToDo, availableAt = Some(timeSource.now.plusDays(1))).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(10)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "pull an item deferred for future processing as Failed" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, Failed, availableAt = Some(timeSource.now.plusDays(2))).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(1)).futureValue should be(None)
      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(3)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (1)
      )
    }

    "pull an item marked as Failed without an 'availableAt' field" in {

      val insertRecord: WorkItem[ExampleItem] = repository.pushNew(item1, timeSource.now).futureValue

      repository.markAs(insertRecord.id, Failed, availableAt = Some(timeSource.now.plusDays(2))).futureValue should be(true)

      repository.collection.findOneAndUpdate(
        filter = Filters.equal("_id", insertRecord.id),
        update = Updates.unset("availableAt"),
        options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      ).toFuture.futureValue

      repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(3)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (1)
      )
    }

    "read a workitem in legacy format" in {
      implicit val format = repository.domainFormat
      Json.parse(
        """{
            "receivedAt":{"$date":1426090745091},
            "updatedAt":{"$date":1426090745091},
            "status":"todo",
            "failureCount":0,
            "item":{"id":"id1"},
            "_id":{"$oid":"55006afb0100000100463c03"}
          }"""
      ).as[WorkItem[ExampleItem]]
    }

    "verify number of indexes created" in {
      repository.collection.dropIndexes.toFuture.futureValue
      repository.ensureIndexes.futureValue
      repository.collection.listIndexes.toFuture.futureValue.size should be(3 + 1) //_id index is created by default
    }

    "count the number of items in a specific state" in {
      repository.pushNew(allItems, timeSource.now).futureValue

      repository.count(ToDo).futureValue shouldBe 6
      repository.count(InProgress).futureValue shouldBe 0
      repository.count(Succeeded).futureValue shouldBe 0
      repository.count(Deferred).futureValue shouldBe 0
      repository.count(Failed).futureValue shouldBe 0
      repository.count(PermanentlyFailed).futureValue shouldBe 0
      repository.count(Ignored).futureValue shouldBe 0
      repository.count(Duplicate).futureValue shouldBe 0
    }

    "pull a 'ToDo state item' when available" in {
      val inProgressRecord: WorkItem[ExampleItem] = repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(inProgressRecord.id, InProgress).futureValue should be(true)

      val failedRecord: WorkItem[ExampleItem] = repository.pushNew(item2, timeSource.now.plusDays(1)).futureValue
      repository.markAs(failedRecord.id, Failed).futureValue should be(true)

      val todoRecord: WorkItem[ExampleItem] = repository.pushNew(item3, timeSource.now.plusDays(1)).futureValue
      repository.markAs(todoRecord.id, ToDo).futureValue should be(true)

      timeSource.advance(repository.inProgressRetryAfter.plus(1))
      val result = repository.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(10)).futureValue.get
      result should have(
        'item (item3),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "pull a 'Failed state item not updated since failedBefore' when available and there is not any ToDo item" in {
      val inProgressRecord: WorkItem[ExampleItem] = repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(inProgressRecord.id, InProgress).futureValue should be(true)

      val failedRecord: WorkItem[ExampleItem] = repository.pushNew(item2, timeSource.now.plusDays(2)).futureValue
      repository.markAs(failedRecord.id, Failed).futureValue should be(true)

      val anotherInProgressRecord: WorkItem[ExampleItem] = repository.pushNew(item3, timeSource.now).futureValue
      repository.markAs(anotherInProgressRecord.id, InProgress).futureValue should be(true)

      timeSource.advance(repository.inProgressRetryAfter.plus(1))
      val result = repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusDays(3)).futureValue.get
      result should have(
        'item (item2),
        'status (InProgress),
        'failureCount (1)
      )
    }

  }

  "Cancelling a notification" should {

    val allowedStatuses = Set(ToDo, Failed, PermanentlyFailed, Ignored, Duplicate, Deferred)

    for (status <- allowedStatuses) {
      s"work if it is in the $status state" in {
        val id = repository.pushNew(item1, timeSource.now).futureValue.id
        repository.markAs(id, status).futureValue should be(true)
        repository.cancel(id).futureValue should be(StatusUpdateResult.Updated(previousStatus = status, newStatus = Cancelled))
        repository.findById(id).futureValue.get should have('status (Cancelled))
      }
    }

    for (status <- ProcessingStatus.processingStatuses -- allowedStatuses) {
      s"not work if it is in the $status state" in {
        val id = repository.pushNew(item1, timeSource.now).futureValue.id
        repository.markAs(id, status).futureValue should be(true)
        repository.cancel(id).futureValue should be(StatusUpdateResult.NotUpdated(currentState = status))
        repository.findById(id).futureValue.get should have('status (status))
      }
    }

    "not work if it is missing" in {
      repository.cancel(new ObjectId()).futureValue should be(StatusUpdateResult.NotFound)
    }
  }
}
