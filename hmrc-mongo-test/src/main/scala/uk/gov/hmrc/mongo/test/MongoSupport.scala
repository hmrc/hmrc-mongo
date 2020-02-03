/*
 * Copyright 2020 HM Revenue & Customs
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

import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.{Completed, Document, MongoClient, MongoCollection, MongoDatabase, ReadPreference}
import org.scalatest.concurrent.ScalaFutures
import play.api.Configuration
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.throttle.ThrottleConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

trait MongoSupport extends ScalaFutures {
  protected val databaseName: String = "test-" + this.getClass.getSimpleName
  protected val mongoUri: String     = s"mongodb://localhost:27017/$databaseName"

  protected lazy val throttleConfig: ThrottleConfig = new ThrottleConfig(Configuration(("mongodb.uri", mongoUri)))
  protected lazy val mongoComponent: MongoComponent = MongoComponent(mongoUri)
  protected lazy val mongoClient: MongoClient       = mongoComponent.client
  protected lazy val mongoDatabase: MongoDatabase   = mongoComponent.database

  protected def dropDatabase(): Unit =
    mongoDatabase
      .drop()
      .toFuture
      .futureValue

  protected def prepareDatabase(): Unit = {
    Logger.warn(s"in MongoSupport.prepareDatabase")
    dropDatabase()
    Logger.warn(s" database dropped")
  }

  protected def updateIndexPreference(onlyAllowIndexedQuery: Boolean): Future[Boolean] = {
    val notablescan = if (onlyAllowIndexedQuery) 1 else 0

    mongoClient
      .getDatabase("admin")
      .withReadPreference(ReadPreference.primaryPreferred())
      .runCommand(Document("setParameter" -> 1, "notablescan" -> notablescan))
      .toFuture
      .map(_.getBoolean("was"))
  }
}

trait MongoCollectionSupport extends MongoSupport {
  protected def collectionName: String

  protected def indexes: Seq[IndexModel]

  protected def jsonSchema: Option[BsonDocument] = None

  protected lazy val mongoCollection: MongoCollection[Document] =
    mongoDatabase.getCollection(collectionName)

  protected def findAll(): Future[Seq[Document]] =
    mongoCollection
      .find()
      .toFuture

  protected def count(): Future[Long] =
    mongoCollection
      .countDocuments()
      .toFuture()

  protected def find(filter: Bson): Future[Seq[Document]] =
    mongoCollection
      .find(filter)
      .toFuture()

  protected def insert[T](document: Document): Future[Completed] =
    mongoCollection
      .insertOne(document)
      .toFuture()

  protected def createCollection(): Unit =
    mongoDatabase
      .createCollection(collectionName)
      .toFuture
      .futureValue

  protected def dropCollection(): Unit =
    mongoCollection
      .drop()
      .toFuture
      .futureValue

  protected def createIndexes(): Seq[String] =
    Future.traverse(indexes) { index =>
      mongoCollection
        .createIndex(index.getKeys, index.getOptions)
        .toFuture
    }.futureValue

  protected def createSchemas(): Unit =
    jsonSchema.fold(()){ schema =>
      mongoComponent.database
        .runCommand(
          Document(
            "collMod"          -> collectionName,
            "validator"        -> Document(f"$$jsonSchema" -> schema),
            "validationLevel"  -> "strict",
            "validationAction" -> "error"
          )
        )
        .toFuture
        .map(_ => ())
        .futureValue
    }

  override protected def prepareDatabase(): Unit = {
    super.prepareDatabase()
    createIndexes()
    createSchemas()
  }
}
