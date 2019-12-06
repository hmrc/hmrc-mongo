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
import play.api.libs.json.{Reads, Writes}
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.cache.collection.PlayMongoCacheCollection
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
      , ttl              = ttl
      , timestampSupport = timestampSupport
      ) {

  def get[T : Reads](
        session   : Session
      , sessionKey: String
      , dataKey   : String
      ): Future[Option[T]] =
    session.get(sessionKey)
      .fold[Future[Option[T]]](Future(None))(get(_, dataKey))

  def put[T : Writes](
        session   : Session
      , sessionKey: String
      , dataKey   : String
      , data      : T
      ): Future[String] = {
    val sessionId = session.get(sessionKey).getOrElse(java.util.UUID.randomUUID.toString)
    put(sessionId, dataKey, data)
      .map(_ => sessionId)
    }

  def delete(
        session   : Session
      , sessionKey: String
      , dataKey   : String
      ): Future[Unit] =
    session.get(sessionKey)
      .fold(Future(()))(delete(_, dataKey))
}
