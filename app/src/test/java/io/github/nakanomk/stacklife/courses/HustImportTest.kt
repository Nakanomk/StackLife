package io.github.nakanomk.stacklife.courses

import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.*
import org.junit.Test

class HustImportTest {
    private val term = TermSpec("合成学期", LocalDate.parse("2026-09-07"), 18, BellSchedule.SUMMER, LocalDate.parse("2026-11-02"))
    private fun raw(text: String) = Json.parseToJsonElement(text).jsonArray
    private fun item(room: String, jc: String, name: String = "操作系统") = """{"KCMC":"$name","JC":"$jc","JSMC":"$room"}"""
    private fun week(week: Int, vararg items: String) = """{"ZC":"$week","MONDAY":[${items.joinToString(",")}]}"""
    private fun at(millis: Long) = Instant.ofEpochMilli(millis).atZone(campusZone).toLocalDateTime().toString()
    private fun rejected(action: () -> Unit) {
        try { action(); fail("expected rejected draft") } catch (_: IllegalArgumentException) { }
    }
    private fun badSource(action: () -> Unit) {
        try { action(); fail("expected source error") } catch (_: IOException) { }
    }

    @Test fun strictExclusionDeduplicatesOnlyOccurrencesAndUsesActualSeason() {
        val a = item("A", "5–6")
        val b = item("B", "5–6")
        val rows = raw("[${week(9, b)},${week(1, a, a, item("A", "实践"))},${week(3, b)}]")
        val importer: CourseImporter = HustCourseImporter
        assertEquals("hust-hub", importer.id)
        val draft = importer.parse(rows, term)
        assertEquals(3, draft.validCount)
        val issue = draft.issues.single()
        assertEquals("实践", issue.fields!!.period)
        rejected { draft.resolve(emptyMap()) }
        rejected { draft.resolve(mapOf("not an issue" to IssueDecision.Exclude)) }
        val decisions = mapOf(issue.position to IssueDecision.Exclude)
        val classes = draft.resolve(decisions)
        assertEquals(listOf(1, 3, 9), classes.map { it.week })
        assertEquals(listOf("A", "B", "B"), classes.map { it.room })
        assertEquals(listOf("2026-09-07T14:30", "2026-09-21T14:30", "2026-11-02T14:00"), classes.map { at(it.startMillis) })
        assertEquals(listOf("2026-09-07T16:05", "2026-09-21T16:05", "2026-11-02T15:35"), classes.map { at(it.endMillis) })
        assertEquals(classes, importer.parse(rows, term).resolve(decisions))
    }

    @Test fun correctionIsExplicitAndEmptyCannotReplace() {
        val draft = HustCourseImporter.parse(raw("[${week(1, item("", "实践"))}]"), term)
        val issue = draft.issues.single()
        assertEquals(0, draft.validCount)
        rejected { draft.resolve(mapOf(issue.position to IssueDecision.Exclude)) }
        rejected { draft.resolve(mapOf(issue.position to IssueDecision.Correct(issue.fields!!))) }
        val corrected = issue.fields!!.copy(period = "12")
        val result = draft.resolve(mapOf(issue.position to IssueDecision.Correct(corrected))).single()
        assertEquals("2026-09-07T21:35", at(result.startMillis))
        assertEquals("2026-09-07T22:20", at(result.endMillis))
        rejected { HustCourseImporter.parse(JsonArray(emptyList()), term).resolve(emptyMap()) }
        val longRoom = "楼".repeat(MAX_COURSE_FIELD + 1)
        val longDraft = HustCourseImporter.parse(raw("[${week(1, item(longRoom, "1"))}]"), term)
        val repair = longDraft.issues.single()
        val fields = requireNotNull(repair.fields)
        assertEquals(longRoom, fields.room)
        val correctedRoom = IssueDecision.Correct(fields.copy(room = "A"))
        assertEquals("A", longDraft.resolve(mapOf(repair.position to correctedRoom)).single().room)
    }

    @Test fun exactRangesAndRoomArePartOfOccurrenceIdentity() {
        val bad = listOf("0", "13", "1-13", "6–5", "1-2-3", "1节", "实践", "3/4", "", "2.5", "+2")
        val items = (bad.map { item("A", it) } + listOf(item("A", "5-6"), item("B", "5 — 6"), item("A", "5–6"))).toTypedArray()
        val draft = HustCourseImporter.parse(raw("[${week(1, *items)}]"), term)
        assertEquals(bad, draft.issues.map { it.fields!!.period })
        assertEquals(listOf("A", "B"), draft.resolve(draft.issues.associate { it.position to IssueDecision.Exclude }).map { it.room })
    }

