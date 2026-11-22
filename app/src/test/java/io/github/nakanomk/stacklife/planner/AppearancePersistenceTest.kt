package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePersistenceTest {
    @Test fun fourTabSettingsAndStoredChatSnapshotsDecodeBeforeStrictConstruction() {
        val old = """{"order":["GRAPHS","TODAY","COURSES","WORKSPACE"],"start":"COURSES","unknownPreference":3}"""
        val order = listOf(Destination.GRAPHS, Destination.TODAY, Destination.COURSES, Destination.WORKSPACE, Destination.COUNTDOWN)
        val a = decodeAppearance(old)
        assertEquals(order, a.visibleOrder); assertEquals(Destination.COURSES, a.start)
        val snapshot = kotlinx.serialization.json.Json.decodeFromString<ChangeSnapshot>("""{"type":"appearance","value":$old}""")
        assertEquals(AppearanceSnapshot(a), snapshot)
        val selected = a.withVisible(setOf(Destination.COUNTDOWN,Destination.GRAPHS))
        assertEquals(Destination.COUNTDOWN, selected.start); assertEquals(selected, decodeAppearance(encodeAppearance(selected)))
        val future = decodeAppearance("""{"order":["FUTURE","COURSES"],"visible":["FUTURE","GRAPHS"],"start":"FUTURE","pageBackgrounds":{"FUTURE":{"other":true}}}""")
        assertEquals(listOf(Destination.GRAPHS), future.visibleOrder); assertEquals(Destination.GRAPHS, future.start)
    }
    @Test fun emptyVisibilityHiddenStartAndDuplicateOrderAreRejectedOnWrite() {
        val base = Appearance()
        for (json in listOf("""{"visible":[]}""", """{"visible":["GRAPHS"]}""",
            """{"order":["GRAPHS","TODAY","TODAY","COURSES","WORKSPACE"]}""")) {
            val patch = AppearanceFields(AppearanceGroup.LAYOUT, kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject)
            assertTrue(runCatching { patch.applyTo(base) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun missingGlassPreferenceEnablesGlassAndAnExplicitOptOutSurvivesUpgrade() {
        assertTrue(decodeAppearance("{}").glass)
        val legacy = decodeAppearance("""{"compact":true}""")
        assertTrue(legacy.compact)
        assertTrue(legacy.glass)
        val optedOut = Appearance(glass = false)
        assertEquals(optedOut, decodeAppearance(encodeAppearance(optedOut)))
    }

    @Test fun resettingQueuePresentationRestoresTheFloatingTrayAndKeepsMaterialChoices() {
        val pinned = AppearanceFields(AppearanceGroup.LAYOUT, buildJsonObject { put("floatingDayTray", JsonPrimitive(false)) })
            .applyTo(decodeAppearance("""{"glass":false}"""))
        assertFalse(decodeAppearance(encodeAppearance(pinned)).floatingDayTray)
        assertEquals(Appearance(glass = false), AppearanceFields(AppearanceGroup.LAYOUT, null).applyTo(pinned))
    }

    @Test fun missingCourseOutlineIsOffAndExplicitChoicesSurviveUpgrade() {
        assertFalse(decodeAppearance("{}").courseTextOutline)
        val legacy = decodeAppearance("""{"compact":true,"glass":false}""")
        assertFalse(legacy.courseTextOutline)
        val material = AppearanceGroup.MATERIAL
        val on = decodeAppearance("""{"compact":true,"glass":false,"courseTextOutline":true}""")
        assertEquals(legacy.copy(courseTextOutline = true), on)
        assertEquals(on, decodeAppearance(encodeAppearance(on)))
        val off = AppearanceFields(material, buildJsonObject { put("courseTextOutline", JsonPrimitive(false)) }).applyTo(on)
        assertEquals(legacy, decodeAppearance(encodeAppearance(off)))
        assertEquals(JsonPrimitive(false), material.project(off)["courseTextOutline"])
        assertFalse("courseTextOutline" in AppearanceGroup.LAYOUT.project(off))
        val reset = AppearanceFields(material, null).applyTo(on)
        assertFalse(reset.courseTextOutline)
        assertTrue(reset.compact)
    }
}
