package com.tvpad.wifi

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

/** Tiny protobuf writer: only what the Android TV remote protocol needs. */
class PW {
    private val out = ByteArrayOutputStream()

    private fun varint(value: Long) {
        var v = value
        while ((v and 0x7FL.inv()) != 0L) {
            out.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    fun int(field: Int, value: Int) = apply {
        varint((field shl 3).toLong())
        varint(value.toLong())
    }

    fun bytes(field: Int, value: ByteArray) = apply {
        varint(((field shl 3) or 2).toLong())
        varint(value.size.toLong())
        out.write(value)
    }

    fun str(field: Int, value: String) = bytes(field, value.toByteArray())

    fun msg(field: Int, value: PW) = bytes(field, value.build())

    fun build(): ByteArray = out.toByteArray()

    /** The message prefixed with its varint length, as sent on the wire. */
    fun frame(): ByteArray {
        val body = build()
        val o = ByteArrayOutputStream()
        var v = body.size.toLong()
        while (v > 0x7F) {
            o.write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
        o.write(v.toInt())
        o.write(body)
        return o.toByteArray()
    }
}

class Field(val num: Int, val value: Long, val data: ByteArray?)

fun List<Field>.field(num: Int): Field? = firstOrNull { it.num == num }

/** Parses one protobuf message into its top-level fields. */
fun parse(data: ByteArray): List<Field> {
    val res = mutableListOf<Field>()
    var i = 0

    fun varint(): Long {
        var shift = 0
        var r = 0L
        var more: Boolean
        do {
            val b = data[i++].toInt() and 0xFF
            r = r or ((b and 0x7F).toLong() shl shift)
            more = (b and 0x80) != 0
            shift += 7
        } while (more)
        return r
    }

    while (i < data.size) {
        val tag = varint()
        val num = (tag ushr 3).toInt()
        when ((tag and 7).toInt()) {
            0 -> res.add(Field(num, varint(), null))
            1 -> i += 8
            2 -> {
                val len = varint().toInt()
                res.add(Field(num, 0, data.copyOfRange(i, i + len)))
                i += len
            }
            5 -> i += 4
            else -> return res
        }
    }
    return res
}

/** Reads one length-prefixed message from the stream. */
fun readFrame(input: InputStream): ByteArray {
    var len = 0
    var shift = 0
    while (true) {
        val b = input.read()
        if (b < 0) throw EOFException("TV closed the connection")
        len = len or ((b and 0x7F) shl shift)
        if ((b and 0x80) == 0) break
        shift += 7
    }
    val buf = ByteArray(len)
    var off = 0
    while (off < len) {
        val n = input.read(buf, off, len - off)
        if (n < 0) throw EOFException("TV closed the connection")
        off += n
    }
    return buf
}
