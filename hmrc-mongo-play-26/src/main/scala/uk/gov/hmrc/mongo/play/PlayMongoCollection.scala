/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.play

import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala._
import org.mongodb.scala.model.IndexModel
import play.api.Logger
import play.api.libs.json.Format
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.json.CollectionFactory

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.reflect.ClassTag

class PlayMongoCollection[A: ClassTag](
  mongoComponent: MongoComponent,
  val collectionName: String,
      domainFormat  : Format[A],
      optRegistry   : Option[CodecRegistry] = None,
  val indexes       : Seq[IndexModel]
  )(implicit ec: ExecutionContext) {

  private val logger = Logger(getClass)

  val collection: MongoCollection[A] =
    CollectionFactory.collection(mongoComponent.database, collectionName, domainFormat, optRegistry)

  Await.result(createIndexes(), 3.seconds)

  //todo can we ensure indexes exist before attempting to create them?
  def createIndexes(): Future[Seq[String]] =
    if (indexes.nonEmpty) {
      collection
        .createIndexes(indexes)
        .toFuture
        .recover {
          case throwable: Throwable =>
            logger.error("Failed to create indexes", throwable)
            Seq.empty
        }
    } else {
      logger.info("Skipping Mongo index creation as no indexes supplied")
      Future.successful(Seq.empty)
    }

}

object PlayMongoCollection {
  def apply[A: ClassTag](
    mongoComponent: MongoComponent,
    collectionName: String,
    domainFormat  : Format[A],
    optRegistry   : Option[CodecRegistry] = None,
    indexes       : Seq[IndexModel])(implicit ec: ExecutionContext): MongoCollection[A] =
    new PlayMongoCollection[A](mongoComponent, collectionName, domainFormat, optRegistry, indexes).collection
}
