package io.github.nakanomk.stacklife.reminders

import android.content.Intent
import android.net.Uri
import io.github.nakanomk.stacklife.planner.OccurrenceKey
import io.github.nakanomk.stacklife.planner.storageKey
import kotlinx.serialization.json.Json

internal const val ALARM_ACTION = "io.github.nakanomk.stacklife.reminders.ALARM"
internal const val COMPLETE_ACTION = "io.github.nakanomk.stacklife.reminders.COMPLETE"
internal const val DISMISS_ACTION = "io.github.nakanomk.stacklife.reminders.DISMISS"
internal data class ReminderTarget(val key: OccurrenceKey, val startMillis: Long)

private fun locator(host: String, key: OccurrenceKey) = Uri.Builder().scheme("stacklife").authority(host).appendPath(key.storageKey())
internal fun ReminderTarget.uri(): Uri = locator("occurrence", key).appendPath(startMillis.toString()).build()
internal fun Intent.reminderTarget(): ReminderTarget? {
    val uri = data ?: return null
    if (action != Intent.ACTION_VIEW || uri.scheme != "stacklife" || uri.encodedAuthority != "occurrence") return null
    val fields = uri.pathSegments
    if (fields.size != 2) return null
    return try { ReminderTarget(Json.decodeFromString<OccurrenceKey>(fields[0]), fields[1].toLong()) }
    catch (_: IllegalArgumentException) { null }
}
internal fun AlarmStamp.actionUri(): Uri = locator("reminder", key).appendPath(ruleId).appendPath(triggerMillis.toString())
    .appendPath(version.toString()).appendPath(Json.encodeToString(time)).build()
internal fun Uri?.alarmStamp(): AlarmStamp? {
    if (this == null || scheme != "stacklife" || encodedAuthority != "reminder") return null
    val fields = pathSegments
    if (fields.size != 5) return null
    return try { AlarmStamp(Json.decodeFromString<OccurrenceKey>(fields[0]), fields[1], fields[2].toLong(), fields[3].toLong(),
        Json.decodeFromString(fields[4])) }
    catch (_: IllegalArgumentException) { null }
}
