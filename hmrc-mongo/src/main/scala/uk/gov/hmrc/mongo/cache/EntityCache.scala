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

import play.api.libs.json.Format
import scala.concurrent.{ExecutionContext, Future}

/** A single entity is stored in the cache.
  * Akin to previous ShortLivedCache/Save4Later.
  */
trait EntityCache[CacheId, A] {
  val cacheRepo: MongoCacheRepository[CacheId]
  val format: Format[A]

  private implicit val f = format
  private val dataKey    = DataKey[A]("dataKey")

  def putCache(cacheId: CacheId)(data: A)(implicit ec: ExecutionContext): Future[Unit] =
    cacheRepo
      .put[A](cacheId)(dataKey, data)
      .map(_ => ())

  def getFromCache(cacheId: CacheId): Future[Option[A]] =
    cacheRepo.get[A](cacheId)(dataKey)

  def deleteFromCache(cacheId: CacheId): Future[Unit] =
    cacheRepo.delete(cacheId)(dataKey)
}
