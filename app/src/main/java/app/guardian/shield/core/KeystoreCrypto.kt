package app.guardian.shield.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption backed by the hardware-backed Android Keystore.
 *
 * Used for the one secret that matters: the accountability partner's TOTP seed.
 * The key never leaves the keystore, so the seed cannot be recovered by reading
 * app files (e.g. from a backup or an adb pull), only by this app on this device.
 */
object KeystoreCrypto {

    private const val KEY_ALIAS = "shield_master_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return gen.generateKey()
    }

    /** Returns base64(iv || ciphertext), or null if the keystore is unavailable. */
    fun encrypt(plain: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrNull()

    fun decrypt(encoded: String?): ByteArray? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val iv = raw.copyOfRange(0, IV_LENGTH)
            val ct = raw.copyOfRange(IV_LENGTH, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(ct)
        }.getOrNull()
    }
}
