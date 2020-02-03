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

package uk.gov.hmrc.mongo.play.json

import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala._
import org.mongodb.scala.model.IndexModel
import play.api.Logger
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class PlayMongoCollection[A: ClassTag](
  mongoComponent: MongoComponent,
  val collectionName: String,
  domainFormat: Format[A],
  optRegistry: Option[CodecRegistry] = None,
  val indexes: Seq[IndexModel],
  val optSchema: Option[Document] = None,
  rebuildIndexes: Boolean = false
)(implicit ec: ExecutionContext)
    extends MongoDatabaseCollection {

  private val logger = Logger(getClass)

  val collection: MongoCollection[A] =
    CollectionFactory.collection(mongoComponent.database, collectionName, domainFormat)

  Await.result(ensureIndexes, 5.seconds)

  Await.result(ensureSchema, 5.seconds)

  def ensureIndexes: Future[Seq[String]] = {
    if (indexes.isEmpty)
      logger.info("Skipping Mongo index creation as no indexes supplied")

    Future.traverse(indexes) { index =>
      collection
        .createIndex(index.getKeys, index.getOptions)
        .toFuture
        .recoverWith {
          case PlayMongoCollection.IndexConflict(e) if rebuildIndexes =>
            logger.warn("Conflicting Mongo index found. This index will be updated")
            for {
              _      <- collection.dropIndex(index.getOptions.getName).toFuture
              result <- collection.createIndex(index.getKeys, index.getOptions).toFuture
            } yield result
        }
    }
  }

  def ensureSchema: Future[Unit] =
    optSchema.fold(Future.successful(())){ schema =>
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
}

object PlayMongoCollection {
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
