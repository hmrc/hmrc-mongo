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

package uk.gov.hmrc.mongo.lock

import java.time.Instant

import com.mongodb.client.model.Filters.{eq => mongoEq}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}

class MongoLockServiceSpec
  extends AnyWordSpecLike
     with Matchers
     with DefaultPlayMongoRepositorySupport[Lock] {

  "attemptLockWithRelease" should {
    "obtain lock, run the block supplied and release the lock" in {

      val optionalLock = mongoLockService
        .attemptLockWithRelease {
          find(mongoEq(Lock.id, lockId)).map(_.head)
        }
        .futureValue

      optionalLock.map { lock =>
        lock.id         shouldBe lockId
        lock.expiryTime shouldBe lock.timeCreated.plusSeconds(1)
      }
      count().futureValue shouldBe 0
    }

    "obtain lock, run the block supplied and release the lock when the block returns a failed future" in {
      a[RuntimeException] should be thrownBy {
        mongoLockService.attemptLockWithRelease(Future.failed(new RuntimeException)).futureValue
      }
      count().futureValue shouldBe 0
    }

    "obtain lock, run the block supplied and release the lock when the block throws an exception" in {
      a[RuntimeException] should be thrownBy {
        mongoLockService.attemptLockWithRelease(throw new RuntimeException).futureValue
      }
      count().futureValue shouldBe 0
    }

    "not run the block supplied if the lock is owned by someone else and return None" in {
      val existingLock = Lock(lockId, "owner2", now, now.plusSeconds(100))
      insert(existingLock).futureValue

      mongoLockService
        .attemptLockWithRelease(fail("Should not execute!"))
        .futureValue shouldBe None

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

    "not run the block supplied if the lock is already owned by the caller and return None" in {
      val existingLock = Lock(lockId, owner, now, now.plusSeconds(100))
      insert(existingLock).futureValue

      mongoLockService
        .attemptLockWithRelease(fail("Should not execute!"))
        .futureValue shouldBe None

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }
  }

  "attemptLockWithRefreshExpiry" should {

    "execute the body if no previous lock is set" in {
      var counter = 0
      mongoLockService.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue
      counter shouldBe 1
    }

    "execute the body if the lock for same serverId exists" in {
      var counter = 0
      mongoLockService.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      mongoLockService.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }

    "not execute the body and exit if the lock for another serverId exists" in {
      var counter = 0
      repository
        .toService(lockId, ttl)
        .attemptLockWithRefreshExpiry {
          Future.successful(counter += 1)
        }
        .futureValue

      mongoLockService.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 1
    }

    "execute the body if run after the ttl time has expired" in {
      var counter = 0
      mongoLockService.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      Thread.sleep(1000 + 1)

      mongoLockService.attemptLockWithRefreshExpiry {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }
  }

  private val lockId        = "lockId"
  private val owner         = "owner"
  private val ttl: Duration = 1000.millis
  private val now           = Instant.now()

  override protected val repository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport)
  private val mongoLockService    = repository.toService(lockId, ttl)
}
