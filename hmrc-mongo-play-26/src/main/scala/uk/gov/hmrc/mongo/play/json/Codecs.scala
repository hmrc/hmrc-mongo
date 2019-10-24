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

package uk.gov.hmrc.mongo.play.json

import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.{BsonReader, BsonWriter, BsonType}
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.collection.immutable.Document
import play.api.libs.json._

import scala.reflect.ClassTag

trait Codecs {
  def playFormatCodec[A](format: Format[A])(implicit ct: ClassTag[A]): Codec[A] = new Codec[A] {
    override def getEncoderClass: Class[A] =
      ct.runtimeClass.asInstanceOf[Class[A]]

    override def encode(writer: BsonWriter, value: A, encoderContext: EncoderContext): Unit =
      format.writes(value) match {
        case JsNull       => sys.error(s"Unsupported encoding of $value (as JsNull)")
        case JsBoolean(b) => DEFAULT_CODEC_REGISTRY.get(classOf[Boolean])
                               .encode(writer, b, encoderContext)
        case JsNumber(n)  => DEFAULT_CODEC_REGISTRY.get(classOf[Number])
                               .encode(writer, n, encoderContext)
        case JsString(s)  => DEFAULT_CODEC_REGISTRY.get(classOf[String])
                               .encode(writer, s, encoderContext)
        case _: JsArray   => sys.error(s"Unsupported encoding of $value (as JsArray)")
        case o: JsObject  => DEFAULT_CODEC_REGISTRY.get(classOf[Document])
                               .encode(writer, Document(o.toString), encoderContext)
      }

    override def decode(reader: BsonReader, decoderContext: DecoderContext): A = {
      def decodeVal[T](clazz: Class[T]) =
        DEFAULT_CODEC_REGISTRY.get(clazz)
          .decode(reader, decoderContext)

      val json = reader.getCurrentBsonType match {
        case BsonType.BOOLEAN    => JsBoolean(decodeVal(classOf[Boolean]))
        case BsonType.DOUBLE
           | BsonType.INT32
           | BsonType.INT64
           | BsonType.DECIMAL128 => JsNumber(decodeVal(classOf[BigDecimal]))
        case BsonType.DOCUMENT   => Json.parse(decodeVal(classOf[Document]).toJson)
        case BsonType.STRING     => JsString(decodeVal(classOf[String]))
        case other               => sys.error(s"Unsupported decoding of $other")
      }

      format.reads(json) match {
        case JsSuccess(v, _) => v
        case JsError(errors) => sys.error(s"Failed to parse json as ${ct.runtimeClass.getName} '$json': $errors")
      }
    }
  }
}

object Codecs extends Codecs
