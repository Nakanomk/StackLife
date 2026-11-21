package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePersistenceTest {
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
