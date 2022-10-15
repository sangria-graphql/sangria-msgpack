package sangria.marshalling

import java.math.BigInteger
import org.msgpack.core.MessagePack
import org.msgpack.value._

import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import scala.util.Try

object msgpack {
  class MsgpackResultMarshaller(bigDecimalMarshaller: MsgpackBigDecimalMarshaller)
      extends ResultMarshaller {
    type Node = Value
    override type MapBuilder = MsgpackMapBuilder

    def emptyMapNode(keys: Seq[String]) = new MsgpackMapBuilder(keys)

    def addMapNodeElem(
        builder: MapBuilder,
        key: String,
        value: Value,
        optional: Boolean): MsgpackMapBuilder =
      builder.add(key, value)

    def mapNode(builder: MapBuilder) = builder.build

    def mapNode(keyValues: Seq[(String, Value)]) = keyValues
      .foldLeft(ValueFactory.newMapBuilder()) { case (acc, (key, value)) =>
        acc.put(ValueFactory.newString(key), value)
      }
      .build()

    def arrayNode(values: Vector[Value]) = ValueFactory.newArray(values.toArray, true)
    def optionalArrayNodeValue(value: Option[Value]) = value match {
      case Some(v) => v
      case None => nullNode
    }

    def scalarNode(value: Any, typeName: String, info: Set[ScalarValueInfo]): Value = value match {
      case v: String => ValueFactory.newString(v)
      case v: Boolean => ValueFactory.newBoolean(v)
      case v: Int => ValueFactory.newInteger(v)
      case v: Long => ValueFactory.newInteger(v)
      case v: Float => ValueFactory.newFloat(v)
      case v: Double => ValueFactory.newFloat(v)
      case v: BigInt => ValueFactory.newInteger(v.bigInteger)
      case v: BigDecimal => bigDecimalMarshaller.marshalBigDecimal(v)
      case v: Array[Byte] => ValueFactory.newBinary(v)
      case v => throw new IllegalArgumentException("Unsupported scalar value: " + v)
    }

    def enumNode(value: String, typeName: String) = ValueFactory.newString(value)

    def nullNode = ValueFactory.newNil()

    def renderPretty(node: Value): String = renderCompact(node)
    def renderCompact(node: Value): String = render(node)

    override def capabilities = Set(BlobSupport)
  }

  implicit def msgpackResultMarshaller(implicit
      bigDecimalMarshaller: MsgpackBigDecimalMarshaller): MsgpackResultMarshaller =
    new MsgpackResultMarshaller(bigDecimalMarshaller)

  class MsgpackMarshallerForType(bigDecimalMarshaller: MsgpackBigDecimalMarshaller)
      extends ResultMarshallerForType[Value] {
    val marshaller = new MsgpackResultMarshaller(bigDecimalMarshaller)
  }

  implicit def msgpackMarshallerForType(implicit
      bigDecimalMarshaller: MsgpackBigDecimalMarshaller): MsgpackMarshallerForType =
    new MsgpackMarshallerForType(bigDecimalMarshaller)

  class MsgpackInputUnmarshaller(bigDecimalMarshaller: MsgpackBigDecimalMarshaller)
      extends InputUnmarshaller[Value] {
    def getRootMapValue(node: Value, key: String) = Option(
      node.asInstanceOf[MapValue].map().get(ValueFactory.newString(key)))

    def isMapNode(node: Value) = node.isInstanceOf[MapValue]
    def getMapValue(node: Value, key: String): Option[Value] =
      Option(node.asInstanceOf[MapValue].map().get(ValueFactory.newString(key)))

    // preserve order
    def getMapKeys(node: Value) =
      node.asInstanceOf[MapValue].getKeyValueArray.zipWithIndex.filter(_._2 % 2 == 0).map {
        case (s: StringValue, _) => s.asString
        case (invalid, _) =>
          throw new IllegalArgumentException(
            s"Invalid map key type. Only Strings are supported. Key value: $invalid")
      }

    def isListNode(node: Value): Boolean = node.isInstanceOf[ArrayValue]
    def getListValue(node: Value): Seq[Value] = node.asInstanceOf[ArrayValue].list().asScala.toSeq

    def isDefined(node: Value) = !node.isInstanceOf[NilValue]
    def getScalarValue(node: Value) = node match {
      case value if bigDecimalMarshaller.isBigDecimal(value) =>
        bigDecimalMarshaller.unmarshalBigDecimal(value)
      case s: StringValue => s.asString
      case b: BooleanValue => b.getBoolean
      case i: IntegerValue if i.isInIntRange => i.asInt
      case i: IntegerValue if i.isInLongRange => i.asLong
      case i: IntegerValue => BigInt(i.asBigInteger)
      case f: FloatValue => f.toDouble
      case v =>
        throw new IllegalStateException(s"'$v' is not a supported scalar value")
    }

    def getScalaScalarValue(node: Value) = getScalarValue(node)

    def isEnumNode(node: Value): Boolean = node.isInstanceOf[StringValue]
    def isScalarNode(node: Value): Boolean = node match {
      case value if bigDecimalMarshaller.isBigDecimal(value) => true
      case _: StringValue | _: BooleanValue | _: IntegerValue | _: FloatValue => true
      case _ => false
    }

    def isVariableNode(node: Value) = false
    def getVariableName(node: Value) = throw new IllegalArgumentException(
      "variables are not supported")

    def render(node: Value): String = msgpack.render(node)
  }

  implicit def msgpackInputUnmarshaller(implicit
      bigDecimalMarshaller: MsgpackBigDecimalMarshaller): MsgpackInputUnmarshaller =
    new MsgpackInputUnmarshaller(bigDecimalMarshaller)

