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

package uk.gov.hmrc.mongo.play

import org.scalacheck.{Arbitrary, Gen, Prop}
import org.scalatest.{AppendedClues, Matchers, OptionValues, WordSpecLike}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.Matchers.{equal => equal2, _}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.bson.codecs.configuration.{CodecRegistries, CodecRegistry}
import com.mongodb.client.result.UpdateResult
import org.mongodb.scala.{Completed, MongoCollection, MongoDatabase}
import org.mongodb.scala.bson.codecs.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.model.{Filters, Updates}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, MongoFormats}

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag

class PlayMongoCollectionSpec
  extends WordSpecLike
     with ScalaFutures
     with ScalaCheckDrivenPropertyChecks {

  import PlayMongoCollectionSpec._

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)

  val mongoComponent = {
    val databaseName: String = "test-" + this.getClass.getSimpleName
    MongoComponent(mongoUri = s"mongodb://localhost:27017/$databaseName")
  }

  val playMongoCollection = new PlayMongoCollection[MyObject](
      mongoComponent = mongoComponent
    , collectionName = "myobject"
    , domainFormat   = myObjectFormat
    , optRegistry    = Some(CodecRegistries.fromCodecs(
                         Codecs.playFormatCodec(stringWrapperFormat)
                       , Codecs.playFormatCodec(booleanWrapperFormat)
                       , Codecs.playFormatCodec(intWrapperFormat)
                       , Codecs.playFormatCodec(longWrapperFormat)
                       , Codecs.playFormatCodec(doubleWrapperFormat)
                       , Codecs.playFormatCodec(bigDecimalWrapperFormat)
                       , Codecs.playFormatCodec(astFormat)
                       ))
    , indexes        = Seq.empty
    )

  "PlayMongoCollection.collection" should {

    "read and write object with fields" in {
      forAll(myObjectGen) { myObj =>
        dropDatabase()
        val result = playMongoCollection.collection.insertOne(myObj).toFuture
        result.futureValue shouldBe Completed()

        val writtenObj = playMongoCollection.collection.find().toFuture
        writtenObj.futureValue shouldBe List(myObj)
      }
    }

    "filter by fields" in {
      forAll(myObjectGen) { myObj =>
        dropDatabase()

        val result = playMongoCollection.collection.insertOne(myObj).toFuture
        result.futureValue shouldBe Completed()

        val byString = playMongoCollection.collection.find(filter = Filters.equal("string", myObj.string)).toFuture
        byString.futureValue shouldBe List(myObj)

        val byBoolean = playMongoCollection.collection.find(filter = Filters.equal("boolean", myObj.boolean)).toFuture
        byBoolean.futureValue shouldBe List(myObj)

        val byInt = playMongoCollection.collection.find(filter = Filters.equal("int", myObj.int)).toFuture
        byInt.futureValue shouldBe List(myObj)

        val byLong = playMongoCollection.collection.find(filter = Filters.equal("long", myObj.long)).toFuture
        byLong.futureValue shouldBe List(myObj)

        val byDouble = playMongoCollection.collection.find(filter = Filters.equal("double", myObj.double)).toFuture
        byDouble.futureValue shouldBe List(myObj)

        val byBigDecimal = playMongoCollection.collection.find(filter = Filters.equal("bigDecimal", myObj.bigDecimal)).toFuture
        byBigDecimal.futureValue shouldBe List(myObj)

        // val byAst = playMongoCollection.collection.find(filter = Filters.equal("ast", myObj.ast)).toFuture
        // byAst.futureValue shouldBe List(myObj)
      }
    }

    "update fields" in {
      forAll(myObjectGen) { originalObj =>
        forAll(myObjectGen suchThat(_ != originalObj)) { targetObj =>
          dropDatabase()

          val result = playMongoCollection.collection.insertOne(originalObj).toFuture
          result.futureValue shouldBe Completed()

          val byString = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("string", targetObj.string)).toFuture
          byString.futureValue.wasAcknowledged shouldBe true

          val byBoolean = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("boolean", targetObj.boolean)).toFuture
          byBoolean.futureValue.wasAcknowledged shouldBe true

          val byInt = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("int", targetObj.int)).toFuture
          byInt.futureValue.wasAcknowledged shouldBe true

          val byLong = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("long", targetObj.long)).toFuture
          byLong.futureValue.wasAcknowledged shouldBe true

          val byDouble = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("double", targetObj.double)).toFuture
          byDouble.futureValue.wasAcknowledged shouldBe true

          val byBigDecimal = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("bigDecimal", targetObj.bigDecimal)).toFuture
          byBigDecimal.futureValue.wasAcknowledged shouldBe true

          // val byAst = playMongoCollection.collection.updateOne(filter = new com.mongodb.BasicDBObject(), update = Updates.set("ast", targetObj.ast)).toFuture
          // byAst.futureValue.wasAcknowledged shouldBe true

          val writtenObj = playMongoCollection.collection.find().toFuture
          writtenObj.futureValue shouldBe List(targetObj)
        }
      }
    }
  }

  def dropDatabase() =
    mongoComponent.database
      .drop()
      .toFuture
      .futureValue
}

