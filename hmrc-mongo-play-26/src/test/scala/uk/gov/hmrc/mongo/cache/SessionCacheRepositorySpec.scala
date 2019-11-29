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
import java.time.{LocalDateTime, ZoneOffset}

import org.mongodb.scala.model.IndexModel
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json._
import uk.gov.hmrc.http.logging.{Authorization, ForwardedFor, RequestId, SessionId}
import uk.gov.hmrc.http.{HeaderCarrier, Token}
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.cache.collection.CacheItem
import uk.gov.hmrc.mongo.play.json.Codecs._
import uk.gov.hmrc.mongo.test.DefaultMongoCollectionSupport

import scala.concurrent.ExecutionContext.Implicits.global

class SessionCacheRepositorySpec
    extends AnyWordSpecLike
    with Matchers
    with DefaultMongoCollectionSupport
    with ScalaFutures {

  "fetch" should {

    "successfully return value of desired type if cache item exists" in {

      insert(cacheItem.asDocument()).futureValue
      cacheRepository.fetch().futureValue shouldBe Some(person)
    }

    "successfully return None if cache item does not exist" in {
      cacheRepository.fetch().futureValue shouldBe None
    }
  }

  "cache" should {

    "successfully create a cache entry if one does not already exist" in {
      cacheRepository.cache(person).futureValue            shouldBe ()
      count().futureValue                                  shouldBe 1
      findAll().futureValue.head.asJson[CacheItem[Person]] shouldBe CacheItem(cacheId, person, now, now)

    }

    "successfully update a cache entry if one does not already exist" in {
      val creationTimestamp = LocalDateTime.now(ZoneOffset.UTC)

      insert(CacheItem(cacheId, person, creationTimestamp, creationTimestamp).asDocument()).futureValue

      cacheRepository.cache(person).futureValue            shouldBe ()
      count().futureValue                                  shouldBe 1
      findAll().futureValue.head.asJson[CacheItem[Person]] shouldBe CacheItem(cacheId, person, creationTimestamp, now)
    }
  }

  "remove" should {
    "successfully remove cache entry that exists" in {
      insert(cacheItem.asDocument()).futureValue
      count().futureValue shouldBe 1

      cacheRepository.remove().futureValue
      count().futureValue shouldBe 0

    }

    "not remove cacheItem if no cache entry is found" in {
      insert(cacheItem.copy(id = "another-id").asDocument()).futureValue
      count().futureValue shouldBe 1

      cacheRepository.remove()
      count().futureValue shouldBe 1
    }

  }

  implicit val format: Format[CacheItem[Person]] = CacheItem.format(Person.format)

  implicit val hc: HeaderCarrier = HeaderCarrier(
    authorization = Some(Authorization("auth")),
    sessionId     = Some(SessionId("session")),
    requestId     = Some(RequestId("request")),
    token         = Some(Token("token")),
    forwarded     = Some(ForwardedFor("forwarded"))
  )

  private val now       = LocalDateTime.now(ZoneOffset.UTC)
  private val cacheId   = "session"
  private val person    = Person("Sarah", 30, "Female")
  private val cacheItem = CacheItem(cacheId, person, now, now)

  private val timestampSupport = new TimestampSupport {
    override def timestamp(): LocalDateTime = now
  }

  private val cacheRepository = new SessionCacheRepository[Person](
    mongoComponent   = mongoComponent,
    timestampSupport = timestampSupport,
    format           = Person.format)

  override protected val collectionName: String   = cacheRepository.collectionName
  override protected val indexes: Seq[IndexModel] = cacheRepository.indexes

}
