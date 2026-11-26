package io.github.nakanomk.stacklife.storage

import android.util.JsonReader
import io.github.nakanomk.stacklife.planner.*
import android.util.JsonToken
import io.github.nakanomk.stacklife.archive.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.security.MessageDigest

private const val MAX_RECORDS=8192
private const val MAX_RECORD_BYTES=8L*1024*1024
internal val backupFormat=Json { encodeDefaults=true }
internal class IncomingTables private constructor(internal val parts:Set<BackupPart>,internal val records:List<BackupRecord>,internal val available:Set<BackupPart> = parts) {
    companion object {
        fun checked(parts:Set<BackupPart>,records:List<BackupRecord>):IncomingTables {
            validateBackup(parts,records)
            return IncomingTables(parts,records)
        }
    }
    val counts:Map<BackupPart,Int> = records.groupingBy { it.part() }.eachCount().let { counts -> parts.associateWith { counts[it] ?: 0 } }
    val files:BackupFiles = records.fileClosure()
    fun select(parts:Set<BackupPart>):IncomingTables {
        val selected=parts-BackupPart.CONNECTION
        require(this.parts.containsAll(selected))
        return if(selected==this.parts) this else IncomingTables(selected,records.filter { it.part() in selected },available)
    }
}
internal class TableReplacement internal constructor(val counts:Map<BackupPart,BackupCounts>,val impacts:List<String>,val conflicts:List<String>,
    val stamp:String,private val replace:(Long)->Unit) {
    suspend fun apply(revision:Long) { check(conflicts.isEmpty()) { "Resolve backup conflicts before replacing data" }; replace(revision) }
}
internal class BackupTables(private val db:PlannerDatabase) {
    suspend fun export(parts:Set<BackupPart>,directory:File):ExportedTables {
        require(directory.isDirectory || directory.mkdirs())
        val counts=linkedMapOf<BackupPart,Int>(); val photos=linkedSetOf<String>(); val attachments=linkedMapOf<String,io.github.nakanomk.stacklife.assistant.ChatAttachment>()
        for(part in parts-BackupPart.CONNECTION) {
            val rows=ArrayList<BackupRecord>(); var bytes=0L
            File(directory,"${part.wire}.jsonl").outputStream().buffered().use { out ->
                db.capture(setOf(part)) { row ->
                    require(rows.size<MAX_RECORDS) { "${part.wire}: more than $MAX_RECORDS records" }
                    val line=backupFormat.encodeToString<BackupRecord>(row).toByteArray(Charsets.UTF_8)
                    bytes+=line.size+1L; require(bytes<=MAX_RECORD_BYTES) { "${part.wire}: exceeds 8 MiB" }
                    out.write(line); out.write(10); rows.add(row)
                }
            }
            validateBackup(setOf(part),rows); counts[part]=rows.size
            val files=rows.fileClosure(); photos.addAll(files.backgrounds); attachments.putAll(files.attachments)
        }
        return ExportedTables(counts,BackupFiles(photos,attachments))
    }

    fun read(parts:Set<BackupPart>,directory:File):IncomingTables {
        val selected=parts-BackupPart.CONNECTION; val rows=ArrayList<BackupRecord>()
        for(part in selected) {
            val file=File(directory,"${part.wire}.jsonl"); require(file.isFile && file.length()<=MAX_RECORD_BYTES) { "Missing or oversized ${part.wire} records" }
            val begin=rows.size
            InputStreamReader(file.inputStream(),Charsets.UTF_8.newDecoder()).buffered().use { reader ->
                while(true) {
                    val line=reader.readLine() ?: break
                    require(rows.size-begin<MAX_RECORDS) { "${part.wire}: more than $MAX_RECORDS records" }
                    try {
                        validateJsonShape(line)
                        val row=decodePortableRow(line); require(row.part()==part); rows.add(row)
                    } catch(e:Exception) { throw IllegalArgumentException("Invalid ${part.wire} record ${rows.size-begin+1}") }
                }
            }
        }
        return IncomingTables.checked(selected,rows)
    }

