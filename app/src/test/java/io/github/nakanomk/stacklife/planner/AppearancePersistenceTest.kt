package io.github.nakanomk.stacklife.planner

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePersistenceTest {
    @Test fun wheelDefaultsOnlyFillAbsentFieldsAndLayoutReset() {
        assertEquals(DayScrollEffect.WHEEL, decodeAppearance("{}").dayScrollEffect)
        val flat = decodeAppearance("""{"dayScrollEffect":"FLAT","glass":false}""")
        assertEquals(DayScrollEffect.FLAT, flat.dayScrollEffect)
        assertEquals(flat, decodeAppearance(encodeAppearance(flat)))
        val reset = AppearanceFields(AppearanceGroup.LAYOUT, null).applyTo(flat)
        assertEquals(flat.copy(dayScrollEffect = DayScrollEffect.WHEEL), reset)
    }

    @Test fun markerLayoutRoundTripsExplicitOffAndResetsWithoutChangingMaterial() {
        val legacy = decodeAppearance("""{"glass":false}""")
        assertTrue(legacy.showNowLine); assertTrue(legacy.nowArrow)
        assertEquals(legacy, decodeAppearance("""{"glass":false,"nowLine":"WAVE","nowTrail":true}"""))
        val off = decodeAppearance("""{"glass":false,"nowLine":"OFF","nowArrow":false,"nowTrail":false}""")
        assertEquals(legacy.copy(showNowLine = false, nowArrow = false), off)
        assertEquals(off, decodeAppearance(encodeAppearance(off)))
        assertEquals(off, AppearanceFields(AppearanceGroup.LAYOUT, AppearanceGroup.LAYOUT.project(off)).applyTo(legacy))
        assertEquals(legacy, AppearanceFields(AppearanceGroup.LAYOUT, null).applyTo(off))
    }

    @Test fun fourTabSettingsAndStoredChatSnapshotsDecodeBeforeStrictConstruction() {
        val old = """{"order":["GRAPHS","TODAY","COURSES","WORKSPACE"],"start":"COURSES","unknownPreference":3}"""
        val order = listOf(Destination.GRAPHS, Destination.TODAY, Destination.COURSES, Destination.WORKSPACE, Destination.COUNTDOWN)
        val a = decodeAppearance(old)
        assertEquals(order, a.visibleOrder); assertEquals(Destination.COURSES, a.start)
        assertTrue(a.showNavLabels)
        val snapshot = kotlinx.serialization.json.Json.decodeFromString<ChangeSnapshot>("""{"type":"appearance","value":$old}""")
        assertEquals(AppearanceSnapshot(a), snapshot)
        val selected = a.withVisible(setOf(Destination.COUNTDOWN,Destination.GRAPHS)).copy(showNavLabels = false)
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
        assertFalse(legacy.frostedGlass)
        val frosted = legacy.copy(frostedGlass = true)
        assertEquals(frosted, decodeAppearance(encodeAppearance(frosted)))
        assertEquals(legacy, AppearanceFields(AppearanceGroup.MATERIAL, null).applyTo(frosted))
        val optedOut = Appearance(glass = false)
        assertEquals(optedOut, decodeAppearance(encodeAppearance(optedOut)))
    }

    @Test fun newWallpaperDefaultsOnlyFillMissingPreferencesAndNeverBecomePrivateFileReferences() {
        val first = decodeAppearance("""{"compact":true}""")
        assertEquals(WallpaperPreset.BLUE, first.wallpaper(Destination.COURSES).preset)
        assertEquals(.15f, Appearance().background.blur, 0f)
        assertEquals(.15f, first.wallpaper(Destination.COURSES).blur, 0f)
        val explicitZero = decodeAppearance("""{"background":{"preset":"BLUE","blur":0}}""")
        assertEquals(0f, decodeAppearance(encodeAppearance(explicitZero)).wallpaper(Destination.COURSES).blur, 0f)
        assertEquals(0f, decodeAppearance("""{"background":{"preset":"BLUE"}}""").background.blur, 0f)
        assertFalse(first.showCourseGrid)
        val id = "dd957e19-e907-4e7c-a513-707fb834162b"
        val old = decodeAppearance("""{"background":{"imageId":"$id","scale":2.0},"pageBackgrounds":{"COURSES":{}},"showCourseGrid":true,"compact":true}""")
        assertEquals(WallpaperSpec(), old.wallpaper(Destination.COURSES))
        assertEquals(WallpaperSpec(imageId = id, scale = 2f), old.wallpaper(Destination.WORKSPACE))
        assertTrue(old.showCourseGrid)
        assertEquals(WallpaperSpec(), decodeAppearance("""{"background":{"imageId":null}}""").wallpaper(Destination.COURSES))
        assertEquals(0f, old.background.blur, 0f)
        val perPage = old.copy(background = old.background.copy(blur = .5f),
            pageBackgrounds = mapOf(Destination.COURSES to WallpaperSpec(preset = WallpaperPreset.PINK, offsetX = .5f, blur = 1f)))
        assertEquals(perPage, decodeAppearance(encodeAppearance(perPage)))
        assertEquals(setOf(id), perPage.backgroundIds())
        val reset = AppearanceFields(AppearanceGroup.MATERIAL, null).applyTo(perPage)
        assertEquals(first, reset)
        assertTrue(reset.backgroundIds().isEmpty())
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
