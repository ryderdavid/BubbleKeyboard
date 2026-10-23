package com.ryderdavid.voicebar.voice

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys, encrypted at rest with an AES key that lives in the Android Keystore (it never leaves
 * the secure hardware where the phone has it). The ciphertext sits in its own private prefs file,
 * which is left out of backups and device transfers (the Keystore key can't follow it anyway).
 * Without a working Keystore nothing is saved; [put] reports that.
 */
object Secrets {
    const val ELEVENLABS = "elevenlabs_api_key"
    const val ANTHROPIC = "anthropic_api_key"
    /** Optional: cleanup with GPT-6 Luna instead of (or as well as) Claude. */
    const val OPENAI = "openai_api_key"

    private const val KEY_ALIAS = "voicebar_secrets"
    private const val PREFIX = "secret_"
    /** Excluded from backup in res/xml/backup_rules.xml and data_extraction_rules.xml. */
    const val FILE = "voicebar_secrets"

    /** Lets tests run without the Android Keystore. */
    @androidx.annotation.VisibleForTesting
    var allowPlaintextForTests = false

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Saves (or with a blank value removes) a key; false if it couldn't be stored securely. */
    fun put(context: Context, name: String, value: String?): Boolean {
        val prefs = prefs(context)
        if (value.isNullOrBlank()) {
            prefs.edit { remove(PREFIX + name) }
            return true
        }
        val sealed = runCatching { encrypt(value.trim()) }.getOrNull() ?: return false
        prefs.edit { putString(PREFIX + name, sealed) }
        return true
    }

    fun get(context: Context, name: String): String? {
        val stored = prefs(context).getString(PREFIX + name, null) ?: return null
        return runCatching { decrypt(stored) }.getOrNull()
    }

    fun has(context: Context, name: String) = !get(context, name).isNullOrBlank()

    private fun keystoreKey(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }.getOrNull()

    private fun encrypt(plain: String): String? {
        val key = keystoreKey()
            ?: return if (allowPlaintextForTests) "p:" + Base64.encodeToString(plain.toByteArray(), Base64.NO_WRAP) else null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
        return "k:$iv:$data"
    }

    private fun decrypt(stored: String): String? {
        if (stored.startsWith("p:")) return if (!allowPlaintextForTests) null else String(Base64.decode(stored.substring(2), Base64.NO_WRAP))
        val parts = stored.split(":")
        if (parts.size != 3 || parts[0] != "k") return null
        val key = keystoreKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)))
    }
}