    @Test fun malformedFieldsAndContainersRequireIndividualDecisions() {
        val rows = raw("""[null,{"ZC":1,"MONDAY":"wrong"},{"ZC":1,"MONDAY":[true,{"KCMC":9,"JC":1},
            {"KCMC":"x","JC":2,"JSMC":{}},{"KCMC":"ok","JC":3}]},{"ZC":"21","SUNDAY":[{"KCMC":"z","JC":2}]}]""")
        val draft = HustCourseImporter.parse(rows, term)
        assertEquals(1, draft.validCount)
        assertEquals(6, draft.issues.size)
        val exclude = draft.issues.associate { it.position to IssueDecision.Exclude }
        assertEquals("ok", draft.resolve(exclude).single().title)
        val sunday = draft.issues.last()
        val fixed = draft.resolve(exclude + (sunday.position to IssueDecision.Correct(sunday.fields!!.copy(week = "2"))))
        assertEquals("2026-09-20T08:55", at(fixed.last().startMillis))
    }

    @Test fun weekOneHintIsNotAnInferredCalendarAndWinterCanSwitchToSummer() {
        val rows = raw("""[{"ZC":3,"KS":"2026-09-21"},{"ZC":"1","KS":"2026-09-07"},{"ZC":1,"KS":"2026-09-07"}]""")
        assertEquals(listOf("2026-09-07"), firstWeekHints(rows))
        val reverse = term.copy(initial = BellSchedule.WINTER, switchDate = LocalDate.parse("2026-09-09"))
        assertEquals(12, reverse.periodsOn(term.firstMonday).size)
        assertEquals("14:00", reverse.periodsOn(LocalDate.parse("2026-09-08"))[4].start.toString())
        assertEquals("14:30", reverse.periodsOn(LocalDate.parse("2026-09-09"))[4].start.toString())
    }

    @Test fun navigationAndExtractionHaveDifferentTrustBoundaries() {
        assertEquals("hust.edu.cn", schoolHost("https://hust.edu.cn/"))
        assertEquals("sso.hust.edu.cn", schoolHost("https://sso.hust.edu.cn/login"))
        for (url in listOf("http://hubs.hust.edu.cn/", "https://nothust.edu.cn/", "https://hust.edu.cn.example.org/",
            "https://hubs.hust.edu.cn@evil.test/", "https://evil.test@hubs.hust.edu.cn/", "file:///x", "https://hubs.hust.edu.cn:8443/"))
            assertNull(url, schoolHost(url))
        assertTrue(isScheduleRoute(HUB_URL))
        assertTrue(isScheduleRoute("$HUB_URL?term=chosen"))
        for (url in listOf("$HUB_URL/else", "$HUB_URL#other", HUB_URL.replace("hubs.", "sso."), HUB_URL.replace("index", "%69ndex")))
            assertFalse(url, isScheduleRoute(url))
    }

    @Test fun fileIsBoundedUtf8ArrayAndJsMustDecodeTwoLayers() {
        val text = "[${week(1, item("A", "1"))}]"
        val document = readWeekFile(text.byteInputStream())
        assertEquals(document, decodeHubReply(JsonPrimitive(text).toString()))
        badSource { decodeHubReply(text) }
        badSource { decodeHubReply(JsonPrimitive("\"no_table\"").toString()) }
        badSource { readWeekFile("{\"data\":[]}".byteInputStream()) }
        badSource { readWeekFile("[] []".byteInputStream()) }
        badSource { readWeekFile("[{}".byteInputStream()) }
        val unicode = "\uFEFF[${week(1, item("𠀋", "1"))}]".toByteArray()
        val fragments = object : ByteArrayInputStream(unicode) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 1))
        }
        assertEquals(raw(unicode.toString(Charsets.UTF_8).removePrefix("\uFEFF")), readWeekFile(fragments))
        badSource { readWeekFile(("[]" + " ".repeat(2 * 1024 * 1024)).byteInputStream()) }
        badSource { readWeekFile(ByteArrayInputStream(byteArrayOf(91, 34, 0xC0.toByte(), 34, 93))) }
    }
}
