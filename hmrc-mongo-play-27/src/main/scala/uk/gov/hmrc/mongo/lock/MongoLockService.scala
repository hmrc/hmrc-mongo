/*
 * Copyright 2021 HM Revenue & Customs
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

trait MongoLockService {

  val lockRepository: LockRepository
  val lockId: String
  val ttl: Duration

  private val ownerId = UUID.randomUUID().toString

  def attemptLockWithRelease[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
       acquired <- lockRepository.takeLock(lockId, ownerId, ttl)
       result   <- ifelseF(acquired)(
                     body.flatMap(value => lockRepository.releaseLock(lockId, ownerId).map(_ => Some(value))),
                     None
                   )
     } yield result
    ).recoverWith {
      case ex => lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }

  def attemptLockWithRefreshExpiry[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
       refreshed <- lockRepository.refreshExpiry(lockId, ownerId, ttl)
       acquired  <- ifelseF(!refreshed)(
                      lockRepository.takeLock(lockId, ownerId, ttl),
                      false
                    )
       result    <- ifelseF(refreshed || acquired)(
                      body.map(Option.apply),
                      None
                    )
     } yield result
    ).recoverWith {
      case ex => lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }

  private def ifelseF[A](predicate: Boolean)(matched: => Future[A], unmatched: => A): Future[A] =
    if (predicate) matched
    else Future.successful(unmatched)
}

object MongoLockService {

  def apply(lockRepository: LockRepository, lockId: String, ttl: Duration): MongoLockService = {
    val (lockRepository1, lockId1, ttl1) = (lockRepository, lockId, ttl)
    new MongoLockService {
      override val lockRepository: LockRepository = lockRepository1
      override val lockId        : String         = lockId1
      override val ttl           : Duration       = ttl1
    }
  }
}
