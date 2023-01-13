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

import java.time.{Duration => JavaDuration}

import com.google.inject.ImplementedBy
import javax.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Updates
import play.api.Logger
import uk.gov.hmrc.mongo.{MongoComponent, TimestampSupport}
import uk.gov.hmrc.mongo.MongoUtils.DuplicateKey
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MongoLockRepository])
trait LockRepository {
  def takeLock(lockId: String, owner: String, ttl: Duration): Future[Boolean]

  def releaseLock(lockId: String, owner: String): Future[Unit]

  def refreshExpiry(lockId: String, owner: String, ttl: Duration): Future[Boolean]

  def isLocked(lockId: String, owner: String): Future[Boolean]
}

@Singleton
class MongoLockRepository @Inject() (mongoComponent: MongoComponent, timestampSupport: TimestampSupport)(
  implicit ec: ExecutionContext
) extends PlayMongoRepository[Lock](
      mongoComponent,
      collectionName = "locks",
      domainFormat   = Lock.format,
      indexes        = Seq.empty
    )
    with LockRepository {

  private val logger = Logger(getClass)

  override def takeLock(lockId: String, owner: String, ttl: Duration): Future[Boolean] = {
    val timeCreated = timestampSupport.timestamp()
    val expiryTime  = timeCreated.plus(JavaDuration.ofMillis(ttl.toMillis))

    (for {
      deleteResult <- collection
                        .deleteOne(
                           and(
                             equal(Lock.id, lockId),
                             lte(Lock.expiryTime, timeCreated)
                           )
                         )
                        .toFuture()
      _            =  if (deleteResult.getDeletedCount != 0)
                        logger.info(s"Removed ${deleteResult.getDeletedCount} expired locks for $lockId")
      _            <- collection
                        .insertOne(
                           Lock(
                             id          = lockId,
                             owner       = owner,
                             timeCreated = timeCreated,
                             expiryTime  = expiryTime
                           )
                         )
                        .toFuture()
      _            =  logger.debug(s"Took lock '$lockId' for '$owner' at $timeCreated. Expires at: $expiryTime")
     } yield true
    ).recover {
      case DuplicateKey(e) =>
        logger.debug(s"Unable to take lock '$lockId' for '$owner'")
        false
    }
  }

  override def releaseLock(lockId: String, owner: String): Future[Unit] = {
    logger.debug(s"Releasing lock '$lockId' for '$owner'")
    collection
      .deleteOne(
         and(
           equal(Lock.id, lockId),
           equal(Lock.owner, owner)
         )
       )
      .toFuture()
      .map(_ => ())
  }

  override def refreshExpiry(lockId: String, owner: String, ttl: Duration): Future[Boolean] = {
    val timeCreated = timestampSupport.timestamp()
    val expiryTime  = timeCreated.plus(JavaDuration.ofMillis(ttl.toMillis))

    // Use findOneAndUpdate to ensure the read and the write are performed as one atomic operation
    collection
      .findOneAndUpdate(
        filter = and(
                   equal(Lock.id, lockId),
                   equal(Lock.owner, owner),
                   gte(Lock.expiryTime, timeCreated)
                 ),
        update = Updates.set(Lock.expiryTime, expiryTime)
      )
      .toFutureOption()
      .map {
        case Some(_) =>
          logger.debug(s"Renewed lock '$lockId' for '$owner' at $timeCreated.  Expires at: $expiryTime")
          true
        case None =>
          logger.debug(s"Could not renew lock '$lockId' for '$owner' that does not exist or has expired")
          false
      }
      .recover {
        case DuplicateKey(e) =>
          logger.debug(s"Unable to renew lock '$lockId' for '$owner'")
          false
      }
  }

  override def isLocked(lockId: String, owner: String): Future[Boolean] =
    collection
      .find(
        and(
          equal(Lock.id, lockId),
          equal(Lock.owner, owner),
          gt(Lock.expiryTime, timestampSupport.timestamp())
        )
       )
      .toFuture()
      .map(_.nonEmpty)
}
