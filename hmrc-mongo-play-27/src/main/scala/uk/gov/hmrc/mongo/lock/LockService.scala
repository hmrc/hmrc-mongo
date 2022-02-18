/*
 * Copyright 2022 HM Revenue & Customs
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

/** For locking for a particular task.
  * The lock will be released when the task has finished.
  */
trait LockService {

  val lockRepository: LockRepository
  val lockId: String
  val ttl: Duration

  private val ownerId = UUID.randomUUID().toString

  /** Runs `body` if a lock can be taken.
    * The lock will be released when the task has finished.
    */
  def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
       acquired <- lockRepository.takeLock(lockId, ownerId, ttl)
       result   <- if (acquired)
                     body.flatMap(value => lockRepository.releaseLock(lockId, ownerId).map(_ => Some(value)))
                   else
                     Future.successful(None)
     } yield result
    ).recoverWith {
      case ex => lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }
}

object LockService {

  def apply(lockRepository: LockRepository, lockId: String, ttl: Duration): LockService = {
    val (lockRepository1, lockId1, ttl1) = (lockRepository, lockId, ttl)
    new LockService {
      override val lockRepository: LockRepository = lockRepository1
      override val lockId        : String         = lockId1
      override val ttl           : Duration       = ttl1
    }
  }
}
