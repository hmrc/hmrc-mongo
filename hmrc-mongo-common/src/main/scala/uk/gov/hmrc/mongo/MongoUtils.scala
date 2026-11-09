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

import com.mongodb.client.model.Accumulators
import org.bson.BsonType
import org.mongodb.scala.{Document, MongoCollection, MongoCommandException, MongoServerException, ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Aggregates, Filters, IndexModel, ValidationAction, ValidationLevel}
import org.slf4j.{Logger, LoggerFactory}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

trait MongoUtils {
  private val logger: Logger = LoggerFactory.getLogger(classOf[MongoUtils].getName)

  private def indexName(index: Document): String =
    index("name").asString.getValue

  def ensureIndexes[A](
    collection    : MongoCollection[A],
    indexes       : Seq[IndexModel],
    replaceIndexes: Boolean
  )(implicit ec: ExecutionContext
  ): Future[Seq[String]] =
    for {
      existingIndexes  <- collection.listIndexes().toFuture()
      orphanedIndexes  =  { // compare keys, since idx.getOptions.getName will be null if not set
                            val indexKeys = indexes.map(_.getKeys.toBsonDocument) :+ BsonDocument("_id" -> 1)
                            existingIndexes.filterNot(existingIndex => indexKeys.contains(existingIndex("key").asDocument))
                          }
      _                <- if (orphanedIndexes.nonEmpty && replaceIndexes)
                            Future.traverse(orphanedIndexes) { orphanIdx =>
                              logger.warn(s"Index '${indexName(orphanIdx)}' key: '${orphanIdx("key")}' is no longer defined for ${collection.namespace} - dropping index")
                              collection
                                .dropIndex(indexName(orphanIdx))
                                .toFuture()
                                .recoverWith {
                                  // could be caused by race conditions between server instances
                                  case IndexNotFound(e) => Future.unit
                                }
                             }
                          else if (orphanedIndexes.nonEmpty) {
                            val str =
                              orphanedIndexes
                                .map(orphanIdx => s"index: '${indexName(orphanIdx)}' key: '${orphanIdx("key")}'")
                                .mkString(", ")
                            Future.successful(logger.warn(s"The following indexes exist in mongo for ${collection.namespace}, but are (no longer) provided to ensureIndexes: $str"))
                          } else
                            Future.unit
      res              <- Future.traverse(indexes) { index =>
                            collection
                              .createIndex(index.getKeys, index.getOptions)
                              .toFuture()
                              .recoverWith {
                                // we recover from `IndexConflict` rather than predicting if requested IndexModel matches the existing Index
                                // since the returned Index is a BsonDocument - and also not all fields cause conflicts.
                                case IndexConflict(e) if replaceIndexes =>
                                  val conflictingIdxName =
                                    existingIndexes.find(idx => idx("key").asDocument == index.getKeys.toBsonDocument).map(indexName)
                                      // this shouldn't happen - if it was the same name, but different definition, it would have been dropped by now for replaceIndexes = true
                                      .getOrElse(sys.error(s"Could not find the conflicting index ${index.getKeys.toBsonDocument}"))
                                  logger.warn(s"Conflicting Mongo index found. Index '${conflictingIdxName}' in ${collection.namespace} will be recreated")
                                  for {
                                    _      <- collection.dropIndex(conflictingIdxName).toFuture()
                                    result <- collection.createIndex(index.getKeys, index.getOptions).toFuture()
                                  } yield result
                                case IndexConflict(e) =>
                                    logger.error(s"Conflicting Mongo index found", e)
                                    throw e
                              }
                            }
    } yield res

  /**
   * Logs warnings related to the state of the TTL indexes on the collection.
   * @param checkType true will additionally check for invalid data in the TTL's key field.
   */
  private[mongo] def checkTtlIndex(
                                    mongoComponent: MongoComponent,
                                    collectionName: String,
                                    checkType     : Boolean
                                  )(implicit ec: ExecutionContext): Future[Unit] = {

    val collection = mongoComponent.database.getCollection(collectionName)

    getTTLIndexes(collection).map { ttls =>
      if (ttls.isEmpty) logger.warn(s"No ttl indexes were found for collection $collectionName")
      else if (checkType) {
        checkKeyFieldDataForTTLs(collection, ttls)
          .map(_.foreach(warning => logger.warn(s"There are ${warning.message}")))
        }
    }
  }

