package lian.core.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed, non-exportable keys (Phase 3.8.1). Jetpack Security's EncryptedSharedPreferences
 * and MasterKey are deprecated, so the Keystore is configured directly.
 *
 * Two keys, because background work and at-rest protection pull in different directions:
 *  - [historyKey] (AES-256-GCM) only works while the device is unlocked; the UI is the only reader.
 *  - [watchMacKey] (HMAC-SHA256) must work while locked, and only ever produces one-way fingerprints.
 */
object KeystoreKeys {
    const val HISTORY = "lian.k.history.v1"
    const val WATCH = "lian.k.watch.v1"
    private const val PROVIDER = "AndroidKeyStore"

    /** Load-or-create is serialised: two racing creators would otherwise orphan data (Phase 4, defect #4). */
    private val lock = Any()

    /** True when the device lacked a secure lock screen and the history key was created without the unlock requirement. */
    @Volatile
    var historyKeyReducedProtection: Boolean = false
        private set

    fun historyKey(): SecretKey = loadOrCreate(HISTORY, KeyProperties.KEY_ALGORITHM_AES) { strongBox, unlockedOnly ->
        KeyGenParameterSpec.Builder(HISTORY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(unlockedOnly)
            .setIsStrongBoxBacked(strongBox)
            .build()
    }

    fun watchMacKey(): SecretKey = loadOrCreate(WATCH, KeyProperties.KEY_ALGORITHM_HMAC_SHA256) { strongBox, _ ->
        KeyGenParameterSpec.Builder(WATCH, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setIsStrongBoxBacked(strongBox)
            .build()
    }

    /** "Delete all data": without the keys, any leftover ciphertext is permanently unreadable. */
    fun destroyAll() = synchronized(lock) {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        listOf(HISTORY, WATCH).forEach { if (keyStore.containsAlias(it)) keyStore.deleteEntry(it) }
    }

    private fun loadOrCreate(alias: String, algorithm: String, spec: (strongBox: Boolean, unlockedOnly: Boolean) -> KeyGenParameterSpec): SecretKey =
        synchronized(lock) {
            val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

            // StrongBox first, then the TEE; the unlock requirement is dropped only as a last resort
            // (no secure lock screen), and that is surfaced to the user.
            val attempts = listOf(true to true, false to true, false to false)
            var last: Exception? = null
            for ((strongBox, unlockedOnly) in attempts) {
                try {
                    val generator = KeyGenerator.getInstance(algorithm, PROVIDER)
                    generator.init(spec(strongBox, unlockedOnly))
                    val key = generator.generateKey()
                    if (alias == HISTORY && !unlockedOnly) historyKeyReducedProtection = true
                    return key
                } catch (e: ProviderException) {
                    last = e   // includes StrongBoxUnavailableException
                } catch (e: GeneralSecurityException) {
                    last = e
                }
            }
            throw IllegalStateException("could not create Keystore key $alias", last)
        }
}

/**
 * One-way fingerprints for Watch snapshots, so stored snapshots never contain package names.
 * A single Keystore HMAC operation derives an in-memory key per process; per-fingerprint Keystore
 * calls would cost about 1,000 binder round-trips per run (Phase 4, defect #3).
 */
class Fingerprinter(derivedKey: ByteArray) {
    private val key = SecretKeySpec(derivedKey, "HmacSHA256")

    fun of(vararg parts: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        for (part in parts) {
            mac.update(part.toByteArray(Charsets.UTF_8))
            mac.update(0.toByte())   // unambiguous framing between parts
        }
        return toHex(mac.doFinal().copyOf(16))
    }

    companion object {
        private val LABEL = "lian/watch-fp/v1".toByteArray(Charsets.UTF_8)

        fun fromKeystore(macKey: SecretKey): Fingerprinter =
            Fingerprinter(Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(LABEL))

        private fun toHex(bytes: ByteArray): String {
            val hex = "0123456789abcdef"
            return buildString(bytes.size * 2) { for (b in bytes) append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF]) }
        }
    }
}
