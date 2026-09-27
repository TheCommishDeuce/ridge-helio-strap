package app.strap.pairing

import android.content.Context
import app.strap.BuildConfig
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import strap.protocol.crypto.HuamiCrypto
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The paired strap: its address and its 16-byte auth key. [toString] never shows the key. */
class Pairing(val mac: String, val authKey: ByteArray) {
    override fun toString(): String = "Pairing($mac)"

    companion object {
        private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

        /**
         * From what the owner pastes: either `mac` + a 32-hex key, or the keyfetch QR payload
         * (`{"v":1,"mac":…,"key":…}`) in the key field. Returns null with nothing half-parsed.
         */
        fun parse(macInput: String, keyInput: String): Pairing? {
            val text = keyInput.trim()
            val (mac, key) = if (text.startsWith("{")) {
                runCatching { JSONObject(text).let { it.getString("mac") to it.getString("key") } }.getOrNull() ?: return null
            } else {
                macInput to text
            }
            val normalized = mac.trim().uppercase()
            if (!MAC.matches(normalized)) return null
            val bytes = runCatching { HuamiCrypto.parseAuthKey(key) }.getOrNull() ?: return null
            return Pairing(normalized, bytes)
        }
    }
}

/** Where the phone pushes to. [toString] never shows the token. */
class ServerLink(val baseUrl: String, val token: String) {
    override fun toString(): String = "ServerLink($baseUrl)"

    companion object {
        /**
         * Accepts `https://host[/]` and the 64-hex token from the server setup. HTTPS only,
         * except that the demo build also takes `http://127.0.0.1:<port>` (its local server).
         */
        fun parse(url: String, token: String): ServerLink? {
            val u = url.trim().trimEnd('/').let { if ("://" in it) it else "https://$it" }
            val t = token.trim()
            val local = BuildConfig.LOCAL_HTTP && Regex("^http://127\\.0\\.0\\.1(:\\d+)?$").matches(u)
            if (!(u.startsWith("https://") || local) || !Regex("^[0-9a-fA-F]{64}$").matches(t)) return null
            return ServerLink(u, t)
        }
    }
}

/**
 * Stores the pairing with the auth key encrypted under an Android Keystore AES-GCM key that
 * never leaves the secure hardware. The MAC is not a secret and is stored beside it.
 */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    /**
     * The stored pairing, or null. A sealed key this device's Keystore cannot open (restored
     * from elsewhere, or its Keystore key was wiped) reads as "not paired": the owner pastes
     * the key again, rather than the app crashing on every start.
     */
    fun load(): Pairing? {
        val mac = prefs.getString(KEY_MAC, null) ?: return null
        val sealed = prefs.getString(KEY_SEALED, null) ?: return null
        return open(sealed)?.let { Pairing(mac, it) }
    }

    fun save(pairing: Pairing) {
        prefs.edit().putString(KEY_MAC, pairing.mac).putString(KEY_SEALED, seal(pairing.authKey)).apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_MAC).remove(KEY_SEALED).apply()
    }

    /** The server's base URL and the phone's bearer token (sealed like the auth key), or null. */
    fun loadServer(): ServerLink? {
        val url = prefs.getString(KEY_SERVER_URL, null) ?: return null
        val sealed = prefs.getString(KEY_SERVER_TOKEN, null) ?: return null
        return open(sealed)?.let { ServerLink(url, String(it, Charsets.UTF_8)) }
    }

    fun saveServer(link: ServerLink) {
        prefs.edit()
            .putString(KEY_SERVER_URL, link.baseUrl)
            .putString(KEY_SERVER_TOKEN, seal(link.token.toByteArray(Charsets.UTF_8)))
            .apply()
    }

    fun forgetServer() {
        prefs.edit().remove(KEY_SERVER_URL).remove(KEY_SERVER_TOKEN).apply()
    }

    private fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun open(sealed: String): ByteArray? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, IV_BYTES))
        cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
    } catch (e: GeneralSecurityException) {
        Log.w("strap", "sealed value cannot be opened on this device (${e.javaClass.simpleName})")
        null
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "strap_auth_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val KEY_MAC = "mac"
        const val KEY_SEALED = "sealed_key"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_SERVER_TOKEN = "server_token"
    }
}
