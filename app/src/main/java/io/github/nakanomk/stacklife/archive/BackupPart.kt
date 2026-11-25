package io.github.nakanomk.stacklife.archive

import io.github.nakanomk.stacklife.assistant.ChatAttachment

/** A category declares write authority, not merely a group of similarly named ZIP entries. */
internal enum class BackupPart(val wire: String) {
    APPEARANCE("appearance"), TODAY("today"), QUEUE("queue"), STACK("stack"), GRAPHS("graphs"), COURSES("courses"),
    COUNTDOWNS("countdowns"), HISTORY("history"), PREFERENCES("preferences"), CONNECTION("connection");

    companion object { val byWire = entries.associateBy(BackupPart::wire) }
}
internal data class BackupCounts(val added: Int = 0, val updated: Int = 0, val removed: Int = 0) {
    operator fun plus(other: BackupCounts) = BackupCounts(added + other.added, updated + other.updated, removed + other.removed)
}
internal data class BackupFiles(val backgrounds: Set<String>, val attachments: Map<String, ChatAttachment>)
internal data class ExportedTables(val counts: Map<BackupPart, Int>, val files: BackupFiles)
