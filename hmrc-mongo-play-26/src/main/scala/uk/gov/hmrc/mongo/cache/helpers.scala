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

import play.api.libs.json.{Format, Reads, Writes}
import play.api.mvc.Request
import scala.concurrent.{ExecutionContext, Future}

/** CacheId is stored in session with sessionIdKey */
trait SessionCache {
  val cacheRepo   : MongoCacheRepository
  val sessionIdKey: String

  def putSession[T : Writes](dataKey: DataKey[T], data: T)(implicit request: Request[Any], ec: ExecutionContext): Future[(String, String)] =
    cacheRepo.put[T](CacheIdStrategy.sessionUuid(sessionIdKey))(dataKey, data)
      .map(sessionIdKey -> _.asString)

  def getFromSession[T : Reads](dataKey: DataKey[T])(implicit request: Request[Any]): Future[Option[T]] =
    cacheRepo.get[T](CacheIdStrategy.sessionUuid(sessionIdKey))(dataKey)

  def deleteFromSession[T](dataKey: DataKey[T])(implicit request: Request[Any]): Future[Unit] =
    cacheRepo.delete(CacheIdStrategy.sessionUuid(sessionIdKey))(dataKey)
}

 /** CacheId is provided */
trait SimpleCache {
  val cacheRepo: MongoCacheRepository

  def putCache[T : Writes](cacheId: CacheId, dataKey: DataKey[T], data: T)(implicit ec: ExecutionContext): Future[Unit] =
    cacheRepo.put[T](CacheIdStrategy.const(cacheId))(dataKey, data)
      .map(_ => ())

  def getFromCache[T : Reads](cacheId: CacheId, dataKey: DataKey[T]): Future[Option[T]] =
    cacheRepo.get[T](CacheIdStrategy.const(cacheId))(dataKey)

  def deleteFromCache[T](cacheId: CacheId, dataKey: DataKey[T]): Future[Unit] =
    cacheRepo.delete(CacheIdStrategy.const(cacheId))(dataKey)
}

 /** CacheId is provided and a single entity is stored in the cache */
trait SimpleEntityCache[A] {
  val cacheRepo : MongoCacheRepository
  val format    : Format[A]

  private implicit val f = format
  private val dataKey = DataKey[A]("dataKey")

  def putCache(cacheId: CacheId, data: A)(implicit ec: ExecutionContext): Future[Unit] =
    cacheRepo.put[A](CacheIdStrategy.const(cacheId))(dataKey, data)
      .map(_ => ())

  def getFromCache(cacheId: CacheId): Future[Option[A]] =
    cacheRepo.get[A](CacheIdStrategy.const(cacheId))(dataKey)

  def deleteFromCache(cacheId: CacheId): Future[Unit] =
    cacheRepo.delete(CacheIdStrategy.const(cacheId))(dataKey)
}

/** CacheId is tied to sessionId and a single entity is stored in the cache */
trait SessionEntityCache[A] {
  val cacheRepo : MongoCacheRepository
  val format    : Format[A]

  private implicit val f = format
  private val dataKey = DataKey[A]("dataKey")

  def putSession(data: A)(implicit request: Request[Any], ec: ExecutionContext): Future[Unit] =
    cacheRepo.put[A](CacheIdStrategy.sessionId)(dataKey, data)
      .map(_ => ())

  def getFromSession(implicit request: Request[Any]): Future[Option[A]] =
    cacheRepo.get[A](CacheIdStrategy.sessionId)(dataKey)

  def deleteFromSession(implicit request: Request[Any]): Future[Unit] =
    cacheRepo.delete(CacheIdStrategy.sessionId)(dataKey)
}