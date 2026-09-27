package strap.protocol

import strap.protocol.LegacyVectors.hex
import strap.protocol.LegacyVectors.num
import strap.protocol.LegacyVectors.str
import strap.protocol.crypto.B163
import strap.protocol.crypto.HuamiCrypto
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoTest {
    // ── independent of the old code: the curve itself ──

    /** NIST B-163 group order (FIPS 186-4 D.1.3.2). n·G = ∞ proves constants AND arithmetic. */
    private val order = BigInteger("40000000000000000000292fe77e70c12a4234c33", 16)

    @Test
    fun `base point lies on the curve`() = assertTrue(B163.onCurve(B163.G))

    @Test
    fun `base point has the published group order`() {
        assertNull(B163.multiply(B163.G, order))
        val almost = B163.multiply(B163.G, order - BigInteger.ONE)!!
        assertEquals(B163.G.x, almost.x) // (n-1)·G = −G shares G's x
    }

    @Test
    fun `scalar multiplication is a homomorphism`() {
        val a = BigInteger("123456789abcdef0123456789", 16)
        val b = BigInteger("fedcba9876543210fedcba98", 16)
        val lhs = B163.multiply(B163.multiply(B163.G, a)!!, b)
        val rhs = B163.multiply(B163.multiply(B163.G, b)!!, a)
        assertEquals(lhs, rhs)
        assertEquals(B163.multiply(B163.G, a + b), B163.add(B163.multiply(B163.G, a), B163.multiply(B163.G, b)))
    }

    @Test
    fun `random keypairs agree on the shared secret`() {
        val rng = SecureRandom()
        val alice = B163.generateKeypair(rng)
        val bob = B163.generateKeypair(rng)
        assertContentEquals(B163.sharedSecret(alice.privateKey, bob.publicKey), B163.sharedSecret(bob.privateKey, alice.publicKey))
    }

    @Test
    fun `rejects a remote point that is not on the curve`() {
        val bogus = ByteArray(48).also { it[0] = 1 }
        assertFailsWith<IllegalArgumentException> { B163.sharedSecret(ByteArray(24) { 0x55 }, bogus) }
    }

    @Test
    fun `private key is masked to 162 bits`() {
        val kp = B163.keypairFrom(ByteArray(24) { 0xFF.toByte() })
        assertEquals(0x03, kp.privateKey[20].toInt())
        assertTrue(kp.privateKey.copyOfRange(21, 24).all { it == 0.toByte() })
    }

    @Test
    fun `auth key parsing`() {
        val key = HuamiCrypto.parseAuthKey(" 0x000102030405060708090a0b0c0d0e0F ")
        assertContentEquals(ByteArray(16) { it.toByte() }, key)
        assertFailsWith<IllegalArgumentException> { HuamiCrypto.parseAuthKey("abc") }
    }

    // ── parity with the old Dart implementation ──

    @Test
    fun `ECDH matches the old implementation byte for byte`() {
        val v = LegacyVectors.obj("ecdh")
        val a = B163.keypairFrom(v.hex("privA_in"))
        val b = B163.keypairFrom(v.hex("privB_in"))
        assertEquals(v.str("privA"), a.privateKey.hex())
        assertEquals(v.str("pubA"), a.publicKey.hex())
        assertEquals(v.str("pubB"), b.publicKey.hex())
        assertEquals(v.str("shared"), B163.sharedSecret(a.privateKey, b.publicKey).hex())
    }

    @Test
    fun `CRC-32 and AES match the old implementation`() {
        val crc = LegacyVectors.obj("crc32")
        assertEquals(crc.num("crc"), HuamiCrypto.crc32(crc.hex("data")))
        val aes = LegacyVectors.obj("aes")
        val cipher = HuamiCrypto.aesEcbEncrypt(aes.hex("key"), aes.hex("plain"))
        assertEquals(aes.str("cipher"), cipher.hex())
        assertContentEquals(aes.hex("plain"), HuamiCrypto.aesEcbDecrypt(aes.hex("key"), cipher))
    }
}
