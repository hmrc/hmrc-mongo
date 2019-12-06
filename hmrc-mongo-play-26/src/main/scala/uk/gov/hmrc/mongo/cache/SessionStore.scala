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

import com.google.inject.Inject
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, ReturnDocument, Updates}
import play.api.libs.json.{Format, JsValue, Reads, Writes}
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.cache.collection.PlayMongoCacheCollection
import uk.gov.hmrc.mongo.play.json.Codecs
import play.api.mvc.Session
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class SessionStore @Inject()(
    mongoComponent  : MongoComponent
  , collectionName  : String
  , ttl             : Duration
  , timestampSupport: TimestampSupport
  )(implicit ec: ExecutionContext)
  extends PlayMongoCacheCollection(
        mongoComponent   = mongoComponent
      , collectionName   = collectionName
      , domainFormat     = implicitly[Format[JsValue]]
      , ttl              = ttl
      , timestampSupport = timestampSupport
      ) {

  def get[T : Reads](
        session   : Session
      , sessionKey: String
      , dataKey   : String
      ): Future[Option[T]] =
      session.get(sessionKey) match {
          case None            => Future(None)
          case Some(sessionId) => find(sessionId).map { optCache =>
                                    optCache.flatMap(cache => (cache.data \ dataKey).asOpt[T])
                                  }
      }

  def put[T : Writes](
        session   : Session
      , sessionKey: String
      , dataKey   : String
      , data      : T
      ): Future[String] = {
    val sessionId = session.get(sessionKey).getOrElse(java.util.UUID.randomUUID.toString)
    val timestamp = timestampSupport.timestamp()
    collection
      .findOneAndUpdate(
          filter = Filters.equal("_id", sessionId)
        , update = Updates.combine(
                       Updates.set("data." + dataKey            , Codecs.toBson(data))
                     , Updates.set("modifiedDetails.lastUpdated", timestamp)
                     , Updates.setOnInsert("_id"                      , sessionId)
                     , Updates.setOnInsert("modifiedDetails.createdAt", timestamp)
                     )
        , options = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
      )
      .toFuture()
      .map(_ => sessionId)
    }

  def delete(session: Session, sessionKey: String, dataKey: String): Future[Unit] =
    session.get(sessionKey) match {
      case None            => Future(())
      case Some(sessionId) => collection
                                .findOneAndUpdate(
                                    filter = Filters.equal("_id", sessionId)
                                  , update = Updates.combine(
                                                 Updates.unset("data." + dataKey)
                                               , Updates.set("modifiedDetails.lastUpdated", timestampSupport.timestamp())
                                               )
                                )
                                .toFuture
                                .map(_ => ())
    }
}
