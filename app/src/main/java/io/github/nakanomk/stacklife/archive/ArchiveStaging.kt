package io.github.nakanomk.stacklife.archive

import java.io.File
import java.nio.file.Files

/** Scoped private extraction directory for non-secret, allowlisted members only. Keep key-bearing
 * records in bounded memory, or encrypt them directly for the target Keystore. Never spool raw ZIPs.
 * Caller runs on IO. Abandoned directories are reaped on the next use after process death. */
internal object ArchiveStaging {
    private val active = HashSet<File>()
    suspend fun <T> use(cache: File, type: ArchiveType, action: suspend (File) -> T): T {
        val root = File(cache, "${type.wire}-transfer")
        val dir = synchronized(active) {
            Files.createDirectories(root.toPath())
            root.listFiles()?.filterNot { it in active }?.forEach { it.deleteRecursively() }
            Files.createTempDirectory(root.toPath(), "read-").toFile().also { active.add(it) }
        }
        try { return action(dir) } finally {
            synchronized(active) { dir.deleteRecursively(); active.remove(dir) }
        }
    }
}
