/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.mongo.play.json

import org.bson.codecs.Codec
import play.api.libs.json._

import scala.reflect.runtime.universe._
import scala.reflect.ClassTag

private [json] trait CodecHelper {
  private[json] def playFormatSumCodec[A, B <: A](
    format: Format[A]
  )(implicit ct: ClassTag[B]): Codec[B]

 /** This variant of `playFormatCodec` allows to register codecs for all direct subclasses, which are defined by a play format for a supertype.
    * This is helpful when writing an instance of the subclass to mongo, since codecs are looked up by reflection, and the format will need to be registered explicitly for the subclass.
    *
    * E.g.
    * ```
    * sealed trait Sum
    * case class Sum1() extends Sum
    * case class Sum2() extends Sum
    * val sumFormat: Format[Sum] = ...
    *   new PlayMongoRepository[Sum](
    *     domainFormat = sumFormat,
    *     extraCodecs  = Codecs.playFormatSumCodecs(sumFormat)
    *   )
    * ```
    * @throws IllegalArgumentException if the class is not a sealed trait
    */
 def playFormatSumCodecs[A](
    format: Format[A]
  )(implicit tt: TypeTag[A]): Seq[Codec[_]] = {
    val clazz: ClassSymbol =
      tt.tpe.typeSymbol.asClass

    // requirements such that `clazz.knownDirectSubclasses` includes all possible types
    require(clazz.isSealed)
    require(clazz.isAbstract)

    clazz.knownDirectSubclasses
      .collect { case c: ClassSymbol => c }
      .map(subclassSymbol => playFormatSumCodec(format)(ClassTag(tt.mirror.runtimeClass(subclassSymbol))))
      .toSeq
  }
}