  class MsgpackToInput(bigDecimalMarshaller: MsgpackBigDecimalMarshaller)
      extends ToInput[Value, Value] {
    def toInput(value: Value) = (value, msgpackInputUnmarshaller(bigDecimalMarshaller))
  }

  implicit def msgpackToInput(implicit
      bigDecimalMarshaller: MsgpackBigDecimalMarshaller): MsgpackToInput =
    new MsgpackToInput(bigDecimalMarshaller)

  class MsgpackFromInput(bigDecimalMarshaller: MsgpackBigDecimalMarshaller)
      extends FromInput[Value] {
    val marshaller = msgpackResultMarshaller(bigDecimalMarshaller)
    def fromResult(node: marshaller.Node): Value = node.asInstanceOf[Value]
  }

  implicit def msgpackFromInput(implicit
      bigDecimalMarshaller: MsgpackBigDecimalMarshaller): MsgpackFromInput =
    new MsgpackFromInput(bigDecimalMarshaller)

  trait MsgpackBigDecimalMarshaller {
    def marshalBigDecimal(value: BigDecimal): Value

    def isBigDecimal(value: Value): Boolean
    def unmarshalBigDecimal(value: Value): BigDecimal
  }

  object MsgpackBigDecimalMarshaller {
    val ExtensionType: Byte = 47

    implicit object DefaultMsgpackBigDecimalMarshaller extends MsgpackBigDecimalMarshaller {
      def marshalBigDecimal(number: BigDecimal): ImmutableExtensionValue = {
        val scale = number.scale
        val unscaled = number.bigDecimal.unscaledValue
        val value: Array[Byte] = unscaled.toByteArray
        val bytes: Array[Byte] = new Array[Byte](value.length + 4)

        bytes(0) = (scale >>> 24).toByte
        bytes(1) = (scale >>> 16).toByte
        bytes(2) = (scale >>> 8).toByte
        bytes(3) = (scale >>> 0).toByte

        System.arraycopy(value, 0, bytes, 4, value.length)

        ValueFactory.newExtension(ExtensionType, bytes)
      }

      def isBigDecimal(value: Value): Boolean = value match {
        case ext: ExtensionValue if ext.getType == ExtensionType => true
        case _ => false
      }

      def unmarshalBigDecimal(value: Value): BigDecimal = {
        val bytes = value.asInstanceOf[ExtensionValue].getData
        val scale: Int =
          ((bytes(0): Int) << 24) + ((bytes(1): Int) << 16) + ((bytes(2): Int) << 8) + ((bytes(
            3): Int) << 0)
        val vals: Array[Byte] = new Array[Byte](bytes.length - 4)

        System.arraycopy(bytes, 4, vals, 0, vals.length)

        val unscaled = new BigInteger(vals)

        BigDecimal(new java.math.BigDecimal(unscaled, scale))
      }
    }
  }

  object standardTypeBigDecimal {
    implicit object DefaultMsgpackBigDecimalMarshaller extends MsgpackBigDecimalMarshaller {
      def marshalBigDecimal(value: BigDecimal) =
        value.toBigIntExact.map(i => ValueFactory.newInteger(i.bigInteger)).getOrElse {
          if (value.isExactDouble)
            ValueFactory.newFloat(value.doubleValue)
          else
            throw new IllegalArgumentException(
              s"MessagePack cannot serialize a BigDecimal ($value) that can't be represented as double or a BigInt. " +
                "Please use different `MsgpackBigDecimalMarshaller`. For example you can use the default one or define your own.")
        }

      def isBigDecimal(value: Value) = false
      def unmarshalBigDecimal(value: Value) =
        throw new IllegalStateException(
          "BigDecimal is not supported! It's represented as integer and double numbers (as long as it fits).")
    }
  }

  /** String rendering is a base64 binary value
    */
  private def render(value: Value): String = {
    val packer = MessagePack.newDefaultBufferPacker()

    packer.packValue(value)
    packer.close()

    val bytes = packer.toByteArray

    new String(java.util.Base64.getEncoder.encode(bytes), StandardCharsets.UTF_8)
  }

  implicit object MsgpackInputParser extends InputParser[Value] {
    def parse(str: String): Try[ImmutableValue] = Try {
      val bytes = java.util.Base64.getDecoder.decode(str)
      val unpacker = MessagePack.newDefaultUnpacker(bytes)

      unpacker.unpackValue()
    }
  }

  private[msgpack] final class MsgpackMapBuilder(keys: Seq[String]) {
    import scala.collection.mutable.{Set => MutableSet}

    private val elements = new Array[Value](keys.size * 2)
    private val indexesSet = MutableSet[Int]()
    private val indexLookup = {
      val builder = Map.newBuilder[String, Int]
      var idx = 0

      keys.foreach { key =>
        builder += key -> idx
        idx += 2
      }

      builder.result()
    }

    def add(key: String, elem: Value): MsgpackMapBuilder = {
      val idx = indexLookup(key)

      elements(idx) = ValueFactory.newString(key)
      elements(idx + 1) = elem
      indexesSet += idx

      this
    }

    def build: ImmutableMapValue = {
      val buffer = new Array[Value](indexesSet.size * 2)
      var bufferIdx = 0

      for (i <- 0 to keys.size * 2 by 2 if indexesSet contains i) {
        buffer(bufferIdx) = elements(i)
        buffer(bufferIdx + 1) = elements(i + 1)
        bufferIdx = bufferIdx + 2
      }

      ValueFactory.newMap(buffer, true)
    }
  }
}
