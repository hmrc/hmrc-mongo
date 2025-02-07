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

package uk.gov.hmrc.mongo.metrix

import com.google.inject.ImplementedBy
import org.mongodb.scala.{ObservableFuture, ReadPreference, SingleObservableFuture}
import org.mongodb.scala.model.{DeleteOneModel, IndexModel, IndexOptions, ReplaceOneModel, ReplaceOptions}
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes.ascending
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoMetricRepository])
trait MetricRepository {
  def findAll(): Future[List[PersistedMetric]]
  def putAll(metrics: Seq[PersistedMetric]): Future[Unit]
}

@Singleton
class MongoMetricRepository @Inject() (
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[PersistedMetric](
  collectionName = "metrics",
  mongoComponent = mongoComponent,
  domainFormat   = PersistedMetric.format,
  indexes        = Seq(
                     IndexModel(ascending("name"), IndexOptions().name("metric_key_idx").unique(true))
                   )
) with MetricRepository {

  override lazy val requiresTtlIndex = false // we periodically find and replace/delete all metrics

  override def findAll(): Future[List[PersistedMetric]] =
    collection.withReadPreference(ReadPreference.secondaryPreferred())
      .find()
      .toFuture()
      .map(_.toList)

  override def putAll(metrics: Seq[PersistedMetric]): Future[Unit] =
    for {
      persistedMetrics  <- findAll()
      _                 <- if (metrics.isEmpty)
                             Future.unit
                           else
                             collection.bulkWrite(metrics.map(metric =>
                              ReplaceOneModel(
                                filter         = equal("name", metric.name),
                                replacement    = metric,
                                replaceOptions = ReplaceOptions().upsert(true)
                              )
                            )).toFuture()
      metricsToToDelete =  persistedMetrics.map(_.name).toSet.diff(metrics.map(_.name).toSet).toSeq
      _                 <- if (metricsToToDelete.isEmpty)
                             Future.unit
                           else
                             collection.bulkWrite(metricsToToDelete.map(metricName =>
                               DeleteOneModel(
                                 filter      = equal("name", metricName),
                               )
                             )).toFuture()
    } yield ()
}