    suspend fun plan(incoming:IncomingTables,backgrounds:Map<String,String>,attachments:Map<String,String>):TableReplacement {
        val selected=incoming.parts
        val capture=selected.toMutableSet().apply {
            // Chat edits bypass Planner's revision; even a status-only change invalidates approval.
            add(BackupPart.HISTORY)
            if(any { it in workspaceClosure }) addAll(workspaceClosure)
        }
        val old=ArrayList<BackupRecord>()
        val revision=db.plannerDao().settings()?.revision ?: 0L
        val digest=MessageDigest.getInstance("SHA-256")
        digest.update("planner:$revision\n".toByteArray(Charsets.UTF_8))
        db.capture(capture) { r ->
            digest.update(backupFormat.encodeToString<BackupRecord>(r).toByteArray(Charsets.UTF_8)); digest.update(10.toByte())
            if(r.part()!=BackupPart.HISTORY || BackupPart.HISTORY in selected) old.add(r)
        }
        val stamp=digest.digest().joinToString("") { "%02x".format(it) }
        require(backgrounds.all { (src,dst) -> src in incoming.files.backgrounds && privatePhotoId.matches(dst) })
        require(attachments.all { (src,dst) -> src in incoming.files.attachments && privatePhotoId.matches(dst) })
        require(incoming.files.attachments.keys.mapTo(hashSetOf()) { attachments[it] ?: it }.size==incoming.files.attachments.size) { "Attachment identity collision" }
        val remap=backgrounds.any { it.key!=it.value } || attachments.any { it.key!=it.value }
        val next=if(remap) incoming.records.mapTo(ArrayList()) { it.remapped(backgrounds,attachments) } else ArrayList(incoming.records)
        // An explicitly empty singleton means reset, not "ignore". Empty Today still needs the
        // two disabled, editable routine identities that a newly created planner starts with.
        if(incoming.counts[BackupPart.APPEARANCE]==0) next.add(BAppearance(Appearance()))
        if(incoming.counts[BackupPart.PREFERENCES]==0) next.add(BPreferences(RestorePolicy.PREPEND,false))
        if(incoming.counts[BackupPart.TODAY]==0) next.addAll(emptyDayRoots())
        return db.replacement(selected,incoming.available,old,next,stamp,revision)
    }
}
private val workspaceClosure=setOf(BackupPart.TODAY,BackupPart.QUEUE,BackupPart.STACK,BackupPart.GRAPHS)

/** The generated serializers check types and unknown keys but accept repeated keys. Bound nesting
 * before decoding (including ignored/compatibility fields of an old change snapshot). */
private fun validateJsonShape(line:String) {
    JsonReader(StringReader(line)).use { reader ->
        fun value(depth:Int) {
            require(depth<=64) { "Record nested too deeply" }
            when(reader.peek()) {
                JsonToken.BEGIN_OBJECT -> { val seen=HashSet<String>(); reader.beginObject(); while(reader.hasNext()) { require(seen.add(reader.nextName())); value(depth+1) }; reader.endObject() }
                JsonToken.BEGIN_ARRAY -> { reader.beginArray(); while(reader.hasNext()) value(depth+1); reader.endArray() }
                JsonToken.BOOLEAN -> reader.nextBoolean(); JsonToken.STRING,JsonToken.NUMBER -> reader.nextString(); JsonToken.NULL -> reader.nextNull()
                else -> throw IllegalArgumentException("Invalid JSON value")
            }
        }
        value(0); require(reader.peek()==JsonToken.END_DOCUMENT)
    }
}

private fun emptyDayRoots():List<BackupRecord> = buildList {
    for(role in RoutineKind.entries) {
        val id=role.scheduleId
        add(BSchedule(id,null,null,false,role,null))
        add(BSegment(id,DailySegment(java.time.LocalDate.of(1,1,1).toEpochDay(),if(role==RoutineKind.WAKE) 420 else 1380,null,0)))
        val subject=ReminderSubject.Schedule(id)
        add(BPolicy(subject,false)); add(BRule(subject,initialReminder,0))
    }
}

/** StoredAppearance migrates old on-device chat rows. That permissive migration must not drop
 * unknown fields or normalize an invalid navigation list supplied by a v1 transfer. */
private fun decodePortableRow(line:String):BackupRecord {
    val row=backupFormat.decodeFromString<BackupRecord>(line)
    val changes=(row as? BMessage)?.event?.changes ?: return row
    if(changes.none { it.before is AppearanceSnapshot || it.after is AppearanceSnapshot }) return row
    val raw=backupFormat.parseToJsonElement(line).jsonObject.getValue("event").jsonObject.getValue("changes").jsonArray
    for((i,change) in changes.withIndex()) {
        fun checkSide(side:String,snapshot:ChangeSnapshot?) {
            if(snapshot is AppearanceSnapshot) {
                val value=raw[i].jsonObject.getValue(side).jsonObject.getValue("value")
                require(backupFormat.decodeFromJsonElement(PortableAppearance, value)==snapshot.value)
            }
        }
        checkSide("before",change.before); checkSide("after",change.after)
    }
    return row
}
