/*
 * Copyright 2022 HM Revenue & Customs
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
import java.time.temporal.ChronoUnit

import com.mongodb.MongoServerException
import com.mongodb.client.model.Filters.{eq => mongoEq}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.MongoUtils.DuplicateKey
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class MongoLockRepositorySpec
  extends AnyWordSpecLike
     with Matchers
     with DefaultPlayMongoRepositorySupport[Lock] {

  "takeLock" should {
    "successfully create a lock if one does not already exist" in {
      repository.takeLock(lockId, owner, ttl).futureValue shouldBe true

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe Lock(lockId, owner, now, now.plus(1, ChronoUnit.SECONDS))
    }

    "successfully create a lock if a different one already exists" in {
      insert(Lock("different-lock", owner, now, now.plus(1, ChronoUnit.SECONDS))).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe true

      count().futureValue shouldBe 2

      find(mongoEq(Lock.id, lockId)).futureValue.head shouldBe Lock(lockId, owner, now, now.plus(1, ChronoUnit.SECONDS))
    }

    "do not change a non-expired lock with a different owner" in {
      val existingLock = Lock(lockId, "different-owner", now, now.plus(100, ChronoUnit.SECONDS))

      insert(existingLock).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe false

      count().futureValue shouldBe 1

      find(mongoEq(Lock.id, lockId)).futureValue.head shouldBe existingLock
    }

    "do not change a non-expired lock with the same owner" in {
      val existingLock = Lock(lockId, owner, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe false

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

    "change an expired lock" in {
      val existingLock = Lock(lockId, owner, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe true

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe Lock(lockId, owner, now, now.plus(1, ChronoUnit.SECONDS))
    }
  }

  "refreshExpiry" should {
    "not renew a lock if one does not already exist" in {
      repository.refreshExpiry(lockId, owner, ttl).futureValue shouldBe false
      count().futureValue                                      shouldBe 0
    }

    "not renew a different lock if one exists" in {
      val existingLock = Lock("different-lock", owner, now, now.plus(1, ChronoUnit.SECONDS))

      insert(existingLock).futureValue

      repository.refreshExpiry(lockId, owner, ttl).futureValue shouldBe false
      count().futureValue                                      shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

    "not change a non-expired lock with a different owner" in {
      val existingLock = Lock(lockId, "different-owner", now, now.plus(100, ChronoUnit.SECONDS))

      insert(existingLock).futureValue

      repository.refreshExpiry(lockId, owner, ttl).futureValue shouldBe false

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

    "change a non-expired lock with the same owner" in {
      val existingLock = Lock(lockId, owner, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue
      repository.refreshExpiry(lockId, owner, ttl).futureValue shouldBe true
      count().futureValue                                      shouldBe 1

      findAll().futureValue.head shouldBe Lock(lockId, owner, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.SECONDS))
    }
  }

  "releaseLock" should {
    "remove an owned and expired lock" in {
      val existingLock = Lock(lockId, owner, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue

      count().futureValue shouldBe 1

      repository.releaseLock(lockId, owner).futureValue

      count().futureValue shouldBe 0
    }

    "remove an owned and unexpired lock" in {
      val lock = Lock(lockId, owner, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))

      insert(lock).futureValue

      count().futureValue shouldBe 1

      repository.releaseLock(lockId, owner).futureValue

      count().futureValue shouldBe 0
    }

    "do nothing if the lock doesn't exist" in {
      repository.releaseLock(lockId, owner).futureValue

      count().futureValue shouldBe 0
    }

    "leave an expired lock from a different owner" in {
      val existingLock = Lock(lockId, "someoneElse", now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue

      repository.releaseLock(lockId, owner).futureValue

      count().futureValue        shouldBe 1
      findAll().futureValue.head shouldBe existingLock
    }

    "leave an unexpired lock from a different owner" in {
      val existingLock = Lock(lockId, "different-owner", now.minus(2, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))
      insert(existingLock).futureValue

      repository.releaseLock(lockId, owner).futureValue

      count().futureValue        shouldBe 1
      findAll().futureValue.head shouldBe existingLock

    }

    "not affect other locks" in {
      val existingLock = Lock("different-lock", owner, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))
      insert(existingLock).futureValue

      repository.releaseLock(lockId, owner).futureValue

      count().futureValue        shouldBe 1
      findAll().futureValue.head shouldBe existingLock
    }
  }

  "isLocked" should {
    "return false if no lock obtained" in {
      repository.isLocked(lockId, owner).futureValue shouldBe false
    }

    "return true if lock held" in {
      insert(Lock(lockId, owner, now, now.plus(100, ChronoUnit.SECONDS))).futureValue
      repository.isLocked(lockId, owner).futureValue shouldBe true
    }

    "return false if the lock is held but expired" in {
      insert(Lock(lockId, owner, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))).futureValue
      repository.isLocked(lockId, owner).futureValue shouldBe false
    }
  }

  "Mongo should" should {
    "throw an exception if a lock object is inserted that is not unique" in {
      val lock1 = Lock("lockName", "owner1", now.plus(1, ChronoUnit.DAYS), now.plus(2, ChronoUnit.DAYS))
      val lock2 = Lock("lockName", "owner2", now.plus(3, ChronoUnit.DAYS), now.plus(4, ChronoUnit.DAYS))
      insert(lock1).futureValue

      whenReady(insert(lock2).failed) { ex =>
        ex shouldBe a[MongoServerException]
        DuplicateKey.unapply(ex.asInstanceOf[MongoServerException]) shouldBe defined
      }

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe lock1
    }
  }

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): Instant = now
  }

  override protected lazy val repository = new MongoLockRepository(mongoComponent, timestampSupport)

  private val lockId = "lockId"
  private val owner  = "owner"
  private val ttl    = 1000.millis
  private val now    = Instant.now()
}
