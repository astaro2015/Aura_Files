package com.aurafiles.app.util

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

fun ByteArray.toHexString(): String {
    val out = CharArray(size * 2)
    var index = 0
    for (byte in this) {
        val value = byte.toInt() and 0xff
        out[index++] = HEX_DIGITS[value ushr 4]
        out[index++] = HEX_DIGITS[value and 0x0f]
    }
    return String(out)
}
