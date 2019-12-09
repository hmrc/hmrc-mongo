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

package uk.gov.hmrc.mongo.cache

import java.time.Instant

import org.mongodb.scala.model.IndexModel
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json._
import uk.gov.hmrc.mongo.TimestampSupport
import play.mvc.Http.RequestBuilder
import uk.gov.hmrc.mongo.cache.collection.{CacheItem, PlayMongoCacheCollection}
import uk.gov.hmrc.mongo.play.json.Codecs._
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport

import scala.concurrent.ExecutionContext.Implicits.global

class SessionCacheRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with DefaultMongoCollectionSupport {

  "get" should {
    "successfully return value of desired type if cache item exists" in {
      insert(JsonOps[CacheItem](cacheItem).toDocument()).futureValue
      cacheRepository.get()(req).futureValue shouldBe Some(person)
    }

    "successfully return None if cache item does not exist" in {
      cacheRepository.get().futureValue shouldBe None
    }
  }

  "put" should {
    "successfully create a cache entry if one does not already exist" in {
      cacheRepository.put(person).futureValue        shouldBe ()
      count().futureValue                            shouldBe 1
      findAll().futureValue.head.fromBson[CacheItem] shouldBe CacheItem(cacheId, JsObject(Seq(dataKey -> Json.toJson(person))), now, now)
    }

    "successfully update a cache entry if one does not already exist" in {
      val creationTimestamp = Instant.now()

      insert(CacheItem(cacheId, JsObject(Seq(dataKey -> Json.toJson(person))), creationTimestamp, creationTimestamp).toDocument()).futureValue

      cacheRepository.put(person).futureValue        shouldBe ()
      count().futureValue                            shouldBe 1
      findAll().futureValue.head.fromBson[CacheItem] shouldBe CacheItem(cacheId, JsObject(Seq(dataKey -> Json.toJson(person))), creationTimestamp, now)
    }
  }

  "delete" should {
    "successfully remove cache entry that exists" in {
      insert(cacheItem.toDocument()).futureValue
      count().futureValue shouldBe 1

      cacheRepository.delete().futureValue
      count().futureValue shouldBe 0
    }

    "not remove cacheItem if no cache entry is found" in {
      insert(cacheItem.copy(id = "another-id").toDocument()).futureValue
      count().futureValue shouldBe 1

      cacheRepository.delete()
      count().futureValue shouldBe 1
    }
  }

  implicit lazy val format2: Format[Person] = Person.format
  implicit lazy val format: Format[CacheItem] = PlayMongoCacheCollection.format

  implicit val req = new RequestBuilder().session("sessionId", "session").build.asScala

  private val now       = Instant.now()
  private val cacheId   = "session"
  private val dataKey   = "dataKey"
  private val person    = Person("Sarah", 30, "Female")
  private val cacheItem = CacheItem(cacheId, JsObject(Seq(dataKey -> Json.toJson(person))), now, now)

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): Instant = now
  }

  private lazy val cacheRepository = new SessionCacheRepository[Person](
    mongoComponent   = mongoComponent,
    timestampSupport = timestampSupport,
    format           = Person.format)

  override protected lazy val collectionName: String   = cacheRepository.collectionName
  override protected lazy val indexes: Seq[IndexModel] = cacheRepository.indexes
}
