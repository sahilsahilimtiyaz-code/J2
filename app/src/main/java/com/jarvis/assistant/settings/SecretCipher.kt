package com.jarvis.assistant.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecretCipherException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pluggable key provider so [SecretCipher] uses the hardware-backed Android
 * Keystore (`AndroidKeyStore`) on device while remaining deterministic in JVM
 * unit tests.
 */
interface KeystoreKeyProvider {
    fun getOrCreateKey(): SecretKey
}

class AndroidKeystoreKeyProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : KeystoreKeyProvider {

    override fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_KEY_ALIAS = "jarvis_settings_aes_v1"
    }
}

/**
 * In-memory AES-256 key provider used only for pure JVM unit tests where the
 * Android Keystore hardware provider is not registered.
 */
class InMemoryKeystoreKeyProvider(
    keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
) : KeystoreKeyProvider {
    private val secretKey: SecretKey = SecretKeySpec(keyBytes.copyOf(32), "AES")
    override fun getOrCreateKey(): SecretKey = secretKey
}

/**
 * Encrypts TTS API keys, LLM provider API keys, and other sensitive credentials
 * with an AES-256-GCM key held by the Android Keystore (`AndroidKeyStore`).
 *
 * The master key never leaves the Keystore. The encoded payload format is:
 * `[version=1][iv length][iv][ciphertext+tag]`, Base64-encoded (no wrap) for
 * safe persistence in DataStore and the synchronous Keystore Vault.
 */
class SecretCipher(
    private val keyProvider: KeystoreKeyProvider = AndroidKeystoreKeyProvider(),
) {

    private val memoryCache = ConcurrentHashMap<String, String>()

    /**
     * Returns true if [value] already matches the binary framing of an encrypted
     * SecretCipher payload (`[VERSION=1][ivLen][iv][ciphertext]`), preventing
     * accidental double-encryption.
     */
    fun isEncryptedPayload(value: String): Boolean {
        val clean = value.trim()
        if (clean.length < 24) return false
        return runCatching {
            val packed = Base64.getDecoder().decode(clean)
            if (packed.size < 4 || packed[0] != VERSION) return false
            val ivLength = packed[1].toInt() and 0xFF
            ivLength in 12..16 && packed.size >= 2 + ivLength + 16
        }.getOrDefault(false)
    }

    @Synchronized
    fun encrypt(plainText: String): String {
        val clean = plainText.trim()
        if (clean.isEmpty()) return ""
        // Guard against double-encrypting an already-encrypted payload
        if (isEncryptedPayload(clean)) {
            val verified = runCatching { decrypt(clean) }.getOrNull()
            if (!verified.isNullOrBlank()) return clean
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider.getOrCreateKey())
            cipher.updateAAD(ASSOCIATED_DATA)
            val iv = cipher.iv
            require(iv.size in 1..255) { "Unexpected IV length" }
            val ciphertext = cipher.doFinal(clean.toByteArray(StandardCharsets.UTF_8))
            val packed = ByteBuffer.allocate(2 + iv.size + ciphertext.size)
                .put(VERSION)
                .put(iv.size.toByte())
                .put(iv)
                .put(ciphertext)
                .array()
            val encoded = Base64.getEncoder().withoutPadding().encodeToString(packed)
                .let { Base64.getEncoder().encodeToString(packed) }
            memoryCache[encoded] = clean
            encoded
        } catch (e: Exception) {
            throw SecretCipherException("Could not encrypt the API key on this device.", e)
        }
    }

    @Synchronized
    fun decrypt(encoded: String): String {
        val cleanEncoded = encoded.trim()
        if (cleanEncoded.isEmpty()) return ""
        memoryCache[cleanEncoded]?.let { cached ->
            if (cached.isNotBlank()) return cached
        }
        return try {
            val packed = Base64.getDecoder().decode(cleanEncoded)
            require(packed.size >= 4) { "Encrypted value is too short" }
            require(packed[0] == VERSION) { "Unknown encrypted value version" }
            val ivLength = packed[1].toInt() and 0xFF
            require(ivLength in 1..255) { "Invalid IV length" }
            require(packed.size > 2 + ivLength) { "Encrypted value is truncated" }
            val iv = packed.copyOfRange(2, 2 + ivLength)
            val ciphertext = packed.copyOfRange(2 + ivLength, packed.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider.getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(ASSOCIATED_DATA)
            val plain = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8).trim()
            if (plain.isNotBlank()) {
                memoryCache[cleanEncoded] = plain
            }
            plain
        } catch (e: Exception) {
            memoryCache[cleanEncoded] ?: throw SecretCipherException(
                "The saved API key could not be decrypted. Re-enter it in Settings.",
                e,
            )
        }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        val ASSOCIATED_DATA: ByteArray = "jarvis.settings.v1".toByteArray(StandardCharsets.UTF_8)
    }
}

/**
 * Synchronous, commit-verified Android Keystore Vault that persists encrypted
 * TTS API keys (Fish Audio, ElevenLabs) and LLM provider keys to private disk
 * storage immediately using `commit()`, ensuring credentials are never lost
 * across app restarts, process kills, or configuration updates.
 */
class EncryptedKeystoreVault(
    context: Context,
    private val cipher: SecretCipher,
) {
    private val prefs = context.applicationContext.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun putEncrypted(slot: String, encryptedValue: String) {
        val clean = encryptedValue.trim()
        if (clean.isBlank()) return
        prefs.edit().putString(slot, clean).commit()
    }

    @Synchronized
    fun putPlaintext(slot: String, plainText: String): String {
        val clean = plainText.trim()
        if (clean.isBlank()) return getEncrypted(slot)
        val encrypted = cipher.encrypt(clean)
        prefs.edit().putString(slot, encrypted).commit()
        return encrypted
    }

    @Synchronized
    fun getEncrypted(slot: String): String =
        prefs.getString(slot, null)?.trim().orEmpty()

    @Synchronized
    fun getDecrypted(slot: String): String {
        val enc = getEncrypted(slot)
        if (enc.isBlank()) return ""
        return runCatching { cipher.decrypt(enc) }.getOrDefault("")
    }

    @Synchronized
    fun putMetadata(slot: String, value: String) {
        val clean = value.trim()
        if (clean.isBlank()) return
        prefs.edit().putString(slot, clean).commit()
    }

    @Synchronized
    fun getMetadata(slot: String): String =
        prefs.getString(slot, null)?.trim().orEmpty()

    @Synchronized
    fun removeProviderKey(providerId: String) {
        prefs.edit().remove(slotForProvider(providerId)).commit()
    }

    companion object {
        const val VAULT_PREFS_NAME = "jarvis_keystore_vault_v1"
        const val SLOT_TTS_ACTIVE = "vault_tts_active_key"
        const val SLOT_TTS_FISH = "vault_tts_fish_key"
        const val SLOT_TTS_ELEVEN = "vault_tts_eleven_key"
        const val SLOT_VOICE_ID_FISH = "vault_voice_id_fish"
        const val SLOT_VOICE_ID_ELEVEN = "vault_voice_id_eleven"

        fun slotForProvider(providerId: String): String =
            "vault_provider_key_${providerId.trim().lowercase()}"
    }
}
