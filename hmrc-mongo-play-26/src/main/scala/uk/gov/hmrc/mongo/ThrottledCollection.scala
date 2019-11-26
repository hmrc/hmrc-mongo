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

package uk.gov.hmrc.servicedependencies.persistence

import org.mongodb.scala._
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.result.UpdateResult
import play.api.{Configuration, Logger}
import scala.concurrent.{ExecutionContext, Future}


class ThrottledCollection[T](configuration: Configuration, collection: MongoCollection[T])(implicit ec: ExecutionContext) {
  import java.util.concurrent.Executors

  val throttleSize = configuration.getOptional[Int]("mongodb.throttlesize").getOrElse(100)
  Logger.debug(s"ThrottledCollection using throttleSize=$throttleSize")

  val throttledEc = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(throttleSize))

  import scala.concurrent.duration.DurationInt

  def throttled(f: => SingleObservable[UpdateResult]): SingleObservable[UpdateResult] =
    toObservable(Future {
      println(s"${Thread.currentThread}")
      scala.concurrent.Await.result(f.toFuture, 100.seconds)
    }(throttledEc))(ec)


  def updateMany(filter: Bson, update: Bson): SingleObservable[UpdateResult] =
    throttled {
      collection.updateMany(filter, update)
    }

  def updateOne(filter: Bson, update: Bson): SingleObservable[UpdateResult] =
    throttled {
      collection.updateOne(filter, update)
    }


  import org.mongodb.scala.{Observer, SingleObservable, Subscription}
  import scala.util.{Success, Failure}

  def toObservable[A](f: Future[A])(implicit ec: ExecutionContext): SingleObservable[A] = new SingleObservable [A] {
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
                  case Failure(e) => observer.onError(e)
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
