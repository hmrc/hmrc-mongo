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
import org.mongodb.scala.{Observable, Observer, SingleObservable, Subscription}
import play.api.{Configuration, Logger}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}


/** Single instance to ensure same throttle is applied across all mongo queries */
@Singleton
class ThrottleConfig @Inject()(configuration: Configuration) {
  // default size is 500 == default WaitQueueSize
  // larger than this will cause `com.mongodb.MongoWaitQueueFullException: Too many operations are already waiting for a connection. Max number of operations (maxWaitQueueSize) of 500 has been exceeded.`
  val maxWaitQueueSize = 500
  // TODO look this up?
  //   e.g. if inject PlayMongoComponent (which requires moving into hmrc-mongo-play), then:
  //   (component: PlayMongoComponent).client.settings.getConnectionPoolSettings.getMaxWaitQueueSize
  //   although .settings is deprecated...
  // TODO if looked up, does it need to be overrideable in config?
  val throttleSize = configuration.getOptional[Int]("mongodb.throttlesize").getOrElse(maxWaitQueueSize )
  Logger.debug(s"Throttling mongo queries using throttleSize=$throttleSize")

  val throttledEc =
    ExecutionContext.fromExecutorService(
      java.util.concurrent.Executors.newFixedThreadPool(throttleSize))
}

// TODO preserving MDC?
trait WithThrottling{
  def throttleConfig: ThrottleConfig

  def throttled[A](f: => SingleObservable[A])(implicit ec: ExecutionContext): SingleObservable[A] =
    toSingleObservable(
      Future {
        Await.result(f.toFuture, 100.seconds)
      }(throttleConfig.throttledEc)
    )(ec)

  // In addition to SingleObservable, would also need to support the types:
  //   FindObservable, AggregateObservable, DistinctObservable, DistinctObservable, MapReduceObservable, Observable, ListIndexesObservable, ChangeStreamObservable
  // But non-trivial to implement conversions from Future[Seq[A]] back to expected type...

  private def toSingleObservable[A](f: Future[A])(implicit ec: ExecutionContext): SingleObservable[A] =
    // based on SingleItemObservable implementation
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

  implicit class ScalaSingleObservable[T](observable: => SingleObservable[T]) {
    def toThrottledFuture(): Future[T] =
      Future {
        Await.result(observable.toFuture, 20.seconds)
      }(throttleConfig.throttledEc)

    def toThrottledFutureOption(): Future[Option[T]] =
      Future {
        Await.result(observable.toFutureOption, 20.seconds)
      }(throttleConfig.throttledEc)

    // TODO preferable to overload toFuture. But how to ensure it is picked up instead of org.mongodb.scala.ScalaSingleObservable?

    def toFuture(): Future[T] =
      Future {
        Await.result(observable.toFuture, 20.seconds)
      }(throttleConfig.throttledEc)

    def toFutureOption(): Future[Option[T]] =
      Future {
        Await.result(observable.toFutureOption, 20.seconds)
      }(throttleConfig.throttledEc)
  }

  implicit class ScalaObservable[T](observable: => Observable[T]) {
    def toThrottledFuture(): Future[Seq[T]] =
      Future {
        Await.result(observable.toFuture, 20.seconds)
      }(throttleConfig.throttledEc)

    def toFuture(): Future[Seq[T]] =
      Future {
        Await.result(observable.toFuture, 20.seconds)
      }(throttleConfig.throttledEc)
  }
}
