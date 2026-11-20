package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePersistenceTest {
    @Test fun untouchedProfileGetsGlassButCustomizedLegacySolidAndExplicitOptOutStaySolid() {
        assertTrue(decodeAppearance("{}").glass)
        val legacy = decodeAppearance("""{"compact":true}""")
        assertTrue(legacy.compact)
        assertFalse(legacy.glass)
        val optedOut = Appearance(glass = false)
        assertEquals(optedOut, decodeAppearance(encodeAppearance(optedOut)))
    }

    @Test fun courseTextOutlineDefaultsOnForOldProfilesAndExplicitOffRoundTrips() {
        assertTrue(decodeAppearance("{}").courseTextOutline)
        val legacy = decodeAppearance("""{"compact":true,"glass":false}""")
        assertTrue(legacy.courseTextOutline)
        val material = AppearanceGroup.MATERIAL
        val off = AppearanceFields(material, buildJsonObject { put("courseTextOutline", JsonPrimitive(false)) }).applyTo(legacy)
        assertEquals(legacy.copy(courseTextOutline = false), off)
        assertEquals(off, decodeAppearance(encodeAppearance(off)))
        assertEquals(JsonPrimitive(false), material.project(off)["courseTextOutline"])
        assertFalse("courseTextOutline" in AppearanceGroup.LAYOUT.project(off))
        val reset = AppearanceFields(material, null).applyTo(off)
        assertTrue(reset.courseTextOutline)
        assertTrue(reset.compact)
    }
}
