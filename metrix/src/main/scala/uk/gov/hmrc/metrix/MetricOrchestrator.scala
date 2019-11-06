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

package uk.gov.hmrc.metrix

import com.codahale.metrics.{Gauge, MetricRegistry}
import play.api.Logger
import uk.gov.hmrc.metrix.domain.{MetricRepository, MetricSource, PersistedMetric}
import uk.gov.hmrc.mongo.lock.MongoLockService

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class MetricCache {

  private val cache = mutable.Map[String, Int]()

  def refreshWith(allMetrics: List[PersistedMetric]) = {
    allMetrics.foreach(m => cache.put(m.name, m.count))
    val asMap: Map[String, Int] = allMetrics.map(m => m.name -> m.count).toMap
    cache.keys.foreach(key => if (!asMap.contains(key)) cache.remove(key))
  }

  def valueOf(name: String): Int = cache.getOrElse(name, 0)
}

final case class CachedMetricGauge(name: String, metrics: MetricCache) extends Gauge[Int] {
  override def getValue: Int = {
    val value = metrics.valueOf(name)
    Logger.debug(s"Gauge for metric $name is reporting on value: $value")
    value
  }
}

trait MetricOrchestrationResult {
  def andLogTheResult()
}

final case class MetricsUpdatedAndRefreshed(updatedMetrics: Map[String, Int],
                                            refreshedMetrics: Seq[PersistedMetric]) extends MetricOrchestrationResult {
  override def andLogTheResult(): Unit = {
    Logger.info(s"Acquired the lock. Both update and refresh have been performed.")
    Logger.debug(
      s"""
         | The updated metrics coming from sources are: $updatedMetrics.
         | Metrics refreshed on the cache are: $refreshedMetrics
       """.stripMargin)
  }
}

final case class MetricsOnlyRefreshed(refreshedMetrics: List[PersistedMetric]) extends MetricOrchestrationResult {
  override def andLogTheResult(): Unit = {
    Logger.info(s"Failed to acquire the lock. Therefore only refresh has been performed.")
    Logger.debug(
      s"""
         | Metrics refreshed on the cache are: $refreshedMetrics
       """.stripMargin)
  }
}


class MetricOrchestrator(metricSources: List[MetricSource],
                         lock: MongoLockService,
                         metricRepository: MetricRepository,
                         metricRegistry: MetricRegistry) {

  val metricCache = new MetricCache()

  private def updateMetricRepository(resetOn: Option[PersistedMetric => Boolean] = None)(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    val resetingFilter: PersistedMetric => Boolean = resetOn.getOrElse((_: PersistedMetric) => false)
    for {
      persistedMetrics <- if (resetOn.isDefined) metricRepository.findAll() else Future(List())
      mapFromReset = persistedMetrics.filter(resetingFilter).map { case PersistedMetric(name, _) => name -> 0 }.toMap
      mapFromSources <- Future.traverse(metricSources)(_.metrics)
      mapToPersist = (mapFromReset :: mapFromSources) reduce {
        _ ++ _
      }
      metricsToPersist = mapToPersist.map { case (name: String, value: Int) => PersistedMetric(name, value) }.toList
      _ <- Future.traverse(metricsToPersist) { m => metricRepository.persist(m) }
    } yield mapToPersist
  }

  private def doNotSkipAny(metric: PersistedMetric): Boolean = false

  def attemptToUpdateAndRefreshMetrics(skipReportingOn: PersistedMetric => Boolean = doNotSkipAny)
                                      (implicit ec: ExecutionContext): Future[MetricOrchestrationResult] = {
    lock.attemptLockWithRelease {
      updateMetricRepository()
    } flatMap { maybeUpdatedMetrics =>
      metricRepository.findAll() map { persistedMetrics =>
        persistedMetrics.filterNot(skipReportingOn)
      } map { filteredMetrics =>
        metricCache.refreshWith(filteredMetrics)

        val currentGauges = metricRegistry.getGauges

        filteredMetrics
          .foreach(metric => if (!currentGauges.containsKey(metric.name))
            metricRegistry.register(metric.name, CachedMetricGauge(metric.name, metricCache)))

        maybeUpdatedMetrics match {
          case Some(updatedMetrics) => MetricsUpdatedAndRefreshed(updatedMetrics, filteredMetrics)
          case None => MetricsOnlyRefreshed(filteredMetrics)
        }
      }
    }
  }

  def attemptToUpdateRefreshAndResetMetrics(resetMetricOn: PersistedMetric => Boolean)
                                           (implicit ec: ExecutionContext): Future[MetricOrchestrationResult] = {
    for {
      lockOnMetrics <- lock.attemptLockWithRelease(updateMetricRepository(Some(resetMetricOn)))
      persistedMetrics <- metricRepository.findAll()
      _ = metricCache.refreshWith(persistedMetrics)
    } yield {
      val currentGauges = metricRegistry.getGauges.keySet()
      persistedMetrics
        .filterNot(metric => currentGauges.contains(metric.name))
        .map(metric => metricRegistry.register(metric.name, CachedMetricGauge(metric.name, metricCache)))
      lockOnMetrics match {
        case Some(updatedMetrics) => MetricsUpdatedAndRefreshed(updatedMetrics, persistedMetrics)
        case None => MetricsOnlyRefreshed(persistedMetrics)
      }
    }
  }
}
