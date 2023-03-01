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

import java.util.concurrent.ConcurrentHashMap

import com.codahale.metrics.{Gauge, MetricRegistry}
import play.api.Logger
import uk.gov.hmrc.mongo.lock.LockService

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}


final case class CachedMetricGauge(
  name       : String,
  lookupValue: String => Int
) extends Gauge[Int] {

  private val logger = Logger(getClass)

  override def getValue: Int = {
    val value = lookupValue(name)
    logger.debug(s"Gauge for metric $name is reporting on value: $value")
    value
  }
}

trait MetricOrchestrationResult {
  def log(): Unit
}

object MetricOrchestrationResult {
  private val logger = Logger(getClass)

  final case class UpdatedAndRefreshed(updatedMetrics: Map[String, Int], refreshedMetrics: Seq[PersistedMetric])
      extends MetricOrchestrationResult {
    override def log(): Unit = {
      logger.info(s"Acquired the lock. Both update and refresh have been performed.")
      logger.debug(s"""
           | The updated metrics coming from sources are: $updatedMetrics.
           | Metrics refreshed on the cache are: $refreshedMetrics
         """.stripMargin)
    }
  }

  final case class RefreshedOnly(refreshedMetrics: List[PersistedMetric]) extends MetricOrchestrationResult {
    override def log(): Unit = {
      logger.info(s"Failed to acquire the lock. Therefore only refresh has been performed.")
      logger.debug(s"""
           | Metrics refreshed on the cache are: $refreshedMetrics
         """.stripMargin)
    }
  }
}

class MetricOrchestrator(
  metricSources   : List[MetricSource],
  lockService     : LockService,
  metricRepository: MetricRepository,
  metricRegistry  : MetricRegistry
) {

  private val cache = new ConcurrentHashMap[String, Int]().asScala

  private def refreshWith(allMetrics: List[PersistedMetric]): Unit = synchronized {
    allMetrics.foreach(metric => cache.put(metric.name, metric.count))
    cache.keys.toList.diff(allMetrics.map(_.name)).foreach(cache.remove)
  }

  private def updateMetricRepository(
    resetToZeroFor: Option[PersistedMetric => Boolean]
  )(implicit
    ec: ExecutionContext
  ): Future[Map[String, Int]] =
    for {
      persistedMetrics  <- metricRepository.findAll().map(_.map(_.name).toSet)
      mapFromSources    <- Future.traverse(metricSources)(_.metrics).map(_.reduce(_ ++ _))
      metricsToPersist  =  mapFromSources
                             .map((PersistedMetric.apply _).tupled)
                             .map(metric =>
                               metric.copy(count = if (persistedMetrics.contains(metric.name) &&
                                                       resetToZeroFor.exists(_(metric)))
                                                      0
                                                   else metric.count
                                          )
                             )
                             .toSeq
      _                 <- metricRepository.putAll(metricsToPersist)
    } yield metricsToPersist.map(m => (m.name, m.count)).toMap

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
    resetToZeroFor  : Option[PersistedMetric => Boolean] = None
  )(implicit ec: ExecutionContext): Future[MetricOrchestrationResult] =
    for {
      // Only the node that acquires the lock will execute the update of the repository
      maybeUpdatedMetrics <- lockService.withLock(updateMetricRepository(resetToZeroFor))
      // But all nodes will refresh all the metrics from that repository
      skipReportingForFn = skipReportingFor.getOrElse(Function.const(false) _)
      persistedMetrics <- metricRepository.findAll().map(_.filterNot(skipReportingForFn))
      _ = refreshWith(persistedMetrics)
      // Register with DropWizard metric registry if not already
      _ = ensureMetricRegistered(persistedMetrics)
    } yield {
      maybeUpdatedMetrics match {
        case Some(updatedMetrics) => MetricOrchestrationResult.UpdatedAndRefreshed(updatedMetrics, persistedMetrics)
        case None                 => MetricOrchestrationResult.RefreshedOnly(persistedMetrics)
      }
    }
}
