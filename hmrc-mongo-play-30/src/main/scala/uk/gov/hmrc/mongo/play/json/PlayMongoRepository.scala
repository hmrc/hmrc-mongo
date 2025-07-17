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
import uk.gov.hmrc.mongo.logging.ObservableFutureImplicits

import java.util.concurrent.TimeoutException
import scala.concurrent.{Await, ExecutionContext, Future}
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
  mongoComponent          : MongoComponent,
  final val collectionName: String,
  final val domainFormat  : Format[A],
  final val indexes       : Seq[IndexModel],
  final val optSchema     : Option[BsonDocument] = None,
  replaceIndexes          : Boolean = false,
  extraCodecs             : Seq[Codec[_]] = Seq.empty
)(implicit ec: ExecutionContext)
    extends MongoDatabaseCollection
       with ObservableFutureImplicits {

  private val logger = play.api.Logger(getClass)

  lazy val collection: MongoCollection[A] =
    CollectionFactory.collection(mongoComponent.database, collectionName, domainFormat, extraCodecs)

  /** Can be overridden if the repository manages it's own data cleanup.
    *
    * A comment should be added to document why the repository does't require a ttl index if overriding.
    */
  protected[mongo] lazy val requiresTtlIndex = true

  lazy val initialised: Future[Unit] =
    for {
      _ <- ensureSchema()
      _ <- ensureIndexes()
    } yield logger.info(s"Collection $collectionName has initialised")

  private val initTimeout = mongoComponent.initTimeout
  try {
    // We await to ensure failures are propagated on the constructor thread, but we
    // don't care about long running index creation.
    Await.result(initialised, initTimeout)
  } catch {
    case _: TimeoutException => logger.warn(s"Index creation is taking longer than ${initTimeout.toSeconds} s")
    case t: Throwable        => logger.error(s"Failed to initialise collection $collectionName: ${t.getMessage}", t); throw t
  }

  def ensureIndexes(): Future[Seq[String]] =
    for {
      res <- MongoUtils.ensureIndexes(collection, indexes, replaceIndexes)
      _   <- if (requiresTtlIndex)
               MongoUtils.checkTtlIndex(mongoComponent, collectionName, checkType = false)
             else Future.unit
    } yield res

  def ensureSchema(): Future[Unit] =
    // if schema is not defined, leave any existing ones
    if (optSchema.isDefined)
      MongoUtils.ensureSchema(mongoComponent, collection, optSchema)
    else
      Future.unit
}
