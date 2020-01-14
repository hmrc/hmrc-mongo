/*
 * Copyright 2020 HM Revenue & Customs
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
import uk.gov.hmrc.mongo.throttle.{ThrottleConfig, WithThrottling}
import uk.gov.hmrc.mongo.play.json.PlayMongoCollection

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoMetricRepository])
trait MetricRepository {
  def persist(calculatedMetric: PersistedMetric): Future[Unit]
  def findAll(): Future[List[PersistedMetric]]
}

@Singleton
class MongoMetricRepository @Inject() (
    mongoComponent: MongoComponent,
    val throttleConfig: ThrottleConfig
  )(implicit ec: ExecutionContext)
    extends PlayMongoCollection[PersistedMetric](
      collectionName = "metrics",
      mongoComponent = mongoComponent,
      domainFormat   = PersistedMetric.format,
      indexes = Seq(
        IndexModel(ascending("name"), IndexOptions().name("metric_key_idx").unique(true).background(true))
      )
    )
    with MetricRepository
    with WithThrottling {

  override def findAll(): Future[List[PersistedMetric]] =
    collection.withReadPreference(ReadPreference.secondaryPreferred)
      .find()
      .toThrottledFuture()
      .map(_.toList)

  override def persist(calculatedMetric: PersistedMetric): Future[Unit] =
    collection
      .findOneAndReplace(
        filter = equal("name", calculatedMetric.name),
        calculatedMetric,
        FindOneAndReplaceOptions().upsert(true)
      )
      .toThrottledFutureOption()
      .map(_ => ())
}
