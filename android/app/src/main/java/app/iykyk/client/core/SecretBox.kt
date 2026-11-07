package app.iykyk.client.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts what grants access to the VPN (subscription link, server keys) with an
 * AES-256-GCM key that lives in the Android Keystore and never leaves it. A copy
 * of the app's files (root, a backup tool, a lost unlocked phone dumped over USB)
 * then holds only ciphertext.
 *
 * Values written before encryption existed are read as they are and encrypted on
 * the next save.
 */
object SecretBox {
    private const val ALIAS = "iykyk-storage"
    private const val PREFIX = "v1:"
    private const val IV_BYTES = 12

    fun encrypt(plain: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }.getOrElse {
        // A broken Keystore (rare, some old firmware) must not lock the user out.
        AppLog.i("storage: keystore unavailable (${it.javaClass.simpleName}), stored as is")
        plain
    }

    /** Null when the value cannot be decrypted (key lost after a reset): treated as absent. */
    fun decrypt(stored: String?): String? {
        if (stored == null || !stored.startsWith(PREFIX)) return stored
        return runCatching {
            val sealed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.onFailure {
            AppLog.i("storage: cannot decrypt (${it.javaClass.simpleName})")
        }.getOrNull()
    }

    fun isEncrypted(stored: String?): Boolean = stored?.startsWith(PREFIX) == true

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
