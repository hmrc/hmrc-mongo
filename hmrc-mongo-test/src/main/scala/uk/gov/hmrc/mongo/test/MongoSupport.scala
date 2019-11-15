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

import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.{Document, MongoClient, MongoCollection, MongoDatabase, ReadPreference}
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.mongo.component.MongoComponent

import scala.concurrent.{ExecutionContext, Future}

import ExecutionContext.Implicits.global

trait MongoSupport extends ScalaFutures {
  protected val databaseName: String = "test-" + this.getClass.getSimpleName
  protected val mongoUri: String     = s"mongodb://localhost:27017/$databaseName"

  protected lazy val mongoComponent: MongoComponent = MongoComponent(mongoUri)
  protected lazy val mongoClient: MongoClient       = mongoComponent.client
  protected lazy val mongoDatabase: MongoDatabase   = mongoComponent.database

  protected def dropDatabase(): Unit =
    mongoDatabase
      .drop()
      .toFuture
      .futureValue

  protected def prepareDatabase(): Unit =
    dropDatabase()

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
  protected val collectionName: String

  protected val indexes: Seq[IndexModel]

  protected lazy val mongoCollection: MongoCollection[Document] =
    mongoDatabase.getCollection(collectionName)

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
    if (indexes.nonEmpty) {
      mongoCollection
        .createIndexes(indexes)
        .toFuture
        .futureValue
    } else {
      Seq.empty
    }

  override protected def prepareDatabase(): Unit = {
    super.prepareDatabase()
    createIndexes()
  }
}

object MongoCollectionSupport {
  def apply(name: String, allIndexes: Seq[IndexModel]): MongoCollectionSupport =
    new MongoCollectionSupport {
      override protected val collectionName: String   = name
      override protected val indexes: Seq[IndexModel] = allIndexes
    }
}
