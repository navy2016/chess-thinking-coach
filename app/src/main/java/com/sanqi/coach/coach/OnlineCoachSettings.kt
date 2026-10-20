package com.sanqi.coach.coach

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OnlineCoachSettings(context: Context) {
    data class Configuration(val endpoint: String, val model: String, val apiKey: String) {
        override fun toString(): String = "Configuration(endpoint=$endpoint, model=$model, apiKey=<redacted>)"
    }
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("coach", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(app.noBackupFilesDir, "online-coach-key.json"))
    val endpoint: String get() = prefs.getString("coachEndpoint", "").orEmpty()
    val model: String get() = prefs.getString("coachModel", "").orEmpty()
    val enabled: Boolean get() = prefs.getBoolean("coachOnline", false) && prefs.getBoolean("coachConsent", false)

    companion object {
        private const val ALIAS = "sanqi-online-coach"
        fun normalizeEndpoint(value: String): String {
            val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("INVALID_ENDPOINT")
            require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
            return url.toString()
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun save(address: String, modelName: String, secret: String) {
        val normalized = normalizeEndpoint(address)
        require(modelName.isNotBlank() && modelName.length <= 200)
        require(secret.isNotBlank() && secret.length <= 4096 && '\n' !in secret && '\r' !in secret)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(normalized.toByteArray(Charsets.UTF_8))
        val record = JSONObject().put("endpoint", normalized)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val stream = file.startWrite()
        try { stream.write(record.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
        prefs.edit().putString("coachEndpoint", normalized).putString("coachModel", modelName.trim())
            .putBoolean("coachConsent", true).putBoolean("coachOnline", false).apply()
    }

    fun setEnabled(value: Boolean) {
        if (value) require(configuration() != null)
        prefs.edit().putBoolean("coachOnline", value).apply()
    }

    fun configuration(): Configuration? = try {
        val address = normalizeEndpoint(endpoint)
        val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(data.getString("endpoint") == address)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(data.getString("iv"), Base64.NO_WRAP)))
        cipher.updateAAD(address.toByteArray(Charsets.UTF_8))
        val secret = String(cipher.doFinal(Base64.decode(data.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8)
        Configuration(address, model, secret)
    } catch (_: Exception) { null }
}
