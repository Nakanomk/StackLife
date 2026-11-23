package io.github.nakanomk.stacklife.updates

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.about.baseApkDigest
import io.github.nakanomk.stacklife.about.installedVersion
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CachedReleaseTest {
    private fun withCache(block: (Context, File) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(target.cacheDir.toPath(), "release-test-").toFile()
        val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getCacheDir(): File = root
        }
        try { block(context, File(root, "app-updates").apply { mkdir() }) } finally { root.deleteRecursively() }
    }

    @Test fun genuineInstalledApkCannotMasqueradeAsTheOfferedNewerVersion() = withCache { context, dir ->
        val (name, code) = installedVersion(context)
        val next = "${requireNotNull(name).substringBefore('.').toInt() + 1}.0.0"
        val archive = File(context.applicationInfo.sourceDir).copyTo(File(dir, apkName(next)))
        // Matching package, signer, length and SHA-256; the archive's own version must still match.
        val update = ReleaseUpdate(next, code + 1, "", archive.length(), baseApkDigest(context))
        val progress = mutableListOf<Int>()
        val failure = assertThrows(ReleaseFailure::class.java) { runBlocking { StackLifeReleases(context).prepare(update, progress::add) } }
        assertEquals(ReleaseProblem.INCOMPATIBLE, failure.problem)
        assertFalse(archive.exists())
        assertFalse(100 in progress)
    }

    @Test fun discardsInterruptedTransferAndRejectsChangedCacheEvenAtExpectedLength() = withCache { context, dir ->
        val installed = installedVersion(context)
        val next = "${requireNotNull(installed.name).substringBefore('.').toInt() + 1}.0.0"
        val update = ReleaseUpdate(next, installed.code + 1, "", 4L, MessageDigest.getInstance("SHA-256").digest(byteArrayOf(0, 1, 2, 3)).toHexString())
        val cached = File(dir, apkName(next)).apply { writeBytes(byteArrayOf(0, 1, 2, 4)) }
        val partial = File(dir, "pending.apk").apply { writeBytes(byteArrayOf(0, 1)) }
        val progress = mutableListOf<Int>()
        val failure = assertThrows(ReleaseFailure::class.java) { runBlocking { StackLifeReleases(context).prepare(update, progress::add) } }
        assertEquals(ReleaseProblem.INVALID, failure.problem)
        assertFalse(cached.exists())
        assertFalse(partial.exists())
        assertFalse(100 in progress)
    }
}
