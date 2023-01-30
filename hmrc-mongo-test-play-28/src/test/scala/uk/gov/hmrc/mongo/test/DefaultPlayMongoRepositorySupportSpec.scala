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

package uk.gov.hmrc.mongo.test

import com.mongodb.MongoQueryException
import com.mongodb.client.model.{Filters, Indexes}
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import org.scalatest.{Assertion, Failed, Outcome, Succeeded}
import org.scalatest.exceptions.TestFailedException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{Format, JsObject, Json}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import java.time.Instant

class DefaultPlayMongoRepositorySupportSpec
   extends AnyWordSpec
      with DefaultPlayMongoRepositorySupport[JsObject]
      with Matchers {

  "updateIndexPreference" should {
    "throw an exception in a unindexed query" in {
      repository.collection
        .insertOne(Json.obj("unindexed" -> "value"))
        .toFuture()
        .futureValue

      whenReady {
        repository.collection
          .find(Filters.eq("unindexed", "value"))
          .toFuture()
          .failed
      } { exception =>
        exception shouldBe a[MongoQueryException]
        isIndexException(exception.asInstanceOf[MongoQueryException])
      }
    }

    "not throw an exception in indexed query" in {
      repository.collection
        .insertOne(Json.obj("indexed" -> "value"))
        .toFuture()
        .futureValue

      repository.collection
        .find(Filters.eq("indexed", "value"))
        .first()
        .toFuture()
        .futureValue
        .value
        .get("indexed")
        .map(_.as[String]) shouldEqual Some("value")
    }

    "update notablescan when index preference is to allow only indexed queries" in {
      updateIndexPreference(false).futureValue
      updateIndexPreference(true).futureValue shouldBe false
    }

    "update notablescan when index preference to allow unindexed queries" in {
      updateIndexPreference(true).futureValue
      updateIndexPreference(false).futureValue shouldBe true
    }
  }

  "deleteAll" should {
    "delete all records from a collection" in {
      val items = (1 to 99).map(index => Json.obj(s"key$index" -> index))

      repository
        .collection
        .insertMany(items)
        .toFuture()
        .futureValue

      def repositoryItems() =
        repository
        .collection
          .find()
          .toFuture()
          .futureValue

      repositoryItems().size shouldBe 99

      deleteAll().futureValue.getDeletedCount shouldBe 99

      repositoryItems().size shouldBe 0
    }
  }

  override protected lazy val repository =
    new PlayMongoRepository[JsObject](
      mongoComponent,
      collectionName = "test-collection",
      domainFormat   = Format.of[JsObject],
      indexes        = Seq(IndexModel(Indexes.ascending("indexed")))
    ) {
      override lazy val manageDataCleanup = true
    }

  def isIndexException(actual: MongoQueryException): Assertion =
    if (actual.getErrorCode != 291 &&
        // pre mongo 4.4 we didn't have a specific error code
        (actual.getErrorCode != 2 && !actual.getMessage.contains("No query solutions")))
      throw new TestFailedException(
        message = Some(s"Expected either errorCode 291 or message 'No query solutions'. Actual: $actual"),
        cause  = None,
        failedCodeStackDepth = 10
      )
    else Succeeded
}

class DefaultPlayMongoRepositorySupportWithTtlSpec
   extends AnyWordSpec
      with DefaultPlayMongoRepositorySupport[JsObject]
      with Matchers {

  "PlayMongoRepository" should {
    "not fail tests with ttl index - no data" in {
      // do nothing - test should pass
    }

    "not fail tests with ttl index - valid date" in {
      repository.collection
        .insertOne(Json.obj("created" -> MongoJavatimeFormats.instantWrites.writes(Instant.now())))
        .toFuture()
        .futureValue
    }
  }

  override protected lazy val repository =
    new PlayMongoRepository[JsObject](
      mongoComponent,
      collectionName = "test-collection",
      domainFormat   = Format.of[JsObject],
      indexes        = Seq(IndexModel(Indexes.ascending("created"), IndexOptions().expireAfter(1, TimeUnit.SECONDS)))
    )
}

class DefaultPlayMongoRepositorySupportMissingTtlSpec
   extends AnyWordSpec
      with DefaultPlayMongoRepositorySupport[JsObject]
      with Matchers {

  "PlayMongoRepository" should {
    "fail tests without ttl index" in {
      // do nothing - test should fail
    }
  }

  override def withFixture(test: NoArgTest): Outcome =
    super.withFixture(test) match {
      case Failed(m) if (m.getMessage == "No ttl indices were found for collection test-collection") => Succeeded
      case Succeeded => Failed("Expected test without ttl to fail")
      case outcome   => outcome
    }

  override protected lazy val repository =
    new PlayMongoRepository[JsObject](
      mongoComponent,
      collectionName = "test-collection",
      domainFormat   = Format.of[JsObject],
      indexes        = Seq.empty
    )
}

class DefaultPlayMongoRepositorySupportWithInvalidTtlDataSpec
   extends AnyWordSpec
      with DefaultPlayMongoRepositorySupport[JsObject]
      with Matchers {

  "PlayMongoRepository" should {
    "fail tests with ttl index and non-date data" in {
      repository.collection
        .insertOne(Json.obj("created" -> Instant.now().toString))
        .toFuture()
        .futureValue
    }
  }

  override def withFixture(test: NoArgTest): Outcome =
    super.withFixture(test) match {
      case Failed(m) if (m.getMessage == "ttl index for collection test-collection points at created which has type 'string', it should be 'date'") => Succeeded
      case Succeeded => Failed("Expected test with invalid ttl data to fail")
      case outcome   => outcome
    }

  override protected lazy val repository =
    new PlayMongoRepository[JsObject](
      mongoComponent,
      collectionName = "test-collection",
      domainFormat   = Format.of[JsObject],
      indexes        = Seq(IndexModel(Indexes.ascending("created"), IndexOptions().expireAfter(1, TimeUnit.SECONDS)))
    )
}
