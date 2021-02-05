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
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class WorkItemRepositorySpec extends WordSpec
  with Matchers
  with WithWorkItemRepository
  with LoneElement {

  def createWorkItemsWith(statuses: Seq[ProcessingStatus]) = {
    Future.traverse(statuses) { status =>
      for {
        item <- repo.pushNew(item1, DateTimeUtils.now)
        _ <- repo.markAs(item.id, status)
      } yield ()
    }.futureValue
  }

  "The work item repo as metrics source" should {
    "return the counts for the all processing statuses as map of metrics" in {
      createWorkItemsWith(ProcessingStatus.processingStatuses.toSeq)

      repo.metrics.futureValue should contain only (ProcessingStatus.processingStatuses.toSeq.
        map(status => (s"$collectionName.${status.name}", 1)): _*)
    }

    "return different map of metric counts for different processing statuses" in {
      def metricKey(status: ProcessingStatus) = s"$collectionName.${status.name}"

      createWorkItemsWith(Seq(ToDo, ToDo, InProgress))

      repo.metrics.futureValue should contain allOf(
        metricKey(ToDo) -> 2,
        metricKey(InProgress) -> 1,
        metricKey(Succeeded) -> 0
      )
    }
  }

  "The work item repo" should {
    "be able to save and reload a item" in {
      val returnedItem = repo.pushNew(item1, timeSource.now).futureValue
      val savedItem = repo.findAll().futureValue.loneElement

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

      val returnedItems = repo.pushNew(items, timeSource.now).futureValue
      val savedItems = repo.findAll().futureValue

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
      repo.pushNew(item1, timeSource.now).futureValue
      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "mark a item as done" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, Succeeded).futureValue should be(true)
      repo.findAll().futureValue.loneElement should have(
        'status (Succeeded),
        'failureCount (0),
        'item (item1))
    }

    "return false trying to mark a non-existent item as done" in {
      repo.markAs(new ObjectId(), Succeeded).futureValue should be(false)
      repo.findAll().futureValue should be(empty)
    }

    "never pull a permanently failed notification" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, PermanentlyFailed).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(60), availableBefore = timeSource.now.plusDays(60)).futureValue should be(None)
    }

    "pull nothing when there are no notifications which were received before the requested time" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.minusDays(60)).futureValue should be(None)
    }

    "pull timed out Failed items" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, Failed).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now.plusSeconds(1), availableBefore = timeSource.now.plusSeconds(1)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (1)
      )
    }

    "pull timed out In progress items" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      timeSource.advance(repo.inProgressRetryAfter.plus(1))
      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "pull nothing if no items exist" in {
      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue should be(None)
    }

    "not pull in progress items" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now).futureValue should be(None)
    }

    "not pull items failed after the failedBefore date" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, Failed).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now.minusSeconds(1), availableBefore = timeSource.now).futureValue should be(None)
    }

    "complete a item as Succeded if it is in progress" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      val id = repo.findAll().futureValue.head.id
      repo.complete(id, Succeeded).futureValue should be(true)
      repo.findById(id).futureValue.get should have(
        'status (Succeeded),
        'failureCount (0)
      )
    }

    "increment the failure count when completing a item as Failed" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, InProgress).futureValue should be(true)
      val id = repo.findAll().futureValue.head.id
      repo.complete(id, Failed).futureValue should be(true)
      repo.findById(id).futureValue.get should have(
        'status (Failed),
        'failureCount (1)
      )
    }

    "not complete a item if it is not in progress" in {
      repo.pushNew(item1, timeSource.now).futureValue
      val id = repo.findAll().futureValue.head.id
      repo.complete(id, Failed).futureValue should be(false)
      repo.findById(id).futureValue.get should have(
        'status (ToDo),
        'failureCount (0)
      )
    }

    "not complete a item if it cannot be found" in {
      repo.complete(new ObjectId(), Succeeded).futureValue should be(false)
    }

    "be able to save a single item in a custom initial status" in {
      def defer(item: ExampleItem): ProcessingStatus = Deferred
      val returnedItem = repo.pushNew(item1, timeSource.now, defer _).futureValue
      val savedItem = repo.findAll().futureValue.loneElement

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
      val returnedItems = repo.pushNew(Seq(item1, item2), timeSource.now, maybeDefer _).futureValue
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

      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue.get.item should be(item2)
      repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusSeconds(10)).futureValue should be(None)
    }

    "create an item with a specified time for future processing" in {
      repo.pushNew(item = item1, receivedAt = timeSource.now, availableAt = timeSource.now.plusDays(10)).futureValue
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(10)).futureValue should be(None)
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(11)).futureValue.get.item should be(item1)
    }

    "mark an item ToDo with a specified time for future processing" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, ToDo, availableAt = Some(timeSource.now.plusDays(10))).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(1)).futureValue should be(None)
    }

    "pull an item deferred for future processing as ToDo" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, ToDo, availableAt = Some(timeSource.now.plusDays(1))).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(10)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "pull an item deferred for future processing as Failed" in {
      repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(repo.findAll().futureValue.loneElement.id, Failed, availableAt = Some(timeSource.now.plusDays(2))).futureValue should be(true)
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(1)).futureValue should be(None)
      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(3)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (1)
      )
    }

    "pull an item marked as Failed without an 'availableAt' field" in {

      val insertRecord: WorkItem[ExampleItem] = repo.pushNew(item1, timeSource.now).futureValue

      repo.markAs(insertRecord.id, Failed, availableAt = Some(timeSource.now.plusDays(2))).futureValue should be(true)

      import reactivemongo.play.json.BSONFormats._

      repo.findAndUpdate(
        query = Json.obj("_id" -> insertRecord.id),
        update = Json.obj("$unset" -> Json.obj("availableAt" -> "")),
        fetchNewObject = true
      ).futureValue

      repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(3)).futureValue.get should have(
        'item (item1),
        'status (InProgress),
        'failureCount (1)
      )
    }

    "read a workitem in legacy format" in {
      implicit val format = repo.domainFormatImplicit

      val json = Json.parse(
        """{
          |  "receivedAt":{"$date":1426090745091},
          |  "updatedAt":{"$date":1426090745091},
          |  "status":"todo",
          |  "failureCount":0,
          |  "item":{"id":"id1"},
          |  "_id":{"$oid":"55006afb0100000100463c03"}
          |}""".stripMargin)

      json.as[WorkItem[ExampleItem]]
    }

    "verify number of indexes created" in {
      repo.collection.indexesManager.dropAll().futureValue
      repo.ensureIndexes.futureValue
      repo.collection.indexesManager.list().futureValue.size should be(3 + 1) //_id index is created by default
    }

    "count the number of items in a specific state" in {
      repo.pushNew(allItems, timeSource.now).futureValue

      repo.count(ToDo).futureValue shouldBe 6
      repo.count(InProgress).futureValue shouldBe 0
      repo.count(Succeeded).futureValue shouldBe 0
      repo.count(Deferred).futureValue shouldBe 0
      repo.count(Failed).futureValue shouldBe 0
      repo.count(PermanentlyFailed).futureValue shouldBe 0
      repo.count(Ignored).futureValue shouldBe 0
      repo.count(Duplicate).futureValue shouldBe 0
    }

    "pull a 'ToDo state item' when available" in {
      val inProgressRecord: WorkItem[ExampleItem] = repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(inProgressRecord.id, InProgress).futureValue should be(true)

      val failedRecord: WorkItem[ExampleItem] = repo.pushNew(item2, timeSource.now.plusDays(1)).futureValue
      repo.markAs(failedRecord.id, Failed).futureValue should be(true)

      val todoRecord: WorkItem[ExampleItem] = repo.pushNew(item3, timeSource.now.plusDays(1)).futureValue
      repo.markAs(todoRecord.id, ToDo).futureValue should be(true)

      timeSource.advance(repo.inProgressRetryAfter.plus(1))
      val result = repo.pullOutstanding(failedBefore = timeSource.now.plusDays(1), availableBefore = timeSource.now.plusDays(10)).futureValue.get
      result should have(
        'item (item3),
        'status (InProgress),
        'failureCount (0)
      )
    }

    "pull a 'Failed state item not updated since failedBefore' when available and there is not any ToDo item" in {
      val inProgressRecord: WorkItem[ExampleItem] = repo.pushNew(item1, timeSource.now).futureValue
      repo.markAs(inProgressRecord.id, InProgress).futureValue should be(true)

      val failedRecord: WorkItem[ExampleItem] = repo.pushNew(item2, timeSource.now.plusDays(2)).futureValue
      repo.markAs(failedRecord.id, Failed).futureValue should be(true)

      val anotherInProgressRecord: WorkItem[ExampleItem] = repo.pushNew(item3, timeSource.now).futureValue
      repo.markAs(anotherInProgressRecord.id, InProgress).futureValue should be(true)

      timeSource.advance(repo.inProgressRetryAfter.plus(1))
      val result = repo.pullOutstanding(failedBefore = timeSource.now, availableBefore = timeSource.now.plusDays(3)).futureValue.get
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
        val id = repo.pushNew(item1, timeSource.now).futureValue.id
        repo.markAs(id, status).futureValue should be(true)
        repo.cancel(id).futureValue should be(StatusUpdateResult.Updated(previousStatus = status, newStatus = Cancelled))
        repo.findById(id).futureValue.get should have('status (Cancelled))
      }
    }

    for (status <- ProcessingStatus.processingStatuses -- allowedStatuses) {
      s"not work if it is in the $status state" in {
        val id = repo.pushNew(item1, timeSource.now).futureValue.id
        repo.markAs(id, status).futureValue should be(true)
        repo.cancel(id).futureValue should be(StatusUpdateResult.NotUpdated(currentState = status))
        repo.findById(id).futureValue.get should have('status (status))
      }
    }

    "not work if it is missing" in {
      repo.cancel(new ObjectId()).futureValue should be(StatusUpdateResult.NotFound)
    }
  }
}
