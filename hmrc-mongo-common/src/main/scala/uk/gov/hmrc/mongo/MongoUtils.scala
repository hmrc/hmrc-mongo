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

package uk.gov.hmrc.mongo

import org.mongodb.scala.{Document, MongoCollection, MongoCommandException, MongoServerException}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{IndexModel, ValidationAction, ValidationLevel}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

trait MongoUtils {
  val logger: Logger = LoggerFactory.getLogger(getClass.getName)

  def ensureIndexes[A](
    collection: MongoCollection[A],
    indexes: Seq[IndexModel],
    replaceIndexes: Boolean
  )(implicit ec: ExecutionContext
  ): Future[Seq[String]] =
    for {
      currentIndices <- collection.listIndexes.toFuture.map(_.map(_("name").asString.getValue))
      res <- Future.traverse(indexes) { index =>
               collection
                 .createIndex(index.getKeys, index.getOptions)
                 .toFuture
                 .recoverWith {
                   case IndexConflict(e) if replaceIndexes =>
                     logger.warn("Conflicting Mongo index found. This index will be updated")
                     for {
                       _      <- collection.dropIndex(index.getOptions.getName).toFuture
                       result <- collection.createIndex(index.getKeys, index.getOptions).toFuture
                     } yield result
                 }
               }
      indicesToDrop = if (replaceIndexes) currentIndices.toSet.diff(res.toSet + "_id_") else Set.empty
      _ <-  Future.traverse(indicesToDrop) { indexName =>
             logger.warn(s"Index '$indexName' is not longer defined, removing")
               collection.dropIndex(indexName).toFuture
                 .recoverWith {
                   // could be caused by race conditions between server instances
                   case IndexNotFound(e) => Future.successful(())
                 }
           }
    } yield res

  def existsCollection[A](
      mongoComponent: MongoComponent,
      collection: MongoCollection[A]
    )(implicit ec: ExecutionContext
    ): Future[Boolean] =
      for {
        collections <- mongoComponent.database.listCollectionNames.toFuture
      } yield collections.contains(collection.namespace.getCollectionName)


  /** Create the schema if defined, or remove if not defined.
    * Note, the collection will be created if it does not exist yet.
    */
  def ensureSchema[A](
      mongoComponent: MongoComponent,
      collection: MongoCollection[A],
      optSchema: Option[BsonDocument]
    )(implicit ec: ExecutionContext
    ): Future[Unit] =
      for {
        _       <- Future.successful(logger.info(s"Ensuring ${collection.namespace} has ${optSchema.fold("no")(_ => "a")} jsonSchema"))
        exists  <- existsCollection(mongoComponent, collection)
        _       <- if (!exists) {
                     mongoComponent.database.createCollection(collection.namespace.getCollectionName).toFuture
                   } else Future.successful(())
        collMod =  optSchema.fold(
                     Document(
                       "collMod"          -> collection.namespace.getCollectionName,
                       "validator"        -> Document(),
                       "validationLevel"  -> ValidationLevel.OFF.getValue
                     )
                   )(schema =>
                       Document(
                         "collMod"          -> collection.namespace.getCollectionName,
                         "validator"        -> Document(f"$$jsonSchema" -> schema),
                         "validationLevel"  -> ValidationLevel.STRICT.getValue,
                         "validationAction" -> ValidationAction.ERROR.getValue
                       )
                   )
        _       <- mongoComponent.database.runCommand(collMod).toFuture
       } yield ()

  /** It is possible with MongoDB to have a duplicate key violation when trying to upsert, if two or more threads try
    * the operation concurrently: https://jira.mongodb.org/browse/SERVER-14322
    * See https://jira.tools.tax.service.gov.uk/browse/BDOG-731 for more background.
    *
    * You can wrap the upsert with retryOnDuplicateKey.
    */
  def retryOnDuplicateKey[A](retries: Int = 3)(f: => Future[A])(implicit ec: ExecutionContext): Future[A] = {
    def attempt(retries: Int): Future[A] =
      f.recoverWith {
        case DuplicateKey(e) if (retries > 0) =>
          logger.debug(s"Detected an E11000 duplicate key violation. Retrying upsert. Attempts left: $retries")
          attempt(retries - 1)
      }
    attempt(retries)
  }

  object IndexConflict {
    val IndexOptionsConflict  = 85 // e.g. change of ttl option
    val IndexKeySpecsConflict = 86 // e.g. change of field name
    def unapply(e: MongoCommandException): Option[MongoCommandException] =
      e.getErrorCode match {
        case IndexOptionsConflict
           | IndexKeySpecsConflict => Some(e)
        case _                     => None
      }
  }

  object IndexNotFound {
    val Code = 27
    def unapply(e: MongoCommandException): Option[MongoCommandException] =
      e.getErrorCode match {
        case Code => Some(e)
        case _    => None
      }
  }

  object DuplicateKey {
    val Code = 11000
    def unapply(e: MongoServerException): Option[MongoServerException] =
      e.getCode match {
        case Code => Some(e)
        case _    => None
      }
  }
}

object MongoUtils extends MongoUtils
