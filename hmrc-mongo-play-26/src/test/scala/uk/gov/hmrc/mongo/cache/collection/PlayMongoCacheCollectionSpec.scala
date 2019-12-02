/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.cache.collection
import java.time.{LocalDateTime, ZoneOffset}

import org.mongodb.scala.model.IndexModel
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json._
import uk.gov.hmrc.mongo.cache.Person
import uk.gov.hmrc.mongo.play.json.Codecs._
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport
import uk.gov.hmrc.mongo.{CurrentTimestampSupport, TimestampSupport}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class PlayMongoCacheCollectionSpec
    extends AnyWordSpecLike
    with Matchers
    with DefaultMongoCollectionSupport
    with ScalaFutures
    with Eventually {

  "upsert" should {

    "successfully create a cacheItem if one does not already exist" in {
      cacheRepository.upsert(cacheId, person).futureValue shouldBe ()
      count().futureValue                                 shouldBe 1
      findAll()
        .map(_.fromBson[CacheItem[Person]])
        .futureValue
        .head shouldBe CacheItem(cacheId, person, now, now)
    }

    "successfully update a cacheItem if one does not already exist" in {
      val creationTimestamp = LocalDateTime.now(ZoneOffset.UTC)

      insert(CacheItem(cacheId, person, creationTimestamp, creationTimestamp).toDocument()).futureValue

      cacheRepository.upsert(cacheId, person).futureValue shouldBe ()
      count().futureValue                                 shouldBe 1
      findAll().map(_.fromBson[CacheItem[Person]]).futureValue.head shouldBe CacheItem(
        cacheId,
        person,
        creationTimestamp,
        now)
    }

    "successfully keep items in the cache that are touched" in {

      // we want to use real times here
      val cacheRepository = new PlayMongoCacheCollection[Person](
        mongoComponent   = mongoComponent,
        collectionName   = "play-mongo-cache",
        domainFormat     = Person.format,
        ttl              = 20.seconds,
        timestampSupport = new CurrentTimestampSupport()
      )

      insert(cacheItem.toDocument()).futureValue
      cacheRepository.find(cacheId).futureValue.map(_.data) shouldBe Some(person)
      Thread.sleep(500)
      cacheRepository.upsert(cacheId, person)
      Thread.sleep(600)
      cacheRepository.find(cacheId).futureValue.map(_.data) shouldBe Some(person)
    }

  }

  "find" should {
    "successfully return CacheItem if cacheItem exists within ttl" in {
      insert(cacheItem.toDocument()).futureValue
      cacheRepository.find(cacheId).futureValue shouldBe Some(cacheItem)
    }

    "successfully return None if cacheItem does not exist" in {
      cacheRepository.find(cacheId).futureValue shouldBe None
    }

    "successfully return None if outside ttl" in {
      insert(cacheItem.copy(id = "something-else").toDocument()).futureValue
      //Items can live beyond the TTL https://docs.mongodb.com/manual/core/index-ttl/#timing-of-the-delete-operation
      eventually(timeout(Span(60, Seconds)), interval(Span(500, Millis))) {
        cacheRepository.find("something-else").futureValue shouldBe None
      }
    }
  }

  "remove" should {
    "successfully delete cacheItem that exists" in {
      insert(cacheItem.toDocument()).futureValue
      count().futureValue shouldBe 1

      cacheRepository.remove(cacheId)
      count().futureValue shouldBe 0

    }

    "not delete cacheItem if no cacheItem is found" in {
      insert(cacheItem.copy(id = "another-id").toDocument()).futureValue
      count().futureValue shouldBe 1

      cacheRepository.remove(cacheId)
      count().futureValue shouldBe 1
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

  implicit val format: Format[CacheItem[Person]] = CacheItem.format(Person.format)

  private val now       = LocalDateTime.now(ZoneOffset.UTC)
  private val cacheId   = "cacheId"
  private val person    = Person("Sarah", 30, "Female")
  private val cacheItem = CacheItem(cacheId, person, now, now)
  private val ttl       = 1000.millis

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): LocalDateTime = now
  }

  private val cacheRepository = new PlayMongoCacheCollection[Person](
    mongoComponent   = mongoComponent,
    collectionName   = "play-mongo-cache",
    domainFormat     = Person.format,
    ttl              = ttl,
    timestampSupport = timestampSupport
  )

  override protected val collectionName: String   = cacheRepository.collectionName
  override protected val indexes: Seq[IndexModel] = cacheRepository.indexes

  private def createCacheAndReturnIndexExpiry(ttl: Duration): Option[Long] =
    new PlayMongoCacheCollection[Person](
      mongoComponent   = mongoComponent,
      collectionName   = "play-mongo-cache-index-test",
      domainFormat     = Person.format,
      ttl              = ttl,
      timestampSupport = timestampSupport
    ).collection
      .listIndexes()
      .toFuture()
      .map(_.fromBson[JsValue])
      .futureValue
      .find(index => (index \ "name").as[String] == "lastUpdatedIndex")
      .map(index => (index \ "expireAfterSeconds").as[Long])
}
