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
import org.mongodb.scala.model.IndexModel
import play.api.libs.json.Format
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, TimestampSupport}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class SessionCacheRepository[A: ClassTag] @Inject()(
  mongoComponent: MongoComponent,
  val collectionName: String = "session-cache",
  format: Format[A],
  ttl: Duration = 5.minutes, // TODO any reason to provide default value?
  timestampSupport: TimestampSupport)(
    implicit ec: ExecutionContext) extends MongoDatabaseCollection {

  private val cache = new ShortLivedCacheRepository(
      mongoComponent   = mongoComponent,
      collectionName   = collectionName,
      format           = format,
      ttl              = ttl,
      timestampSupport = timestampSupport
    )

  override def indexes: Seq[IndexModel] =
    cache.indexes

  private def cacheId(implicit hc: HeaderCarrier): String =
    hc.sessionId
      .fold(throw NoSessionException)(_.value)

  def put(body: A)(implicit hc: HeaderCarrier): Future[Unit] =
    cache.put(cacheId, body)

  def get()(implicit hc: HeaderCarrier): Future[Option[A]] =
    cache.get(cacheId)

  def delete()(implicit hc: HeaderCarrier): Future[Unit] =
    cache.delete(cacheId)
}

case object NoSessionException extends Exception("Could not find sessionId in HeaderCarrier")