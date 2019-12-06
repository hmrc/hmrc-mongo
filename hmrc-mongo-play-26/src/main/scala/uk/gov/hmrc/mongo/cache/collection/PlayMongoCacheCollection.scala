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

package uk.gov.hmrc.mongo.cache.collection

import java.time.Instant
import java.util.concurrent.TimeUnit

import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.WriteConcern
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Updates.{combine, set, setOnInsert}
import org.mongodb.scala.model._
import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoCollection}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class PlayMongoCacheCollection[A: ClassTag](
  mongoComponent: MongoComponent,
  collectionName: String,
  domainFormat: Format[A],
  optRegistry: Option[CodecRegistry] = None,
  rebuildIndexes: Boolean            = true,
  ttl: Duration,
  timestampSupport: TimestampSupport
)(implicit ec: ExecutionContext)
    extends PlayMongoCollection(
      mongoComponent = mongoComponent,
      collectionName = collectionName,
      domainFormat   = PlayMongoCacheCollection.format(domainFormat),
      optRegistry    = None,
      indexes = Seq(
        IndexModel(
          Indexes.ascending("modifiedDetails.lastUpdated"),
          IndexOptions()
            .background(false)
            .name("lastUpdatedIndex")
            .expireAfter(ttl.toMillis, TimeUnit.MILLISECONDS)
        )
      ),
      rebuildIndexes = rebuildIndexes
    ) {

  private val logger = Logger(getClass)

  def find(id: String): Future[Option[CacheItem[A]]] =
    collection
      .find(equal("_id", id))
      .first()
      .toFutureOption()

  def remove(id: String): Future[Unit] =
    collection
      .withWriteConcern(WriteConcern.ACKNOWLEDGED)
      .deleteOne(
        filter = equal("_id", id)
      )
      .toFuture()
      .map(_ => ())

  def upsert(id: String, toCache: A): Future[Unit] = {
    val timestamp = timestampSupport.timestamp()
    collection
      .findOneAndUpdate(
        filter = equal("_id", id),
        update = combine(
          set("data", Codecs.toBson(toCache)(domainFormat)),
          set("modifiedDetails.lastUpdated", timestamp),
          setOnInsert("_id", id),
          setOnInsert("modifiedDetails.createdAt", timestamp)
        ),
        options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
      )
      .toFuture()
      .map(_ => ())
  }
}

object PlayMongoCacheCollection {
  def format[A: Format]: Format[CacheItem[A]] = {
    implicit val dtf: Format[Instant] = MongoJavatimeFormats.instantFormats
    ( (__ \ "_id"                            ).format[String]
    ~ (__ \ "data"                           ).format[A]
    ~ (__ \ "modifiedDetails" \ "createdAt"  ).format[Instant]
    ~ (__ \ "modifiedDetails" \ "lastUpdated").format[Instant]
    )(CacheItem.apply, unlift(CacheItem.unapply))
  }
}
