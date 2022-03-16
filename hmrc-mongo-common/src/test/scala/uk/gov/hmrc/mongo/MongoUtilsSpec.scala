/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.mongo

import com.mongodb.MongoCommandException
import org.mongodb.scala.Document
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Indexes, IndexModel, IndexOptions}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future}
import ExecutionContext.Implicits.global
import org.bson.conversions.Bson

class MongoUtilsSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach
     with ScalaCheckDrivenPropertyChecks {

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val collectionName = "myobject"
  val collection =
    mongoComponent.database.getCollection[BsonDocument](collectionName = collectionName)

  "MongoUtils.ensureIndexes" should {
    "recreate indexes when dropping some indexes" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1")),
          IndexModel(Indexes.ascending("field2"), IndexOptions().name("idx2"))
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field2"), IndexOptions().name("idx2")),
          IndexModel(Indexes.ascending("field3"), IndexOptions().name("idx3"))
        )
      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1)),
                              BsonDocument("name" -> "idx2", "key" -> BsonDocument("field2" -> 1))
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = true)
         createdIndexes2 <- collection.listIndexes().toFuture()
         _               =  createdIndexes2.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx2", "key" -> BsonDocument("field2" -> 1)),
                              BsonDocument("name" -> "idx3", "key" -> BsonDocument("field3" -> 1))
                            )
       } yield ()
      ).futureValue
    }

    "throw IndexOptionsConflict when changing key with replaceIndexes=false" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1"))
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field3"), IndexOptions().name("idx1"))
        )
      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1))
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = false)
       } yield ()
      ).failed.futureValue shouldBe an[MongoCommandException]
    }

    "throw IndexOptionsConflict when changing expireAfterSeconds with replaceIndexes=false" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1").expireAfter(10, TimeUnit.SECONDS)),
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1").expireAfter(20, TimeUnit.SECONDS)),
        )

      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1), "expireAfterSeconds" -> 10L)
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = false)
         createdIndexes2 <- collection.listIndexes().toFuture()
       } yield ()
      ).failed.futureValue shouldBe an[MongoCommandException]
    }

    "recreate indexes when changing key with replaceIndexes=true" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1")),
          IndexModel(Indexes.ascending("field2"), IndexOptions().name("idx2"))
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field3"), IndexOptions().name("idx1")),
          IndexModel(Indexes.ascending("field4"), IndexOptions().name("idx2"))
        )
      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1)),
                              BsonDocument("name" -> "idx2", "key" -> BsonDocument("field2" -> 1))
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = true)
         createdIndexes2 <- collection.listIndexes().toFuture()
         _               =  createdIndexes2.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field3" -> 1)),
                              BsonDocument("name" -> "idx2", "key" -> BsonDocument("field4" -> 1))
                            )
       } yield ()
      ).futureValue
    }

    "recreate indexes when changing expiresAfter with replaceIndexes=true" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1").expireAfter(10, TimeUnit.SECONDS)),
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1").expireAfter(20, TimeUnit.SECONDS)),
        )
      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1), "expireAfterSeconds" -> 10L)
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = true)
         createdIndexes2 <- collection.listIndexes().toFuture()
         _               =  createdIndexes2.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1), "expireAfterSeconds" -> 20L)
                            )
       } yield ()
      ).futureValue
    }
  }

  "MongoUtils.defaultName" should {
    "create the same names as mongo" in {
      forAll(indexKeyGen) { indexKey =>
        val index = IndexModel(indexKey, IndexOptions())
        (for {
           // clean up from last index test
           existingIndexes <- collection.listIndexes().toFuture().map(_.filterNot(MongoUtils.indexName(_) == "_id_"))
           _               <- Future.traverse(existingIndexes)(existing =>
                                collection
                                  .dropIndex(MongoUtils.indexName(existing))
                                  .toFuture()
                              )
           // the check
           _               <- collection
                                .createIndex(index.getKeys, index.getOptions)
                                .toFuture()
           createdIndexes  <- collection.listIndexes().toFuture()
         } yield MongoUtils.defaultName(index) shouldBe createdIndexes.map(MongoUtils.indexName).filterNot(_ == "_id_").head
        ).futureValue
      }
    }
  }

  def singleIndexKeyGen: Gen[Bson] =
    for {
      field1      <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      field2      <- Gen.alphaNumStr.suchThat(_.nonEmpty)
      isMultikey  <- Arbitrary.arbitrary[Boolean]
      isAscending <- Arbitrary.arbitrary[Boolean]
      field = if (isMultikey) field1 + "." + field2 else field1
    } yield
      if (isAscending) Indexes.ascending(field) else Indexes.descending(field)

  def indexKeyGen: Gen[Bson] =
    for {
      n         <- Gen.choose(1, 10)
      indexKeys <- Gen.listOfN(n, singleIndexKeyGen)
    } yield
      Indexes.compoundIndex(indexKeys: _*)

  def prepareDatabase(): Unit =
    (for {
       _ <- collection.drop().toFuture()
       _ <- mongoComponent.database.createCollection(collectionName).toFuture()
     } yield ()
    ).futureValue

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    prepareDatabase()
  }

  def toBsonDocument(index: Document): BsonDocument = {
    val d = index.toBsonDocument
    // calling index.remove("v") leaves index untouched - convert to BsonDocument first..
    d.remove("v") // version
    d.remove("ns")
    d
  }
}
