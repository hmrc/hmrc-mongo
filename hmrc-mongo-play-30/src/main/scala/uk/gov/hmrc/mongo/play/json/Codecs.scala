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

trait Codecs extends CodecHelper {
  outer =>
  val logger: Logger = LoggerFactory.getLogger(classOf[Codecs].getName)

  private val bsonDocumentCodec = DEFAULT_CODEC_REGISTRY.get(classOf[BsonDocument])
  private val bsonValueCodec    = DEFAULT_CODEC_REGISTRY.get(classOf[BsonValue])

  def playFormatCodec[A](
    format: Format[A]
  )(implicit ct: ClassTag[A]): Codec[A] =
    playFormatSumCodec[A, A](format, ct.runtimeClass.asInstanceOf[Class[A]])


  /** This variant of `playFormatCodec` allows to register a codec for subclasses, which are defined by a play format for a supertype.
    * This is helpful when writing an instance of the subclass to mongo, since codecs are looked up by reflection, and the format will need to be registered explicitly for the subclass.
    *
    * See @playFormatSumCodecsBuilder for a simplified notation when registering for multiple subclasses.
    */
  private[json] def playFormatSumCodec[A, B <: A](
    format: Format[A],
    clazz : Class[B]
  ): Codec[B] = new Codec[B] {

    override def getEncoderClass: Class[B] =
      clazz

    override def encode(writer: BsonWriter, value: B, encoderContext: EncoderContext): Unit = {
      val bs: BsonValue = jsonToBson(format.writes(value))
      bsonValueCodec.encode(writer, bs, encoderContext)
    }

    override def decode(reader: BsonReader, decoderContext: DecoderContext): B = {
      val bs: BsonValue =
        bsonValueCodec
          .decode(reader, decoderContext)

      val json = bsonToJson(bs)

      format.reads(json) match {
        case JsSuccess(v, _) => scala.util.Try(v.asInstanceOf[B]).getOrElse(sys.error(s"Failed to parse json as ${getEncoderClass.getName} - it was ${v.getClass.getName}")) // TODO can we use ClassTag when present for improved feedback
        case JsError(errors) => sys.error(s"Failed to parse json as ${clazz.getName} : $errors")
      }
    }
  }

  /** This variant of `playFormatCodec` allows to register a codec for subclasses, which are defined by a play format for a supertype.
    * This is helpful when writing an instance of the subclass to mongo, since codecs are looked up by reflection, and the format will need to be registered explicitly for the subclass.
    *
    * It makes it easier to register for multiple subclasses together.
    *
    * E.g.
    * ```
    * sealed trait Sum
    * case class Sum1() extends Sum
    * case class Sum2() extends Sum
    * val sumFormat: Format[Sum] = ...
    *   new PlayMongoRepository[Sum](
    *     domainFormat = sumFormat,
    *     extraCodecs  = Codecs.playFormatCodecsBuilder(sumFormat).forType[Sum1].forType[Sum2].build
    *   )
    * ```
    */
  def playFormatCodecsBuilder[A](
    format: Format[A]
  )(implicit ct: ClassTag[A]) =
    new SumCodecsBuilder(format, Seq(playFormatSumCodec[A, A](format, ct.runtimeClass.asInstanceOf[Class[A]])))

  class SumCodecsBuilder[A] private[json](
    format: Format[A],
    acc   : Seq[Codec[_]]
  ) {
    def forType[B <: A](implicit ct: ClassTag[B]): SumCodecsBuilder[A] =
      new SumCodecsBuilder[A](
        format,
        acc :+ playFormatSumCodec[A, B](format, ct.runtimeClass.asInstanceOf[Class[B]])
      )

    def build: Seq[Codec[_]] =
      acc
  }

  def toBson[A: Writes](a: A): BsonValue =
    jsonToBson(Json.toJson(a))

  def fromBson[A: Reads](bs: BsonValue): A = bsonToJson(bs).as[A]

