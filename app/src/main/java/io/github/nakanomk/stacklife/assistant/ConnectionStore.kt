package io.github.nakanomk.stacklife.assistant

import io.github.nakanomk.stacklife.i18n.uiText
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URISyntaxException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ServiceProfile(val baseUrl: String, val model: String, val origin: String, val supportsImages: Boolean = false)

// Not a data class: no generated toString/componentN/copy that includes the credential.
internal class ServiceConnection(val profile: ServiceProfile, val key: String) {
    init { if (!validKey(key)) throw ConfigurationProblem(uiText("The key must not contain spaces, line breaks, or invalid characters.", "密钥不能包含空格、换行或无效字符。")) }
}

private class ConfigurationProblem(message: String) : IllegalArgumentException(message)

internal fun serviceProfile(baseUrl: String, model: String, supportsImages: Boolean? = null): ServiceProfile {
    val address = baseUrl.trim()
    fun invalidAddress(): Nothing = throw ConfigurationProblem(uiText("Enter a full HTTPS service address. An API path is allowed, but not credentials, a query, or a fragment.", "请输入完整的 HTTPS 服务地址（可含 API 路径，不含账号、查询或片段）。"))
    if (address.length > 4096) invalidAddress()
    val uri = try { URI(address) } catch (_: URISyntaxException) { invalidAddress() }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawAuthority.isNullOrBlank() ||
        '@' in uri.rawAuthority || uri.rawQuery != null || uri.rawFragment != null) invalidAddress()
    val url = address.toHttpUrlOrNull() ?: invalidAddress()
    val modelName = model.trim()
    if (modelName.isBlank() || modelName.length > 256 || modelName.any(Char::isISOControl)) {
        throw ConfigurationProblem(uiText("Enter a valid model name.", "请输入有效的模型名称。"))
    }
    val canonical = url.toString().trimEnd('/')
    return ServiceProfile(
        canonical, modelName,
        url.newBuilder().encodedPath("/").build().toString().removeSuffix("/"),
        supportsImages ?: ServicePreset.entries.any { it != ServicePreset.Custom && it.baseUrl == canonical && it.model == modelName },
    )
}

private fun validKey(key: String): Boolean = key.length in 1..8192 && key.all { it in '!'..'~' }

internal class ConnectionStore(context: Context, storageName: String = "assistant-provider") {
    private val app = context.applicationContext
    private val name = storageName.also { require(it.matches(Regex("[a-zA-Z0-9_-]{1,96}"))) }
    private val alias = "io.github.nakanomk.stacklife.$name"
    private val file get() = AtomicFile(File(app.noBackupFilesDir, name))

    suspend fun profile(): ServiceProfile? = load()?.profile

    suspend fun load(): ServiceConnection? = access { readConnection(file) }

    suspend fun save(baseUrl: String, model: String, newKey: String?, supportsImages: Boolean? = null): ServiceProfile = access {
        val profile = serviceProfile(baseUrl, model, supportsImages)
        val target = file
        val previous = readConnection(target)
        val key = if (newKey.isNullOrBlank()) {
            previous?.takeIf { it.profile.origin == profile.origin }?.key
                ?: throw ConfigurationProblem(uiText("Enter a key when connecting for the first time or changing the service origin, including its port.", "首次连接或更换服务域（含端口）必须重新填写密钥。"))
        } else newKey
        if (!validKey(key)) throw ConfigurationProblem(uiText("The key must not contain spaces, line breaks, or invalid characters.", "密钥不能包含空格、换行或无效字符。"))

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(create = true)) // Keystore supplies a fresh IV.
        val plain = buildJsonObject {
            put("baseUrl", profile.baseUrl); put("model", profile.model); put("key", key)
            put("supportsImages", profile.supportsImages)
        }.toString().encodeToByteArray()
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val iv = cipher.iv
        check(iv.size == IV_SIZE)
        val stream = target.startWrite()
        try {
            stream.write(iv)
            stream.write(encrypted)
            target.finishWrite(stream)
        } catch (failure: Exception) {
            target.failWrite(stream)
            throw failure
        }
        // AtomicFile.finishWrite logs rename failures rather than throwing on some platforms.
        target.openRead().use { check(it.readNBytesCompat(IV_SIZE).contentEquals(iv)) }
        profile
    }

    suspend fun clear(): Unit = access {
        val target = file
        target.delete()
        keyStore().deleteEntry(alias)
        check(!target.baseFile.exists())
    }

    private fun readConnection(target: AtomicFile): ServiceConnection? {
        val stream = try { target.openRead() } catch (failure: FileNotFoundException) {
            if (target.baseFile.exists()) throw failure
            return null
        }
        return stream.use {
            check(it.channel.size() in (IV_SIZE + 16L)..65536L)
            val iv = it.readNBytesCompat(IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(create = false), GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(it.readBytes())
            try {
                val data = Json.parseToJsonElement(plain.decodeToString()).jsonObject
                val profile = serviceProfile(data.getValue("baseUrl").jsonPrimitive.content,
                    data.getValue("model").jsonPrimitive.content, data["supportsImages"]?.jsonPrimitive?.boolean)
                val key = data.getValue("key").jsonPrimitive.content
                ServiceConnection(profile, key)
            } finally { plain.fill(0) }
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(create: Boolean): SecretKey {
        val store = keyStore()
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        check(create)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private suspend fun <T> access(operation: () -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (invalid: ConfigurationProblem) { throw invalid }
            catch (_: Exception) {
                throw IllegalStateException(uiText("Could not access the encrypted service settings. The device key may no longer be valid. Remove the settings and enter them again.", "无法访问加密连接配置：设备密钥可能已失效。请清除连接配置后重新填写。"))
            }
        }
    }

    private companion object {
        val lock = Mutex() // AtomicFile does not serialize different store instances.
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

private fun java.io.InputStream.readNBytesCompat(size: Int): ByteArray =
    ByteArray(size).also { java.io.DataInputStream(this).readFully(it) }
