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

package uk.gov.hmrc.mongo.lock

import java.time.{Duration => JavaDuration}

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates
import play.api.Logger
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.PlayMongoCollection

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MongoLockRepository @Inject() (mongoComponent: MongoComponent, timestampSupport: TimestampSupport)(
  implicit ec: ExecutionContext
) extends PlayMongoCollection[Lock](mongoComponent, "locks", Lock.format, indexes = Seq.empty) {

  private val logger       = Logger(getClass)
  private val duplicateKey = "11000"

  def toService(lockId: String, ttl: Duration) =
    MongoLockService(this, lockId, ttl)

  def lock(lockId: String, owner: String, ttl: Duration): Future[Boolean] = {
    val timeCreated = timestampSupport.timestamp()
    val expiryTime  = timeCreated.plus(JavaDuration.ofMillis(ttl.toMillis))

    val acquireLock = for {
      deleteResult <- collection
                       .deleteOne(filter = and(equal(Lock.id, lockId), lte(Lock.expiryTime, timeCreated)))
                       .toFuture()
      _            =  if (deleteResult.getDeletedCount != 0)
                        logger.info(s"Removed ${deleteResult.getDeletedCount} expired locks for $lockId")
      _            <- collection
                       .insertOne(Lock(id = lockId, owner = owner, timeCreated = timeCreated, expiryTime = expiryTime))
                       .toFuture()
      _            =  logger.debug(s"Took lock '$lockId' for '$owner' at $timeCreated. Expires at: $expiryTime")
    } yield true

    acquireLock.recover {
      case _ =>
        logger.debug(s"Unable to take lock '$lockId' for '$owner'")
        false
    }
  }

  def releaseLock(lockId: String, owner: String): Future[Unit] = {
    logger.debug(s"Releasing lock '$lockId' for '$owner'")
    collection
      .deleteOne(filter = and(equal(Lock.id, lockId), equal(Lock.owner, owner)))
      .toFuture()
      .map(_ => ())
  }

  def refreshExpiry(lockId: String, owner: String, ttl: Duration): Future[Boolean] = {
    val timeCreated = timestampSupport.timestamp()
    val expiryTime  = timeCreated.plus(JavaDuration.ofMillis(ttl.toMillis))

    // Use findOneAndUpdate to ensure the read and the write are performed as one atomic operation
    collection
      .findOneAndUpdate(
        filter = and(equal(Lock.id, lockId), equal(Lock.owner, owner), gte(Lock.expiryTime, timeCreated)),
        update = Updates.set(Lock.expiryTime, expiryTime)
      )
      .toFutureOption()
      .map {
        case Some(_) =>
          logger.debug(s"Could not renew lock '$lockId' for '$owner' that does not exist or has expired")
          true
        case None =>
          logger.debug(s"Renewed lock '$lockId' for '$owner' at $timeCreated.  Expires at: $expiryTime")
          false
      }
      .recover {
        case e if e.getMessage.contains(duplicateKey) =>
          logger.debug(s"Unable to renew lock '$lockId' for '$owner'")
          false
      }
  }

  def isLocked(lockId: String, owner: String): Future[Boolean] =
    collection
      .find(and(equal(Lock.id, lockId), equal(Lock.owner, owner), gt(Lock.expiryTime, timestampSupport.timestamp())))
      .toFuture()
      .map(_.nonEmpty)

  def attemptLockWithRelease[T](lockId: String, owner: String, ttl: Duration, body: => Future[T])(
    implicit ec: ExecutionContext
  ): Future[Option[T]] = {
    val result = for {
      acquired <- lock(lockId, owner, ttl)
      result   <- when(acquired)(
                    body.flatMap(value => releaseLock(lockId, owner).map(_ => Some(value))),
                    None)
    } yield result
    result.recoverWith { case ex => releaseLock(lockId, owner).flatMap(_ => Future.failed(ex)) }
  }

  def attemptLockWithRefreshExpiry[T](lockId: String, owner: String, ttl: Duration, body: => Future[T])(
    implicit ec: ExecutionContext
  ): Future[Option[T]] = {
    val result = for {
      refreshed <- refreshExpiry(lockId, owner, ttl)
      acquired  <- when(!refreshed)(
                     lock(lockId, owner, ttl),
                     false)
      result    <- when(refreshed || acquired)(
                     body.map(Option.apply),
                     None)
    } yield result

    result.recoverWith {
      case ex => releaseLock(lockId, owner).flatMap(_ => Future.failed(ex))
    }
  }

  private def when[A](predicate: Boolean)(matched: => Future[A], unmatched: => A): Future[A] =
    if (predicate) matched
    else Future.successful(unmatched)
}
