package com.yagay.yauto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import org.json.JSONObject
import com.yagay.yauto.core.storage.WorkspaceBackupCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class WebDavSettings(val directory: String, val username: String, val password: String)
internal data class WebDavRestorePreview(val workspace: String, val library: String)

/**
 * User-configured HTTPS WebDAV; credentials are AES-GCM encrypted by Android Keystore.
 * We never send Basic credentials to plain HTTP and never follow redirects.
 */
internal object ShortXWebDav {
    private const val PREFS = "yauto_webdav_config"
    private const val KEY_ALIAS = "yauto.webdav.credentials.v1"
    private const val FILE = "YAuto-workspace-library.json"
    private const val LIMIT = 8 * 1024 * 1024

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or
                KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun encrypt(secret: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size in 29..20000)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    fun validateDirectory(raw: String): String {
        val uri = URI(raw.trim())
        require(uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(uri.path.length <= 500 && !uri.path.contains(".."))
        require(uri.host !in setOf("localhost", "127.0.0.1", "::1"))
        return uri.resolve(".").toString()
    }
    fun save(context: Context, config: WebDavSettings) {
        val address = validateDirectory(config.directory)
        require(config.username.length <= 150 && config.password.length <= 4000)
        val secret = encrypt(config.password)
        check(preferences(context).edit().putString("directory", address)
            .putString("username", config.username)
            .putString("credential", secret).commit())
    }
    fun load(context: Context): WebDavSettings {
        val prefs = preferences(context)
        return WebDavSettings(
            prefs.getString("directory", "").orEmpty(),
            prefs.getString("username", "").orEmpty(),
            decrypt(prefs.getString("credential", "").orEmpty()),
        )
    }
    fun forget(context: Context) = preferences(context).edit().clear().commit()

    private fun open(config: WebDavSettings, method: String): HttpURLConnection {
        val address = validateDirectory(config.directory) + FILE
        val connection = URL(address).openConnection() as javax.net.ssl.HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (config.username.isNotEmpty()) {
            val basic = Base64.encodeToString(
                (config.username + ":" + config.password).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic " + basic)
        }
        return connection
    }
    suspend fun push(config: WebDavSettings, workspaceBackup: String, libraryBackup: String): Int =
        withContext(Dispatchers.IO) {
            WorkspaceBackupCodec.decode(workspaceBackup)
            val envelope = JSONObject().put("format", "YAuto.WebDAV.v1")
                .put("workspace", workspaceBackup).put("library", libraryBackup)
                .toString().toByteArray(Charsets.UTF_8)
            require(envelope.size <= LIMIT)
            val connection = open(config, "PUT")
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(envelope.size)
                connection.outputStream.use { it.write(envelope) }
                val code = connection.responseCode
                require(code in 200..299) { "WebDAV upload HTTP $code" }
                envelope.size
            } finally { connection.disconnect() }
        }

    suspend fun pull(config: WebDavSettings): WebDavRestorePreview = withContext(Dispatchers.IO) {
        val connection = open(config, "GET")
        try {
            val code = connection.responseCode
            require(code == 200) { "WebDAV download HTTP $code" }
            require(connection.contentLengthLong in -1L..LIMIT.toLong())
            val bytes = connection.inputStream.use { input ->
                val data = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    require(data.size() + n <= LIMIT) { "WebDAV backup too large" }
                    data.write(chunk, 0, n)
                }
                data.toByteArray()
            }
            val document = JSONObject(bytes.toString(Charsets.UTF_8))
            require(document.getString("format") == "YAuto.WebDAV.v1")
            val workspace = document.getString("workspace")
            val library = document.getString("library")
            WorkspaceBackupCodec.decode(workspace)
            // Library validation is completed before any restoration is requested.
            val entries = JSONObject(library)
            require(entries.getString("format") == "YAuto.Library.v1")
            require(entries.getJSONArray("entries").length() <= 50)
            WebDavRestorePreview(workspace, library)
        } finally { connection.disconnect() }
    }
}
