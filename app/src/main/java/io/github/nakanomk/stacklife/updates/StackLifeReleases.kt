package io.github.nakanomk.stacklife.updates

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.nakanomk.stacklife.about.installedVersion
import io.github.nakanomk.stacklife.about.signingDigests
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val preparing = Mutex()

internal class StackLifeReleases(context: Context) {
    private val app = context.applicationContext

    suspend fun check(channel: UpdateChannel): ReleaseUpdate? = withContext(Dispatchers.IO) {
        findRelease(installedVersion(app), app.packageName, channel, { signingDigests(app) }, ::releasePage, ::releaseBuildInfo)
    }

    /** Also call on the install action: neither a remembered result nor a private cache hit is verification. */
    suspend fun prepare(update: ReleaseUpdate, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        preparing.withLock {
            if (app.packageName != RELEASE_PACKAGE) incompatible("Not the ordinary StackLife package")
            requireUpgrade(update, installedVersion(app))
            val context = currentCoroutineContext()
            val dir = File(app.cacheDir, "app-updates")
            val pending = File(dir, "pending.apk")
            val verified = File(dir, apkName(update.version))
            onProgress(0)
            try {
                Files.createDirectories(dir.toPath())
                // Only the current candidate is installable. Committed sessions already own their bytes.
                Files.newDirectoryStream(dir.toPath(), "StackLife-*-preview.apk").use { entries ->
                    for (path in entries) if (path.fileName.toString() != verified.name) Files.deleteIfExists(path)
                }
                // A process killed during a download leaves only this unpublished staging file.
                Files.deleteIfExists(pending.toPath())
                if (verified.exists()) {
                    try {
                        if (verified.length() != update.size) invalid("Cached APK size mismatch")
                        verified.inputStream().use { verifyBytes(it, null, update, context, onProgress) }
                        verifyArchive(verified, update)
                    } catch (failure: ReleaseFailure) {
                        Files.deleteIfExists(verified.toPath())
                        throw failure
                    }
                } else {
                    try {
                        fetchApk(update.version) { input, size ->
                            if (size >= 0 && size != update.size) invalid("APK Content-Length mismatch")
                            pending.outputStream().use { verifyBytes(input, it, update, context, onProgress) }
                        }
                        context.ensureActive()
                        verifyArchive(pending, update)
                        context.ensureActive()
                        Files.move(pending.toPath(), verified.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } finally {
                        Files.deleteIfExists(pending.toPath())
                    }
                }
                context.ensureActive()
                onProgress(100)
                verified
            } catch (failure: ReleaseFailure) {
                throw failure
            } catch (e: IOException) {
                throw ReleaseFailure(ReleaseProblem.INVALID, "Cannot prepare private APK file", e)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(file: File, update: ReleaseUpdate) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: invalid("Unreadable APK archive")
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != app.packageName || archive.versionName != update.version || code != update.versionCode) {
            incompatible("APK package, version or code differs")
        }
        val info = archive.applicationInfo ?: invalid("APK has no application info")
        if (info.minSdkVersion > Build.VERSION.SDK_INT || info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            incompatible("Not a compatible preview APK")
        }
        val signatures = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        val signer = signatures?.singleOrNull() ?: incompatible("Expected a single APK signer")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(signer.toByteArray()).toHexString()
        if (signingDigests(app).singleOrNull() != fingerprint) incompatible("APK signing certificate differs")
    }
}

/** Stream the file once, stop at the declared bound, and reserve 100% for the verified archive. */
internal fun verifyBytes(input: InputStream, output: OutputStream?, update: ReleaseUpdate, context: CoroutineContext, progress: (Int) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    var shown = 0
    while (true) {
        context.ensureActive()
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > update.size) invalid("APK larger than declared")
        digest.update(buffer, 0, n)
        output?.write(buffer, 0, n)
        val percent = (total * 100 / update.size).toInt().coerceAtMost(99)
        if (percent != shown) { shown = percent; progress(percent) }
    }
    if (total != update.size || digest.digest().toHexString() != update.sha256) invalid("APK size or SHA-256 mismatch")
}
