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

import javax.inject.Inject
import org.mongodb.scala.model.IndexModel
import play.api.libs.json.{Reads, Writes}
import play.api.mvc.Request
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, TimestampSupport}

/** CacheId is stored in session with sessionIdKey */
class SessionCacheRepository @Inject() (
  mongoComponent: MongoComponent,
  override val collectionName: String,
  replaceIndexes: Boolean = true,
  ttl: Duration,
  timestampSupport: TimestampSupport,
  sessionIdKey: String
)(implicit ec: ExecutionContext)
    extends MongoDatabaseCollection {
  val cacheRepo = new MongoCacheRepository[Request[Any]](
    mongoComponent   = mongoComponent,
    collectionName   = collectionName,
    replaceIndexes   = replaceIndexes,
    ttl              = ttl,
    timestampSupport = timestampSupport,
    cacheIdType      = CacheIdType.SessionUuid(sessionIdKey)
  )

  override val indexes: Seq[IndexModel] =
    cacheRepo.indexes

  /** @return (sessionIdKey, sessionId) - since the sessionId would have been generated if not available in session,
    * it is returned for storage in session.
    */
  def putSession[T: Writes](
    dataKey: DataKey[T],
    data: T
  )(implicit request: Request[Any], ec: ExecutionContext): Future[(String, String)] =
    cacheRepo
      .put[T](request)(dataKey, data)
      .map(res => sessionIdKey -> res.id)

  def getFromSession[T: Reads](dataKey: DataKey[T])(implicit request: Request[Any]): Future[Option[T]] =
    cacheRepo.get[T](request)(dataKey)

  def deleteFromSession[T](dataKey: DataKey[T])(implicit request: Request[Any]): Future[Unit] =
    cacheRepo.delete(request)(dataKey)
}