  private def jsonToBson(js: JsValue): BsonValue =
    js match {
      case JsNull       => BsonNull.VALUE
      case JsBoolean(b) => BsonBoolean.valueOf(b)
      case JsTrue       => BsonBoolean.valueOf(true)  // added to keep compiler warnings happy - but is unreachable since extends JsBoolean
      case JsFalse      => BsonBoolean.valueOf(false) // added to keep compiler warnings happy - but is unreachable since extends JsBoolean
      case JsNumber(n)  => toBsonNumber(n)
      case JsString(s)  => new BsonString(s)
      case JsArray(a)   => new BsonArray(a.map(jsonToBson).asJava)
      case o: JsObject  =>
        if (o.keys.exists(k => k.startsWith("$") && !List("$numberDecimal", "$numberLong").contains(k)))
          // mongo types, identified with $ in `MongoDB Extended JSON format`  (e.g. BsonObjectId, BsonDateTime)
          // should use default conversion to Json. Then PlayJsonReaders will then convert as appropriate
          // The exception are numbers handled above (otherwise precision of $numberDecimal will be lost)
          fromJsonDefault(o)
        else
          new BsonDocument(
            o.fields.map {
              case (k, v) =>
                new BsonElement(k, jsonToBson(v))
            }.asJava
          )
    }

  private def bsonToJson(bs: BsonValue): JsValue =
    bs match {
      case _: BsonNull        => JsNull
      case b: BsonBoolean     => JsBoolean(b.getValue)
      case i: BsonInt32       => JsNumber(i.getValue)
      case l: BsonInt64       => JsNumber(l.getValue)
      case d: BsonDouble      => JsNumber(d.getValue)
      case bd: BsonDecimal128 => // throws ArithmeticException if the Decimal128 value is NaN, Infinity, -Infinity, or -0, none of which can be represented as a BigDecimal
        // Should be OK since these values will not have been written to db from BigDecimal.
        JsNumber(bd.getValue.bigDecimalValue)
      case s: BsonString => JsString(s.getValue)
      case d: BsonDocument =>
        JsObject {
          // Implementation attempts to preserve order as in BSON document (which relies on play's JSON implementation).
          // Note, this however is not necessarily the orginal order, since `_id` always comes first.
          d.entrySet.asScala.toList.map(e => (e.getKey, bsonToJson(e.getValue)))
        }
      case a: BsonArray => JsArray(a.getValues.asScala.map(bsonToJson))
      case other => // other types, attempt to convert to json object (Extended = `MongoDB Extended JSON format`)
        toJsonDefault(other, JsonMode.EXTENDED) match {
          case JsDefined(s)   => s
          case _: JsUndefined => logger.debug(s"Could not convert $other to Json"); JsNull
        }
    }

  private def toBsonNumber(bd: BigDecimal): BsonValue =
    if (!bd.ulp.isWhole && bd.isDecimalDouble) new BsonDouble(bd.doubleValue)
    else if (bd.isValidInt) new BsonInt32(bd.intValue)
    else if (bd.isValidLong) new BsonInt64(bd.longValue)
    else // Not all bigDecimals are representable as Decimal128. Will throw [java.lang.NumberFormatException] with message: `Conversion to Decimal128 would require inexact rounding of -4.2176255923279509728936555398034786404E-54.`
      new BsonDecimal128(new Decimal128(bd.bigDecimal))

  private def toJsonDefault(bs: BsonValue, mode: JsonMode): JsLookupResult = {
    // wrap value in a document inorder to reuse the document -> JsonString, then extract
    val writer = new java.io.StringWriter
    val doc    = new BsonDocument("tempKey", bs)
    val writerSettings = JsonWriterSettings.builder.outputMode(mode).build
    bsonDocumentCodec.encode(new JsonWriter(writer, writerSettings), doc, EncoderContext.builder.build)
    Json.parse(writer.toString) \ "tempKey"
  }

  private def fromJsonDefault(o: JsObject): BsonValue = {
    // wrap value in a document inorder to reuse the Json -> document, then extract
    val o2  = JsObject(Seq(("tempKey", o)))
    val doc = bsonDocumentCodec.decode(new JsonReader(o2.toString), DecoderContext.builder.build)
    doc.get("tempKey")
  }

  implicit class JsonOps[A: Writes](a: A) {
    def toBson: BsonValue = outer.toBson(a)

    def toDocument: ScalaDocument = outer.toBson(a).asDocument()
  }

  implicit class BsonOps(bs: BsonValue) {
    def fromBson[T: Reads]: T = outer.fromBson(bs)
  }

  implicit class DocumentOps(document: ScalaDocument) {
    def fromBson[T: Reads]: T = outer.fromBson(document.toBsonDocument)
  }

  implicit class DocumentsOps(documents: Seq[ScalaDocument]) {
    def fromBson[T: Reads]: Seq[T] = documents.map(document => outer.fromBson(document.toBsonDocument))
  }
}

object Codecs extends Codecs
