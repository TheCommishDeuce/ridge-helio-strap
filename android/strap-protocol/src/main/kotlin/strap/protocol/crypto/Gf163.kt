package strap.protocol.crypto

import java.math.BigInteger

/**
 * GF(2¹⁶³) with reduction polynomial `x¹⁶³ + x⁷ + x⁶ + x³ + 1`, on three 64-bit limbs
 * (bit i of the polynomial = bit i%64 of limb i/64). Fixed-width and allocation-light:
 * the BigInteger version this replaces took seconds per handshake on a phone.
 */
internal object Gf163 {
    private const val DEGREE = 163
    private const val TOP_BIT = DEGREE - 128 // bit of x^163 inside limb 2
    private const val REDUCTION_LOW = 0xC9L // x^7 + x^6 + x^3 + 1

    fun of(value: BigInteger): LongArray =
        LongArray(3) { i -> value.shiftRight(64 * i).toLong() }.also { require(value.bitLength() <= 192) }

    fun toBig(a: LongArray): BigInteger =
        (2 downTo 0).fold(BigInteger.ZERO) { acc, i -> acc.shiftLeft(64).or(BigInteger(java.lang.Long.toUnsignedString(a[i]))) }

    fun one(): LongArray = longArrayOf(1, 0, 0)

    fun isZero(a: LongArray): Boolean = a[0] == 0L && a[1] == 0L && a[2] == 0L

    fun isOne(a: LongArray): Boolean = a[0] == 1L && a[1] == 0L && a[2] == 0L

    fun eq(a: LongArray, b: LongArray): Boolean = a[0] == b[0] && a[1] == b[1] && a[2] == b[2]

    fun add(a: LongArray, b: LongArray): LongArray = longArrayOf(a[0] xor b[0], a[1] xor b[1], a[2] xor b[2])

    /** Interleaved shift-and-add: 163 steps of shift, conditional reduce, conditional xor. */
    fun mul(a: LongArray, b: LongArray): LongArray {
        var t0 = a[0]
        var t1 = a[1]
        var t2 = a[2]
        var z0 = 0L
        var z1 = 0L
        var z2 = 0L
        for (i in 0 until DEGREE) {
            if ((b[i ushr 6] ushr (i and 63)) and 1L != 0L) {
                z0 = z0 xor t0; z1 = z1 xor t1; z2 = z2 xor t2
            }
            t2 = (t2 shl 1) or (t1 ushr 63)
            t1 = (t1 shl 1) or (t0 ushr 63)
            t0 = t0 shl 1
            if ((t2 ushr TOP_BIT) and 1L != 0L) {
                t2 = t2 xor (1L shl TOP_BIT)
                t0 = t0 xor REDUCTION_LOW
            }
        }
        return longArrayOf(z0, z1, z2)
    }

    fun sq(a: LongArray): LongArray = mul(a, a)

    /** Binary extended Euclid over GF(2)[x] (Hankerson et al., Alg. 2.48). */
    fun inv(a: LongArray): LongArray {
        require(!isZero(a)) { "inverse of zero" }
        var u = a.copyOf()
        var v = longArrayOf(REDUCTION_LOW, 0, 1L shl TOP_BIT)
        var g1 = one()
        var g2 = LongArray(3)
        while (!isOne(u)) {
            var j = degree(u) - degree(v)
            if (j < 0) {
                u = v.also { v = u }
                g1 = g2.also { g2 = g1 }
                j = -j
            }
            u = add(u, shl(v, j))
            g1 = add(g1, shl(g2, j))
        }
        return g1
    }

    /** Index of the highest set bit, −1 for zero. */
    private fun degree(a: LongArray): Int {
        for (i in 2 downTo 0) if (a[i] != 0L) return 64 * i + 63 - java.lang.Long.numberOfLeadingZeros(a[i])
        return -1
    }

    /** Left shift by [n] bits within 192 bits (the invariants keep results below x^163). */
    private fun shl(a: LongArray, n: Int): LongArray {
        if (n == 0) return a.copyOf()
        val limbs = n ushr 6
        val bits = n and 63
        val out = LongArray(3)
        for (i in 2 downTo limbs) {
            val src = i - limbs
            var v = a[src] shl bits
            if (bits != 0 && src > 0) v = v or (a[src - 1] ushr (64 - bits))
            out[i] = v
        }
        return out
    }
}
