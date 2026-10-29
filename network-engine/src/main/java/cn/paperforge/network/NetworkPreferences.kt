package cn.paperforge.network

import android.content.Context
import android.security.keystore.*
import android.util.Base64
import java.security.KeyStore
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

/** Network features remain opt-in in both distributions. Tokens use AndroidKeyStore encryption. */
class NetworkPreferences(context: Context) {
    private val integrated = NetworkFiles.integrated(context)
    val store = context.getSharedPreferences("network-tools", 0)
    var enabled: Boolean
        get() = store.getBoolean("updatesEnabled", integrated)
        set(value) {
            store.edit().putBoolean("updatesEnabled", value).commit()
        }

    var repository: String
        get() = store.getString("repository", "HABAI182/paperforge-android")!!
        set(value) {
            store.edit().putString("repository", value.trim()).apply()
        }

    var channel: String
        get() = store.getString("channel", "prerelease")!!
        set(value) {
            store.edit().putString("channel", value).apply()
        }

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (keys.getKey("paperforge-network", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "paperforge-network",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun token(): String = secret("githubToken")

    fun token(value: String) = secret("githubToken", value)

    internal fun secret(slot: String): String {
        val saved = store.getString(slot, null) ?: return ""
        val parts = saved.split(':', limit = 2)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            String(doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    internal fun secret(slot: String, value: String) {
        if (value.isBlank()) {
            store.edit().remove(slot).commit()
            return
        }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        store
            .edit()
            .putString(
                slot,
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
                    ":" +
                    Base64.encodeToString(encrypted, Base64.NO_WRAP),
            )
            .commit()
    }
}
