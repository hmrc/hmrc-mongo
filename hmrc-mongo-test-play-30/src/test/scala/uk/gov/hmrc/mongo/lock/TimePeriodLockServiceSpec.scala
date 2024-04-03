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
import scala.concurrent.duration.{Duration, DurationInt}

class TimePeriodLockServiceSpec
  extends AnyWordSpecLike
     with Matchers
     with DefaultPlayMongoRepositorySupport[Lock] {

  "withRenewedLock" should {
    "execute the body if no previous lock is set" in {
      var counter = 0
      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue
      counter shouldBe 1
    }

    "execute the body if the lock for same serverId exists" in {
      var counter = 0
      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue

      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }

    "not execute the body and exit if the lock for another serverId exists" in {
      var counter = 0
      TimePeriodLockService(repository, lockId, ttl)
        .withRenewedLock {
          Future.successful(counter += 1)
        }
        .futureValue

      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 1
    }

    "execute the body if run after the ttl time has expired" in {
      var counter = 0
      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue

      Thread.sleep(1000 + 1)

      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue

      counter shouldBe 2
    }
  }

  private lazy val lockId        = "lockId"
  private lazy val ttl: Duration = 1000.millis

  override protected val repository: MongoLockRepository =
    new MongoLockRepository(mongoComponent, new CurrentTimestampSupport)

  private lazy val lockService = TimePeriodLockService(repository, lockId, ttl)
}
