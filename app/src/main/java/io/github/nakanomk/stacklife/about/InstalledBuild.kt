package io.github.nakanomk.stacklife.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

internal data class InstalledVersion(val name: String?, val code: Long)

@Suppress("DEPRECATION")
internal fun installedVersion(context: Context): InstalledVersion {
    val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()
    return InstalledVersion(pkg.versionName, code)
}

/** sourceDir is the installed *base* APK, not a remote release or an APK set. Stream it off the UI thread. */
internal fun baseApkDigest(context: Context): String {
    val md = MessageDigest.getInstance("SHA-256")
    File(context.applicationInfo.sourceDir).inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            md.update(buffer, 0, read)
        }
    }
    return md.digest().toHexString()
}

@Suppress("DEPRECATION")
internal fun signingDigests(context: Context): List<String> {
    val pm = context.packageManager
    val pkg = context.packageName
    val signers = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
    } else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
    return signers.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHexString() }
}
