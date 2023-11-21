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

package uk.gov.hmrc.mongo.lock

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class ScheduledLockServiceSpec
  extends AnyWordSpecLike
    with Matchers
    with DefaultPlayMongoRepositorySupport[Lock] {

  private val timestampSupport = new CurrentTimestampSupport

  override protected val repository = new MongoLockRepository(mongoComponent, timestampSupport)

  private val lockId = "lockId"
  private val schedulerInterval = 5.seconds

  private val lockService = ScheduledLockService(repository, lockId, timestampSupport, schedulerInterval)

  "withLock" should {
    "execute the body if no lock is present" in {
      var counter = 0

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 1
    }

    "prevent parallel executions across different instances" in {
      var counter = 0

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      val anotherInstance = ScheduledLockService(repository, lockId, timestampSupport, schedulerInterval)

      anotherInstance.withLock {
        Future.successful(counter += 5) // shouldn't execute
      }.futureValue

      counter shouldBe 1
    }

    "prevent the body being executed more frequently than the scheduler interval" in {
      var counter = 0

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      lockService.withLock {
        Future.successful(counter += 5) // shouldn't execute
      }.futureValue

      counter shouldBe 1

      Thread.sleep(schedulerInterval.toMillis)

      lockService.withLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }

    "prevent the body being executed when the previous execution has yet to finish" in {
      var counter = 0

      val running = lockService.withLock {
        Thread.sleep(schedulerInterval.toMillis + 2000)
        Future.successful(counter += 1)
      }

      Thread.sleep(schedulerInterval.toMillis)

      lockService.withLock {
        Future.successful(counter += 5) // shouldn't execute
      }.futureValue

      running.futureValue

      counter shouldBe 1
    }
  }

}
