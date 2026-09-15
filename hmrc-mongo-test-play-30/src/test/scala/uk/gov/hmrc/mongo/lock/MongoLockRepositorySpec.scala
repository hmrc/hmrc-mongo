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

package uk.gov.hmrc.mongo.lock

import com.mongodb.client.model.Filters
import com.google.inject.{AbstractModule, Guice}
import org.mongodb.scala.{MongoServerException, ObservableFuture}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.MongoUtils.DuplicateKey
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, ZoneId}
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class MongoLockRepositorySpec
  extends AnyWordSpec
     with Matchers
     with OptionValues
     with DefaultPlayMongoRepositorySupport[Lock] {

  "construction" should {
    "default to no indexes with the existing constructor" in {
      repository.indexes shouldBe Seq.empty
      repository.requiresTtlIndex shouldBe false
      repository.collection.listIndexes().toFuture().futureValue.map(_("name").asString.getValue) shouldBe Seq("_id_")
    }

    "support subclasses using the existing named constructor arguments" in {
      val lockRepository = new MongoLockRepository(
        mongoComponent   = mongoComponent,
        timestampSupport = timestampSupport
      ) {}

      lockRepository.indexes shouldBe Seq.empty
    }

    "inject the default singleton through both repository types without index bindings" in {
      val injector = Guice.createInjector(new AbstractModule {
        override def configure(): Unit = {
          bind(classOf[MongoComponent]).toInstance(mongoComponent)
          bind(classOf[TimestampSupport]).toInstance(timestampSupport)
          bind(classOf[ExecutionContext]).toInstance(global)
        }
      })

      val lockRepository = injector.getInstance(classOf[MongoLockRepository])
      lockRepository.indexes shouldBe Seq.empty
      injector.getInstance(classOf[LockRepository]) should be theSameInstanceAs lockRepository
    }

    "create supplied TTL and non-TTL indexes and retain them for default consumers" in {
      val suppliedIndexes: Seq[IndexModel] = Seq(
        IndexModel(Indexes.ascending(Lock.expiryTime), IndexOptions().name("expiryTimeTTL").expireAfter(0, TimeUnit.SECONDS)),
        IndexModel(Indexes.ascending(Lock.owner), IndexOptions().name("ownerIdx"))
      )
      val lockRepository = new MongoLockRepository(
        mongoComponent   = mongoComponent,
        timestampSupport = timestampSupport,
        indexes          = suppliedIndexes
      )

      lockRepository.indexes shouldBe suppliedIndexes
      lockRepository.initialised.futureValue
      val createdIndexes = lockRepository.collection.listIndexes().toFuture().futureValue
      createdIndexes.map(_("name").asString.getValue) should contain theSameElementsAs Seq("_id_", "expiryTimeTTL", "ownerIdx")
      val ttlIndex = createdIndexes.find(_("name").asString.getValue == "expiryTimeTTL").value
      ttlIndex("key").asDocument shouldBe BsonDocument(Lock.expiryTime -> 1)
      ttlIndex("expireAfterSeconds").asNumber.longValue shouldBe 0L
      createdIndexes.find(_("name").asString.getValue == "ownerIdx").value("key").asDocument shouldBe BsonDocument(Lock.owner -> 1)

      val defaultRepository = new MongoLockRepository(mongoComponent, timestampSupport)
      defaultRepository.collection.listIndexes().toFuture().futureValue shouldBe createdIndexes
    }
  }

  "takeLock" should {
    "successfully create a lock if one does not already exist" in {
      repository.takeLock(lockId, owner, ttl).futureValue shouldBe Some(Lock(lockId, owner, now, now.plusMillis(ttl.toMillis)))

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe Lock(lockId, owner, now, now.plus(1, ChronoUnit.SECONDS))
    }

    "successfully create a lock if a different one already exists" in {
      insert(Lock("different-lock", owner, now, now.plus(1, ChronoUnit.SECONDS))).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe Some(Lock(lockId, owner, now, now.plusMillis(ttl.toMillis)))

      count().futureValue shouldBe 2

      find(Filters.eq(Lock.id, lockId)).futureValue.head shouldBe Lock(lockId, owner, now, now.plus(1, ChronoUnit.SECONDS))
    }

    "do not change a non-expired lock with a different owner" in {
      val existingLock = Lock(lockId, "different-owner", now, now.plus(100, ChronoUnit.SECONDS))

      insert(existingLock).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe None

      count().futureValue shouldBe 1

      find(Filters.eq(Lock.id, lockId)).futureValue.head shouldBe existingLock
    }

    "do not change a non-expired lock with the same owner" in {
      val existingLock = Lock(lockId, owner, now.minus(1, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe None

      count().futureValue shouldBe 1

      findAll().futureValue.head shouldBe existingLock
    }

    "change an expired lock" in {
      val existingLock = Lock(lockId, owner, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS))

      insert(existingLock).futureValue

      repository.takeLock(lockId, owner, ttl).futureValue shouldBe Some(Lock(lockId, owner, now, now.plusMillis(ttl.toMillis)))

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

  "abandonLock" should {
    s"set the owner to '$$owner (disowned)' without changing the expiryTime of the lock" in {
      val existingLock = Lock(lockId, owner, now, now.plus(1, ChronoUnit.MINUTES))
      insert(existingLock).futureValue

      repository.disownLock(lockId, owner).futureValue

      count().futureValue        shouldBe 1
      findAll().futureValue.head shouldBe existingLock.copy(owner = s"$owner (disowned)")
    }

    s"set the owner to '$$owner (disowned)' and update the expiryTime of the lock" in {
      val existingLock = Lock(lockId, owner, now, now.plus(10, ChronoUnit.MINUTES))
      insert(existingLock).futureValue

      repository.disownLock(lockId, owner, updatedExpiry = Some(now.plus(1, ChronoUnit.MINUTES))).futureValue

      count().futureValue        shouldBe 1
      findAll().futureValue.head shouldBe existingLock.copy(owner = s"$owner (disowned)", expiryTime = now.plus(1, ChronoUnit.MINUTES))
    }

    "not make any changes to a lock with a different owner" in {
      val existingLock = Lock(lockId, "different-owner", now, now.plus(1, ChronoUnit.MINUTES))
      insert(existingLock).futureValue

      repository.disownLock(lockId, owner).futureValue

      count().futureValue        shouldBe 1
      findAll().futureValue.head shouldBe existingLock
    }

    "do nothing when the lock doesn't exist" in {
      repository.disownLock(lockId, owner).futureValue

      count().futureValue shouldBe 0
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

  override protected val repository: MongoLockRepository =
    new MongoLockRepository(mongoComponent, timestampSupport)

  private lazy val lockId = "lockId"
  private lazy val owner  = "owner"
  private lazy val ttl    = 1000.millis
  private lazy val clock  = Clock.tickMillis(ZoneId.systemDefault())
  private lazy val now    = Instant.now(clock)
}
