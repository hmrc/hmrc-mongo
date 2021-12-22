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

package uk.gov.hmrc.mongo.transaction

import org.mongodb.scala._
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt


/** Effectively provides the behaviour not available on `org.mongodb.scala.ClientSession` (driver-scala, nor
  * `com.mongodb.reactivestreams.client.ClientSession` driver-reactive-streams) but is available on
  * `com.mongodb.client.ClientSession` (driver-core).
  *
  * It is recommended to use the Future variant (i.e. add `.toFuture` to all db calls), since the Observable variant
  * has some surprises. E.g.
  *  - some commands return `Observable[Void]` which will ignore any following map/flatMap (e.g. for-comprehension steps)
  */
trait Transactions {
  def mongoComponent: MongoComponent

  private val logger = Logger(this.getClass)

  // Same as com.mongodb.client.internal.ClientSessionImpl.MAX_RETRY_TIME_LIMIT_MS
  private val maxRetryTimeLimitMs = 2.minutes.toMillis

  //--  Future ---

  def withSessionAndTransaction[A](
    f: ClientSession => Future[A]
  )(implicit
    tc: TransactionConfiguration,
    ec: ExecutionContext
  ): Future[A] =
    withClientSession(session =>
      withTransaction(session, f(session))
    )

  def withClientSession[A](
    f: ClientSession => Future[A]
  )(implicit
    tc: TransactionConfiguration,
    ec: ExecutionContext
  ): Future[A] =
    for {
      session <- tc.clientSessionOptions.fold(mongoComponent.client.startSession())(mongoComponent.client.startSession _).toFuture()
      f2      =  f(session)
      _       =  f2.onComplete(_ => session.close())
      res     <- f2
    } yield res

