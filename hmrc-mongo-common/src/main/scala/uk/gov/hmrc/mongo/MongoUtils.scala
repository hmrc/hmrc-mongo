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

package uk.gov.hmrc.mongo

import org.mongodb.scala.{Document, MongoCollection, MongoCommandException}
import org.mongodb.scala.model.IndexModel
import org.mongodb.scala.Document
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}


trait MongoUtils {
  val logger: Logger = LoggerFactory.getLogger(classOf[MongoUtils].getName)

  def ensureIndexes[A](
      collection: MongoCollection[A],
      indexes: Seq[IndexModel],
      rebuildIndexes: Boolean
      )(implicit ec: ExecutionContext
      ): Future[Seq[String]] =
    Future.traverse(indexes) { index =>
      collection
        .createIndex(index.getKeys, index.getOptions)
        .toFuture
        .recoverWith {
          case IndexConflict(e) if rebuildIndexes =>
            logger.warn("Conflicting Mongo index found. This index will be updated")
            for {
              _      <- collection.dropIndex(index.getOptions.getName).toFuture
              result <- collection.createIndex(index.getKeys, index.getOptions).toFuture
            } yield result
        }
    }

  def ensureSchema(
      mongoComponent: MongoComponent,
      collectionName: String,
      schema: Document
    )(implicit ec: ExecutionContext
    ): Future[Unit] = {
      logger.info("Applying schema")
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
    }

  object IndexConflict {
    val IndexOptionsConflict  = 85 // e.g. change of ttl option
    val IndexKeySpecsConflict = 86 // e.g. change of field name
    def unapply(e: MongoCommandException): Option[MongoCommandException] =
      e.getErrorCode match {
        case IndexOptionsConflict | IndexKeySpecsConflict => Some(e)
        case _                                            => None
      }
  }
}

object MongoUtils extends MongoUtils