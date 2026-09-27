package strap.protocol.crypto

import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The symmetric primitives the Huami/ZeppOS wire format needs (spec/01 §3, §4).
 *
 * AES-128/ECB/NoPadding for the auth proof and the encrypted chunk channel; CRC-32 is the
 * IEEE/zlib one, which is exactly `java.util.zip.CRC32`.
 */
public object HuamiCrypto {
    public fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray = aes(Cipher.ENCRYPT_MODE, key, data)

    public fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray = aes(Cipher.DECRYPT_MODE, key, data)

    /** CRC-32 (IEEE) over `data[from until to)`. */
    public fun crc32(data: ByteArray, from: Int = 0, to: Int = data.size): Long =
        CRC32().apply { update(data, from, to - from) }.value

    /** Parses `0x<32 hex>` or `<32 hex>` into the strap's 16-byte auth key. */
    public fun parseAuthKey(text: String): ByteArray {
        val hex = text.trim().removePrefix("0x").removePrefix("0X")
        require(hex.length == 32 && hex.all { it.isHexDigit() }) { "auth key must be 32 hex characters" }
        return ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun aes(mode: Int, key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-128 needs a 16-byte key, got ${key.size}" }
        require(data.isNotEmpty() && data.size % 16 == 0) { "ECB input must be a non-zero multiple of 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
