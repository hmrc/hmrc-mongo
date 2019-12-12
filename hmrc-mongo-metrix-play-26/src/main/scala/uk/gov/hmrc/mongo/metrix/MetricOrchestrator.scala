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

package uk.gov.hmrc.mongo.metrix

import java.util.concurrent.ConcurrentHashMap

import com.codahale.metrics.{Gauge, MetricRegistry}
import play.api.Logger
import uk.gov.hmrc.mongo.lock.MongoLockService

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

trait MetricOrchestrationResult {
  def andLogTheResult()
}

final case class CachedMetricGauge(name: String, lookupValue: String => Int) extends Gauge[Int] {
  override def getValue: Int = {
    val value = lookupValue(name)
    Logger.debug(s"Gauge for metric $name is reporting on value: $value")
    value
  }
}

final case class MetricsUpdatedAndRefreshed(updatedMetrics: Map[String, Int], refreshedMetrics: Seq[PersistedMetric])
    extends MetricOrchestrationResult {
  override def andLogTheResult(): Unit = {
    Logger.info(s"Acquired the lock. Both update and refresh have been performed.")
    Logger.debug(s"""
         | The updated metrics coming from sources are: $updatedMetrics.
         | Metrics refreshed on the cache are: $refreshedMetrics
       """.stripMargin)
  }
}

final case class MetricsOnlyRefreshed(refreshedMetrics: List[PersistedMetric]) extends MetricOrchestrationResult {
  override def andLogTheResult(): Unit = {
    Logger.info(s"Failed to acquire the lock. Therefore only refresh has been performed.")
    Logger.debug(s"""
         | Metrics refreshed on the cache are: $refreshedMetrics
       """.stripMargin)
  }
}

class MetricOrchestrator(
  metricSources: List[MetricSource],
  lockService: MongoLockService,
  metricRepository: MetricRepository,
  metricRegistry: MetricRegistry
) {

  private val cache = new ConcurrentHashMap[String, Int]().asScala

  private def refreshWith(allMetrics: List[PersistedMetric]): Unit = synchronized {
    allMetrics.foreach(metric => cache.put(metric.name, metric.count))
    cache.keys.toList.diff(allMetrics.map(_.name)).foreach(cache.remove)
  }

  private def updateMetricRepository(
    resetToZeroFor: Option[PersistedMetric => Boolean]
  )(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    for {
      mapFromReset <- resetToZeroFor match {
                       case Some(reset) =>
                         metricRepository
                           .findAll()
                           .map(_.filter(reset).map { case PersistedMetric(name, _) => name -> 0 }.toMap)
                       case None => Future(Map.empty[String, Int])
                     }
      mapFromSources <- Future.traverse(metricSources)(_.metrics)
      mapToPersist = (mapFromReset :: mapFromSources) reduce {
        _ ++ _
      }
      metricsToPersist = mapToPersist.map { case (name: String, value: Int) => PersistedMetric(name, value) }.toList
      _ <- Future.traverse(metricsToPersist) { m =>
            metricRepository.persist(m)
          }
    } yield mapToPersist

  private def ensureMetricRegistered(persistedMetrics: List[PersistedMetric]): Unit = {
    // Register with DropWizard metric registry if not already
    val currentGauges = metricRegistry.getGauges
    persistedMetrics
      .foreach(
        metric =>
          if (!currentGauges.containsKey(metric.name))
            metricRegistry.register(metric.name, CachedMetricGauge(metric.name, cache.getOrElse(_, 0)))
      )
  }

  /**
    * Attempt to hold the mongo-lock to update the persisted metrics (only one node will be successful doing this
    * at a time). Whether successful or not acquiring the lock, refresh the internal metric cache from the
    * metrics persisted in the repository.
    *
    * @param skipReportingFor optional filter; set to true for any metric that should be ignored from the refresh
    * @param resetToZeroFor   optional filter; set to true for any metric that should be reset to zero during the refresh
    * @param ec               execution context
    * @return
    */
  def attemptMetricRefresh(
    skipReportingFor: Option[PersistedMetric => Boolean] = None,
    resetToZeroFor: Option[PersistedMetric => Boolean]   = None
  )(implicit ec: ExecutionContext): Future[MetricOrchestrationResult] =
    for {
      // Only the node that acquires the lock will execute the update of the repository
      maybeUpdatedMetrics <- lockService.attemptLockWithRelease(updateMetricRepository(resetToZeroFor))
      // But all nodes will refresh all the metrics from that repository
      skipReportingForFn = skipReportingFor.getOrElse(Function.const(false) _)
      persistedMetrics <- metricRepository.findAll().map(_.filterNot(skipReportingForFn))
      _ = refreshWith(persistedMetrics)
      // Register with DropWizard metric registry if not already
      _ = ensureMetricRegistered(persistedMetrics)
    } yield {
      maybeUpdatedMetrics match {
        case Some(updatedMetrics) => MetricsUpdatedAndRefreshed(updatedMetrics, persistedMetrics)
        case None                 => MetricsOnlyRefreshed(persistedMetrics)
      }
    }
}
