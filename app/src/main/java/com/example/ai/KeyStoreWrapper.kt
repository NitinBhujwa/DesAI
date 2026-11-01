package com.example.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Interface for encrypted API key storage.
 * Keys must never be hardcoded and must never be logged.
 */
interface ApiKeyStore {
  fun saveKey(providerId: String, apiKey: String)
  fun getKey(providerId: String): String?
  fun deleteKey(providerId: String)
}

/**
 * Android Keystore-backed AES/GCM implementation of [ApiKeyStore].
 */
class AndroidKeyStoreApiKeyStore(context: Context) : ApiKeyStore {
  private val prefs =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  override fun saveKey(providerId: String, apiKey: String) {
    if (apiKey.isBlank()) {
      deleteKey(providerId)
      return
    }
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
    val iv = cipher.iv
    val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
    val combined =
      Base64.encodeToString(iv, Base64.NO_WRAP) +
        ":" +
        Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    prefs.edit().putString(prefKey(providerId), combined).apply()
  }

  override fun getKey(providerId: String): String? {
    val stored = prefs.getString(prefKey(providerId), null) ?: return null
    val parts = stored.split(":")
    if (parts.size != 2) return null
    return runCatching {
      val iv = Base64.decode(parts[0], Base64.NO_WRAP)
      val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
      val cipher = Cipher.getInstance(TRANSFORMATION)
      val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
      String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()
  }

  override fun deleteKey(providerId: String) {
    prefs.edit().remove(prefKey(providerId)).apply()
  }

  private fun getOrCreateSecretKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
    if (existing != null) {
      return existing.secretKey
    }
    val keyGenerator =
      KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec =
      KeyGenParameterSpec.Builder(
          KEY_ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private fun prefKey(providerId: String): String = "enc_key_${providerId.lowercase()}"

  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ui_studio_ai_provider_master_key"
    private const val PREFS_NAME = "ui_studio_encrypted_keys"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
  }
}
