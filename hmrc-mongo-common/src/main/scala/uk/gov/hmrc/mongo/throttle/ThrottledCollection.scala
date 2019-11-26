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

package uk.gov.hmrc.mongo.throttle

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.{MongoCollection, Observer, SingleObservable, Subscription}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.result.UpdateResult
import play.api.{Configuration, Logger}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Success, Failure}


/** Single instance to ensure same throttle is applied across all mongo queries */
@Singleton
class ThrottlingConfig @Inject()(configuration: Configuration) {
  val throttleSize = configuration.getOptional[Int]("mongodb.throttlesize").getOrElse(100)
  Logger.debug(s"Throttling mongo queries using throttleSize=$throttleSize")

  val throttledEc =
    ExecutionContext.fromExecutorService(
      java.util.concurrent.Executors.newFixedThreadPool(throttleSize))
}

// TODO preserving MDC?
trait WithThrottling{
  def throttlingConfig: ThrottlingConfig

  def throttled[A](f: => SingleObservable[A])(implicit ec: ExecutionContext): SingleObservable[A] =
    toObservable(
      Future {
        scala.concurrent.Await.result(f.toFuture, 100.seconds)
      }(throttlingConfig.throttledEc)
    )(ec)

  private def toObservable[A](f: Future[A])(implicit ec: ExecutionContext): SingleObservable[A] =
    // based on SingleObservable.apply(a: A) implementation
    new SingleObservable [A] {
      override def subscribe(observer: Observer[_ >: A]): Unit = {
        observer.onSubscribe(
          new Subscription {
            @volatile
            private var subscribed: Boolean = true

            override def isUnsubscribed: Boolean = !subscribed

            override def request(n: Long): Unit = {
              require(n > 0L, s"Number requested must be greater than zero: $n")

              f.onComplete { t =>
                if (subscribed) {
                  t match {
                    case Success(item) => observer.onNext(item)
                    case Failure(e)    => observer.onError(e)
                  }
                  observer.onComplete()
                }
              }(ec)
            }

            override def unsubscribe(): Unit = subscribed = false
          }
        )
      }
    }
}
