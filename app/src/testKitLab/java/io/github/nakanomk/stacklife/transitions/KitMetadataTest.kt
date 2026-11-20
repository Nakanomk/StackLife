package io.github.nakanomk.stacklife.transitions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KitMetadataTest {
    @Test fun explicitBoundsTokenPrecedesLegacyDescription() {
        assertFalse(usesWindowBounds(null))
        assertFalse(usesWindowBounds("rect"))
        assertFalse(usesWindowBounds("rect|SCREEN|Do not use window coordinates"))
        assertTrue(usesWindowBounds("rect| window |Bounds of the card"))
        assertTrue(usesWindowBounds("rect|View bounds in window coordinates"))
    }

    @Test fun missingSystemBackCannotReplayAnObsoleteTarget() {
        assertFalse(permitsFollowSystem(null))
        assertFalse(permitsFollowSystem("int|VIEW_ANIM_DEFAULT,VIEW_ANIM_NONE|Back types"))
        assertTrue(permitsFollowSystem("int|VIEW_ANIM_DEFAULT, VIEW_ANIM_FOLLOW_SYSTEM|Back types"))
        assertTrue(permitsFollowSystem("int")) // writable, but no precise support set advertised
    }
}
