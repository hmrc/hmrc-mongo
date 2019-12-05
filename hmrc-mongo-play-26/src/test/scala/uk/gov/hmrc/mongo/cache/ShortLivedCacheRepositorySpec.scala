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
import uk.gov.hmrc.mongo.cache.collection.CacheItem
import uk.gov.hmrc.mongo.play.json.Codecs._
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport

import scala.concurrent.ExecutionContext.Implicits.global

class ShortLivedCacheRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with DefaultMongoCollectionSupport
    with ScalaFutures {

  "fetch" should {

    "successfully return value of desired type if cache item exists" in {
      insert(cacheItem.toDocument()).futureValue
      cacheRepository.fetch(cacheId).futureValue shouldBe Some(person)
    }

    "successfully return None if cache item does not exist" in {
      cacheRepository.fetch(cacheId).futureValue shouldBe None
    }
  }

  "cache" should {

    "successfully create a cache entry if one does not already exist" in {
      cacheRepository.cache(cacheId, person).futureValue     shouldBe ()
      count().futureValue                                    shouldBe 1
      findAll().futureValue.head.fromBson[CacheItem[Person]] shouldBe CacheItem(cacheId, person, now, now)
    }

    "successfully update a cache entry if one does not already exist" in {
      val creationTimestamp = Instant.now()

      insert(CacheItem(cacheId, person, creationTimestamp, creationTimestamp).toDocument()).futureValue

      cacheRepository.cache(cacheId, person).futureValue     shouldBe ()
      count().futureValue                                    shouldBe 1
      findAll().futureValue.head.fromBson[CacheItem[Person]] shouldBe CacheItem(cacheId, person, creationTimestamp, now)
    }
  }

  implicit val format: Format[CacheItem[Person]] = CacheItem.format(Person.format)

  private val now       = Instant.now()
  private val cacheId   = "cacheId"
  private val person    = Person("Sarah", 30, "Female")
  private val cacheItem = CacheItem(cacheId, person, now, now)

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): Instant = now
  }

  private val cacheRepository = new ShortLivedCacheRepository[Person](
    mongoComponent   = mongoComponent,
    timestampSupport = timestampSupport,
    format           = Person.format)

  override protected val collectionName: String   = cacheRepository.collectionName
  override protected val indexes: Seq[IndexModel] = cacheRepository.indexes

}
