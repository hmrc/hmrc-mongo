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

import org.bson.{BsonNull, BsonReader, BsonWriter, BsonType}
import org.bson.codecs.{Codec, DecoderContext, EncoderContext}
import org.bson.json.{JsonWriterSettings, JsonMode}
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
        case JsNull       => writer.writeNull()
        case JsBoolean(b) => writer.writeBoolean(b)
        // case JsNumber(n) if !n.ulp.isWhole => //DEFAULT_CODEC_REGISTRY.get(classOf[org.bson.BsonDouble])
        //                                       //  .encode(writer, n, encoderContext)
        //                                       println(s"*********** Writing Double: $n (${n.ulp}) ${n.doubleValue}")
        //                                       writer.writeDouble(n.doubleValue)
        case JsNumber(n) if n.isValidInt   => writer.writeInt32(n.intValue)
        case JsNumber(n) if n.isValidLong  => writer.writeInt64(n.longValue)
        case JsNumber(n) /*if n.isDecimalDouble*/ => //DEFAULT_CODEC_REGISTRY.get(classOf[org.bson.BsonDouble])
                                              //  .encode(writer, n, encoderContext)
                                              println(s"*********** Writing Double: $n (${n.ulp}) ${n.doubleValue}")
                                              writer.writeDouble(n.doubleValue)
        // case JsNumber(n)                   => //DEFAULT_CODEC_REGISTRY.get(classOf[org.bson.BsonInt64])
        //                                       //  .encode(writer, n.bigDecimal, encoderContext)
        //                                       // println(s"!!!!!!!!!!!!!!!!!! writing ${n.bigDecimal} as Decimal...")
        //                                       // // Note, Decimal128 is written as { "$numberDecimal": "..." } and can't be converted to ... with JsonMode.RELAXED when reading back as document...
        //                                       // writer.writeDecimal128(new org.bson.types.Decimal128(n.bigDecimal))
        //                                       println(s"*********** Writing Other: $n (ulp: ${n.ulp}, isWhole: ${n.ulp.isWhole}) ${n.longValue}")
        //                                       writer.writeDecimal128(new org.bson.types.Decimal128(n.bigDecimal))
        //                                       //writer.writeInt64(n.longValue)
        case JsString(s)  => writer.writeString(s)
        case j: JsArray   => sys.error(s"Unsupported encoding of $value (as JsArray)")
                            //  DEFAULT_CODEC_REGISTRY.get(classOf[org.bson.BsonArray])
                            //    .encode(writer, BsonArray(), encoderContext)
        case o: JsObject  => DEFAULT_CODEC_REGISTRY.get(classOf[Document])
                               .encode(writer, Document(o.toString), encoderContext)
      }

    override def decode(reader: BsonReader, decoderContext: DecoderContext): A = {
      def decodeVal[T](clazz: Class[T]) =
        DEFAULT_CODEC_REGISTRY.get(clazz)
          .decode(reader, decoderContext)

      println(s">>>>>>>> reader.getCurrentBsonType=${reader.getCurrentBsonType}")

      val json = reader.getCurrentBsonType match {
        case BsonType.BOOLEAN    => JsBoolean(decodeVal(classOf[Boolean]))
        case BsonType.DOUBLE
           | BsonType.INT32
           | BsonType.INT64
           | BsonType.DECIMAL128 => val x = decodeVal(classOf[BigDecimal])
                                    println(s">>>>>>>>> decoding BigDecimal: $x")
                                    JsNumber(x)
        case BsonType.DOCUMENT   => val x = decodeVal(classOf[Document])
                                     // or alternative to Json skipping string step?

                                     println(s">>>>>>>>> decoding Document: $x")
                                     println(s">>>>>>>>> decoding Document (as json): ${x.toJson}")
                                     println(s">>>>>>>>> decoding Document (as json relaxed): ${x.toJson(new JsonWriterSettings(JsonMode.RELAXED))}")

                                    Json.parse(x.toJson(new JsonWriterSettings(JsonMode.RELAXED)))
        case BsonType.STRING     => JsString(decodeVal(classOf[String]))
        case BsonType.NULL       => JsNull
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