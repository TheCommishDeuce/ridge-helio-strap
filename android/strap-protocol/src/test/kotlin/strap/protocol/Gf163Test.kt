package strap.protocol

import strap.protocol.crypto.B163
import strap.protocol.crypto.Gf163
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Gf163Test {
    private val poly = BigInteger.ONE.shiftLeft(163).or(BigInteger.valueOf(0xC9))
    private val rng = Random(163)

    private fun element(): BigInteger = BigInteger(163, java.util.Random(rng.nextLong()))

    /** Reference: schoolbook carry-less multiply then reduce, on BigInteger. */
    private fun referenceMul(a: BigInteger, b: BigInteger): BigInteger {
        var product = BigInteger.ZERO
        for (i in 0 until b.bitLength()) if (b.testBit(i)) product = product.xor(a.shiftLeft(i))
        while (product.bitLength() > 163) product = product.xor(poly.shiftLeft(product.bitLength() - 1 - 163))
        return product
    }

    @Test
    fun `limb multiply equals the reference multiply`() = repeat(200) {
        val a = element()
        val b = element()
        assertEquals(referenceMul(a, b), Gf163.toBig(Gf163.mul(Gf163.of(a), Gf163.of(b))))
    }

    @Test
    fun `inverse times element is one`() = repeat(200) {
        val a = Gf163.of(element().max(BigInteger.ONE))
        assertTrue(Gf163.isOne(Gf163.mul(a, Gf163.inv(a))))
    }

    @Test
    fun `limb conversion round-trips including the top bit`() {
        val top = BigInteger.ONE.shiftLeft(162).or(BigInteger.ONE.shiftLeft(63)).or(BigInteger.ONE.shiftLeft(64))
        assertEquals(top, Gf163.toBig(Gf163.of(top)))
    }

    /** The phone ran a handshake for >60 s on the old BigInteger arithmetic. Keep it fast. */
    @Test
    fun `a keypair plus a shared secret is fast`() {
        val rnd = SecureRandom()
        val start = System.nanoTime()
        repeat(10) {
            val a = B163.generateKeypair(rnd)
            val b = B163.generateKeypair(rnd)
            B163.sharedSecret(a.privateKey, b.publicKey)
        }
        val perHandshakeMs = (System.nanoTime() - start) / 1e6 / 10
        assertTrue(perHandshakeMs < 50, "handshake crypto took $perHandshakeMs ms on the JVM")
    }
}