  /** A transaction is started before running the continuation. It will be committed or aborted depending on whether the callback executes
    * successfully or not.
    * It also provides retries for network issues
    * See https://github.com/mongodb/mongo-java-driver/tree/r4.3.1/driver-core/src/test/resources/transactions-convenient-api#retry-timeout-is-enforced
    * And https://docs.mongodb.com/manual/core/retryable-writes/#retryable-writes-and-multi-document-transactions
    */
  // based on implementation: https://github.com/mongodb/mongo-java-driver/blob/r4.3.1/driver-sync/src/main/com/mongodb/client/internal/ClientSessionImpl.java#L203-L247
  def withTransaction[A](
    session: ClientSession,
    f      : => Future[A]
  )(implicit
    tc: TransactionConfiguration,
    ec: ExecutionContext
  ): Future[A] = {
    val startTimeMs = System.currentTimeMillis()

    def retryFor[B](cond: MongoException => Boolean)(f: => Future[B]): Future[B] =
      f.recoverWith {
        case e: MongoException if cond(e) && System.currentTimeMillis() - startTimeMs < maxRetryTimeLimitMs =>
          retryFor(cond)(f)
      }

    retryFor(_.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))(
      for {
        _       <- Future.successful(tc.transactionOptions.fold(session.startTransaction)(session.startTransaction _))
        res     <- f.recoverWith { case e1 =>
                     session.abortTransaction().toSingle().toFuture()
                       .recoverWith {
                         case e2 => logger.error(s"Error aborting transaction: ${e2.getMessage}", e2)
                                    Future.failed(e1)
                       }
                       .flatMap(_ => Future.failed(e1))
                   }
        _       <- retryFor(e =>
                     !e.isInstanceOf[MongoExecutionTimeoutException]
                     && e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
                   )(session.commitTransaction().toSingle().toFuture())
      } yield res
    )
  }

  //--  Observable ---

  def withSessionAndTransaction[A](
    f: ClientSession => Observable[A]
  )(implicit
    tc: TransactionConfiguration
  ): Observable[A] =
    withClientSession(session =>
      withTransaction(session, f(session))
    )

  def withClientSession[A](
    f: ClientSession => Observable[A]
  )(implicit
    tc: TransactionConfiguration
  ): Observable[A] =
    for {
      session <- tc.clientSessionOptions.fold(mongoComponent.client.startSession())(mongoComponent.client.startSession _)
      res     <- f(session).recover { case e => session.close(); throw e }
      _       =  session.close()
      } yield res

  /** A transaction is started before running the continuation. It will be committed or aborted depending on whether the callback executes
    * successfully or not.
    * It also provides retries for network issues
    * See https://github.com/mongodb/mongo-java-driver/tree/r4.3.1/driver-core/src/test/resources/transactions-convenient-api#retry-timeout-is-enforced
    * And https://docs.mongodb.com/manual/core/retryable-writes/#retryable-writes-and-multi-document-transactions
    */
  // based on implementation: https://github.com/mongodb/mongo-java-driver/blob/r4.3.1/driver-sync/src/main/com/mongodb/client/internal/ClientSessionImpl.java#L203-L247
  def withTransaction[A](
    session: ClientSession,
    f      : => Observable[A]
  )(implicit
    tc: TransactionConfiguration
  ): Observable[A] = {
    val startTimeMs = System.currentTimeMillis()

    def retryFor[B](cond: MongoException => Boolean)(f: => Observable[B]): Observable[B] =
      f.recoverWith {
        case e: MongoException if cond(e) && System.currentTimeMillis() - startTimeMs < maxRetryTimeLimitMs =>
          logger.error(s"Failed with ${e.getLocalizedMessage} - will retry")
          retryFor(cond)(f)
      }

    retryFor(_.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)){
      tc.transactionOptions.fold(session.startTransaction)(session.startTransaction _)
      for {
        res <- f.recoverWith { case e1 =>
                 completeWith(session.abortTransaction(), ())
                   .recover {
                     case e2 => logger.error(s"Error aborting transaction: ${e2.getMessage}", e2)
                                throw e1
                   }
                   .map[A](_ => throw e1)
               }
        _   <- retryFor(e =>
                 !e.isInstanceOf[MongoExecutionTimeoutException]
                 && e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)
               )(completeWith(session.commitTransaction(), ()))
      } yield res
    }
  }

  /** Observable[Void] by definition will never emit a value to be mapped (see org.mongodb.scala.internal.MapObservable).
    * When converting to Future, it works since it completes with `None` (see org.mongodb.scala.Observable.headOption).
    * This function converts an Observable[Void] to the provided value to continue.
    *
    * `completeWithUnit` now exists on `Observable` but throws "java.lang.IllegalStateException: The Observable has not been subscribed to."
    */
  def completeWith[A](obs: Observable[Void], f: => A): Observable[A] =
    new Observable[A] {
      override def subscribe(observer: Observer[_ >: A]): Unit =
        obs.subscribe(
          new Observer[Void] {
            override def onError(throwable: Throwable): Unit =
              observer.onError(throwable)

            override def onSubscribe(subscription: Subscription): Unit =
              observer.onSubscribe(subscription)

            override def onComplete(): Unit = {
              observer.onNext(f)
              observer.onComplete()
            }

            override def onNext(tResult: Void): Unit =
              ??? // by definition never called
          }
        )
    }
}


case class TransactionConfiguration(
  clientSessionOptions: Option[ClientSessionOptions] = None, // will default to that defined on client
  transactionOptions  : Option[TransactionOptions]   = None  // will default to that defined by ClientSessionOptions
)

object TransactionConfiguration {
  /** A TransactionConfiguration with strict causal consistency.
    * You may get away with a more relaxed configuration, which can be more performant.
    * See https://docs.mongodb.com/manual/core/causal-consistency-read-write-concerns/
    */
  lazy val strict: TransactionConfiguration =
    TransactionConfiguration(
      clientSessionOptions = Some(
                               ClientSessionOptions.builder()
                                 .causallyConsistent(true)
                                 .build()
                             ),
      transactionOptions   = Some(
                               TransactionOptions.builder()
                                 .readConcern(ReadConcern.MAJORITY)
                                 .writeConcern(WriteConcern.MAJORITY)
                                 .build()
                             )
    )
}
