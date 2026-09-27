package strap.protocol.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * ECDH over NIST **B-163** (sect163r2): `y² + xy = x³ + x² + b` over GF(2¹⁶³), reduction
 * polynomial `x¹⁶³ + x⁷ + x⁶ + x³ + 1` (spec/01 §4). The old code's file was named
 * `sect163k1`; its constants are B-163's.
 *
 * Written from the textbook affine formulas for binary curves, not translated from any
 * other implementation, and cross-checked against vectors produced by the old app.
 * Field arithmetic is [Gf163] (fixed 3×64-bit limbs). Interop only: this is
 * NOT constant-time and must not be reused for anything but talking to the strap.
 *
 * Wire format: a scalar is 24 bytes and a point is `x[24] ‖ y[24]`, each little-endian.
 */
public object B163 {
    public class Keypair(public val privateKey: ByteArray, public val publicKey: ByteArray)

    private const val DEGREE = 163
    private const val BYTES = 24
    private val B: BigInteger = BigInteger("20a601907b8c953ca1481eb10512f78744a3205fd", 16)
    private val B_LIMBS: LongArray = Gf163.of(B)
    internal val G: Point = Point(
        BigInteger("3f0eba16286a2d57ea0991168d4994637e8343e36", 16),
        BigInteger("0d51fbc6c71a0094fa2cdd545b11c5c0c797324f1", 16),
    )

    /** An affine point; the point at infinity is represented by `null`. */
    internal data class Point(val x: BigInteger, val y: BigInteger)

    public fun generateKeypair(random: SecureRandom = SecureRandom()): Keypair {
        repeat(64) {
            val candidate = ByteArray(BYTES).also(random::nextBytes)
            val scalar = sanitize(candidate)
            if (scalar.bitLength() >= DEGREE / 2) return keypairOf(scalar)
        }
        error("keypair generation failed")
    }

    /** Builds the keypair for a given private key — deterministic, for tests and vectors. */
    public fun keypairFrom(privateKey: ByteArray): Keypair {
        require(privateKey.size == BYTES) { "private key must be $BYTES bytes" }
        val scalar = sanitize(privateKey)
        require(scalar.bitLength() >= DEGREE / 2) { "private key too small" }
        return keypairOf(scalar)
    }

    /** The 48-byte shared point `x ‖ y` the handshake derives its session key from. */
    public fun sharedSecret(privateKey: ByteArray, remotePublic: ByteArray): ByteArray {
        require(privateKey.size == BYTES && remotePublic.size == 2 * BYTES) { "bad key sizes" }
        val remote = Point(fromLe(remotePublic, 0), fromLe(remotePublic, BYTES))
        require(remote.x.signum() != 0 || remote.y.signum() != 0) { "remote point is zero" }
        require(onCurve(remote)) { "remote point not on curve" }
        val shared = multiply(remote, sanitize(privateKey)) ?: error("shared point at infinity")
        return encode(shared)
    }

    private fun keypairOf(scalar: BigInteger): Keypair {
        val pub = multiply(G, scalar) ?: error("public point at infinity")
        return Keypair(toLe(scalar), encode(pub))
    }

    /** Masks a 24-byte scalar to its low 162 bits, as the strap's peers do. */
    private fun sanitize(bytes: ByteArray): BigInteger {
        val masked = bytes.copyOf(BYTES)
        masked[20] = (masked[20].toInt() and 0x03).toByte()
        for (i in 21 until BYTES) masked[i] = 0
        return fromLe(masked, 0)
    }

    // ── point arithmetic (a = 1), on GF(2^163) limbs ─────────────────────────

    /** Affine point on limbs; the point at infinity is `null`. */
    private class P(val x: LongArray, val y: LongArray)

    private fun P.toPoint() = Point(Gf163.toBig(x), Gf163.toBig(y))

    private fun Point.toP() = P(Gf163.of(x), Gf163.of(y))

    internal fun onCurve(p: Point): Boolean {
        val x = Gf163.of(p.x)
        val y = Gf163.of(p.y)
        val xx = Gf163.sq(x)
        val lhs = Gf163.add(Gf163.sq(y), Gf163.mul(x, y))
        val rhs = Gf163.add(Gf163.add(Gf163.mul(xx, x), xx), B_LIMBS)
        return Gf163.eq(lhs, rhs)
    }

    internal fun add(p: Point?, q: Point?): Point? = add(p?.toP(), q?.toP())?.toPoint()

    internal fun double(p: Point?): Point? = double(p?.toP())?.toPoint()

    internal fun multiply(p: Point, k: BigInteger): Point? = multiply(p.toP(), k)?.toPoint()

    private fun add(p: P?, q: P?): P? {
        if (p == null) return q
        if (q == null) return p
        if (Gf163.eq(p.x, q.x)) return if (Gf163.eq(p.y, q.y)) double(p) else null // q == -p
        val lambda = Gf163.mul(Gf163.add(p.y, q.y), Gf163.inv(Gf163.add(p.x, q.x)))
        val x3 = Gf163.add(Gf163.add(Gf163.add(Gf163.sq(lambda), lambda), Gf163.add(p.x, q.x)), Gf163.one())
        val y3 = Gf163.add(Gf163.add(Gf163.mul(lambda, Gf163.add(p.x, x3)), x3), p.y)
        return P(x3, y3)
    }

    private fun double(p: P?): P? {
        if (p == null || Gf163.isZero(p.x)) return null
        val lambda = Gf163.add(p.x, Gf163.mul(p.y, Gf163.inv(p.x)))
        val x3 = Gf163.add(Gf163.add(Gf163.sq(lambda), lambda), Gf163.one())
        val y3 = Gf163.add(Gf163.sq(p.x), Gf163.mul(Gf163.add(lambda, Gf163.one()), x3))
        return P(x3, y3)
    }

    private fun multiply(p: P, k: BigInteger): P? {
        var acc: P? = null
        for (i in k.bitLength() - 1 downTo 0) {
            acc = double(acc)
            if (k.testBit(i)) acc = add(acc, p)
        }
        return acc
    }

    // ── encoding ─────────────────────────────────────────────────────────────

    private fun encode(p: Point): ByteArray = toLe(p.x) + toLe(p.y)

    private fun fromLe(bytes: ByteArray, offset: Int): BigInteger {
        val be = ByteArray(BYTES) { bytes[offset + BYTES - 1 - it] }
        return BigInteger(1, be)
    }

    private fun toLe(value: BigInteger): ByteArray {
        val be = value.toByteArray().dropWhile { it == 0.toByte() }
        require(be.size <= BYTES) { "value exceeds $BYTES bytes" }
        return ByteArray(BYTES) { i -> if (i < be.size) be[be.size - 1 - i] else 0 }
    }
}