  private[mongo] def getTTLIndexes(
     collection    : MongoCollection[Document],
  )(implicit ec: ExecutionContext): Future[Seq[Document]] =
   for {
     indexes <- collection.listIndexes().toFuture()
   } yield indexes.filter(i => i.contains("expireAfterSeconds"))

  private[mongo] def checkKeyFieldDataForTTLs(
                                               collection: MongoCollection[Document],
                                               ttls: Seq[Document]
                                             )(implicit ec: ExecutionContext): Future[List[TtlWarning]] = {
    val warnings = ttls.map { (ttl: Document) =>
      val keyField: String = ttl("key").asDocument.getFirstKey // ttl indices are single-field indices
      val indexName: String = ttl.getString("name")
      checkKeyFieldData(collection, keyField, indexName)
    }
    Future.sequence(warnings).map(_.flatten.toList)
  }

  private[mongo] def checkKeyFieldData(
   collection: MongoCollection[Document],
   keyField: String,
   indexName: String
  )(implicit ec: ExecutionContext): Future[List[TtlWarning]] = {

    collection.find().comment(MongoComment.NoIndexRequired).limit(1).headOption().map(_.isDefined).flatMap{ hasData =>
      if (hasData) {
        val collectionName = collection.namespace.getCollectionName
        val isANonDateType: List[BsonType] = BsonType.values.toList.filterNot(Set(BsonType.DATE_TIME, BsonType.END_OF_DOCUMENT, BsonType.MIN_KEY, BsonType.MAX_KEY ))
        val isANonDateTypeOrNull = Filters.or(
          Filters.equal(keyField, null) +: isANonDateType.map(t => Filters.`type`(keyField, t)): _*
        )

        collection
          .aggregate[BsonDocument](Seq(
            Aggregates.`match`(isANonDateTypeOrNull),
            Aggregates.group(BsonDocument("$type" -> s"$$$keyField"), Accumulators.sum("n", 1))
          ))
          .hintString(indexName)
          .maxTime(Duration(10, TimeUnit.SECONDS))
          .toFuture()
          .map(_.map {
            case d if d.getString("_id").getValue == "null" => TtlWarning.NullKeyField(collectionName, keyField, d.getInt32("n").getValue)
            case d if d.getString("_id").getValue == "missing" => TtlWarning.MissingKeyField(collectionName, keyField, d.getInt32("n").getValue)
            case d => TtlWarning.InvalidType(collectionName, keyField, d.getString("_id").getValue, d.getInt32("n").getValue)
          }.toList)
      } else Future.successful(List[TtlWarning]())
    }
  }

  def existsCollection[A](
      mongoComponent: MongoComponent,
      collection    : MongoCollection[A]
    )(implicit ec: ExecutionContext
    ): Future[Boolean] =
      for {
        collections <- mongoComponent.database.listCollectionNames().toFuture()
      } yield collections.contains(collection.namespace.getCollectionName)


  /** Create the schema if defined, or remove if not defined.
    * Note, the collection will be created if it does not exist yet.
    */
  def ensureSchema[A](
      mongoComponent: MongoComponent,
      collection    : MongoCollection[A],
      optSchema     : Option[BsonDocument]
    )(implicit ec: ExecutionContext
    ): Future[Unit] =
      for {
        _       <- Future.successful(logger.info(s"Ensuring ${collection.namespace} has ${optSchema.fold("no")(_ => "a")} jsonSchema"))
        exists  <- existsCollection(mongoComponent, collection)
        _       <- if (!exists)
                     mongoComponent.database.createCollection(collection.namespace.getCollectionName).toFuture()
                   else
                     Future.unit
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
        _       <- mongoComponent.database.runCommand(collMod).toFuture()
       } yield ()

  object IndexConflict {
    val IndexOptionsConflict  = 85 // e.g. change of index name or ttl option
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

protected[mongo] sealed trait TtlWarning {
  val message: String
}
object TtlWarning {
  case class InvalidType(collectionName: String, keyField: String, dataType: String, count: Int) extends TtlWarning {
    val message: String =
      s"$count documents in collection $collectionName where TTL index key field $keyField contains nonDate data of type '$dataType'"
  }
  case class MissingKeyField(collectionName: String, keyField: String, count: Int) extends TtlWarning {
    val message: String =
      s"$count documents in collection $collectionName where TTL index key field $keyField is missing data"
  }
  case class NullKeyField(collectionName: String, keyField: String, count: Int) extends TtlWarning {
    val message: String =
      s"$count documents in collection $collectionName where TTL index key field $keyField is null"
  }
}