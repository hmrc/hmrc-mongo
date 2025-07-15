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

import org.mongodb.scala.{Observable, SingleObservable}
import uk.gov.hmrc.mdc.Mdc

import scala.concurrent.{ExecutionContext, Future}

/** Can be used instead of [[org.mongodb.scala.ObservableFuture]] and [[org.mongodb.scala.SingleObservableFuture]]
  * to convert an [[Observable]] to a [[Future]] but preserving any MDC data for logging.
  */
trait ObservableFutureImplicits {
  implicit class ObservableFuture[T](obs: => Observable[T]) extends org.mongodb.scala.ObservableFuture(obs) {

    /** Same as [[org.mongodb.scala.ObservableFuture#toFuture]] but preserves Mdc */
    def toFuture()(implicit ec: ExecutionContext): Future[Seq[T]] =
      Mdc.preservingMdc(
        super.toFuture()
      )
  }

  implicit class SingleObservableFuture[T](obs: => SingleObservable[T]) extends org.mongodb.scala.SingleObservableFuture(obs) {

    /** Same as [[org.mongodb.scala.SingleObservableFuture#toFuture]] but preserves MDC */
    def toFuture()(implicit ec: ExecutionContext): Future[T] =
      Mdc.preservingMdc(
        super.toFuture()
      )

    /** Same as [[org.mongodb.scala.SingleObservableFuture#toFutureOption]] but preserves MDC */
    def toFutureOption()(implicit ec: ExecutionContext): Future[Option[T]] =
      Mdc.preservingMdc(
        super.toFutureOption()
      )
  }
}

object ObservableFutureImplicits extends ObservableFutureImplicits
