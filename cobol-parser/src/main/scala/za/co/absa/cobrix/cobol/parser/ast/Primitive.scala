/*
 * Copyright 2018 ABSA Group Limited
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

package za.co.absa.cobrix.cobol.parser.ast

import scodec.Codec
import za.co.absa.cobrix.cobol.parser.ast.datatype.{AlphaNumeric, CobolType, Decimal, Integral}
import za.co.absa.cobrix.cobol.parser.common.Constants
import za.co.absa.cobrix.cobol.parser.decoders.DecoderSelector
import za.co.absa.cobrix.cobol.parser.encoding.EBCDIC
import za.co.absa.cobrix.cobol.parser.exceptions.SyntaxErrorException

/** An abstraction of the statements describing fields of primitive data types in the COBOL copybook
  *
  * @param level       A level for the statement
  * @param name        An identifier
  * @param lineNumber  An line number in the copybook
  * @param redefines   A name of a field which is redefined by this one
  * @param occurs      The number of elements in an fixed size array / minimum items in variable-sized array
  * @param to          The maximum number of items in a variable size array
  * @param dependingOn A field which specifies size of the array in a record
  * @param parent      A parent node
  */
case class Primitive(
                      level: Int,
                      name: String,
                      lineNumber: Int,
                      dataType: CobolType,
                      redefines: Option[String] = None,
                      isRedefined: Boolean = false,
                      occurs: Option[Int] = None,
                      to: Option[Int] = None,
                      dependingOn: Option[String] = None,
                      isDependee: Boolean = false,
                      isFiller: Boolean = false,
                      decode: DecoderSelector.Decoder,
                      binaryProperties: BinaryProperties = BinaryProperties(0, 0, 0)
                    )
                    (val parent: Option[Group] = None)
  extends Statement {

  /** Returns a string representation of the field */
  override def toString: String = {
    s"${" " * 2 * level}$camelCased ${camelCase(redefines.getOrElse(""))} $dataType"
  }

  /** Returns the original field with updated binary properties */
  def withUpdatedBinaryProperties(newBinaryProperties: BinaryProperties): Primitive = {
    copy(binaryProperties = newBinaryProperties)(parent)
  }

  /** Returns the original field with updated `isRedefined` flag */
  def withUpdatedIsRedefined(newIsRedefined: Boolean): Primitive = {
    copy(isRedefined = newIsRedefined)(parent)
  }

  /** Returns the original field with updated `isDependee` flag */
  def withUpdatedIsDependee(newIsDependee: Boolean): Primitive = {
    copy(isDependee = newIsDependee)(parent)
  }

  /** Returns the binary size in bits for the field */
  def getBinarySizeBits: Int = {
    validateDataTypes()
    val size = dataType match {
      case a: AlphaNumeric =>
        // Each character is represented by a byte
        val codec = a.enc.getOrElse(EBCDIC()).codec(None, a.length, None)
        getBitCount(codec, None, a.length, isSignSeparate = false) //count of entire word
      case d: Decimal =>
        val codec = d.enc.getOrElse(EBCDIC()).codec(d.compact, d.precision, d.signPosition)
        // Support explicit decimal point (aka REAL DECIMAL, PIC 999.99.)
        val precision = if (d.compact.isEmpty && d.explicitDecimal) d.precision + 1 else d.precision
        getBitCount(codec, d.compact, precision, isSignSeparate = d.isSignSeparate)
      case i: Integral =>
        val codec = i.enc.getOrElse(EBCDIC()).codec(i.compact, i.precision, i.signPosition)
        // Hack around byte-alignment
        getBitCount(codec, i.compact, i.precision, isSignSeparate = i.isSignSeparate)
    }
    // round size up to next byte
    ((size + 7) / 8) * 8
  }

  /** Returns a value of a field biven a binary data.
    * The return data type depends on the data type of the field
    *
    * @param itOffset An offset of the field inside the binary data
    * @param record   A record in a binary format represented as a vector of bits
    */
  @throws(classOf[Exception])
  def decodeTypeValue(itOffset: Long, record: Array[Byte]): Any = {
    val bytesCount = binaryProperties.dataSize / 8
    val idx = (itOffset / 8).toInt
    if (idx + bytesCount > record.length) {
      return null
    }
    val bytes = java.util.Arrays.copyOfRange(record, idx, idx + bytesCount)

    decode(bytes)
  }


  /** Returns the number of bits an integral value occupies given an encoding and a binary representation format.
    *
    * @param codec     A type of encoding (EBCDIC / ASCII)
    * @param comp      A type of binary number representation format
    * @param precision A precision that is the number of digits in a number
    *
    */
  private def getBitCount(codec: Codec[_ <: AnyVal], comp: Option[Int], precision: Int, isSignSeparate: Boolean): Int = {
    comp match {
      case Some(x) =>
        x match {
          case a if a == 3 =>
            (precision + 1) * codec.sizeBound.lowerBound.toInt //bcd
          case _ => codec.sizeBound.lowerBound.toInt // bin/float/floatL
        }
      case None =>
        val signAdditionalBits = if (isSignSeparate) 8 else 0
        precision * codec.sizeBound.lowerBound.toInt + signAdditionalBits
    }
  }

  @throws(classOf[SyntaxErrorException])
  private def validateDataTypes(): Unit = {
    dataType match {
      case d: Decimal =>
        if (d.precision - d.scale > Constants.maxDecimalPrecision) {
          throw new SyntaxErrorException(lineNumber, name,
            s"Decimal numbers with precision bigger than ${Constants.maxDecimalPrecision} are not supported.")
        }
        if (d.scale > Constants.maxDecimalScale) {
          throw new SyntaxErrorException(lineNumber, name,
            s"Decimal numbers with scale bigger than ${Constants.maxDecimalScale} are not supported.")
        }
      case i: Integral =>
        for (bin <- i.compact) {
          if (i.precision > Constants.maxBinIntPrecision) {
            throw new SyntaxErrorException(lineNumber, name,
              s"BINARY-encoded integers with precision bigger than ${Constants.maxBinIntPrecision} are not supported.")
          }
        }
      case _ =>
    }

  }

}
