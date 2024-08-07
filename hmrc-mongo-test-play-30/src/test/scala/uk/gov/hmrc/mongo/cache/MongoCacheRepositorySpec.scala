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

package uk.gov.hmrc.mongo.cache

import org.mongodb.scala.ObservableFuture
import org.scalatest.OptionValues
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json._
import uk.gov.hmrc.mongo.{CurrentTimestampSupport, TimestampSupport}
import uk.gov.hmrc.mongo.play.json.Codecs._
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.time.{Clock, Instant, ZoneId}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class MongoCacheRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[CacheItem]
     with OptionValues
     with ScalaFutures
     with Eventually {

  "put" should {
    "successfully create a cacheItem if one does not already exist" in {
      repository.put(cacheId)(dataKey, person).futureValue shouldBe cacheItem
      count().futureValue                                  shouldBe 1
      findAll().futureValue.head                           shouldBe cacheItem
    }

    "successfully update a cacheItem if one does not already exist" in {
      val creationTimestamp = Instant.now(clock)

      insert(cacheItem.copy(createdAt = creationTimestamp, modifiedAt = creationTimestamp)).futureValue

      repository.put(cacheId)(dataKey, person).futureValue shouldBe cacheItem.copy(createdAt = creationTimestamp, modifiedAt = now)
      count().futureValue                                  shouldBe 1
      findAll().futureValue.head                           shouldBe cacheItem.copy(createdAt = creationTimestamp, modifiedAt = now)
    }

    "successfully keep items in the cache that are touched" in {
      // we want to use real times here
      val cacheRepository = new MongoCacheRepository[String](
        mongoComponent   = mongoComponent,
        collectionName   = "play-mongo-cache",
        ttl              = 20.seconds,
        timestampSupport = new CurrentTimestampSupport(),
        cacheIdType      = CacheIdType.SimpleCacheId
      )

      insert(cacheItem).futureValue
      cacheRepository.get[Person](cacheId)(dataKey).futureValue shouldBe Some(person)
      Thread.sleep(500)
      cacheRepository.put(cacheId)(dataKey, person)
      Thread.sleep(600)
      cacheRepository.get[Person](cacheId)(dataKey).futureValue shouldBe Some(person)
    }
  }

  "get" should {
    "successfully return CacheItem if cacheItem exists within ttl" in {
      insert(cacheItem).futureValue
      repository.get[Person](cacheId)(dataKey).futureValue shouldBe Some(person)
    }

    "successfully return None if cacheItem does not exist" in {
      repository.get[Person](cacheId)(dataKey).futureValue shouldBe None
    }

    "successfully return None if cacheItem exists but does not contain the data key" in {
      repository.put(cacheId)(DataKey[Person]("something-else"), person).futureValue
      repository.get[Person](cacheId)(dataKey).futureValue shouldBe None
    }

    "successfully return None if outside ttl" in {
      val cacheId2 = "something-else"
      insert(cacheItem.copy(id = cacheId2)).futureValue
      //Items can live beyond the TTL https://docs.mongodb.com/manual/core/index-ttl/#timing-of-the-delete-operation
      eventually(timeout(Span(60, Seconds)), interval(Span(500, Millis))) {
        repository.get[Person](cacheId2)(dataKey).futureValue shouldBe None
      }
    }

    "fail the future if the item can be found but cannot be deserialised" in {
      val invalidCacheItem = CacheItem(cacheId, JsObject(Seq(dataKey.unwrap -> Json.obj("foo" -> "bar"))), now, now)
      insert(invalidCacheItem).futureValue
      val result = repository.get[Person](cacheId)(dataKey)
      result.failed.futureValue shouldBe an[JsResultException]
    }
  }

  "delete" should {
    "successfully delete cacheItem that exists" in {
      insert(cacheItem).futureValue
      count().futureValue shouldBe 1

      repository.deleteEntity(cacheId).futureValue
      count().futureValue shouldBe 0
    }

    "not delete cacheItem if no cacheItem is found" in {
      insert(cacheItem.copy(id = "another-id")).futureValue
      count().futureValue shouldBe 1

      repository.deleteEntity(cacheId).futureValue
      count().futureValue shouldBe 1
    }
  }

  "cache" should {
    "support . in key name" in {
      val dataKeyWithDot = DataKey[Person]("step.1")
      repository.put(cacheId)(dataKeyWithDot, person)

      repository.get[Person](cacheId)(dataKeyWithDot).futureValue shouldBe Some(person)
    }

    "update same object" in {
      // demonstrates updating the cache in steps and the retrieving the whole result
      // step 1
      val dataKey1 = DataKey[Person]("step1")
      repository.put(cacheId)(dataKey1, person)

      // step 2
      case class Foo(value: String)
      import play.api.libs.functional.syntax._
      implicit val ff: Format[Foo] = implicitly[Format[String]].inmap(Foo.apply, _.value)
      val foo  = Foo("bar")
      val dataKey2 = DataKey[Foo]("step2")
      repository.put(cacheId)(dataKey2, foo)

      // read by steps individually
      repository.get[Person](cacheId)(dataKey1).futureValue shouldBe Some(person)
      repository.get[Foo](cacheId)(dataKey2).futureValue shouldBe Some(foo)

      // read out all steps together
      case class AllSteps(person: Person, foo: Foo)
      implicit val asf: Format[AllSteps] =
        ( (__ \ "step1").format[Person]
        ~ (__ \ "step2").format[Foo]
        )(AllSteps.apply, as => (as.person, as.foo))
      val cacheItem = repository.findById(cacheId).futureValue.value
      cacheItem.data.as[AllSteps] shouldBe AllSteps(person, foo)
    }
  }

  "ensureIndex" should {
    "rebuild indexes when they are modified" in {
      createCacheAndReturnIndexExpiry(1000.millis) shouldBe Some(1L)
      createCacheAndReturnIndexExpiry(5000.millis) shouldBe Some(5L)
    }

    "not rebuild indexes when they are not modified" in {
      createCacheAndReturnIndexExpiry(1000.millis) shouldBe Some(1L)
      createCacheAndReturnIndexExpiry(1000.millis) shouldBe Some(1L)
    }
  }

  implicit val format: Format[Person]     = Person.format
  implicit val format2: Format[CacheItem] = MongoCacheRepository.format

  private val clock     = Clock.tickMillis(ZoneId.systemDefault())
  private val now       = Instant.now(clock)
  private val cacheId   = "cacheId"
  private val dataKey   = DataKey[Person]("dataKey")
  private val person    = Person("Sarah", 30, "Female")
  private val cacheItem = CacheItem(cacheId, JsObject(Seq(dataKey.unwrap -> Json.toJson(person))), now, now)
  private val ttl       = 1000.millis

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): Instant = now
  }

  protected override val repository: MongoCacheRepository[String] =
    new MongoCacheRepository[String](
      mongoComponent   = mongoComponent,
      collectionName   = "play-mongo-cache",
      ttl              = ttl,
      timestampSupport = timestampSupport,
      cacheIdType      = CacheIdType.SimpleCacheId
    )

  private def createCacheAndReturnIndexExpiry(ttl: Duration): Option[Long] = {
    val repository = new MongoCacheRepository[String](
      mongoComponent   = mongoComponent,
      collectionName   = "mongo-cache-repo-test",
      ttl              = ttl,
      timestampSupport = timestampSupport,
      cacheIdType      = CacheIdType.SimpleCacheId
    )
    repository.initialised.futureValue
    repository
      .collection
      .listIndexes()
      .toFuture()
      .map(_.fromBson[JsValue])
      .futureValue
      .find(index => (index \ "name").as[String] == "lastUpdatedIndex")
      .map(index => (index \ "expireAfterSeconds").as[Long])
  }
}
