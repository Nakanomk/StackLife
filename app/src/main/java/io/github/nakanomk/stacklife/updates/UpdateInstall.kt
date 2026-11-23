package io.github.nakanomk.stacklife.updates

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import io.github.nakanomk.stacklife.StackLifeApplication
import io.github.nakanomk.stacklife.about.installedVersion
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val INSTALL_RESULT = "io.github.nakanomk.stacklife.UPDATE_RESULT"

/** Android alone authorizes and replaces the installed package. The receipt can outlive this process. */
internal suspend fun installUpdate(context: Context, apk: File, release: ReleaseUpdate, unattended: Boolean,
    recordSession: (Int) -> Boolean) = withContext(Dispatchers.IO) {
    val installer = context.packageManager.packageInstaller
    check(installedVersion(context).code < release.versionCode && apk.length() == release.size)
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(context.packageName)
        setSize(release.size)
        if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
        if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(if (unattended) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            else PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
    }
    val id = installer.createSession(params)
    try {
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, release.size).use { dst ->
                apk.inputStream().use { src -> if (src.copyTo(dst, 64 * 1024) != release.size) throw IOException("Incomplete APK") }
                session.fsync(dst)
            }
            ensureActive()
            // PackageInstaller fills EXTRA_STATUS and (only if required) EXTRA_INTENT. No external sender can address the receiver.
            val target = Intent(context, UpdateInstallReceiver::class.java).setAction(INSTALL_RESULT)
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val receipt = PendingIntent.getBroadcast(context, id, target, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
            if (!recordSession(id)) throw IOException("Cannot persist install session")
            session.commit(receipt.intentSender)
        }
    } catch (e: Exception) {
        runCatching { installer.abandonSession(id) }
        throw e
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == INSTALL_RESULT) (context.applicationContext as StackLifeApplication).updates.onInstallResult(intent)
    }
}
