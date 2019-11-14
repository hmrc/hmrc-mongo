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

package uk.gov.hmrc.metrix.persistence

import com.mongodb.BasicDBObject
import org.mongodb.scala.{MongoClient, MongoDatabase}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterEach, LoneElement}
import uk.gov.hmrc.metrix.UnitSpec
import uk.gov.hmrc.metrix.domain.PersistedMetric
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.test.MongoSupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class MongoMetricRepositorySpec extends UnitSpec with MongoSupport with ScalaFutures with LoneElement with BeforeAndAfterEach {

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  lazy val metricsRepo = new MongoMetricRepository(databaseName, mongoComponent)

  override def beforeEach(): Unit = {
    super.beforeEach()
    metricsRepo.collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())
  }

  override def afterEach(): Unit = {
    super.afterEach()
    metricsRepo.collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())
  }

  "update" should {
    "store the provided MetricsStorage instance with the 'name' key" in {
      val storedMetric = PersistedMetric("test-metric", 5)

      metricsRepo.persist(storedMetric).futureValue

      metricsRepo.findAll().futureValue.loneElement shouldBe storedMetric
    }
  }

}
