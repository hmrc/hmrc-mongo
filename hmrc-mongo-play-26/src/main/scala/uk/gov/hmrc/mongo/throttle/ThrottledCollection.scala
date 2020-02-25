/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.throttle

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.{Observable, SingleObservable}
import play.api.{Configuration, Logger}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}


/** Single instance to ensure same throttle is applied across all mongo queries */
@Singleton
class ThrottleConfig @Inject()(configuration: Configuration) {

  val throttleSize =
    configuration.getOptional[Int]("mongodb.throttle.size").getOrElse(500)

  Logger.debug(s"Throttling mongo queries using throttleSize=$throttleSize")

  val throttledEc =
    ExecutionContext.fromExecutorService(
      java.util.concurrent.Executors.newFixedThreadPool(throttleSize))

  val timeout =
    configuration.getOptional[Duration]("mongodb.throttle.timeout").getOrElse(20.seconds)
}

trait WithThrottling {
  def throttleConfig: ThrottleConfig

  protected[throttle] def throttling[A, B](a: => A)(f: A => Future[B])(implicit ec: ExecutionContext): Future[B] =
    Mdc.preservingMdc(
      Future(
        Await.result(f(a), throttleConfig.timeout)
      )(throttleConfig.throttledEc)
    )(ec)

  // the following overrides of `toFuture` ensure that the observables are evaluated on the throttled thread-pool.

  private object ObservableImplicits extends org.mongodb.scala.ObservableImplicits

  implicit class SingleObservableFuture[T](observable: => SingleObservable[T])(implicit ec: ExecutionContext) {
    def toFuture(): Future[T] =
      throttling(observable)(new ObservableImplicits.SingleObservableFuture(_).toFuture)

    def toFutureOption(): Future[Option[T]] =
      throttling(observable)(new ObservableImplicits.SingleObservableFuture(_).toFutureOption)
  }

  implicit class ObservableFuture[T](observable: => Observable[T])(implicit ec: ExecutionContext) {
    def toFuture(): Future[Seq[T]] =
      throttling(observable)(new ObservableImplicits.ObservableFuture(_).toFuture)
  }
}