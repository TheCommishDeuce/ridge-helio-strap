package strap.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import strap.protocol.LegacyVectors.arr
import strap.protocol.LegacyVectors.hex
import strap.protocol.LegacyVectors.num
import strap.protocol.LegacyVectors.str
import strap.protocol.auth.Handshake
import strap.protocol.auth.HandshakeEvent
import strap.protocol.auth.HandshakeFailure
import strap.protocol.crypto.B163
import strap.protocol.transport.ChunkDecoder
import strap.protocol.transport.ChunkEncoder
import strap.protocol.transport.ChunkFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransportTest {
    // ── spec/01 §3 header table, independent of the old code ──

    @Test
    fun `first chunk carries marker, flags, handle, count, original length and endpoint`() {
        val chunk = ChunkEncoder().encode(0x0016, byteArrayOf(0x03)).single()
        assertEquals("030700010001000000160003", chunk.hex())
        // 03 · flags first|last|ack · 00 · handle 1 · count 0 · len 1 · endpoint 0x0016 · payload
    }

    @Test
    fun `handle increments per message and wraps`() {
        val enc = ChunkEncoder()
        repeat(254) { enc.encode(1, byteArrayOf(1)) } // handles 1..254
        assertEquals(0xFF, enc.encode(1, byteArrayOf(1)).single()[3].toInt() and 0xFF)
        assertEquals(0x00, enc.encode(1, byteArrayOf(1)).single()[3].toInt())
    }

    @Test
    fun `encrypted frame keeps the original length and round-trips`() {
        val key = ByteArray(16) { (it * 9).toByte() }
        val enc = ChunkEncoder().apply { setEncryption(key, 7) }
        val payload = ByteArray(100) { it.toByte() }
        val chunks = enc.encode(0x0047, payload, encrypt = true)
        assertEquals(100L, Bytes.u32(chunks[0], 5))
        assertTrue(chunks.all { it[1].toInt() and 0x08 != 0 })
        val dec = ChunkDecoder()
        val frame = chunks.map { dec.feed(it, key) }.last()!!
        assertContentEquals(payload, frame.payload)
        assertEquals(0x0047, frame.endpoint)
        assertTrue(frame.needsAck)
    }

    @Test
    fun `encrypted frame without a session key yields no frame`() {
        val key = ByteArray(16)
        val chunks = ChunkEncoder().apply { setEncryption(key, 0) }.encode(1, byteArrayOf(1, 2), encrypt = true)
        assertNull(ChunkDecoder().feed(chunks.single(), sessionKey = null))
    }

    @Test
    fun `ack layout`() = assertEquals("0400050103", ChunkFormat.ack(handle = 5, count = 3).hex())

    // ── parity with the old implementation ──

    private fun assertEncodes(v: JsonObject, enc: ChunkEncoder, encrypt: Boolean = false) {
        val chunks = enc.encode(v.num("endpoint").toInt(), v.hex("payload"), encrypt)
        assertEquals(v.arr("chunks").map { it.jsonPrimitive.content }, chunks.map { it.hex() })
    }

    @Test
    fun `plain framing matches the old implementation at MTU 247 and 23`() {
        val shared = ChunkEncoder(247) // the generator reused one encoder: handles 1 then 2
        assertEncodes(LegacyVectors.obj("chunks", "plain_short"), shared)
        assertEncodes(LegacyVectors.obj("chunks", "plain_long"), shared)
        assertEncodes(LegacyVectors.obj("chunks", "plain_tiny"), ChunkEncoder(23))
    }

    @Test
    fun `encrypted framing matches the old implementation, and decodes`() {
        val v = LegacyVectors.obj("chunks", "encrypted")
        val key = v.hex("sessionKey")
        val enc = ChunkEncoder(247).apply { setEncryption(key, v.num("sequence")) }
        for (m in v.arr("messages")) assertEncodes(m.jsonObject, enc, encrypt = true)
        val second = v.arr("messages")[1].jsonObject
        val dec = ChunkDecoder()
        val frame = second.arr("chunks").map { dec.feed(it.jsonPrimitive.content.unhex(), key) }.last()!!
        assertEquals(second.str("payload"), frame.payload.hex())
    }

    @Test
    fun `handshake derives the old implementation's proof, session key and sequence`() {
        val ecdh = LegacyVectors.obj("ecdh")
        val v = LegacyVectors.obj("auth")
        val ours = B163.keypairFrom(ecdh.hex("privA_in"))
        val hs = Handshake(v.hex("authKey"), ours)
        assertEquals("04020002" + ecdh.str("pubA"), hs.start().hex())
        val reply = "100401".unhex() + v.hex("remoteRandom") + ecdh.hex("pubB")
        val proof = assertIs<HandshakeEvent.SendProof>(hs.onPayload(reply))
        assertEquals(v.str("proof"), proof.payload.hex())
        val done = assertIs<HandshakeEvent.Authenticated>(hs.onPayload("100501".unhex()))
        assertEquals(v.str("sessionKey"), done.sessionKey.hex())
        assertEquals(v.num("sequence"), done.sequence)
    }

    @Test
    fun `handshake names a wrong auth key and ignores unrelated frames`() {
        val ecdh = LegacyVectors.obj("ecdh")
        val hs = Handshake(ByteArray(16), B163.keypairFrom(ecdh.hex("privA_in")))
        hs.start()
        assertEquals(HandshakeEvent.Ignored, hs.onPayload("0102".unhex()))
        assertEquals(HandshakeEvent.Ignored, hs.onPayload("100501".unhex())) // not waiting for step 5 yet
        hs.onPayload("100401".unhex() + ByteArray(16) + ecdh.hex("pubB"))
        assertEquals(HandshakeEvent.Failed(HandshakeFailure.WrongAuthKey), hs.onPayload("100525".unhex()))
    }

    @Test
    fun `handshake reports a refused public key and a short reply`() {
        val kp = B163.keypairFrom(LegacyVectors.obj("ecdh").hex("privA_in"))
        Handshake(ByteArray(16), kp).run {
            start()
            assertEquals(HandshakeEvent.Failed(HandshakeFailure.Rejected(2, 0x02)), onPayload("100402".unhex()))
        }
        Handshake(ByteArray(16), kp).run {
            start()
            assertIs<HandshakeEvent.Failed>(onPayload("100401".unhex() + ByteArray(10)))
        }
    }
}
