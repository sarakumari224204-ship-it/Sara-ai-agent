package com.saraai.offline.ai

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureApiKeys {
    private const val PREFS = "sara_secure_api"
    private const val ALIAS = "sara_api_key_master"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (ks.containsAlias(ALIAS)) {
            return (ks.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val kg = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    fun save(ctx: Context, providerId: String, plainKey: String) {
        if (plainKey.isBlank()) { delete(ctx, providerId); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(plainKey.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv + enc, Base64.NO_WRAP)
        prefs(ctx).edit().putString("key_$providerId", payload).apply()
    }

    fun load(ctx: Context, providerId: String): String {
        val payload = prefs(ctx).getString("key_$providerId", null) ?: return ""
        return try {
            val raw = Base64.decode(payload, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val data = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (_: Throwable) { "" }
    }

    fun hasKey(ctx: Context, providerId: String): Boolean =
        !prefs(ctx).getString("key_$providerId", null).isNullOrBlank()

    fun mask(ctx: Context, providerId: String): String {
        val k = load(ctx, providerId)
        if (k.isBlank()) return "(not set)"
        if (k.length <= 8) return "••••••••"
        return k.take(4) + "••••" + k.takeLast(4)
    }

    fun delete(ctx: Context, providerId: String) {
        prefs(ctx).edit().remove("key_$providerId").apply()
    }
}
