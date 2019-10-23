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

package uk.gov.hmrc.mongo.test

import com.mongodb.MongoQueryException
import com.mongodb.client.model.Filters.{eq => mongoEq}
import com.mongodb.client.model.Indexes
import org.mongodb.scala.bson.BsonString
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.IndexModel
import org.scalatest.Matchers.{include, _}
import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class DefaultMongoSupportSpec extends WordSpec with DefaultMongoSupport with ScalaFutures {

  "updateIndexPreference" should {

    "throw and exception in a unindexed query" in {
      mongoCollection()
        .insertOne(Document("unindexed" -> "value"))
        .toFuture()
        .futureValue

      ScalaFutures.whenReady {
        mongoCollection()
          .find(mongoEq("unindexed", "value"))
          .toFuture()
          .failed
      } { exception =>
        exception            shouldBe a[MongoQueryException]
        exception.getMessage should include("No query solutions")
      }
    }

    "not throw an exception in indexed query" in {
      mongoCollection()
        .insertOne(Document("indexed" -> "value"))
        .toFuture()
        .futureValue

      mongoCollection()
        .find(mongoEq("indexed", "value"))
        .first()
        .toFuture()
        .futureValue
        .get("indexed") shouldEqual Some(BsonString("value"))
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

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)
  override protected val collectionName: String        = "test-collection"
  override protected val indexes: Seq[IndexModel]      = Seq(IndexModel(Indexes.ascending("indexed")))
}
