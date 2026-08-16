package com.example.voiceassistant.tv

import java.io.ByteArrayOutputStream

/**
 * Minimal hand-rolled protobuf wire-format codec.
 *
 * The Android TV Remote protocol v2 messages (polo.proto / remotemessage.proto)
 * only ever use varints (ints/enums/bools), strings/bytes, and nested messages -
 * so a full protobuf runtime isn't needed. This covers exactly those shapes.
 */
object ProtoWire {

    private const val WIRETYPE_VARINT = 0
    private const val WIRETYPE_LENGTH_DELIMITED = 2

    private fun tag(fieldNumber: Int, wireType: Int) = (fieldNumber shl 3) or wireType

    fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val bits = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(bits)
                return
            } else {
                out.write(bits or 0x80)
            }
        }
    }

    fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeVarint(out, tag(fieldNumber, WIRETYPE_VARINT).toLong())
        writeVarint(out, value)
    }

    fun writeStringField(out: ByteArrayOutputStream, fieldNumber: Int, value: String) =
        writeBytesField(out, fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun writeBytesField(out: ByteArrayOutputStream, fieldNumber: Int, value: ByteArray) {
        writeVarint(out, tag(fieldNumber, WIRETYPE_LENGTH_DELIMITED).toLong())
        writeVarint(out, value.size.toLong())
        out.write(value)
    }

    fun writeMessageField(out: ByteArrayOutputStream, fieldNumber: Int, message: ByteArray) =
        writeBytesField(out, fieldNumber, message)

    fun message(block: (ByteArrayOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        block(out)
        return out.toByteArray()
    }

    /** A parsed top-level field: either a varint value or a length-delimited payload. */
    sealed class Field {
        data class Varint(val value: Long) : Field()
        data class Bytes(val value: ByteArray) : Field()
    }

    /** Parses raw protobuf bytes into a map of field number -> last-seen field. */
    fun parse(data: ByteArray): Map<Int, Field> {
        val result = HashMap<Int, Field>()
        var pos = 0
        while (pos < data.size) {
            val (tagVal, tagLen) = readVarint(data, pos)
            pos += tagLen
            val fieldNumber = (tagVal shr 3).toInt()
            val wireType = (tagVal and 0x7).toInt()
            when (wireType) {
                WIRETYPE_VARINT -> {
                    val (v, len) = readVarint(data, pos)
                    pos += len
                    result[fieldNumber] = Field.Varint(v)
                }
                WIRETYPE_LENGTH_DELIMITED -> {
                    val (len, lenLen) = readVarint(data, pos)
                    pos += lenLen
                    val end = pos + len.toInt()
                    result[fieldNumber] = Field.Bytes(data.copyOfRange(pos, end))
                    pos = end
                }
                else -> return result // fixed32/fixed64 aren't used by this protocol
            }
        }
        return result
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (true) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to (pos - start)
    }
}
