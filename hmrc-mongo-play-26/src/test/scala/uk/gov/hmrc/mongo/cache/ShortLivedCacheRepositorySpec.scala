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
import uk.gov.hmrc.mongo.play.json.Codecs._
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport

import scala.concurrent.ExecutionContext.Implicits.global

class EntityCacheRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with DefaultMongoCollectionSupport
    with ScalaFutures {

  "get" should {
    "successfully return value of desired type if cache item exists" in {
      insert(cacheItem.toDocument()).futureValue
      cacheRepository.get(cacheId).futureValue shouldBe Some(person)
    }

    "successfully return None if cache item does not exist" in {
      cacheRepository.get(cacheId).futureValue shouldBe None
    }
  }

  "put" should {
    "successfully create a cache entry if one does not already exist" in {
      cacheRepository.put(cacheId, person).futureValue       shouldBe ()
      count().futureValue                                    shouldBe 1
      findAll().futureValue.head.fromBson[CacheItem] shouldBe cacheItem
    }

    "successfully update a cache entry if one does not already exist" in {
      val creationTimestamp = Instant.now()

      insert(cacheItem.copy(createdAt = creationTimestamp, modifiedAt = creationTimestamp).toDocument()).futureValue

      cacheRepository.put(cacheId, person).futureValue       shouldBe ()
      count().futureValue                                    shouldBe 1
      findAll().futureValue.head.fromBson[CacheItem] shouldBe cacheItem.copy(createdAt = creationTimestamp, modifiedAt = now)
    }
  }

  implicit val format2: Format[Person] = Person.format
  implicit val format: Format[CacheItem] = MongoCacheRepository.format

  private val now       = Instant.now()
  private val cacheId   = CacheId("cacheId")
  private val dataKey   = DataKey[Person]("dataKey")
  private val person    = Person("Sarah", 30, "Female")
  private def cacheItem = CacheItem(cacheId.asString, JsObject(Seq(dataKey.asString -> Json.toJson(person))), now, now)

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): Instant = now
  }

  private lazy val cacheRepository = new EntityCacheRepository[Person](
    mongoComponent   = mongoComponent,
    timestampSupport = timestampSupport,
    format           = Person.format)

  override protected lazy val collectionName: String   = cacheRepository.collectionName
  override protected lazy val indexes: Seq[IndexModel] = cacheRepository.indexes
}
