package io.github.nakanomk.stacklife.notes

import androidx.compose.ui.text.TextRange
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

internal data class NoteValue(val source: String, val selection: TextRange = TextRange(source.length))

// Like Telegram MediaDataController.getDraft, the route carries an identity, not the document.
// Only the small index is replaced for a cursor move. Its atomic rename also promotes or
// discards a child edit without ever overwriting the parent's still-referenced source.
internal class NoteDraft private constructor(
    private val directory: File,
    private var acceptedRef: Ref,
    private var workingRef: Ref,
    saved: NoteValue,
    edit: NoteValue,
) {
    val id: String get() = directory.name
    var accepted = saved
        private set
    var working = edit
        private set

    private data class Ref(val slot: Int, val length: Int, val start: Int, val end: Int) {
        fun write(out: DataOutputStream) {
            out.writeInt(slot); out.writeInt(length); out.writeInt(start); out.writeInt(end)
        }
        fun load(dir: File, existing: String? = null): NoteValue {
            if (slot !in 0..2 || start !in 0..length || end !in 0..length) throw IOException("Invalid note index")
            val source = existing ?: File(dir, slot.toString()).let { file ->
                if (file.length() != length * 2L) throw IOException("Incomplete note")
                DataInputStream(file.inputStream().buffered()).use { input -> CharArray(length) { input.readChar() }.concatToString() }
            }
            if (source.length != length) throw IOException("Incomplete note")
            return NoteValue(source, TextRange(start, end))
        }
    }

    fun checkpoint(value: NoteValue) {
        if (value == working) return
        require(value.selection.min >= 0 && value.selection.max <= value.source.length)
        val slot = when (value.source) {
            working.source -> workingRef.slot
            accepted.source -> acceptedRef.slot
            else -> (0..2).first { it != workingRef.slot && it != acceptedRef.slot }.also {
                writeSource(File(directory, it.toString()), value.source)
            }
        }
        val ref = Ref(slot, value.source.length, value.selection.start, value.selection.end)
        publish(acceptedRef, ref)
        working = value
    }

    fun accept() {
        if (acceptedRef != workingRef) publish(workingRef, workingRef)
        accepted = working
        removeOtherSlots()
    }

    fun discardEdit() {
        if (acceptedRef != workingRef) publish(acceptedRef, acceptedRef)
        working = accepted
        removeOtherSlots()
    }

    private fun removeOtherSlots() {
        for (slot in 0..2) if (slot != acceptedRef.slot && slot != workingRef.slot) {
            Files.deleteIfExists(File(directory, slot.toString()).toPath())
        }
    }

    private fun publish(saved: Ref, edit: Ref) {
        val temp = File(directory, "index.new")
        DataOutputStream(temp.outputStream().buffered()).use { it.writeInt(1); saved.write(it); edit.write(it) }
        Files.move(temp.toPath(), File(directory, "index").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        acceptedRef = saved
        workingRef = edit
    }

    companion object {
        // TextRange indexes UTF-16 code units, including transient split surrogate pairs.
        private fun writeSource(file: File, text: String) {
            DataOutputStream(file.outputStream().buffered()).use { it.writeChars(text) }
        }

        private fun directory(root: File, id: String): File {
            if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id) throw IOException("Invalid draft identity")
            return File(root, id)
        }

        fun create(root: File, source: String): NoteDraft {
            Files.createDirectories(root.toPath())
            val dir = directory(root, UUID.randomUUID().toString())
            Files.createDirectory(dir.toPath())
            try {
                val initial = NoteValue(source)
                writeSource(File(dir, "0"), source)
                val ref = Ref(0, source.length, source.length, source.length)
                return NoteDraft(dir, ref, ref, initial, initial).also { it.publish(ref, ref) }
            } catch (failure: IOException) {
                dir.deleteRecursively()
                throw failure
            }
        }

        private fun readIndex(dir: File): Pair<Ref, Ref> =
            DataInputStream(File(dir, "index").inputStream().buffered()).use { input ->
                if (input.readInt() != 1) throw IOException("Unknown draft version")
                fun ref() = Ref(input.readInt(), input.readInt(), input.readInt(), input.readInt())
                val refs = ref() to ref()
                if (input.read() != -1) throw IOException("Invalid draft index")
                refs
            }

        fun accepted(root: File, id: String): NoteValue {
            val dir = directory(root, id)
            return readIndex(dir).first.load(dir)
        }

        fun open(root: File, id: String, discardWorking: Boolean = false): NoteDraft {
            val dir = directory(root, id)
            val (saved, edit) = readIndex(dir)
            val accepted = saved.load(dir)
            if (discardWorking) return NoteDraft(dir, saved, edit, accepted, accepted).also { it.discardEdit() }
            return NoteDraft(dir, saved, edit, accepted, edit.load(dir, accepted.source.takeIf { edit.slot == saved.slot }))
        }

        fun delete(root: File, id: String) {
            val dir = directory(root, id)
            if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not remove draft")
        }
    }
}
