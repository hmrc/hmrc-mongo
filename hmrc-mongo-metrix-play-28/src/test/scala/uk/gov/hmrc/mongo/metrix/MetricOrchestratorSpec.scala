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

import com.codahale.metrics.{Metric, MetricFilter, MetricRegistry}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.Inside._
import org.scalatest.LoneElement
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, LockService}
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.{Duration, DurationLong}
import scala.concurrent.{ExecutionContext, Future}

class MetricOrchestratorSpec
    extends UnitSpec
    with LoneElement
    with MockitoSugar
    with ArgumentMatchersSugar
    with DefaultPlayMongoRepositorySupport[PersistedMetric] {

  import MetricOrchestrationResult._

  "metric orchestrator" should {

    "register all the gauges" in {

      val acquiredMetrics = Map("a" -> 1, "b" -> 2)

      val orchestrator = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      // when
      orchestrator.attemptMetricRefresh().futureValue shouldResultIn UpdatedAndRefreshed(
        acquiredMetrics,
        persistedMetricsFrom(acquiredMetrics)
      )

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2
    }

    "be calculated across multiple sources" in {
      val acquiredMetrics      = Map("a" -> 1, "b" -> 2)
      val otherAcquiredMetrics = Map("z" -> 3, "x" -> 4)

      val orchestrator = metricOrchestratorFor(
        List(
          sourceReturning(acquiredMetrics),
          sourceReturning(otherAcquiredMetrics)
        )
      )

      // when
      orchestrator.attemptMetricRefresh().futureValue shouldResultIn UpdatedAndRefreshed(
        acquiredMetrics ++ otherAcquiredMetrics,
        persistedMetricsFrom(acquiredMetrics ++ otherAcquiredMetrics)
      )

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2
      metricRegistry.getGauges.get(s"z").getValue shouldBe 3
      metricRegistry.getGauges.get(s"x").getValue shouldBe 4

    }

    "update the metrics when the source changes" in {
      val firstMetrics  = Map("metric1" -> 32, "metric2" -> 43)
      val secondMetrics = Map("metric1" -> 11, "metric2" -> 87, "metric3" -> 22)
      val orchestrator = metricOrchestratorFor(
        List(
          sourceReturningFirstAndThen(firstMetrics, secondMetrics)
        )
      )

      // when
      orchestrator.attemptMetricRefresh().futureValue

      metricRegistry.getGauges.get("metric1").getValue shouldBe 32
      metricRegistry.getGauges.get("metric2").getValue shouldBe 43

      // when
      orchestrator.attemptMetricRefresh().futureValue

      metricRegistry.getGauges.get("metric1").getValue shouldBe 11
      metricRegistry.getGauges.get("metric2").getValue shouldBe 87
      metricRegistry.getGauges.get("metric3").getValue shouldBe 22
    }

    "skip reporting all the metrics matching when the skip filter matches all" in {
      val acquiredMetrics = Map("opened.name" -> 4, "ravaged.name" -> 2, "not.ravaged.name" -> 8)
      val orchestrator    = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      orchestrator
        .attemptMetricRefresh(
          skipReportingFor = Some((_: PersistedMetric) => true)
        )
        .futureValue shouldResultIn UpdatedAndRefreshed(acquiredMetrics, Seq.empty)

      metricRegistry.getGauges shouldBe empty
    }

    "skip reporting the metrics matching the specific skip filter" in {
      val openedMetricName     = "opened.name"
      val notRavagedMetricName = "not.ravaged.name"
      val acquiredMetrics      = Map(openedMetricName -> 4, "ravaged.name" -> 2, notRavagedMetricName -> 8)
      val orchestrator         = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      orchestrator
        .attemptMetricRefresh(skipReportingFor = Some((metric: PersistedMetric) => {
          metric.name.contains("ravaged") && metric.count < 3
        }))
        .futureValue shouldResultIn UpdatedAndRefreshed(
        acquiredMetrics,
        List(PersistedMetric(openedMetricName, 4), PersistedMetric(notRavagedMetricName, 8))
      )

      metricRegistry.getGauges                                    should have size 2
      metricRegistry.getGauges.get(openedMetricName).getValue     shouldBe 4
      metricRegistry.getGauges.get(notRavagedMetricName).getValue shouldBe 8
    }

    "not reset value if metrics matching filter when a new value is provided" in {
      val otherMetricName                = "opened.name"
      val notResetedMetricName           = "not.reseted.name"
      val resetableButProvidedMetricName = "reseted.name"

      val acquiredMetrics = Map(otherMetricName -> 4, resetableButProvidedMetricName -> 2, notResetedMetricName -> 8)
      val orchestrator    = metricOrchestratorFor(List(sourceReturning(acquiredMetrics)))

      orchestrator
        .attemptMetricRefresh(resetToZeroFor = Some(m => m.name == resetableButProvidedMetricName))
        .futureValue shouldResultIn
        UpdatedAndRefreshed(
          acquiredMetrics,
          List(
            PersistedMetric(otherMetricName, 4),
            PersistedMetric(resetableButProvidedMetricName, 2),
            PersistedMetric(notResetedMetricName, 8)
          )
        )

      metricRegistry.getGauges                                              should have size 3
      metricRegistry.getGauges.get(otherMetricName).getValue                shouldBe 4
      metricRegistry.getGauges.get(resetableButProvidedMetricName).getValue shouldBe 2
      metricRegistry.getGauges.get(notResetedMetricName).getValue           shouldBe 8
    }

    "reset value if metrics matching reset filter and no metric is provided" in {
      val otherMetricName      = "opened.name"
      val notResetedMetricName = "not.reseted.name"
      val resetableMetricName  = "reseted.name"

      val mockMetricSource = mock[MetricSource]
      val orchestrator     = metricOrchestratorFor(List(mockMetricSource))

      val acquiredMetrics = Map(otherMetricName -> 4, resetableMetricName -> 2, notResetedMetricName -> 8)
      when(mockMetricSource.metrics(any)).thenReturn(Future.successful(acquiredMetrics))
      orchestrator
        .attemptMetricRefresh(resetToZeroFor = Some((metric: PersistedMetric) => {
          metric.name == "reseted.name"
        }))
        .futureValue

      val newAcquiredMetrics = Map(otherMetricName -> 5, notResetedMetricName -> 6)
      when(mockMetricSource.metrics(any)).thenReturn(Future.successful(newAcquiredMetrics))
      orchestrator
        .attemptMetricRefresh(resetToZeroFor = Some((metric: PersistedMetric) => {
          metric.name == "reseted.name"
        }))
        .futureValue

      metricRegistry.getGauges                                    should have size 3
      metricRegistry.getGauges.get(otherMetricName).getValue      shouldBe 5
      metricRegistry.getGauges.get(resetableMetricName).getValue  shouldBe 0
      metricRegistry.getGauges.get(notResetedMetricName).getValue shouldBe 6
    }

    "cache the metrics" in {
      val acquiredMetrics = Map("a" -> 1, "b" -> 2)

      val metricRepository: MetricRepository = mock[MetricRepository]

      val orchestrator = new MetricOrchestrator(
        metricRepository = metricRepository,
        metricSources    = List(sourceReturning(acquiredMetrics)),
        lockService      = mongoLockService,
        metricRegistry   = metricRegistry
      )

      when(metricRepository.findAll())
        .thenReturn(
          Future(List(PersistedMetric("a", 1), PersistedMetric("b", 2), PersistedMetric("z", 8))),
          // result for after delete call
          Future(List(PersistedMetric("a", 1), PersistedMetric("b", 2)))
        )

      when(metricRepository.persist(any[PersistedMetric]))
        .thenReturn(Future.unit)

      when(metricRepository.delete(any[String]))
        .thenReturn(Future.unit)

      // when
      orchestrator.attemptMetricRefresh().futureValue shouldResultIn
        UpdatedAndRefreshed(
          acquiredMetrics,
          List(PersistedMetric("a", 1), PersistedMetric("b", 2))
        )

      verify(metricRepository, times(2)).findAll()
      verify(metricRepository, times(2)).persist(any[PersistedMetric])
      verify(metricRepository, times(1)).delete(any[String])

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2

      verifyNoMoreInteractions(metricRepository)
    }

    "update the cache even if the lock is not acquired" in {
      val mockedMetricRepository: MetricRepository = mock[MetricRepository]

      val lockRepo = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport) {
        // Force the lock to never be acquired for the purpose of this test
        override def takeLock(lockId: String, owner: String, ttl: Duration): Future[Boolean] = Future(false)
      }

      val lockService =
        LockService(
          lockRepository = lockRepo,
          lockId         = "test-lock",
          ttl            = 1.milliseconds
        )

      val orchestrator = new MetricOrchestrator(
        metricRepository = mockedMetricRepository,
        metricSources    = List(sourceReturning(Map("a" -> 1, "b" -> 2))),
        lockService      = lockService,
        metricRegistry   = metricRegistry
      )

      when(mockedMetricRepository.findAll()).thenReturn(Future(List(PersistedMetric("a", 4), PersistedMetric("b", 5))))

      orchestrator.attemptMetricRefresh().futureValue shouldResultIn RefreshedOnly(
        List(PersistedMetric("a", 4), PersistedMetric("b", 5))
      )

      verify(mockedMetricRepository).findAll()

      metricRegistry.getGauges.get(s"a").getValue shouldBe 4
      metricRegistry.getGauges.get(s"b").getValue shouldBe 5

      verifyNoMoreInteractions(mockedMetricRepository)
    }

    "gauges are registered after all metrics are written to mongo even if writing takes a long time" in {

      val acquiredMetrics = Map("a" -> 1, "b" -> 2)

      val orchestrator = metricOrchestratorFor(
        sources          = List(sourceReturning(acquiredMetrics)),
        metricRepository = new SlowlyWritingMetricRepository
      )

      // when
      orchestrator.attemptMetricRefresh().futureValue shouldResultIn UpdatedAndRefreshed(
        acquiredMetrics,
        persistedMetricsFrom(acquiredMetrics)
      )

      metricRegistry.getGauges.get(s"a").getValue shouldBe 1
      metricRegistry.getGauges.get(s"b").getValue shouldBe 2
    }
  }

  private val metricRegistry        = new MetricRegistry()
  override protected val repository = new MongoMetricRepository(mongoComponent)

  override def beforeEach(): Unit = {
    super.beforeEach()
    metricRegistry.removeMatching(new MetricFilter {
      override def matches(name: String, metric: Metric): Boolean = true
    })
  }

  private class SlowlyWritingMetricRepository extends MongoMetricRepository(
    mongoComponent = mongoComponent
  ) {
    override def persist(calculatedMetric: PersistedMetric): Future[Unit] =
      Future(Thread.sleep(200)).flatMap(_ => super.persist(calculatedMetric))
  }

  private val mongoLockService: LockService =
    LockService(
      lockRepository = new MongoLockRepository(mongoComponent, new CurrentTimestampSupport),
      lockId         = "test-metrics",
      ttl            = 0.microseconds
    )

  private def metricOrchestratorFor(
    sources: List[MetricSource],
    metricRepository: MetricRepository = repository
  ) =
    new MetricOrchestrator(
      metricSources    = sources,
      lockService      = mongoLockService,
      metricRepository = metricRepository,
      metricRegistry   = metricRegistry
    )

  private def persistedMetricsFrom(metricsMap: Map[String, Int]): Seq[PersistedMetric] =
    metricsMap.map { case (name, count) => PersistedMetric(name, count) }.toSeq

  private def sourceReturning(metricsMap: Map[String, Int]): MetricSource =
    new MetricSource {
      override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
        Future.successful(metricsMap)
    }

  private def sourceReturningFirstAndThen(
    firstMetricsMap: Map[String, Int],
    secondMetricsMap: Map[String, Int]
  ): MetricSource =
    new MetricSource {
      var iteration = 0

      override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
        if (iteration % 2 == 0) {
          iteration += 1
          Future.successful(firstMetricsMap)
        } else {
          iteration += 1
          Future.successful(secondMetricsMap)
        }
    }

  implicit class MetricOrchestrationResultComparison(metricUpdateResult: MetricOrchestrationResult) {
    def shouldResultIn(expectedUpdateResult: RefreshedOnly): Unit =
      inside(metricUpdateResult) {
        case RefreshedOnly(refreshedMetrics) =>
          refreshedMetrics should contain theSameElementsAs expectedUpdateResult.refreshedMetrics
      }

    def shouldResultIn(expectedUpdateResult: UpdatedAndRefreshed): Unit =
      inside(metricUpdateResult) {
        case UpdatedAndRefreshed(updatedMetrics, refreshedMetrics) =>
          updatedMetrics   shouldBe expectedUpdateResult.updatedMetrics
          refreshedMetrics should contain theSameElementsAs expectedUpdateResult.refreshedMetrics
      }
  }
}
