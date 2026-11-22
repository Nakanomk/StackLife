package io.github.nakanomk.stacklife.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Test fun persistsEncryptedProfileWithFreshIvAndBindsKeyToCanonicalOrigin() = runBlocking {
        val name = "assistant-test-${UUID.randomUUID()}"
        val store = ConnectionStore(context, name)
        val key = "fixture-only-${UUID.randomUUID()}"
        val path = File(context.noBackupFilesDir, name)
        val alias = "io.github.nakanomk.stacklife.$name"
        try {
            assertNull(store.profile())
            assertTrue(runCatching { store.save("https://one.invalid/api", "fixture-model", null) }.isFailure)
            val saved = store.save("https://ONE.invalid:443/api/", "fixture-model", key)
            assertEquals(ServiceProfile("https://one.invalid/api", "fixture-model", "https://one.invalid"), saved)
            val first = path.readBytes()
            assertNull("Keystore key cannot be exported", keystore().getKey(alias, null).encoded)
            for (plain in listOf(key, "one.invalid", "fixture-model")) {
                assertFalse(first.toString(Charsets.ISO_8859_1).contains(plain))
            }
            val reopened = ConnectionStore(context, name)
            assertEquals(saved, reopened.profile())
            assertEquals(key, reopened.load()?.key)
            assertFalse(reopened.load().toString().contains(key))
            reopened.save(saved.baseUrl, saved.model, null)
            val second = path.readBytes()
            assertFalse("even identical plaintext is encrypted under a new IV", first.copyOf(12).contentEquals(second.copyOf(12)))
            reopened.save(saved.baseUrl, saved.model, null, supportsImages = true)
            assertTrue(store.profile()!!.supportsImages)
            reopened.save("https://one.invalid/new-prefix/v1", "m2", "   ")
            assertFalse(store.profile()!!.supportsImages)
            assertEquals(key, store.load()?.key)
            for (changed in listOf("https://two.invalid/v1", "https://one.invalid:444/v1")) {
                assertTrue(runCatching { store.save(changed, "m2", null) }.exceptionOrNull() is IllegalArgumentException)
                assertEquals("https://one.invalid", reopened.profile()?.origin)
                assertEquals(key, reopened.load()?.key)
            }
            val replacement = "fixture-replacement"
            store.save("https://two.invalid/v1", "m2", replacement)
            assertEquals("https://two.invalid", reopened.profile()?.origin)
            assertEquals(replacement, reopened.load()?.key)
        } finally { store.clear() }
        assertNull(store.load()); assertFalse(path.exists()); assertFalse(keystore().containsAlias(alias))
    }

    @Test fun missingDeviceKeyNeverReturnsOrReplacesCiphertextUntilExplicitClear() = runBlocking {
        val name = "assistant-test-${UUID.randomUUID()}"
        val store = ConnectionStore(context, name)
        val path = File(context.noBackupFilesDir, name)
        val key = "fixture-secret-${UUID.randomUUID()}"
        try {
            store.save("https://lost.invalid/v1", "private-model", key)
            val encrypted = path.readBytes()
            keystore().deleteEntry("io.github.nakanomk.stacklife.$name")
            val error = runCatching { store.load() }.exceptionOrNull()
            assertTrue(error is IllegalStateException && "清除" in error.message.orEmpty())
            assertFalse(error.toString().contains(key)); assertFalse(error.toString().contains("private-model"))
            assertTrue(runCatching { store.save("https://lost.invalid/v2", "m2", "explicit-new-key") }.isFailure)
            assertArrayEquals(encrypted, path.readBytes())
            store.clear()
            assertNull(store.profile())
            store.save("https://recover.invalid/v1", "m3", "new-test-only-key")
            assertEquals("new-test-only-key", ConnectionStore(context, name).load()?.key)
        } finally { store.clear() }
    }
}
