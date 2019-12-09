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

import org.bson.codecs.configuration.CodecRegistry
import org.mongodb.scala.WriteConcern
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, Indexes, IndexModel, IndexOptions, ReturnDocument, Updates}
import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __, JsObject, Reads, Writes}
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoCollection}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class MongoCacheRepository(
  mongoComponent: MongoComponent,
  collectionName: String,
  rebuildIndexes: Boolean            = true,
  ttl: Duration,
  timestampSupport: TimestampSupport
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
         cacheIdStrategy: CacheIdStrategy
      )( dataKey        : DataKey[A]
       ): Future[Option[A]] = {
    val cacheId = cacheIdStrategy.strategy()
    this.collection
      .find(Filters.equal("_id", cacheId.asString))
      .first()
      .toFutureOption()
      .map(_.flatMap(cache => (cache.data \ dataKey.asString).asOpt[A]))
  }

  def put[A : Writes](
         cacheIdStrategy: CacheIdStrategy
      )( dataKey        : DataKey[A]
       , data           : A
       ): Future[CacheId] = {
    val cacheId = cacheIdStrategy.strategy()
    val timestamp = timestampSupport.timestamp()
    this.collection
      .findOneAndUpdate(
          filter = Filters.equal("_id", cacheId.asString)
        , update = Updates.combine(
                       Updates.set("data." + dataKey.asString, Codecs.toBson(data))
                     , Updates.set("modifiedDetails.lastUpdated", timestamp)
                     , Updates.setOnInsert("_id", cacheId.asString)
                     , Updates.setOnInsert("modifiedDetails.createdAt", timestamp)
                     )
        , options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
        )
      .toFuture()
      .map(_ => cacheId)
    }

  def delete[A](
        cacheIdStrategy: CacheIdStrategy
      )(dataKey        : DataKey[A]
      ): Future[Unit] = {
    val cacheId = cacheIdStrategy.strategy()
    this.collection
      .findOneAndUpdate(
          filter = Filters.equal("_id", cacheId.asString)
        , update = Updates.combine(
                       Updates.unset("data." + dataKey.asString)
                     , Updates.set("modifiedDetails.lastUpdated", timestampSupport.timestamp())
                     )
        )
      .toFuture
      .map(_ => ())
      }

  def deleteEntity(cacheIdStrategy: CacheIdStrategy): Future[Unit] = {
    val cacheId = cacheIdStrategy.strategy()
    this.collection
      .withWriteConcern(WriteConcern.ACKNOWLEDGED)
      .deleteOne(
        filter = Filters.equal("_id", cacheId.asString)
      )
      .toFuture()
      .map(_ => ())
  }
}

object MongoCacheRepository {
  val format: Format[CacheItem] = {
    implicit val dtf: Format[Instant] = MongoJavatimeFormats.instantFormats
    ( (__ \ "_id"                            ).format[String]
    ~ (__ \ "data"                           ).format[JsObject]
    ~ (__ \ "modifiedDetails" \ "createdAt"  ).format[Instant]
    ~ (__ \ "modifiedDetails" \ "lastUpdated").format[Instant]
    )(CacheItem.apply, unlift(CacheItem.unapply))
  }
}
