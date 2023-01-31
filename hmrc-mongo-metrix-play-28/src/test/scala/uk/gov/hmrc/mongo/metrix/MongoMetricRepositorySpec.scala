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

import org.scalatest.LoneElement
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class MongoMetricRepositorySpec
  extends UnitSpec
     with ScalaFutures
     with LoneElement
     with DefaultPlayMongoRepositorySupport[PersistedMetric] {

  override lazy val repository = new MongoMetricRepository(mongoComponent)

  "MongoMetricRepository.putAll" should {
    "store the provided PersistedMetrics with the 'name' keys" in {
      val storedMetric = PersistedMetric("test-metric", 5)

      repository.putAll(Seq(storedMetric)).futureValue

      repository.findAll().futureValue.loneElement shouldBe storedMetric
    }

    "remove any previous PersistedMetrics" in {
      val existing = PersistedMetric("test-metric", 5)
      repository.collection.insertOne(existing)

      val newMetric = PersistedMetric("test-metric", 5)
      repository.putAll(Seq(newMetric)).futureValue

      repository.findAll().futureValue.loneElement shouldBe newMetric
    }
  }
}
