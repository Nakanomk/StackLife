package io.github.nakanomk.stacklife.planner

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
}
