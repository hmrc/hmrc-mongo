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

package uk.gov.hmrc.mongo

import com.mongodb.{ConnectionString, MongoClientSettings, MongoCommandException}
import com.mongodb.event.{CommandListener, CommandStartedEvent}
import org.mongodb.scala.{Document, MongoClient, MongoCollection, ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.{BsonArray, BsonDateTime, BsonDocument, BsonInt32, BsonNull, BsonString}
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.concurrent.TimeUnit
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}
import ExecutionContext.Implicits.global

class MongoUtilsSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterEach {

  val mongoComponent: MongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val collectionName = "myobject"
  val collection: MongoCollection[Document] =
    mongoComponent.database.getCollection[Document](collectionName = collectionName)

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
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1), "expireAfterSeconds" -> 10)
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = false)
         _               <- collection.listIndexes().toFuture()
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
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1), "expireAfterSeconds" -> 10)
                            )
         _               <- MongoUtils.ensureIndexes(collection, indexes2, replaceIndexes = true)
         createdIndexes2 <- collection.listIndexes().toFuture()
         _               =  createdIndexes2.map(toBsonDocument) should contain theSameElementsAs Seq(
                              BsonDocument("name" -> "_id_", "key" -> BsonDocument("_id"    -> 1)),
                              BsonDocument("name" -> "idx1", "key" -> BsonDocument("field1" -> 1), "expireAfterSeconds" -> 20)
                            )
       } yield ()
      ).futureValue
    }
  }

  "MongoUtils.getTTLIndexes" should {
    "return empty when no ttl indexes" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions())
        )
      val res =
        (for {
          _ <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
          res <- MongoUtils.getTTLIndexes(collection)
        } yield res
          ).futureValue
      res shouldBe List()
    }
  }

  "MongoUtils.checkKeyFieldData" should {
    "only fetch the invalid document when the other 1000 TTL values are dates" in {
      val indexName = "ttlIndex"
      val validDate = new BsonDateTime(System.currentTimeMillis())
      val documents =
        (1 to 1000).map(_ => Document("field2" -> validDate)) :+
          Document("field2" -> BsonString("not a date"))

      MongoUtils.ensureIndexes(
        collection,
        Seq(IndexModel(Indexes.ascending("field2"), IndexOptions().name(indexName).expireAfter(1, TimeUnit.HOURS))),
        replaceIndexes = false
      ).futureValue
      collection.insertMany(documents).toFuture().futureValue

      // Capture the real validation query so this test follows changes to its implementation.
      val aggregateCommand = Promise[org.bson.BsonDocument]()
      val listener = new CommandListener {
        override def commandStarted(event: CommandStartedEvent): Unit =
          if (event.getCommandName == "aggregate") {
            aggregateCommand.trySuccess(event.getCommand.clone())
            ()
          }
      }
      val client = MongoClient(
        MongoClientSettings.builder()
          .applyConnectionString(new ConnectionString(s"mongodb://localhost:27017/${mongoComponent.database.name}"))
          .codecRegistry(mongoComponent.database.codecRegistry)
          .addCommandListener(listener)
          .build()
      )

      try {
        val observedCollection = client.getDatabase(mongoComponent.database.name).getCollection[Document](collectionName)
        MongoUtils.checkKeyFieldData(observedCollection, "field2", indexName).futureValue shouldBe
          List(TtlWarning.InvalidType(collectionName, "field2", "string", 1))

        val command = aggregateCommand.future.futureValue
        val aggregate = BsonDocument(
          "aggregate" -> command.get("aggregate"),
          "pipeline"  -> command.get("pipeline"),
          "cursor"    -> command.get("cursor"),
          "hint"      -> command.get("hint")
        )
        val explanation = mongoComponent.database
          .runCommand(BsonDocument("explain" -> aggregate, "verbosity" -> "executionStats"))
          .toFuture()
          .futureValue
          .toBsonDocument
        val executionStats =
          if (explanation.containsKey("executionStats")) explanation.getDocument("executionStats")
          else explanation.getArray("stages").getValues.asScala
            .map(_.asDocument())
            .find(_.containsKey("$cursor"))
            .getOrElse(fail("Explain did not include cursor execution statistics"))
            .getDocument("$cursor")
            .getDocument("executionStats")

        // IXSCAN alone is insufficient: including $type: array makes it fetch all 1001 documents.
        withClue(s"TTL validation examined ${executionStats.getNumber("totalKeysExamined").longValue()} index keys: ") {
          executionStats.getNumber("totalDocsExamined").longValue() should be <= 1L
        }
      } finally client.close()
    }

    s"return no warnings when ttl index but no data" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
            .name("ttlIndex")
            .expireAfter(1000, TimeUnit.MILLISECONDS)
          )
        )
      val res =
        (for {
          _ <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
          res <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
        } yield res
          ).futureValue
      res shouldBe List()
    }

    "return an InvalidType warning when ttl index key field contains one non-date" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
                                                    .name("ttlIndex")
                                                    .expireAfter(1000, TimeUnit.MILLISECONDS)
                    )
        )
      val res =
        (for {
           _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
           items =  (1 to 99).map(i => Document("field2" -> (if (i == 30) new BsonInt32(i) else new BsonDateTime(i))))
           _     <- collection.insertMany(items) .toFuture()
           res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
         } yield res
        ).futureValue
      res shouldBe List(TtlWarning.InvalidType(collectionName, "field2", "int", 1))
    }

    "return an InvalidType warning when ttl index key field contains multiple non-dates" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
            .name("ttlIndex")
            .expireAfter(1000, TimeUnit.MILLISECONDS)
          )
        )
      val res =
        (for {
          _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
          items =  (1 to 99).map(i => Document("field2" -> (if (i % 2 == 0) new BsonInt32(i) else new BsonDateTime(i))))
          _     <- collection.insertMany(items) .toFuture()
          res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
        } yield res
          ).futureValue

      res should contain(TtlWarning.InvalidType(collectionName, "field2", "int", 49))
    }

    "return multiple InvalidType warnings when ttl index key field contains multiple non-date types" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
            .name("ttlIndex")
            .expireAfter(1000, TimeUnit.MILLISECONDS)
          )
        )
      val res =
        (for {
          _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
          items =  (1 to 99).map(i => Document("field2" -> (if (i % 2 == 0) new BsonInt32(i) else new BsonString("Something"))))
          _     <- collection.insertMany(items) .toFuture()
          res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
        } yield res
          ).futureValue

      res should contain(TtlWarning.InvalidType(collectionName, "field2", "int", 49))
      res should contain(TtlWarning.InvalidType(collectionName, "field2", "string", 50))
    }

    "return a MissingKeyField warning when ttl index points at missing field" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
                                                    .name("ttlIndex")
                                                    .expireAfter(1000, TimeUnit.MILLISECONDS)
                    )
        )
      val res =
        (for {
           _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
           items =  (1 to 99).map(_ => Document())
           _     <- collection.insertMany(items) .toFuture()
           res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
         } yield res
        ).futureValue
      res shouldBe List(TtlWarning.MissingKeyField(collectionName, "field2", 99))
    }

    "return a NullKeyField warning when ttl index points at missing field" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
            .name("ttlIndex")
            .expireAfter(1000, TimeUnit.MILLISECONDS)
          )
        )
      val res =
        (for {
          _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
          items =  (1 to 99).map(_ => Document("field2" -> BsonNull()))
          _     <- collection.insertMany(items) .toFuture()
          res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
        } yield res
          ).futureValue
      res shouldBe List(TtlWarning.NullKeyField(collectionName, "field2", 99))
    }


    s"return no warnings when ttl index points at date" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
                                                    .name("ttlIndex")
                                                    .expireAfter(1000, TimeUnit.MILLISECONDS)
                    )
        )
      val res =
        (for {
           _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
           items =  (1 to 99).map(i => Document("field2" -> new BsonDateTime(i)))
           _     <- collection.insertMany(items) .toFuture()
           res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
         } yield res
        ).futureValue
      res shouldBe List()
    }

    s"return a warning when ttl index points at array with only dates" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
                                                    .name("ttlIndex")
                                                    .expireAfter(1000, TimeUnit.MILLISECONDS)
                    )
        )
      val res =
        (for {
           _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
           items =  (1 to 99).map(i => Document("field2" -> new BsonArray(Seq(new BsonDateTime(i), new BsonDateTime(i)).asJava)))
           _     <- collection.insertMany(items).toFuture()
           res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
         } yield res
        ).futureValue
      res shouldBe List(TtlWarning.InvalidType(collectionName, "field2", "array", 99))
    }

    s"return a warning when ttl index points at array containing both dates and other types" in {
      // This scenario would actually be picked up by a TTL index correctly,
      // however using arrays in the TTL key field should be discouraged
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
            .name("ttlIndex")
            .expireAfter(1000, TimeUnit.MILLISECONDS)
          )
        )
      val res =
        (for {
          _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
          items =  (1 to 99).map(i => Document("field2" -> new BsonArray(Seq(new BsonDateTime(i), new BsonString(i.toString)).asJava)))
          _     <- collection.insertMany(items) .toFuture()
          res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
        } yield res
          ).futureValue
      res shouldBe List(TtlWarning.InvalidType(collectionName, "field2", "array", 99))
    }

    s"return an InvalidType warning when ttl index points at array containing no dates" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
                                                    .name("ttlIndex")
                                                    .expireAfter(1000, TimeUnit.MILLISECONDS)
                    )
        )
      val res =
        (for {
           _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
           items =  (1 to 99).map(i => Document("field2" -> new BsonArray(Seq(new BsonString(i.toString), new BsonString(i.toString)).asJava)))
           _     <- collection.insertMany(items) .toFuture()
           res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
         } yield res
        ).futureValue
      res shouldBe List(TtlWarning.InvalidType(collectionName, "field2", "array", 99))
    }

    s"return no warnings when requested and ttl exists" in {
      val indexes1 =
        Seq(
          IndexModel(Indexes.ascending("field1"), IndexOptions()),
          IndexModel(Indexes.ascending("field2"), IndexOptions()
                                                    .name("ttlIndex")
                                                    .expireAfter(1000, TimeUnit.MILLISECONDS)
                    )
        )
      val res =
        (for {
           _     <- MongoUtils.ensureIndexes(collection, indexes1, replaceIndexes = false)
           items =  (1 to 99).map(i => Document("field2" -> new BsonDateTime(i)))
           _     <- collection.insertMany(items) .toFuture()
           res   <- MongoUtils.checkKeyFieldData(collection, "field2", "ttlIndex")
         } yield res
        ).futureValue
      res shouldBe List()
    }
  }

  "MongoUtils.checkTtlIndex" should {
    "propagate a failed data check instead of reporting success" in {
      MongoUtils.ensureIndexes(
        collection,
        Seq(IndexModel(Indexes.ascending("field2"), IndexOptions().name("ttlIndex").expireAfter(1, TimeUnit.HOURS))),
        replaceIndexes = false
      ).futureValue

      val queryFailure = new RuntimeException("TTL data check failed")
      val mongoUtils = new MongoUtils {
        override private[mongo] def checkKeyFieldDataForTTLs(
          collection: MongoCollection[Document],
          ttls: Seq[Document]
        )(implicit ec: ExecutionContext): Future[List[TtlWarning]] =
          Future.failed(queryFailure)
      }

      // Inspect either outcome so the failure clearly shows Success(()) when the inner future is lost.
      val outcome = mongoUtils.checkTtlIndex(mongoComponent, collectionName, checkType = true)
        .transform(result => Success(result))
        .futureValue

      outcome shouldBe Failure(queryFailure)
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
