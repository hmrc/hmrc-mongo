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
import uk.gov.hmrc.mongo.{MongoComponent, MongoDatabaseCollection, TimestampSupport}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

/** A variation of [[MongoCacheRepository]] where there is a single
  * entity that is stored in the cache.
  */
class ShortLivedCacheRepository[A: ClassTag] @Inject()(
  mongoComponent: MongoComponent,
  val collectionName: String = "short-lived-cache",
  format: Format[A],
  ttl: Duration = 5.minutes, // TODO any reason to provide default value?
  timestampSupport: TimestampSupport)(
    implicit ec: ExecutionContext) extends MongoDatabaseCollection {

  private val cache = new MongoCacheRepository(
      mongoComponent   = mongoComponent,
      collectionName   = collectionName,
      ttl              = ttl,
      timestampSupport = timestampSupport
    )

  override def indexes: Seq[IndexModel] =
    cache.indexes

  private implicit val f = format

  val dataKey = "dataKey"

  def put(key: String, body: A): Future[Unit] =
    cache.put(key, dataKey, body)

  def get(key: String): Future[Option[A]] =
    cache.get(key, dataKey)

  def delete(key: String): Future[Unit] =
    cache.delete(key)
}
