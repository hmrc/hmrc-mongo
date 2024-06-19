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

import org.bson._
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.json.{JsonMode, JsonReader, JsonWriter, JsonWriterSettings}
import org.bson.types.Decimal128
import org.mongodb.scala.bsonDocumentToDocument
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.slf4j.{Logger, LoggerFactory}
import org.mongodb.scala.{Document => ScalaDocument}
import play.api.libs.json._

import scala.jdk.CollectionConverters._
import scala.reflect.ClassTag

private [json] trait CodecHelper {
  private[json] def playFormatSumCodec[A, B <: A](
    format: Format[A],
    clazz : Class[B]
  ): Codec[B]

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
    */
  inline def playFormatSumCodecs[T: scala.deriving.Mirror.SumOf](
    format: Format[T]
  ): Seq[Codec[_]] =
    sealedChildren[T].map(subclass => playFormatSumCodec(format, subclass.runtimeClass.asInstanceOf[Class[T]]))
      ++ singletonValues[T].map(value => playFormatSumCodec(format, value.getClass))


  // https://users.scala-lang.org/t/scala-3-macro-get-a-list-of-all-direct-child-objects-of-a-sealed-trait/8450
  // https://docs.scala-lang.org/scala3/reference/contextual/derivation.html
  private inline def sealedChildren[T](using m: scala.deriving.Mirror.SumOf[T]): Seq[ClassTag[T]] =
    sealedChildrenRec[m.MirroredType, m.MirroredElemTypes]

  inline def sealedChildrenRec[T, Elem <: Tuple]: List[ClassTag[T]] =
    import scala.compiletime.*

    inline erasedValue[Elem] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[ClassTag[t]].asInstanceOf[ClassTag[T]] :: sealedChildrenRec[T, ts]

  // Collect all values too - this only works if the enum *only* contains singletons
  // How to summon ValueOf[t] *only* when it exists?
  private inline def singletonValues[A](using m: scala.deriving.Mirror.SumOf[A]): Seq[A] =
    singletonValuesRec[m.MirroredType, m.MirroredElemTypes]

  inline def singletonValuesRec[T, Elem <: Tuple]: List[T] =
    import scala.compiletime.*

    inline erasedValue[Elem] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  => summonInline[ValueOf[t]].value.asInstanceOf[T] :: singletonValuesRec[T, ts]
}
