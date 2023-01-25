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
import org.mongodb.scala.model.IndexModel
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.libs.json.{Format, JsObject, Json}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import scala.concurrent.ExecutionContext.Implicits.global
import org.scalatest.{Assertion, Succeeded}
import org.scalatest.exceptions.TestFailedException

class DefaultPlayMongoRepositorySupportSpec
   extends AnyWordSpecLike
      with DefaultPlayMongoRepositorySupport[JsObject]
      with Matchers {

  "updateIndexPreference" should {
    "throw and exception in a unindexed query" in {
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
    )

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
