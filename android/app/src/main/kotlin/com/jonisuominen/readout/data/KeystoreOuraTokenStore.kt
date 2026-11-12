package com.jonisuominen.readout.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.jonisuominen.readout.core.OuraError
import com.jonisuominen.readout.core.OuraException
import com.jonisuominen.readout.core.OuraTokenStoring
import com.jonisuominen.readout.core.OuraTokens
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Oura tokens, encrypted with a key that never leaves the Android Keystore.
 * They are kept out of the snapshot file and out of backups.
 */
class KeystoreOuraTokenStore(context: Context) : OuraTokenStoring {
    private val preferences = context.getSharedPreferences("readout.secure", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): OuraTokens? {
        val stored = preferences.getString(TOKENS_KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            val plain = cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
            json.decodeFromString(OuraTokens.serializer(), plain.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            throw OuraException(OuraError.SECURE_STORAGE)
        }
    }

    override fun save(tokens: OuraTokens) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(json.encodeToString(OuraTokens.serializer(), tokens).toByteArray(Charsets.UTF_8))
            val stored = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
            if (!preferences.edit().putString(TOKENS_KEY, stored).commit()) throw OuraException(OuraError.SECURE_STORAGE)
        } catch (error: OuraException) {
            throw error
        } catch (_: Exception) {
            throw OuraException(OuraError.SECURE_STORAGE)
        }
    }

    override fun delete() {
        try {
            if (!preferences.edit().remove(TOKENS_KEY).commit()) throw OuraException(OuraError.SECURE_STORAGE)
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (error: OuraException) {
            throw error
        } catch (_: Exception) {
            throw OuraException(OuraError.SECURE_STORAGE)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "readout.oura.tokens"
        const val TOKENS_KEY = "oura.oauth.tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
