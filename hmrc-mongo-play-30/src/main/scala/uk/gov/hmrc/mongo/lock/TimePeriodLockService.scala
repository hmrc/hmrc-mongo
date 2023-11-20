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

import java.util.UUID

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/** For locking for a give time period (i.e. stop other instances executing the task until it stops renewing the lock).
  * The lock will be held on to when the task has finished, until it expires.
  */
trait TimePeriodLockService {

  val lockRepository: LockRepository
  val lockId: String
  val ttl: Duration

  private val ownerId = UUID.randomUUID().toString

  /** Runs `body` if a lock can be taken or if the existing lock is owned by this service instance.
    * The lock is not released at the end of but task (unless it ends in failure), but is held onto until it expires.
    */
  def withRenewedLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
       refreshed <- lockRepository.refreshExpiry(lockId, ownerId, ttl)
       acquired  <- if (!refreshed)
                      lockRepository.takeLock(lockId, ownerId, ttl).map(_.isDefined)
                    else
                      Future.successful(false)
       result    <- if (refreshed || acquired)
                      body.map(Option.apply)
                    else
                      Future.successful(None)
     } yield result
    ).recoverWith {
      case ex => lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }
}

object TimePeriodLockService {

  def apply(lockRepository: LockRepository, lockId: String, ttl: Duration): TimePeriodLockService = {
    val (lockRepository1, lockId1, ttl1) = (lockRepository, lockId, ttl)
    new TimePeriodLockService {
      override val lockRepository: LockRepository = lockRepository1
      override val lockId        : String         = lockId1
      override val ttl           : Duration       = ttl1
    }
  }
}
