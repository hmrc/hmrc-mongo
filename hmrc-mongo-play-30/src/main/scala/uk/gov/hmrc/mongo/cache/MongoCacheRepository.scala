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

package uk.gov.hmrc.mongo.cache

import org.bson.codecs.Codec
import org.mongodb.scala.{WriteConcern, SingleObservableFuture}
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, IndexModel, IndexOptions, Indexes, ReturnDocument, Updates}
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, JsObject, JsPath, JsResultException, Reads, Writes, __}
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class MongoCacheRepository[CacheId](
  mongoComponent  : MongoComponent,
  collectionName  : String,
  replaceIndexes  : Boolean = true,
  ttl             : Duration,
  timestampSupport: TimestampSupport,
  cacheIdType     : CacheIdType[CacheId],
  extraIndexes    : Seq[IndexModel] = Seq.empty,
  extraCodecs     : Seq[Codec[_]]   = Seq.empty
)(implicit
  ec              : ExecutionContext
) extends PlayMongoRepository[CacheItem](
  mongoComponent = mongoComponent,
  collectionName = collectionName,
  domainFormat   = MongoCacheRepository.format,
  indexes        = Seq(
                     IndexModel(
                       Indexes.ascending("modifiedDetails.lastUpdated"),
                       IndexOptions()
                         .name("lastUpdatedIndex")
                         .expireAfter(ttl.toMillis, TimeUnit.MILLISECONDS)
                     )
                   ) ++ extraIndexes,
  replaceIndexes = replaceIndexes,
  extraCodecs    = extraCodecs
) {

  def findById(cacheId: CacheId): Future[Option[CacheItem]] = {
    val id = cacheIdType.run(cacheId)
    collection
      .find(Filters.equal("_id", id))
      .headOption()
  }

  def get[A: Reads](
    cacheId: CacheId
  )(dataKey: DataKey[A]): Future[Option[A]] = {
    def dataPath: JsPath =
      dataKey.unwrap.split('.').foldLeft[JsPath](JsPath)(_ \ _)
    findById(cacheId)
      .map(
        _.flatMap(cache =>
          dataPath.asSingleJson(cache.data)
            .validateOpt[A]
            .fold(e => throw JsResultException(e), identity)
        )
      )
  }

  def put[A: Writes](
    cacheId: CacheId
  )(dataKey: DataKey[A],
    data   : A
  ): Future[CacheItem] = {
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
      .toFuture()
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
    implicit val dtf: Format[Instant] = MongoJavatimeFormats.instantFormat
    ( (__ \ "_id"                            ).format[String]
    ~ (__ \ "data"                           ).format[JsObject]
    ~ (__ \ "modifiedDetails" \ "createdAt"  ).format[Instant]
    ~ (__ \ "modifiedDetails" \ "lastUpdated").format[Instant]
    )(CacheItem.apply, ci => (ci.id, ci.data, ci.createdAt, ci.modifiedAt))
  }
}
