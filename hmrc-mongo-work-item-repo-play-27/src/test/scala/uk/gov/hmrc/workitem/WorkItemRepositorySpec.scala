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

import java.time.Instant
import java.time.temporal.ChronoUnit

import org.bson.types.ObjectId
import org.mongodb.scala.model._
import org.scalatest.LoneElement
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class WorkItemRepositorySpec
  extends AnyWordSpec
     with Matchers
     with WithWorkItemRepository
     with LoneElement {

  def createWorkItemsWith(statuses: Seq[ProcessingStatus]): Unit =
    Future.traverse(statuses) { status =>
      for {
        item <- repository.pushNew(item1, Instant.now())
        _    <- repository.markAs(item.id, status)
      } yield ()
    }.futureValue

  "The work item repo as metrics source" should {
    "return the counts for the all processing statuses as map of metrics" in {
      createWorkItemsWith(ProcessingStatus.values.toSeq)

      repository.metrics.futureValue should contain only (ProcessingStatus.values.toSeq.
        map(status => (s"$collectionName.${status.name}", 1)): _*)
    }

    "return different map of metric counts for different processing statuses" in {
      def metricKey(status: ProcessingStatus) = s"$collectionName.${status.name}"

      createWorkItemsWith(Seq(ProcessingStatus.ToDo, ProcessingStatus.ToDo, ProcessingStatus.InProgress))

      repository.metrics.futureValue should contain allOf(
        metricKey(ProcessingStatus.ToDo) -> 2,
        metricKey(ProcessingStatus.InProgress) -> 1,
        metricKey(ProcessingStatus.Succeeded) -> 0
      )
    }
  }

  "The work item repo" should {
    "be able to save and reload a item" in {
      val returnedItem = repository.pushNew(item1, timeSource.now).futureValue
      val savedItem = findAll().futureValue.loneElement

      returnedItem should have(
        'item (item1),
        'status (ProcessingStatus.ToDo),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )

      returnedItem should equal(savedItem)
    }

    "be able to save and reload a bulk set of items" in {
      val items = Seq(item1, item2, item3, item4, item5, item6)

      val returnedItems = repository.pushNewBatch(items, timeSource.now).futureValue
      val savedItems = findAll().futureValue

      every(savedItems) should have(
        'status (ProcessingStatus.ToDo),
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
        'status (ProcessingStatus.InProgress),
        'failureCount (0)
      )
    }

    "mark a item as done" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.Succeeded).futureValue should be(true)
      findAll().futureValue.loneElement should have(
        'status (ProcessingStatus.Succeeded),
        'failureCount (0),
        'item (item1))
    }

    "return false trying to mark a non-existent item as done" in {
      repository.markAs(new ObjectId(), ProcessingStatus.Succeeded).futureValue should be(false)
      findAll().futureValue should be(empty)
    }

    "never pull a permanently failed notification" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.PermanentlyFailed).futureValue should be(true)
      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(60, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(60, ChronoUnit.DAYS)
      ).futureValue should be(None)
    }

    "pull nothing when there are no notifications which were received before the requested time" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.pullOutstanding(
        failedBefore    = timeSource.now,
        availableBefore = timeSource.now.minus(60, ChronoUnit.DAYS)
      ).futureValue should be(None)
    }

    "pull timed out Failed items" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.Failed).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.plusSeconds(1), availableBefore = timeSource.now.plusSeconds(1)).futureValue.get should have(
        'item (item1),
        'status (ProcessingStatus.InProgress),
        'failureCount (1)
      )
    }

    "pull timed out In progress items" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.InProgress).futureValue should be(true)
      timeSource.advance(repository.inProgressRetryAfter.plus(1, ChronoUnit.MILLIS))
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue.get should have(
        'item (item1),
        'status (ProcessingStatus.InProgress),
        'failureCount (0)
      )
    }

    "pull nothing if no items exist" in {
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue should be(None)
    }

    "not pull in progress items" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.InProgress).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue should be(None)
    }

    "not pull items failed after the failedBefore date" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.Failed).futureValue should be(true)
      repository.pullOutstanding(failedBefore = timeSource.now.minusSeconds(1), availableBefore = timeSource.now).futureValue should be(None)
    }

    "complete a item as Succeded if it is in progress" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.InProgress).futureValue should be(true)
      val id = findAll().futureValue.head.id
      repository.complete(id, ProcessingStatus.Succeeded).futureValue should be(true)
      repository.findById(id).futureValue.get should have(
        'status (ProcessingStatus.Succeeded),
        'failureCount (0)
      )
    }

    "increment the failure count when completing a item as Failed" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(findAll().futureValue.loneElement.id, ProcessingStatus.InProgress).futureValue should be(true)
      val id = findAll().futureValue.head.id
      repository.complete(id, ProcessingStatus.Failed).futureValue should be(true)
      repository.findById(id).futureValue.get should have(
        'status (ProcessingStatus.Failed),
        'failureCount (1)
      )
    }

    "not complete a item if it is not in progress" in {
      repository.pushNew(item1, timeSource.now).futureValue
      val id = findAll().futureValue.head.id
      repository.complete(id, ProcessingStatus.Failed).futureValue should be(false)
      repository.findById(id).futureValue.get should have(
        'status (ProcessingStatus.ToDo),
        'failureCount (0)
      )
    }

    "not complete a item if it cannot be found" in {
      repository.complete(new ObjectId(), ProcessingStatus.Succeeded).futureValue should be(false)
    }

    "be able to save a single item in a custom initial status" in {
      def defer(item: ExampleItem): ProcessingStatus = ProcessingStatus.Deferred
      val returnedItem = repository.pushNew(item1, timeSource.now, defer _).futureValue
      val savedItem = findAll().futureValue.loneElement

      returnedItem should have(
        'item (item1),
        'status (ProcessingStatus.Deferred),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )

      returnedItem should equal(savedItem)
    }

    "be able to selectively save an item in a custom initial status" in {
      def maybeDefer(item: ExampleItem): ProcessingStatus = item match {
        case i if i.id == "id1" => ProcessingStatus.Deferred
        case _ => ProcessingStatus.ToDo
      }
      val returnedItems = repository.pushNewBatch(Seq(item1, item2), timeSource.now, maybeDefer _).futureValue
      exactly(1, returnedItems) should have(
        'item (item1),
        'status (ProcessingStatus.Deferred),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )
      exactly(1, returnedItems) should have(
        'item (item2),
        'status (ProcessingStatus.ToDo),
        'failureCount (0),
        'receivedAt (timeSource.now),
        'updatedAt (timeSource.now)
      )

      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue.get.item should be(item2)
      repository.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue should be(None)
    }

    "create an item with a specified time for future processing" in {
      repository.pushNew(
        item        = item1,
        availableAt = timeSource.now.plus(10, ChronoUnit.DAYS)
      ).futureValue

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(10, ChronoUnit.DAYS)
      ).futureValue should be(None)

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(11, ChronoUnit.DAYS)
      ).futureValue.get.item should be(item1)
    }

    "mark an item ToDo with a specified time for future processing" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(
        findAll().futureValue.loneElement.id,
        ProcessingStatus.ToDo,
        availableAt = Some(timeSource.now.plus(10, ChronoUnit.DAYS))
      ).futureValue should be(true)

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(1, ChronoUnit.DAYS)
      ).futureValue should be(None)
    }

    "pull an item deferred for future processing as ToDo" in {
      repository.pushNew(item1, timeSource.now).futureValue

      repository.markAs(
        findAll().futureValue.loneElement.id,
        ProcessingStatus.ToDo,
        availableAt = Some(timeSource.now.plus(1, ChronoUnit.DAYS))
      ).futureValue should be(true)

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(10, ChronoUnit.DAYS)
      ).futureValue.get should have(
        'item (item1),
        'status (ProcessingStatus.InProgress),
        'failureCount (0)
      )
    }

    "pull an item deferred for future processing as Failed" in {
      repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(
        findAll().futureValue.loneElement.id,
        ProcessingStatus.Failed,
        availableAt = Some(timeSource.now.plus(2, ChronoUnit.DAYS))
      ).futureValue should be(true)

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(1, ChronoUnit.DAYS)
      ).futureValue should be(None)

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(3, ChronoUnit.DAYS)
      ).futureValue.get should have(
        'item (item1),
        'status (ProcessingStatus.InProgress),
        'failureCount (1)
      )
    }

    "pull an item marked as Failed without an 'availableAt' field" in {

      val insertRecord: WorkItem[ExampleItem] = repository.pushNew(item1, timeSource.now).futureValue

      repository.markAs(
        insertRecord.id,
        ProcessingStatus.Failed,
        availableAt = Some(timeSource.now.plus(2, ChronoUnit.DAYS))
      ).futureValue should be(true)

      repository.collection.findOneAndUpdate(
        filter = Filters.equal("_id", insertRecord.id),
        update = Updates.unset("availableAt"),
        options = FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
      ).toFuture.futureValue

      repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(3, ChronoUnit.DAYS)
      ).futureValue.get should have(
        'item (item1),
        'status (ProcessingStatus.InProgress),
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
      repository.pushNewBatch(allItems, timeSource.now).futureValue

      repository.count(ProcessingStatus.ToDo             ).futureValue shouldBe 6
      repository.count(ProcessingStatus.InProgress       ).futureValue shouldBe 0
      repository.count(ProcessingStatus.Succeeded        ).futureValue shouldBe 0
      repository.count(ProcessingStatus.Deferred         ).futureValue shouldBe 0
      repository.count(ProcessingStatus.Failed           ).futureValue shouldBe 0
      repository.count(ProcessingStatus.PermanentlyFailed).futureValue shouldBe 0
      repository.count(ProcessingStatus.Ignored          ).futureValue shouldBe 0
      repository.count(ProcessingStatus.Duplicate        ).futureValue shouldBe 0
    }

    "pull a 'ToDo state item' when available" in {
      val inProgressRecord: WorkItem[ExampleItem] = repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(inProgressRecord.id, ProcessingStatus.InProgress).futureValue should be(true)

      val failedRecord: WorkItem[ExampleItem] = repository.pushNew(
        item2,
        timeSource.now.plus(1, ChronoUnit.DAYS)
      ).futureValue
      repository.markAs(failedRecord.id, ProcessingStatus.Failed).futureValue should be(true)

      val todoRecord: WorkItem[ExampleItem] = repository.pushNew(
        item3,
        timeSource.now.plus(1, ChronoUnit.DAYS)
      ).futureValue
      repository.markAs(todoRecord.id, ProcessingStatus.ToDo).futureValue should be(true)

      timeSource.advance(repository.inProgressRetryAfter.plus(1, ChronoUnit.MILLIS))
      val result = repository.pullOutstanding(
        failedBefore    = timeSource.now.plus(1, ChronoUnit.DAYS),
        availableBefore = timeSource.now.plus(10, ChronoUnit.DAYS)
      ).futureValue.get
      result should have(
        'item (item3),
        'status (ProcessingStatus.InProgress),
        'failureCount (0)
      )
    }

    "pull a 'Failed state item not updated since failedBefore' when available and there is not any ToDo item" in {
      val inProgressRecord: WorkItem[ExampleItem] = repository.pushNew(item1, timeSource.now).futureValue
      repository.markAs(inProgressRecord.id, ProcessingStatus.InProgress).futureValue should be(true)

      val failedRecord: WorkItem[ExampleItem] = repository.pushNew(
        item2,
        timeSource.now.plus(2, ChronoUnit.DAYS)
      ).futureValue
      repository.markAs(failedRecord.id, ProcessingStatus.Failed).futureValue should be(true)

      val anotherInProgressRecord: WorkItem[ExampleItem] = repository.pushNew(item3, timeSource.now).futureValue
      repository.markAs(anotherInProgressRecord.id, ProcessingStatus.InProgress).futureValue should be(true)

      timeSource.advance(repository.inProgressRetryAfter.plus(1, ChronoUnit.MILLIS))
      val result = repository.pullOutstanding(
        failedBefore    = timeSource.now,
        availableBefore = timeSource.now.plus(3, ChronoUnit.DAYS)
      ).futureValue.get
      result should have(
        'item (item2),
        'status (ProcessingStatus.InProgress),
        'failureCount (1)
      )
    }
  }

  "Cancelling a notification" should {
    for (status <- ProcessingStatus.cancellable) {
      s"work if it is in the $status state" in {
        val id = repository.pushNew(item1, timeSource.now).futureValue.id
        repository.markAs(id, status).futureValue should be(true)
        repository.cancel(id).futureValue should be(StatusUpdateResult.Updated(previousStatus = status, newStatus = ProcessingStatus.Cancelled))
        repository.findById(id).futureValue.get should have('status (ProcessingStatus.Cancelled))
      }
    }

    for (status <- ProcessingStatus.values -- ProcessingStatus.cancellable) {
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
