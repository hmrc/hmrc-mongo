/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.metrix

import com.google.inject.{ImplementedBy, Inject, Singleton}
import org.mongodb.scala.ReadPreference
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions}
import org.mongodb.scala.model.Indexes.ascending
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoMetricRepository])
trait MetricRepository {
  def persist(metric: PersistedMetric): Future[Unit]
  def findAll(): Future[List[PersistedMetric]]
  def delete(metricName: String): Future[Unit]
}

@Singleton
class MongoMetricRepository @Inject() (
    mongoComponent: MongoComponent
  )(implicit ec: ExecutionContext)
    extends PlayMongoRepository[PersistedMetric](
      collectionName = "metrics",
      mongoComponent = mongoComponent,
      domainFormat   = PersistedMetric.format,
      indexes = Seq(
        IndexModel(ascending("name"), IndexOptions().name("metric_key_idx").unique(true).background(true))
      )
    )
    with MetricRepository {

  override def findAll(): Future[List[PersistedMetric]] =
    collection.withReadPreference(ReadPreference.secondaryPreferred())
      .find()
      .toFuture()
      .map(_.toList)

  override def persist(metric: PersistedMetric): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = equal("name", metric.name),
        replacement = metric,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())

  override def delete(metricName: String): Future[Unit] =
    collection
      .deleteOne(
        filter = equal("name", metricName)
      ).toFuture()
      .map(_ => ())
}
