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
import org.mongodb.scala.{MongoClient, Observable, Observer, SingleObservable, Subscription}
import play.api.{Configuration, Logger}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}
import scala.util.{Failure, Success}


/** Single instance to ensure same throttle is applied across all mongo queries */
@Singleton
class ThrottleConfig @Inject()(configuration: Configuration) {

  val maxWaitQueueSize = {
    // TODO safe to create another MongoClient? Or should inject PlayMongoComponent ...
    val mongoUri = configuration.get[String]("mongodb.uri")
    val client = MongoClient(uri = mongoUri)
    client.settings.getConnectionPoolSettings.getMaxWaitQueueSize
  }

  /** size should be no larger than the WaitQueueSize (default size is 500)
    * larger than this will cause `com.mongodb.MongoWaitQueueFullException: Too many operations are already waiting for a connection. Max number of operations (maxWaitQueueSize) of 500 has been exceeded.`
    */
  val throttleSize =
    configuration.getOptional[Int]("mongodb.throttlesize").getOrElse(maxWaitQueueSize)

  Logger.debug(s"Throttling mongo queries using throttleSize=$throttleSize")

  val throttledEc =
    ExecutionContext.fromExecutorService(
      java.util.concurrent.Executors.newFixedThreadPool(throttleSize))

  val timeout =
    configuration.getOptional[Duration]("mongodb.queryTimeout").getOrElse(20.seconds)
}

trait WithThrottling{
  def throttleConfig: ThrottleConfig

  def throttled[A](f: => SingleObservable[A])(implicit ec: ExecutionContext): SingleObservable[A] =
    toSingleObservable(
      Future {
        Await.result(f.toFuture, throttleConfig.timeout)
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

  // following have same type after erasure: (f: Function0, implicit ec: scala.concurrent.ExecutionContext)scala.concurrent.Future

  // def throttledF[A](f: => SingleObservable[A])(implicit ec: ExecutionContext): Future[A] =
  //   Future {
  //     Await.result(f.toFuture, 100.seconds)
  //   }(throttleConfig.throttledEc)

  // def throttledF[A](f: => Observable[A])(implicit ec: ExecutionContext): Future[Seq[A]] =
  //   Future {
  //     Await.result(f.toFuture, 100.seconds)
  //   }(throttleConfig.throttledEc)

  // with FunDep:

  trait FunDep[A, In[_], Out[_]] {
    def apply(in: In[A]): Future[Out[A]]
  }

  type Identity[A] = A

  implicit def so[A] = new FunDep[A, SingleObservable, Identity] {
    def apply(in: SingleObservable[A]): Future[A] = in.toFuture
  }

  implicit def o[A] = new FunDep[A, Observable, Seq] {
    def apply(in: Observable[A]): Future[Seq[A]] = in.toFuture
  }

  def throttledF[A, In[_], Out[_]](f: => In[A])(implicit ec: ExecutionContext, fd: FunDep[A, In, Out]): Future[Out[A]] =
    Mdc.preservingMdc {
      Future {
        Await.result(fd(f), throttleConfig.timeout)
      }(throttleConfig.throttledEc)
    }

  implicit class ScalaSingleObservable[T](observable: => SingleObservable[T])(implicit ec: ExecutionContext) {
    def toThrottledFuture(): Future[T] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFuture, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }

    def toThrottledFutureOption(): Future[Option[T]] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFutureOption, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }

    // TODO It would be preferable to overload toFuture.
    // However this will never override the default [[org.mongodb.scala.ScalaSingleObservable]] definition, since the
    // param is by name (Function0[SingleObservable[T]]) and is less specific than the default (although Intellij disagrees)

    def toFuture(): Future[T] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFuture, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }

    def toFutureOption(): Future[Option[T]] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFutureOption, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }
  }

  implicit class ScalaObservable[T](observable: => Observable[T])(implicit ec: ExecutionContext) {
    def toThrottledFuture(): Future[Seq[T]] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFuture, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }

    // TODO It would be preferable to overload toFuture.
    // However this will never override the default [[org.mongodb.scala.ScalaObservable]] definition, since the
    // param is by name (Function0[SingleObservable[T]]) and is less specific than the default (although Intellij disagrees)

    def toFuture(): Future[Seq[T]] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFuture, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }
  }
}