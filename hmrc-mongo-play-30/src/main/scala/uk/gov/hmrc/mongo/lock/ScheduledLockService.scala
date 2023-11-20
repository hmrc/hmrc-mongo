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

import play.api.Logger
import uk.gov.hmrc.mongo.TimestampSupport

import java.time.{Duration => JavaDuration}
import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{ExecutionContext, Future}

/**
  * A locking implementation for scheduled tasks that will only execute the `body` when a new lock has been acquired.
  *
  * The lock's ttl is set to be `schedulerInterval` + 1 second, meaning that the instance that currently owns the lock
  * will always have a chance to refresh the lock.
  *
  * The lock will only be released once the `body` has completed - in the event that the execution of `body` takes longer
  * than the `schedulerInterval` the lock will be released immediately, allowing another instance to acquire the lock and
  * start another execution as soon as possible. Otherwise, the lock is abandoned and the expiryTime of the lock is altered
  * to allow the current owning instance the opportunity to acquire a "new" lock next time round.
  *
  * In the event that the scheduled task ends in failure, the lock will be released immediately.
  */

trait ScheduledLockService {

  val lockRepository: LockRepository
  val lockId: String
  val timestampSupport: TimestampSupport
  val schedulerInterval: Duration

  private val ownerId = UUID.randomUUID().toString

  private val logger = Logger(getClass)

  private lazy val ttl: Duration = schedulerInterval.plus(1.second)

  /**
    * Runs `body` only when a new lock has been acquired, the instance that owns the lock will continue to refresh
    * the lock until the `body` has completed.
    *
    * Once the `body` has completed, the lock will either be released immediately or abandoned depending upon how long
    * `body` took to complete.
    */
  def withLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
    (for {
       refreshed <- lockRepository.refreshExpiry(lockId, ownerId, ttl) // ensure our instance continues to have a valid lock in place
       acquired  <- if (!refreshed)
                      lockRepository.takeLock(lockId, ownerId, ttl)
                    else {
                      logger.info(s"Lock $lockId refreshed for $ownerId")
                      Future.successful(None)
                    }
       result    <- acquired match {
                      case Some(lock) =>
                        logger.info(s"Lock $lockId acquired for $ownerId")
                        // we only start the body if we've acquired a new lock. If we have refreshed, then we are currently running, and we don't want multiple runs in parallel
                        for {
                          res <- body
                          _   <- // if we have run longer than expected, release the lock, since another run is over-due
                            if (timestampSupport.timestamp().toEpochMilli > lock.timeCreated.plus(JavaDuration.ofMillis(schedulerInterval.toMillis)).toEpochMilli) {
                              logger.info(s"Lock $lockId has been held by $ownerId longer than intended, releasing")
                              lockRepository.releaseLock(lockId, ownerId)
                            } else {
                              /* don't release the lock, let it timeout, so nothing else starts prematurely
                              *  we remove our ownership so that we won't refresh it again
                              *  we minus 2 seconds to make the lock expire 1 second before the next scheduler invocation
                              *  which will allow the abandoning instance to acquire a "new" lock next time.
                              */
                              logger.info(s"Lock $lockId is being abandoned by $ownerId to expire naturally")
                              lockRepository.abandonLock(lockId, ownerId, Some(lock.expiryTime.minusSeconds(2)))
                            }
                        } yield Some(res)
                      case None =>
                        Future.successful(None)
                    }
     } yield result
    ).recoverWith {
      case ex => // if we fail with an error, release the lock so another run can start at the earliest opportunity
        lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
    }
}

object ScheduledLockService {

  def apply(lockRepository: LockRepository, lockId: String, timestampSupport: TimestampSupport, schedulerInterval: Duration): ScheduledLockService = {
    val (lockRepository1, lockId1, timestampSupport1, schedulerInterval1) = (lockRepository, lockId, timestampSupport, schedulerInterval)
    new ScheduledLockService {
      override val lockRepository   : LockRepository   = lockRepository1
      override val lockId           : String           = lockId1
      override val timestampSupport : TimestampSupport = timestampSupport1
      override val schedulerInterval: Duration         = schedulerInterval1
    }
  }
}
