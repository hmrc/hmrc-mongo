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

package uk.gov.hmrc.mongo.play.json

import org.bson.BsonType
import org.bson.codecs.Codec
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.{Filters, IndexModel}
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, MongoUtils}

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

/** Initialise a mongo repository.
  * @param mongoComponent
  * @param collectionName the name of the mongo collection.
  * @param domainFormat a play Json format to map the domain to mongo entities.
  * @param indexes indexes to ensure are created.
  * @param optSchema optional - to validate entities written to the collection
  * @param replaceIndexes optional - default is false
  *   If true, existing indices should be removed/updated to match the provided indices.
  *   If false, any old indices are left behind, and indices with changed definitions will throw IndexConflict exceptions.
  * @param extraCodecs optional - to support more types
  */
class PlayMongoRepository[A: ClassTag](
  mongoComponent: MongoComponent,
  final val collectionName: String,
  final val domainFormat: Format[A],
  final val indexes: Seq[IndexModel],
  final val optSchema: Option[BsonDocument] = None,
  replaceIndexes: Boolean = false,
  extraCodecs   : Seq[Codec[_]] = Seq.empty
)(implicit ec: ExecutionContext)
    extends MongoDatabaseCollection {

  lazy val collection: MongoCollection[A] =
    CollectionFactory.collection(mongoComponent.database, collectionName, domainFormat, extraCodecs)

  Await.result(ensureIndexes, 5.seconds)

  Await.result(ensureSchema, 5.seconds)

  def ensureIndexes: Future[Seq[String]] =
    MongoUtils.ensureIndexes(collection, indexes, replaceIndexes)

  def checkTtl: Future[Map[String, String]] = {
    // TODO what if ttl is handled manually (e.g. MongoLockRepository) - do we need to control when this
    // check runs? Just in tests (on demand)?
    println(s">>>>In checkTtl")
    indexes.foldLeft(Future.successful(Map.empty[String, String])){ (acc, idx) =>
      println(s"keys: ${idx.getKeys}")
      for {
        a <- acc
        r <- Future.traverse[java.lang.Long, (String, String), List](
               Option(idx.getOptions.getExpireAfter(TimeUnit.MILLISECONDS)).toList
             ){ expireAfter =>
               println(s"will expire $expireAfter ms")
               val key = idx.getKeys.toBsonDocument.getFirstKey // ttl indices are single-field indices

               for {
                hasData     <- mongoComponent.database.getCollection(collectionName).find().headOption().map(_.isDefined)
                optDataType <- if (hasData)
                                 mongoComponent.database.getCollection(collectionName)
                                  .find(Filters.and(
                                    Filters.exists(key),
                                    Filters.not(Filters.`type`(key, BsonType.DATE_TIME))
                                  ))
                                  .projection(BsonDocument("type" ->  BsonDocument("$type" -> s"$$$key")))
                                  .headOption
                                  .map(_.flatMap(_.get[BsonString]("type").map(_.getValue)))
                               else
                                 Future.successful(Option.empty[String])
               } yield key -> (if (!hasData) "no-data" else optDataType.getOrElse("date"))
             }
      } yield a ++ r
    }.map { res =>
      if (res.isEmpty)
        play.api.Logger(getClass).warn(s"No ttl indices were found for collection $collectionName")
      else
        res.map {
          case (k, "date"   ) => // ok (TODO array that contains "date values" is also valid)
          case (k, "no-data") => // could not verify yet
          case (k, v        ) => play.api.Logger(getClass).warn(s"ttl index for collection $collectionName points at $k which has type '$v', it should be 'date'")
        }
      res
    }
  }
  // result:
    // No ttl (Bad) - e.g. None
    // Misconfigured ttl (Bad) - e.g. Some(Map(key -> bad type))
    // Good ttl - e.g. Some(Map.empty)

    // or list of errors?
    // NoTtl
    // Ttl

    // currently:
      // Map.empty -> no ttl
      // Map(key -> state): state = field type (only date (or array with date) is acceptable)
      //                    state = No-data (we don't know what the field type is) - this could be bad if we know there is data?

  def ensureSchema: Future[Unit] =
    // if schema is not defined, leave any existing ones
    if (optSchema.isDefined)
      MongoUtils.ensureSchema(mongoComponent, collection, optSchema)
    else
      Future.unit
}
