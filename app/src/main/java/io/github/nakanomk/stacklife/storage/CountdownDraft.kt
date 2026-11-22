package io.github.nakanomk.stacklife.storage

import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.planner.*

private fun requireImage(id: String, exists: (String) -> Boolean) {
    if (!privatePhotoId.matches(id) || !exists(id)) throw InvalidCommand(
        uiText("The selected background is unavailable. Choose the photo again.", "所选背景不可用，请重新选择图片。"))
}

internal class CountdownWrites(val imagesChanged: Boolean, private val rows: List<CountdownRow>,
    private val deleted: List<String>, private val newImages: Set<String>?) {
    fun requireImages(exists: (String) -> Boolean) { newImages?.forEach { requireImage(it, exists) } }
    fun applyTo(dao: CountdownDao) {
        for (id in deleted) dao.delete(id)
        if (rows.isNotEmpty()) dao.put(rows)
    }
}

/** Transaction-local overlay. Editing a single card reads only that ID; shifts read only the affected group suffix. */
internal class CountdownDraft(private val dao: CountdownDao, private val imageExists: (String) -> Boolean) {
    private val before = mutableMapOf<String, Countdown?>()
    private val after = mutableMapOf<String, Countdown?>()
    private val sizes = mutableMapOf<Boolean, Int>()

    private fun size(pinned: Boolean) = sizes.getOrPut(pinned) { dao.count(pinned) }
    private fun find(id: String): Countdown? {
        if (id in after) return after[id]
        if (id !in before) before[id] = dao.one(id)
        return before[id]
    }
    private fun requireCard(id: String) = find(id) ?: throw InvalidCommand(uiText("This countdown no longer exists.", "倒数日已不存在。"))
    private fun validate(card: Countdown, oldImage: String?) {
        card.problem()?.let { throw InvalidCommand(it) }
        if (card.backgroundId != null && card.backgroundId != oldImage) requireImage(card.backgroundId, imageExists)
    }

    fun add(id: String, cmd: PlannerCommand.CountdownAdd) {
        if (id.isBlank() || find(id) != null) throw InvalidCommand(uiText("Invalid or duplicate countdown ID.", "倒数日标识无效或重复。"))
        val pos = size(cmd.pinned)
        val card = Countdown(id, cmd.title.trim(), cmd.targetDay, cmd.mode, cmd.pinned, pos, cmd.backgroundId, cmd.maskOpacity)
        validate(card, null)
        after[id] = card
        sizes[cmd.pinned] = pos + 1
    }

    fun edit(cmd: PlannerCommand.CountdownEdit) {
        val old = requireCard(cmd.id)
        val card = old.copy(title = cmd.title?.trim() ?: old.title, targetDay = cmd.targetDay ?: old.targetDay,
            mode = cmd.mode ?: old.mode, maskOpacity = cmd.maskOpacity ?: old.maskOpacity,
            backgroundId = if (cmd.background == null) old.backgroundId else cmd.background.id)
        validate(card, old.backgroundId)
        after[old.id] = card
    }

    private inline fun eachFrom(pinned: Boolean, start: Int, action: (Countdown) -> Unit) {
        // Visit changed rows first, so changes made by action to clean rows below are not visited twice.
        for (row in after.values) if (row != null && row.pinned == pinned && row.position >= start) action(row)
        for (row in dao.suffix(pinned, start)) if (row.id !in after) {
            before[row.id] = row
            action(row)
        }
    }

    private fun leaveGroup(card: Countdown) {
        eachFrom(card.pinned, card.position + 1) { after[it.id] = it.copy(position = it.position - 1) }
        sizes[card.pinned] = size(card.pinned) - 1
    }

    fun remove(id: String) {
        leaveGroup(requireCard(id))
        after[id] = null
    }

    fun pin(id: String, pinned: Boolean) {
        val card = requireCard(id)
        if (card.pinned == pinned) return
        leaveGroup(card)
        val pos = size(pinned)
        after[id] = card.copy(pinned = pinned, position = pos)
        sizes[pinned] = pos + 1
    }

    fun order(cmd: PlannerCommand.CountdownOrder) {
        val positions = HashMap<String, Int>(cmd.ids.size)
        fun invalid(): Nothing = throw InvalidCommand(uiText("Include each countdown in this group exactly once.", "排序必须包含此组的全部倒数日，且不能重复。"))
        if (cmd.ids.size != size(cmd.pinned)) invalid()
        for ((index, id) in cmd.ids.withIndex()) if (positions.put(id, index) != null) invalid()
        eachFrom(cmd.pinned, 0) { card ->
            val pos = positions[card.id] ?: invalid()
            if (card.position != pos) after[card.id] = card.copy(position = pos)
        }
    }

    fun finish(changes: MutableList<PlannerChange>): CountdownWrites? {
        if (after.isEmpty()) return null
        val rows = mutableListOf<CountdownRow>()
        val deleted = mutableListOf<String>()
        var imagesChanged = false
        var newImages: MutableSet<String>? = null
        for ((id, value) in after) {
            val old = before[id]
            if (value == old) continue
            if (value == null) deleted.add(id) else rows.add(CountdownRow(id, value.title, value.targetDay, value.mode,
                value.pinned, value.position, value.backgroundId, value.maskOpacity))
            if (old?.backgroundId != value?.backgroundId) {
                imagesChanged = true
                value?.backgroundId?.let { image -> (newImages ?: mutableSetOf<String>().also { newImages = it }).add(image) }
            }
            changes.add(PlannerChange(id, old?.let(::CountdownSnapshot), value?.let(::CountdownSnapshot)))
        }
        return if (rows.isEmpty() && deleted.isEmpty()) null else CountdownWrites(imagesChanged, rows, deleted, newImages)
    }
}
