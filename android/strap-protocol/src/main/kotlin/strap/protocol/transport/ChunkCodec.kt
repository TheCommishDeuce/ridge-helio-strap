package strap.protocol.transport

import strap.protocol.Bytes
import strap.protocol.crypto.HuamiCrypto

/**
 * The Huami2021 chunked transport over chars `0016`/`0017` (spec/01 §3): frames logical
 * endpoint messages into BLE writes, and reassembles (and decrypts) incoming ones.
 *
 * Encoder and decoder share one file because they are one wire format read in two
 * directions — the flag bits and the padded-ciphertext rule must agree byte for byte.
 */
public object ChunkFormat {
    internal const val MARKER: Int = 0x03
    internal const val FLAG_FIRST: Int = 0x01
    internal const val FLAG_LAST: Int = 0x02
    internal const val FLAG_NEEDS_ACK: Int = 0x04
    internal const val FLAG_ENCRYPTED: Int = 0x08
    internal const val FIRST_HEADER: Int = 11
    internal const val NEXT_HEADER: Int = 5

    /** Plaintext length padded for encryption: `data ‖ seq(4) ‖ crc32(4)` to a 16-byte multiple. */
    internal fun paddedLength(originalLength: Int): Int {
        val raw = originalLength + 8
        return if (raw % 16 == 0) raw else raw + 16 - raw % 16
    }

    internal fun messageKey(sessionKey: ByteArray, handle: Int): ByteArray =
        ByteArray(16) { (sessionKey[it].toInt() xor handle).toByte() }

    /** The ACK the device expects on char `0017` after a frame flagged needs-ack. */
    public fun ack(handle: Int, count: Int): ByteArray =
        byteArrayOf(0x04, 0x00, handle.toByte(), 0x01, count.toByte())
}

/** Splits outgoing messages into chunks. Not thread-safe: one encoder per connection. */
public class ChunkEncoder(private val mtu: Int = DEFAULT_MTU) {
    private var handle = 0
    private var sessionKey: ByteArray? = null
    private var sequence = 0L

    /** Arms encryption for subsequent `encrypt = true` messages. */
    public fun setEncryption(sessionKey: ByteArray, sequence: Long) {
        require(sessionKey.size == 16) { "session key must be 16 bytes" }
        this.sessionKey = sessionKey.copyOf()
        this.sequence = sequence and 0xFFFFFFFFL
    }

    public fun encode(endpoint: Int, data: ByteArray, encrypt: Boolean = false): List<ByteArray> {
        handle = (handle + 1) and 0xFF
        val body = if (encrypt) encrypt(data) else data
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var count = 0
        do {
            val first = count == 0
            val header = if (first) ChunkFormat.FIRST_HEADER else ChunkFormat.NEXT_HEADER
            val budget = maxOf(1, (mtu - 3) - header)
            val take = minOf(body.size - offset, budget)
            val last = body.size - offset <= budget
            var flags = if (first) ChunkFormat.FLAG_FIRST else 0
            if (encrypt) flags = flags or ChunkFormat.FLAG_ENCRYPTED
            if (last) flags = flags or ChunkFormat.FLAG_LAST or ChunkFormat.FLAG_NEEDS_ACK
            val chunk = ByteArray(header + take)
            chunk[0] = ChunkFormat.MARKER.toByte()
            chunk[1] = flags.toByte()
            chunk[3] = handle.toByte()
            chunk[4] = count.toByte()
            if (first) {
                Bytes.putU32(chunk, 5, data.size.toLong()) // the ORIGINAL length, even when encrypted
                Bytes.putU16(chunk, 9, endpoint)
            }
            body.copyInto(chunk, header, offset, offset + take)
            chunks += chunk
            offset += take
            count++
        } while (offset < body.size)
        return chunks
    }

    private fun encrypt(data: ByteArray): ByteArray {
        val key = checkNotNull(sessionKey) { "encrypt requested without a session key" }
        val plain = ByteArray(ChunkFormat.paddedLength(data.size))
        data.copyInto(plain)
        Bytes.putU32(plain, data.size, sequence)
        sequence = (sequence + 1) and 0xFFFFFFFFL
        Bytes.putU32(plain, data.size + 4, HuamiCrypto.crc32(plain, 0, data.size + 4))
        return HuamiCrypto.aesEcbEncrypt(ChunkFormat.messageKey(key, handle), plain)
    }

    public companion object {
        /** The ATT MTU the strap grants; chunk sizes depend on it. */
        public const val DEFAULT_MTU: Int = 247
    }
}

/** One reassembled logical message. */
public class Frame(
    public val endpoint: Int,
    public val payload: ByteArray,
    public val encrypted: Boolean,
    public val needsAck: Boolean,
    public val handle: Int,
    public val count: Int,
)

/** Reassembles chunks arriving on char `0017`. Not thread-safe: one decoder per connection. */
public class ChunkDecoder {
    private var handle: Int? = null
    private var endpoint = 0
    private var length = 0
    private var encrypted = false
    private val buffer = java.io.ByteArrayOutputStream()

    /**
     * Feeds one notification. Returns the frame on its last chunk, else null. Malformed or
     * undecryptable input returns null and resets — the caller sees no frame, never garbage.
     */
    public fun feed(data: ByteArray, sessionKey: ByteArray? = null): Frame? {
        if (data.size < 5 || data[0].toInt() != ChunkFormat.MARKER) return null
        val flags = data[1].toInt() and 0xFF
        val chunkHandle = data[3].toInt() and 0xFF
        val count = data[4].toInt() and 0xFF
        if (handle != null && handle != chunkHandle) return null
        var offset = 5
        if (flags and ChunkFormat.FLAG_FIRST != 0) {
            if (data.size < offset + 6) return null
            length = Bytes.u32(data, offset).toInt()
            endpoint = Bytes.u16(data, offset + 4)
            encrypted = flags and ChunkFormat.FLAG_ENCRYPTED != 0
            buffer.reset()
            handle = chunkHandle
            offset += 6
        }
        buffer.write(data, offset, data.size - offset)
        if (flags and ChunkFormat.FLAG_LAST == 0) return null
        val payload = assemble(sessionKey, chunkHandle)
        val frame = payload?.let {
            Frame(endpoint, it, encrypted, flags and ChunkFormat.FLAG_NEEDS_ACK != 0, chunkHandle, count)
        }
        reset()
        return frame
    }

    private fun assemble(sessionKey: ByteArray?, chunkHandle: Int): ByteArray? {
        var all = buffer.toByteArray()
        if (encrypted) {
            val key = sessionKey ?: return null
            val cipherLength = ChunkFormat.paddedLength(length)
            if (all.size < cipherLength) return null
            all = HuamiCrypto.aesEcbDecrypt(ChunkFormat.messageKey(key, chunkHandle), all.copyOf(cipherLength))
        }
        return all.copyOf(minOf(length, all.size))
    }

    private fun reset() {
        handle = null
        endpoint = 0
        length = 0
        encrypted = false
        buffer.reset()
    }
}
