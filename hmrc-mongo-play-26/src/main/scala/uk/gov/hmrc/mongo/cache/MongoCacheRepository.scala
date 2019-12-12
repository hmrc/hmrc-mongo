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

package uk.gov.hmrc.mongo.cache

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject

import org.mongodb.scala.WriteConcern
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, IndexModel, IndexOptions, Indexes, ReturnDocument, Updates}
import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, JsObject, Reads, Writes, __}
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoCollection}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class MongoCacheRepository[CacheId] @Inject() (
  mongoComponent: MongoComponent,
  collectionName: String,
  rebuildIndexes: Boolean = true,
  ttl: Duration,
  timestampSupport: TimestampSupport,
  cacheIdType: CacheIdType[CacheId]
)(implicit ec: ExecutionContext)
    extends PlayMongoCollection(
      mongoComponent = mongoComponent,
      collectionName = collectionName,
      domainFormat   = MongoCacheRepository.format,
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

  def get[A: Reads](
    cacheId: CacheId
  )(dataKey: DataKey[A]): Future[Option[A]] = {
    val id = cacheIdType.run(cacheId)
    this.collection
      .find(Filters.equal("_id", id))
      .first()
      .toFutureOption()
      .map(_.flatMap(cache => (cache.data \ dataKey.unwrap).asOpt[A]))
  }

  def put[A: Writes](
    cacheId: CacheId
  )(dataKey: DataKey[A], data: A): Future[String] = {
    val id        = cacheIdType.run(cacheId)
    val timestamp = timestampSupport.timestamp()
    this.collection
      .findOneAndUpdate(
        filter = Filters.equal("_id", id),
        update = Updates.combine(
          Updates.set("data." + dataKey.unwrap, Codecs.toBson(data)),
          Updates.set("modifiedDetails.lastUpdated", timestamp),
          Updates.setOnInsert("_id", id),
          Updates.setOnInsert("modifiedDetails.createdAt", timestamp)
        ),
        options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
      )
      .toFuture()
      .map(_ => id)
  }

  def delete[A](
    cacheId: CacheId
  )(dataKey: DataKey[A]): Future[Unit] = {
    val id = cacheIdType.run(cacheId)
    this.collection
      .findOneAndUpdate(
        filter = Filters.equal("_id", id),
        update = Updates.combine(
          Updates.unset("data." + dataKey.unwrap),
          Updates.set("modifiedDetails.lastUpdated", timestampSupport.timestamp())
        )
      )
      .toFuture
      .map(_ => ())
  }

  def deleteEntity(cacheId: CacheId): Future[Unit] = {
    val id = cacheIdType.run(cacheId)
    this.collection
      .withWriteConcern(WriteConcern.ACKNOWLEDGED)
      .deleteOne(
        filter = Filters.equal("_id", id)
      )
      .toFuture()
      .map(_ => ())
  }
}

object MongoCacheRepository {
  val format: Format[CacheItem] = {
    implicit val dtf: Format[Instant] = MongoJavatimeFormats.instantFormats
    ((__ \ "_id").format[String]
      ~ (__ \ "data").format[JsObject]
      ~ (__ \ "modifiedDetails" \ "createdAt").format[Instant]
      ~ (__ \ "modifiedDetails" \ "lastUpdated").format[Instant])(CacheItem.apply, unlift(CacheItem.unapply))
  }
}
