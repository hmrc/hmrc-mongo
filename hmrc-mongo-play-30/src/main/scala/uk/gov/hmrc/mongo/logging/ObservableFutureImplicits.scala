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

package uk.gov.hmrc.mongo.logging

import org.slf4j.MDC
import org.mongodb.scala.{Observable, SingleObservable}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

// TODO this code lives in http-verbs, if it's moved to a new logging library it could be reused here
private object Mdc {

  def mdcData: Map[String, String] =
    Option(MDC.getCopyOfContextMap).map(_.asScala.toMap).getOrElse(Map.empty)

  def withMdc[A](block: => Future[A], mdcData: Map[String, String])(implicit ec: ExecutionContext): Future[A] =
    block.map { a =>
      putMdc(mdcData)
      a
    }.recoverWith {
      case t =>
        putMdc(mdcData)
        Future.failed(t)
    }

  def putMdc(mdc: Map[String, String]): Unit =
    mdc.foreach {
      case (k, v) => MDC.put(k, v)
    }

  /** Restores MDC data to the continuation of a block, which may be discarding MDC data (e.g. uses a different execution context)
    */
  def preservingMdc[A](block: => Future[A])(implicit ec: ExecutionContext): Future[A] =
    withMdc(block, mdcData)
}

/** Can be used instead of [[org.mongodb.scala.ObservableFuture]] and [[org.mongodb.scala.SingleObservableFuture]]
  * to convert an [[Observable]] to a [[Future]] but preserving any MDC data for logging.
  */
trait ObservableFutureImplicits {
  implicit class ObservableFuture[T](obs: => Observable[T]) {
    val observable = obs

    /** Same as [[org.mongodb.scala.ObservableFuture#toFuture]] but preserves Mdc */
    def toFuture()(implicit ec: ExecutionContext): Future[Seq[T]] =
      Mdc.preservingMdc(
        observable.collect().head()
      )
  }

  implicit class SingleObservableFuture[T](obs: => SingleObservable[T]) {
    val observable = obs

    /** Same as [[org.mongodb.scala.SingleObservableFuture#toFuture]] but preserves MDC */
    def toFuture()(implicit ec: ExecutionContext): Future[T] =
      Mdc.preservingMdc(
        observable.head()
      )

    /** Same as [[org.mongodb.scala.SingleObservableFuture#toFutureOption]] but preserves MDC */
    def toFutureOption()(implicit ec: ExecutionContext): Future[Option[T]] =
      Mdc.preservingMdc(
        observable.headOption()
      )
  }
}

object ObservableFutureImplicits extends ObservableFutureImplicits
