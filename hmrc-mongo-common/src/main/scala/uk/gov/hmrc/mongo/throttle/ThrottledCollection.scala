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
import org.mongodb.scala.{MongoClient, Observable, SingleObservable}
import play.api.{Configuration, Logger}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}


/** Single instance to ensure same throttle is applied across all mongo queries */
@Singleton
class ThrottleConfig @Inject()(configuration: Configuration) {

  val maxWaitQueueSize = {
    val mongoUri = configuration.get[String]("mongodb.uri")
    val client = MongoClient(uri = mongoUri)
    client.settings.getConnectionPoolSettings.getMaxWaitQueueSize
  }

  /** size should be no larger than the WaitQueueSize (default size is 500)
    * larger than this will cause `com.mongodb.MongoWaitQueueFullException: Too many operations are already waiting for a connection. Max number of operations (maxWaitQueueSize) of 500 has been exceeded.`
    */
  val throttleSize =
    configuration.getOptional[Int]("mongodb.throttle.size").getOrElse(maxWaitQueueSize)

  Logger.debug(s"Throttling mongo queries using throttleSize=$throttleSize")

  val throttledEc =
    ExecutionContext.fromExecutorService(
      java.util.concurrent.Executors.newFixedThreadPool(throttleSize))

  val timeout =
    configuration.getOptional[Duration]("mongodb.throttle.timeout").getOrElse(20.seconds)
}

trait WithThrottling{
  def throttleConfig: ThrottleConfig

  // Either pass Observable[A], SingleObservable[A] to `throttledF` to get a Future[A]

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

  // Or provide implicit conversions to add `.toThrottledFuture` method to Observable, SingleObservable.

  // Once mongo-scala library is updated to take observable by-name, our implicits conversions can take precedence
  // over the library provided ones, and these methods can be renamed to `.toFuture`

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
  }

  implicit class ScalaObservable[T](observable: => Observable[T])(implicit ec: ExecutionContext) {
    def toThrottledFuture(): Future[Seq[T]] =
      Mdc.preservingMdc {
        Future {
          Await.result(observable.toFuture, throttleConfig.timeout)
        }(throttleConfig.throttledEc)
      }
  }
}