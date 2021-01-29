/*
 * Copyright 2021 HM Revenue & Customs
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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.result.{DeleteResult, InsertOneResult}
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.MongoUtils

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import ExecutionContext.Implicits.global

/** Specialises MongoSupport for [[PlayMongoRepository]].
  *
  * It is recommended to use [[DefaultPlayMongoRepositorySupport]] which brings this
  * together with other useful testing support.
  *
  * {{{repository}}} will need overriding with the repository under test.
  *
  * If a schema is not defined on the repository, it can still be provided for
  * tests by overridding {{{optSchema}}}.
  *
  * CRUD methods are provided which can help preparing and asserting repository behaviour.
  */
trait PlayMongoRepositorySupport[A] extends MongoSupport {
  protected def repository: PlayMongoRepository[A]

  protected lazy val collectionName: String          = repository.collectionName
  protected lazy val indexes: Seq[IndexModel]        = repository.indexes
  protected lazy val optSchema: Option[BsonDocument] = repository.optSchema


  protected def findAll()(implicit ev: ClassTag[A]): Future[Seq[A]] =
    repository.collection
      .find()
      .toFuture

  protected def count(): Future[Long] =
    repository.collection
      .countDocuments()
      .toFuture

  protected def find(filter: Bson)(implicit ev: ClassTag[A]): Future[Seq[A]] =
    repository.collection
      .find(filter)
      .toFuture

  protected def insert(a: A): Future[InsertOneResult] =
    repository.collection
      .insertOne(a)
      .toFuture

  protected def deleteAll(): Future[DeleteResult] =
    repository
      .collection
      .deleteMany(filter = Document())
      .toFuture

  protected def createCollection(): Unit =
    mongoDatabase
      .createCollection(collectionName)
      .toFuture
      .futureValue

  protected def dropCollection(): Unit =
    repository.collection
      .drop()
      .toFuture
      .futureValue

  protected def ensureIndexes(): Seq[String] =
    MongoUtils.ensureIndexes(repository.collection, indexes, replaceIndexes = false)
      .futureValue

  protected def ensureSchemas(): Unit =
    MongoUtils.ensureSchema(mongoComponent, repository.collection, optSchema)
      .futureValue

  override protected def prepareDatabase(): Unit = {
    super.prepareDatabase()
    ensureIndexes()
    ensureSchemas()
  }
}
