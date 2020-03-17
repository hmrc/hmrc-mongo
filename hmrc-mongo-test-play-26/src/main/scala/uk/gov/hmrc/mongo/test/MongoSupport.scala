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

import org.mongodb.scala.{Document, MongoClient, MongoDatabase, ReadPreference}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, TestSuite}
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

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

/** Calls prepareDatabase before each test, ensuring a clean database */
trait CleanMongoCollectionSupport extends MongoSupport with BeforeAndAfterEach {
  this: TestSuite =>

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    prepareDatabase()
  }
}

/** Causes queries which don't use an index to generate [[com.mongodb.MongoQueryException]]
  * or [[com.mongodb.MongoWriteException]] containing message 'No query solutions'
  */
trait IndexedMongoQueriesSupport extends MongoSupport with BeforeAndAfterAll {
  this: TestSuite =>

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    updateIndexPreference(onlyAllowIndexedQuery = true)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    updateIndexPreference(onlyAllowIndexedQuery = false)
  }
}