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

import org.mongodb.scala.{Document, MongoCollection}
import org.mongodb.scala.model.IndexModel
import play.api.Logger
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, MongoUtils}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class PlayMongoCollection[A: ClassTag](
  mongoComponent: MongoComponent,
  val collectionName: String,
  domainFormat: Format[A],
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
    MongoUtils.ensureIndexes(collection, indexes, rebuildIndexes)
  }

  def ensureSchema: Future[Unit] =
    optSchema.fold(Future.successful(()))(schema =>
      MongoUtils.ensureSchema(mongoComponent, collection, schema)
    )
}