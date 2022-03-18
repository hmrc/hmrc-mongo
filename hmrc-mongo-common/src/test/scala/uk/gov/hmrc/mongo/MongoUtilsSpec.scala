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
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global

class MongoUtilsSpec
  extends AnyWordSpecLike
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach {

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
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions())
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field2"), IndexOptions()),
          IndexModel(Indexes.ascending("field3"), IndexOptions())
        )
      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "field1_1", "key" -> BsonDocument("field1" -> 1)),
                              BsonDocument("name" -> "field2_1", "key" -> BsonDocument("field2" -> 1))
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = true)
         createdIndexes2 <- collection.listIndexes().toFuture()
         _               =  createdIndexes2.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "field2_1", "key" -> BsonDocument("field2" -> 1)),
                              BsonDocument("name" -> "field3_1", "key" -> BsonDocument("field3" -> 1))
                            )
       } yield ()
      ).futureValue
    }

    "recreate indexes when changing index key" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1")),
          IndexModel(Indexes.ascending("field2"), IndexOptions().name("idx2"))
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1")),
          IndexModel(Indexes.ascending("field3"), IndexOptions().name("idx2"))
        )
      (for {
         _               <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
         createdIndexes1 <- collection.listIndexes().toFuture()
         _               =  createdIndexes1.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("key" -> BsonDocument("_id"    -> 1), "name" -> "_id_"),
                              BsonDocument("key" -> BsonDocument("field1" -> 1), "name" -> "idx1"),
                              BsonDocument("key" -> BsonDocument("field2" -> 1), "name" -> "idx2")
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = true)
         createdIndexes2 <- collection.listIndexes().toFuture()
         _               =  createdIndexes2.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("key" -> BsonDocument("_id"    -> 1), "name" -> "_id_"),
                              BsonDocument("key" -> BsonDocument("field1" -> 1), "name" -> "idx1"),
                              BsonDocument("key" -> BsonDocument("field3" -> 1), "name" -> "idx2")
                            )
       } yield ()
      ).futureValue
    }

    "recreate indexes when changing index name" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx1")),
          IndexModel(Indexes.ascending("field2"), IndexOptions().name("idx2"))
        )
      val indexes2 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions().name("idx3")),
          IndexModel(Indexes.ascending("field2"), IndexOptions().name("idx2"))
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
                              BsonDocument("key" -> BsonDocument("_id"    -> 1), "name" -> "_id_"),
                              BsonDocument("key" -> BsonDocument("field2" -> 1), "name" -> "idx2"),
                              BsonDocument("key" -> BsonDocument("field1" -> 1), "name" -> "idx3")
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
