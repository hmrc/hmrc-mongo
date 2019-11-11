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

package uk.gov.hmrc.metrix.cache

import uk.gov.hmrc.metrix.UnitSpec
import uk.gov.hmrc.metrix.domain.PersistedMetric

class MetricCacheSpec extends UnitSpec {

  "MetricsCache" should {

    "initialize with a list of metrics" in {
      val metrics = new MetricCache()
      metrics.refreshWith(List(PersistedMetric("a", 1), PersistedMetric("b", 2)))

      metrics.valueOf("a") shouldBe 1
      metrics.valueOf("b") shouldBe 2
    }

    "add elements that were not present before" in {
      val metrics = new MetricCache()

      metrics.refreshWith(List(PersistedMetric("a", 1)))

      metrics.valueOf("a") shouldBe 1
      metrics.valueOf("b") shouldBe 0

      metrics.refreshWith(List(PersistedMetric("a", 1), PersistedMetric("b", 2)))

      metrics.valueOf("a") shouldBe 1
      metrics.valueOf("b") shouldBe 2

    }

    "remove elements that are no longer there" in {
      val metrics = new MetricCache()

      metrics.refreshWith(List(PersistedMetric("a", 1), PersistedMetric("b", 2)))

      metrics.valueOf("a") shouldBe 1
      metrics.valueOf("b") shouldBe 2

      metrics.refreshWith(List(PersistedMetric("a", 1)))

      metrics.valueOf("a") shouldBe 1
      metrics.valueOf("b") shouldBe 0
    }
  }
}
