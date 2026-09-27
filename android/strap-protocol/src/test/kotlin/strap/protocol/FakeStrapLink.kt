package strap.protocol

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import strap.protocol.crypto.B163
import strap.protocol.crypto.HuamiCrypto
import strap.protocol.session.Channel
import strap.protocol.session.Notification
import strap.protocol.session.StrapLink
import strap.protocol.transport.ChunkDecoder
import strap.protocol.transport.ChunkEncoder

/**
 * The device side of a whole connection, from spec/01: it answers the handshake with its own
 * ECDH keypair and verifies our proof, replies to the daily-totals request with an encrypted,
 * needs-ack frame, and hands `0004` writes to a per-type [FetchResponder].
 */
class FakeStrapLink(
    private val authKey: ByteArray,
    private val fetch: Map<Int, FetchResponder> = emptyMap(),
    override val hasFetchChannel: Boolean = true,
    private val silent: Boolean = false,
    private val totals: Triple<Long, Long, Long>? = Triple(8123, 6010, 402),
    /** Other endpoints: request → reply (sent encrypted when the request was), or null for silence. */
    private val services: Map<Int, (ByteArray) -> ByteArray?> = emptyMap(),
) : StrapLink {
    override val mtu: Int = 247
    private val flow = MutableSharedFlow<Notification>(extraBufferCapacity = 100_000)
    override val notifications: SharedFlow<Notification> = flow

    private val device = B163.keypairFrom(ByteArray(24) { (it * 11 + 1).toByte() })
    private val random = ByteArray(16) { (it * 5).toByte() }
    private val rx = ChunkDecoder()
    private val tx = ChunkEncoder(mtu)
    private var sessionKey: ByteArray? = null
    val acks = mutableListOf<ByteArray>()
    val controlWrites = mutableListOf<ByteArray>()
    var failControlWritesAfter = Int.MAX_VALUE

    private fun emit(channel: Channel, bytes: ByteArray) = check(flow.tryEmit(Notification(channel, bytes)))

    private fun sendFrame(endpoint: Int, payload: ByteArray, encrypt: Boolean) =
        tx.encode(endpoint, payload, encrypt).forEach { emit(Channel.CHUNKED, it) }

    override suspend fun writeChunk(bytes: ByteArray) {
        if (silent) return
        val frame = rx.feed(bytes, sessionKey) ?: return
        when (frame.endpoint) {
            0x0082 -> onAuth(frame.payload)
            0x0016 -> if (frame.payload.contentEquals(byteArrayOf(0x03)) && totals != null) {
                val reply = "04010c".unhex() + ByteArray(12).also {
                    Bytes.putU32(it, 0, totals.first); Bytes.putU32(it, 4, totals.second); Bytes.putU32(it, 8, totals.third)
                }
                sendFrame(0x0016, reply, encrypt = true)
            }
            else -> services[frame.endpoint]?.invoke(frame.payload)?.let { sendFrame(frame.endpoint, it, frame.encrypted) }
        }
    }

    private fun onAuth(p: ByteArray) {
        when (p[0].toInt()) {
            0x04 -> {
                val shared = B163.sharedSecret(device.privateKey, p.copyOfRange(4, 52))
                val session = ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() }
                sessionKey = session
                tx.setEncryption(session, Bytes.u32(shared, 0))
                sendFrame(0x0082, "100401".unhex() + random + device.publicKey, encrypt = false)
            }
            0x05 -> {
                val key = checkNotNull(sessionKey)
                val ok = HuamiCrypto.aesEcbDecrypt(authKey, p.copyOfRange(1, 17)).contentEquals(random) &&
                    HuamiCrypto.aesEcbDecrypt(key, p.copyOfRange(17, 33)).contentEquals(random)
                sendFrame(0x0082, if (ok) "100501".unhex() else "100525".unhex(), encrypt = false)
            }
        }
    }

    var fetchChannelOpenedAfterAuth: Boolean? = null

    override suspend fun openFetchChannel() {
        fetchChannelOpenedAfterAuth = sessionKey != null
    }

    override suspend fun writeAck(bytes: ByteArray) {
        acks += bytes
    }

    override suspend fun writeControl(bytes: ByteArray) {
        if (controlWrites.size >= failControlWritesAfter) throw java.io.IOException("radio gone")
        controlWrites += bytes
        if (bytes[0].toInt() == 0x01) lastResponder = responders.getOrPut(bytes[1].toInt() and 0xFF) { BlobFetchStrap(ByteArray(0)) }
        for (event in lastResponder?.handle(bytes) ?: emptyList()) when (event) {
            is FakeFetchStrap.Event.Control -> emit(Channel.CONTROL, event.bytes)
            is FakeFetchStrap.Event.Data -> emit(Channel.DATA, event.bytes)
        }
    }

    private var lastResponder: FetchResponder? = null
    private val responders = fetch.toMutableMap()

    override suspend fun batteryPercent(): Int = 77
}
