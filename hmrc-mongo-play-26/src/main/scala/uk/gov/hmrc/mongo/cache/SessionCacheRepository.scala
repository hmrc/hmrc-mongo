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
import play.api.libs.json.Format
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.cache.collection.PlayMongoCacheCollection
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class SessionCacheRepository[A: ClassTag] @Inject()(
  mongoComponent: MongoComponent,
  collectionName: String = "session-cache",
  format: Format[A],
  ttl: Duration = 5.minutes, // TODO any reason to provide default value?
  timestampSupport: TimestampSupport)(implicit ec: ExecutionContext)
    extends PlayMongoCacheCollection(
      mongoComponent   = mongoComponent,
      collectionName   = collectionName,
      ttl              = ttl,
      timestampSupport = timestampSupport
    ) {

  val dataKey = "dataKey"

  private def cacheId(implicit hc: HeaderCarrier): Future[String] =
    hc.sessionId.fold(
      Future.failed[String](NoSessionException))(
        sid => Future.successful(sid.value))

  def cache(body: A)(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      key    <- cacheId
      result <- put(key, dataKey, body)(format)
    } yield result

  def fetch()(implicit hc: HeaderCarrier): Future[Option[A]] =
    for {
      id     <- cacheId
      result <- get(id, dataKey)(format)
    } yield result

  def remove()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      id     <- cacheId
      result <- delete(id)
    } yield result
}

case object NoSessionException extends Exception("Could not find sessionId in HeaderCarrier")