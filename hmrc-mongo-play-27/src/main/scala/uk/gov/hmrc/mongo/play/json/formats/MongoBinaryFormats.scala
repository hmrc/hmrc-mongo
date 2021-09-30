package uk.gov.hmrc.mongo.play.json.formats

import akka.util.ByteString
import org.bson.BsonBinarySubType
import play.api.libs.functional.syntax._
import play.api.libs.json._

import java.nio.ByteBuffer
import java.util.Base64

trait MongoBinaryFormats {
  outer =>

  private val encoder = Base64.getEncoder
  private val decoder = Base64.getDecoder

  private val GenericBinarySubtype = BsonBinarySubType.BINARY.getValue

  final val byteArrayReads: Reads[Array[Byte]] =
    Reads
      .at[String](__ \ "$binary" \ "subType")
      .map(_.toByte)
      .flatMap {
        case `GenericBinarySubtype` =>
          Reads
            .at[String](__ \ "$binary" \ "base64")
            .map(decoder.decode)
        case other =>
          Reads.failed(f"Invalid BSON binary subtype for generic binary data: '$other%02x'")
      }

  final val byteArrayWrites: Writes[Array[Byte]] = Writes { bytes =>
    Json.obj(
      "$binary" -> Json.obj(
        "base64"  -> encoder.encodeToString(bytes),
        "subType" -> f"$GenericBinarySubtype%02x"
      )
    )
  }

  final val byteArrayFormat: Format[Array[Byte]] =
    Format(byteArrayReads, byteArrayWrites)

  final val byteBufferFormat: Format[ByteBuffer] =
    byteArrayFormat.inmap(ByteBuffer.wrap, _.array())

  final val byteStringFormat: Format[ByteString] =
    byteArrayFormat.inmap(ByteString.apply, _.toArray[Byte])

  trait Implicits {
    implicit val byteArrayFormat: Format[Array[Byte]] = outer.byteArrayFormat
    implicit val byteBufferFormat: Format[ByteBuffer] = outer.byteBufferFormat
    implicit val byteStringFormat: Format[ByteString] = outer.byteStringFormat
  }

  object Implicits extends Implicits
}

object MongoBinaryFormats extends MongoBinaryFormats
