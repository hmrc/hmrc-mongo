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

import org.bson.codecs.Codec
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.IndexModel
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, MongoUtils}

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

  /** Does the Repository manage it's own data cleanup - e.g. doesn't use a TTL index since expiry is data dependent */
  lazy val manageDataCleanup = false

  Await.result(ensureIndexes, 5.seconds)

  Await.result(ensureSchema, 5.seconds)

  def ensureIndexes: Future[Seq[String]] =
    for {
    res <- MongoUtils.ensureIndexes(collection, indexes, replaceIndexes)
    _   <- if (!manageDataCleanup)
             MongoUtils.checkTtl(mongoComponent, collectionName)
           else Future.unit
  } yield res

  def ensureSchema: Future[Unit] =
    // if schema is not defined, leave any existing ones
    if (optSchema.isDefined)
      MongoUtils.ensureSchema(mongoComponent, collection, optSchema)
    else
      Future.unit
}
