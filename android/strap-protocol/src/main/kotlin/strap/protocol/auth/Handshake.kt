package strap.protocol.auth

import strap.protocol.Bytes
import strap.protocol.crypto.B163
import strap.protocol.crypto.HuamiCrypto

/**
 * The five-step ZeppOS auth handshake on endpoint `0x0082` (spec/01 §4), as a pure state
 * machine: the caller frames [start]'s payload and any [HandshakeEvent.SendProof] payload
 * with an unencrypted `ChunkEncoder` on endpoint `0x0082`, and feeds reassembled `0x0082`
 * payloads to [onPayload].
 *
 * Secrets (auth key, session key) never appear in [toString] or failure reasons.
 */
public class Handshake(
    authKey: ByteArray,
    private val keypair: B163.Keypair = B163.generateKeypair(),
) {
    private val authKey: ByteArray = authKey.copyOf()
    private var state = State.IDLE
    private var sessionKey: ByteArray? = null
    private var sequence = 0L

    init {
        require(authKey.size == 16) { "auth key must be 16 bytes" }
    }

    private enum class State { IDLE, SENT_PUBLIC_KEY, SENT_PROOF, DONE }

    /** Step 1: our public key, `04 02 00 02 ‖ pub[48]`. */
    public fun start(): ByteArray {
        check(state == State.IDLE) { "handshake already started" }
        state = State.SENT_PUBLIC_KEY
        return byteArrayOf(0x04, 0x02, 0x00, 0x02) + keypair.publicKey
    }

    /** Steps 2 and 5. Anything that is not a reply we are waiting for is [HandshakeEvent.Ignored]. */
    public fun onPayload(payload: ByteArray): HandshakeEvent {
        if (payload.size < 3 || Bytes.u8(payload, 0) != RESPONSE) return HandshakeEvent.Ignored
        val status = Bytes.u8(payload, 2)
        return when {
            Bytes.u8(payload, 1) == CMD_PUBLIC_KEY && state == State.SENT_PUBLIC_KEY -> onPublicKeyReply(status, payload)
            Bytes.u8(payload, 1) == CMD_SESSION_KEY && state == State.SENT_PROOF -> onSessionReply(status)
            else -> HandshakeEvent.Ignored
        }
    }

    private fun onPublicKeyReply(status: Int, payload: ByteArray): HandshakeEvent {
        if (status != STATUS_OK) return fail(HandshakeFailure.Rejected(step = 2, status = status))
        if (payload.size < 67) return fail(HandshakeFailure.Malformed("public-key reply is ${payload.size} bytes"))
        val random = payload.copyOfRange(3, 19)
        val shared = try {
            B163.sharedSecret(keypair.privateKey, payload.copyOfRange(19, 67))
        } catch (e: IllegalArgumentException) {
            return fail(HandshakeFailure.Malformed(e.message ?: "bad remote public key"))
        }
        sequence = Bytes.u32(shared, 0)
        val session = ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() }
        sessionKey = session
        state = State.SENT_PROOF
        val proof = byteArrayOf(CMD_SESSION_KEY.toByte()) +
            HuamiCrypto.aesEcbEncrypt(authKey, random) +
            HuamiCrypto.aesEcbEncrypt(session, random)
        return HandshakeEvent.SendProof(proof)
    }

    private fun onSessionReply(status: Int): HandshakeEvent = when (status) {
        STATUS_OK -> {
            state = State.DONE
            HandshakeEvent.Authenticated(checkNotNull(sessionKey).copyOf(), sequence)
        }
        STATUS_WRONG_KEY -> fail(HandshakeFailure.WrongAuthKey)
        else -> fail(HandshakeFailure.Rejected(step = 5, status = status))
    }

    private fun fail(failure: HandshakeFailure): HandshakeEvent {
        state = State.DONE
        return HandshakeEvent.Failed(failure)
    }

    override fun toString(): String = "Handshake(state=$state)"

    private companion object {
        const val RESPONSE = 0x10
        const val CMD_PUBLIC_KEY = 0x04
        const val CMD_SESSION_KEY = 0x05
        const val STATUS_OK = 0x01
        const val STATUS_WRONG_KEY = 0x25
    }
}

public sealed interface HandshakeEvent {
    /** Not a reply this handshake is waiting for. */
    public data object Ignored : HandshakeEvent

    /** Step 4: frame and send this payload. */
    public class SendProof(public val payload: ByteArray) : HandshakeEvent

    /** Step 5 succeeded; these two drive encryption of every later frame. */
    public class Authenticated(public val sessionKey: ByteArray, public val sequence: Long) : HandshakeEvent {
        override fun toString(): String = "Authenticated(sequence=<redacted>)"
    }

    public data class Failed(val failure: HandshakeFailure) : HandshakeEvent
}

/** Why the strap refused — secret-free by construction. Timeouts are the caller's to name. */
public sealed interface HandshakeFailure {
    /** Status `0x25`: the auth key does not match this strap's pairing. */
    public data object WrongAuthKey : HandshakeFailure

    public data class Rejected(val step: Int, val status: Int) : HandshakeFailure

    public data class Malformed(val reason: String) : HandshakeFailure
}