object PlayMongoCollectionSpec {

  case class StringWrapper    (unwrap: String    ) extends AnyVal
  case class BooleanWrapper   (unwrap: Boolean   ) extends AnyVal
  case class IntWrapper       (unwrap: Int       ) extends AnyVal
  case class LongWrapper      (unwrap: Long      ) extends AnyVal
  case class DoubleWrapper    (unwrap: Double    ) extends AnyVal
  case class BigDecimalWrapper(unwrap: BigDecimal) extends AnyVal

  sealed trait Ast
  object Ast {
    case object Ast1 extends Ast
    case object Ast2 extends Ast
  }

  case class MyObject(
    string    : StringWrapper
  , boolean   : BooleanWrapper
  , int       : IntWrapper
  , long      : LongWrapper
  , double    : DoubleWrapper
  , bigDecimal: BigDecimalWrapper
  , ast       : Ast
  )

  implicit lazy val stringWrapperFormat: Format[StringWrapper] =
    implicitly[Format[String]].inmap(StringWrapper.apply, unlift(StringWrapper.unapply))

  implicit lazy val booleanWrapperFormat: Format[BooleanWrapper] =
    implicitly[Format[Boolean]].inmap(BooleanWrapper.apply, unlift(BooleanWrapper.unapply))

  implicit lazy val intWrapperFormat: Format[IntWrapper] =
    implicitly[Format[Int]].inmap(IntWrapper.apply, unlift(IntWrapper.unapply))

  implicit lazy val longWrapperFormat: Format[LongWrapper] =
    implicitly[Format[Long]].inmap(LongWrapper.apply, unlift(LongWrapper.unapply))

  implicit lazy val doubleWrapperFormat: Format[DoubleWrapper] =
    implicitly[Format[Double]].inmap(DoubleWrapper.apply, unlift(DoubleWrapper.unapply))

  implicit lazy val bigDecimalWrapperFormat: Format[BigDecimalWrapper] =
    implicitly[Format[BigDecimal]].inmap(BigDecimalWrapper.apply, unlift(BigDecimalWrapper.unapply))


  // TODO this is ineffective - codec is looked up by val.getClass
  // i.e. classOf[Ast.Ast1] not classOf[Ast]
  // Note, codec macro would generate a codec for both classOf[Ast.Ast1] and classOf[Ast.Ast2]
  implicit lazy val astFormat: Format[Ast] = new Format[Ast] {
    override def reads(js: JsValue) =
      js.validate[String]
        .flatMap { case "Ast1" => JsSuccess(Ast.Ast1)
                   case "Ast2" => JsSuccess(Ast.Ast2)
                   case other  => JsError(__, s"Unexpected Ast value $other")
                 }

    override def writes(ast: Ast) =
      ast match {
        case Ast.Ast1 => JsString("Ast1")
        case Ast.Ast2 => JsString("Ast2")
      }
  }

  val myObjectFormat =
    ( (__ \ "string"    ).format[StringWrapper]
    ~ (__ \ "boolean"   ).format[BooleanWrapper]
    ~ (__ \ "int"       ).format[IntWrapper]
    ~ (__ \ "long"      ).format[LongWrapper]
    ~ (__ \ "double"    ).format[DoubleWrapper]
    ~ (__ \ "bigDecimal").format[BigDecimalWrapper]
    ~ (__ \ "ast"       ).format[Ast]
    )(MyObject.apply _, unlift(MyObject.unapply))


  def myObjectGen =
    for {
      s  <- Arbitrary.arbitrary[String]
      b  <- Arbitrary.arbitrary[Boolean]
      i  <- Arbitrary.arbitrary[Int]
      l  <- Arbitrary.arbitrary[Long]
      d  <- Arbitrary.arbitrary[Double]
      bd <- Arbitrary.arbitrary[BigDecimal]
              // TODO is it reasonable to only handle BigDecimal within Decimal128 range?
              .suchThat(bd => scala.util.Try(new org.bson.types.Decimal128(bd.bigDecimal)).isSuccess)
    } yield
      MyObject(
          string     = StringWrapper(s)
        , boolean    = BooleanWrapper(b)
        , int        = IntWrapper(i)
        , long       = LongWrapper(l)
        , double     = DoubleWrapper(d)
        , bigDecimal = BigDecimalWrapper(bd)
        , ast        = Ast.Ast1
        )
}