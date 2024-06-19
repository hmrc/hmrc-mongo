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
import scala.compiletime.{erasedValue, summonInline}
import scala.deriving.Mirror
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
  inline def playFormatSumCodecs[T: Mirror.SumOf](
    format: Format[T]
  ): Seq[Codec[_]] =
    sealedChildren[T]
      .map(subclass => playFormatSumCodec(format, subclass))


  // https://users.scala-lang.org/t/scala-3-macro-get-a-list-of-all-direct-child-objects-of-a-sealed-trait/8450
  // https://docs.scala-lang.org/scala3/reference/contextual/derivation.html
  private inline def sealedChildren[T](using m: Mirror.SumOf[T]): Seq[Class[? <: T]] =
    sealedChildrenRec[m.MirroredType, m.MirroredElemTypes]

  private inline def sealedChildrenRec[T, Elem <: Tuple]: List[Class[? <: T]] =
    inline erasedValue[Elem] match
      case _: EmptyTuple => Nil
      case _: (t *: ts)  =>
        // For enum singletons (implemented as anonymous types `val X = new MyEnum {}`)
        // the ClassTag gives a different result ("MyEnum") to X.getClass ("MyEnum$$anon$0") - which is used
        // when looking up the codec.
        // We can't use summonInline[ValueOf[T]] since this would fail if the enum contains non-singleton values.
        // Luckily for singleton values the mirror is actually the type itself.
        val c = summonInline[Mirror.Of[t]] match
                  case v: t => v.getClass.asInstanceOf[Class[? <: T]]
                  case _    => summonInline[ClassTag[t]].runtimeClass.asInstanceOf[Class[? <: T]]
        c :: sealedChildrenRec[T, ts]
}
