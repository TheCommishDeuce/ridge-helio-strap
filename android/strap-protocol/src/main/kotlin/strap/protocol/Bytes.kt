package strap.protocol

/** Little-endian integer access — every multi-byte field on this wire is LE. */
internal object Bytes {
    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)

    fun i16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()

    fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or
            (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)

    fun putU16(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte()
        b[i + 1] = (v shr 8).toByte()
    }

    fun putU32(b: ByteArray, i: Int, v: Long) {
        for (k in 0 until 4) b[i + k] = (v shr (8 * k)).toByte()
    }
}
