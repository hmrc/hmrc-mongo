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
import play.api.libs.json.{Reads, Writes}
import play.api.mvc.Request
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, TimestampSupport}

/** A variation of [[MongoCacheRepository]] where the key is generated and stored in the session.
*/
class SessionCacheRepository @Inject()(
  mongoComponent: MongoComponent,
  val collectionName: String,
  ttl: Duration,
  timestampSupport: TimestampSupport,
  val sessionIdKey: String)(
  implicit ec: ExecutionContext) extends MongoDatabaseCollection {

  val cacheRepo = new MongoCacheRepository(
        mongoComponent   = mongoComponent
      , collectionName   = collectionName
      , ttl              = ttl
      , timestampSupport = timestampSupport
      )

  override def indexes: Seq[IndexModel] =
    cacheRepo.indexes

  private def cacheId(implicit request: Request[Any]): Option[CacheId] =
    request.session.get(sessionIdKey).map(CacheId.apply)

  def put[T : Writes](key: DataKey[T], data: T)(implicit request: Request[Any], ec: ExecutionContext): Future[(String, String)] = {
    val sessionId = cacheId.getOrElse(CacheId(java.util.UUID.randomUUID.toString))
    cacheRepo.put[T](sessionId, key, data)
      .map(_ => (sessionIdKey, sessionId.asString))
  }

  def get[T : Reads](key: DataKey[T])(implicit request: Request[Any]): Future[Option[T]] =
    cacheId.fold[Future[Option[T]]](Future.successful(None))(cacheRepo.get(_, key))

  def delete[T](key: DataKey[T])(implicit request: Request[Any]): Future[Unit] =
    cacheId.fold(Future.successful(()))(cacheRepo.delete(_, key))
}