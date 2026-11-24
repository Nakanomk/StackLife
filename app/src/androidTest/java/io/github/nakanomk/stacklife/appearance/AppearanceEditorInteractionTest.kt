package io.github.nakanomk.stacklife.appearance

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.R
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.assistant.withChatPhoto
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.Destination.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceEditorInteractionTest {
    private val fixture = WorkspaceFixture()
    private val ui = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(fixture).around(ui)
    private val pagePreview = hasTestTag("appearance-page-preview")

    /** Substitute only the platform's selection; the picker contract, IO, rendering and transaction are real. */
    private class Selection : ActivityResultRegistry(), ActivityResultRegistryOwner {
        @Volatile var uri: Uri? = null
        @Volatile var hold = false
        var launches = 0
            private set
        private var pending: Int? = null
        override val activityResultRegistry: ActivityResultRegistry get() = this
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract is PickVisualMedia && (input as PickVisualMediaRequest).mediaType == PickVisualMedia.ImageOnly)
            check(pending == null)
            launches++
            pending = requestCode
            if (!hold) finishSelection()
        }
        fun finishSelection() {
            val code = checkNotNull(pending)
            pending = null; hold = false
            val selected = uri
            dispatchResult(code, if (selected == null) Activity.RESULT_CANCELED else Activity.RESULT_OK, Intent().setData(selected))
        }
    }

    private fun attach(api: PlannerApi = fixture.api, photos: BackgroundStore? = null, selection: Selection? = null) =
        ui.activityRule.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides (selection ?: activity),
                LocalBackgroundStore provides (photos ?: LocalBackgroundStore.current)) {
                StackLifeApp(viewModel { WorkspaceViewModel(api) })
            }
        } }

    private fun revision() = runBlocking { fixture.state().revision }
    private fun saved() = runBlocking { (fixture.api.observe(PlannerQuery.Settings).first() as PolicyView).appearance }
    private fun seedAppearance(value: Appearance) = runBlocking {
        fixture.apply(PlannerCommand.EditAppearance(AppearancePatch.Replace(value)))
    }
    private fun row(text: String) = hasText(text) and hasClickAction()
    private fun await(m: SemanticsMatcher): SemanticsNodeInteraction {
        ui.waitUntil(12_000) { ui.onAllNodes(m).fetchSemanticsNodes().size == 1 }
        return ui.onNode(m)
    }
    private fun openSettings() = await(hasContentDescription("设置") and hasClickAction()).performClick()
    private fun openGroup(label: String) {
        val group = row(label)
        await(hasScrollToIndexAction()).performScrollToNode(group)
        await(group).performScrollTo().performClick()
        await(hasTestTag("appearance-scroll"))
    }
    private fun field(m: SemanticsMatcher): SemanticsNodeInteraction {
        await(hasTestTag("appearance-scroll")).performScrollToNode(m)
        return ui.onNode(m).performScrollTo().assertIsDisplayed()
    }
    private fun choice(title: String, option: String) {
        field(row(title)).performClick()
        await(hasText(option) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().performClick()
    }
    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureU8") != "yes") return
        ui.waitForIdle()
        val inst = InstrumentationRegistry.getInstrumentation()
        inst.uiAutomation.waitForIdle(400, 5_000)
        val bitmap = checkNotNull(inst.uiAutomation.takeScreenshot())
        try { File(checkNotNull(inst.targetContext.getExternalFilesDir(null)), "u8-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }

    // The fixtures have a blue triangle or green circle on gray. Read inside the colored shape,
    // outside the preview's text and dock, after the real asynchronous decode and crop protection.
    private fun sample(node: SemanticsNodeInteraction, y: Float): Color = node.captureToImage().toPixelMap().let {
        it[it.width / 2, (it.height * y).toInt()]
    }
    private fun blue(c: Color) = c.blue > c.red + .12f && c.blue > c.green + .06f
    private fun green(c: Color) = c.green > c.red + .12f && c.green > c.blue + .06f
    private fun page(destination: Destination): SemanticsNodeInteraction {
        val preview = field(pagePreview)
        ui.onNode(hasTestTag("nav:${destination.key}") and hasAnyAncestor(pagePreview)).assertIsSelected()
        ui.onNode(hasText("${destination.label} · 预览") and hasAnyAncestor(pagePreview)).assertIsDisplayed()
        val text = ui.activity.getString(R.string.ui_sample_note)
        val note = ui.onNode(hasText(text) and hasAnyAncestor(pagePreview)).assertIsDisplayed()
        val dock = ui.onNode(hasTestTag("navigation-capsule") and hasAnyAncestor(pagePreview)).assertIsDisplayed()
        val bounds = note.fetchSemanticsNode().boundsInRoot
        assertTrue("Preview dock covers the note", bounds.bottom <= dock.fetchSemanticsNode().boundsInRoot.top)
        note.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(get(layouts))
            val layout = layouts.single()
            // The semantics adapter rebuilds a MultiParagraph with the parent's max width but
            // returns the wrap-content node's size. Its hasVisualOverflow is not glyph clipping.
            assertEquals(text, layout.layoutInput.text.text)
            val last = layout.lineCount - 1
            assertEquals(text.length, layout.getLineEnd(last, visibleEnd = true))
            for (line in 0..last) {
                assertFalse("Ellipsized preview note line $line", layout.isLineEllipsized(line))
                val width = layout.getLineRight(line) - layout.getLineLeft(line)
                assertTrue("Preview note line width $width exceeds $bounds", width <= bounds.width + 1f)
            }
            assertTrue("Preview note last line at ${layout.getLineBottom(last)} exceeds $bounds",
                layout.getLineBottom(last) <= bounds.height + 1f)
        }
        return preview
    }
    private fun preview(destination: Destination, matches: (Color) -> Boolean) {
        val node = page(destination)
        ui.waitUntil(12_000) { matches(sample(node, .22f)) }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun eachAppearanceHeaderShowsTheGlobalPhotoBeforeAndAfterScrolling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "header-photo-").toFile()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) { emptySet() }
        val api = RoomPlannerApi(fixture.db, imageExists = photos::exists)
        try {
            val id = withSelectedPhoto(context, redPixels(Bitmap.CompressFormat.PNG), "image/png") { uri ->
                runBlocking { photos.importImage(uri) }
            }
            runBlocking {
                val a = Appearance(theme = ThemeMode.LIGHT, background = WallpaperSpec(id, tint = 0f))
                val proposal = api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(a)))) as PreviewResult.Ready
                assertTrue(api.commit(proposal.proposalId) is CommitResult.Applied)
            }
            attach(api, photos)
            openSettings()
            val header = hasTestTag("appearance-header")
            fun showsPhoto(): Boolean {
                val color = sample(ui.onNode(header), .08f) // Above the title, below the status bar.
                return color.red > color.green + .25f && color.red > color.blue + .25f
            }
            for (title in listOf(R.string.ui_colors, R.string.ui_layout, R.string.ui_material_motion)) {
                openGroup(context.getString(title))
                ui.waitUntil(10_000) { showsPhoto() }
                field(row(context.getString(R.string.ui_restore_defaults)))
                assertTrue("Header lost the page photo after scrolling ${context.getString(title)}", showsPhoto())
                ui.onNode(row(context.getString(R.string.ui_cancel)) and hasAnyAncestor(header)).performClick()
                ui.waitUntil(5_000) { ui.onAllNodes(header).fetchSemanticsNodes().isEmpty() }
            }
        } finally { folder.deleteRecursively() }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun largeTextPreviewKeepsTheFullNoteAboveBothDockStyles() {
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                StackLifeApp(viewModel { WorkspaceViewModel(fixture.api) })
            }
        }
        openSettings(); openGroup("布局"); page(TODAY)
        choice(ui.activity.getString(R.string.ui_nav_style), ui.activity.getString(R.string.ui_bottom_nav))
        page(TODAY)
    }

    @Test fun wallpaperShortcutStartsAtTheCardAndRestoresTheUsersScroll() {
        seedAppearance(saved().copy(background = WallpaperSpec()))
        val picker = Selection()
        val original = saved()
        val before = revision()
        attach(selection = picker); openSettings(); openGroup(ui.activity.getString(R.string.wallpaper_title))

        // A stored solid choice should still open the compact card, not a preset or its crop controls.
        val window = await(hasTestTag("appearance-scroll")).getUnclippedBoundsInRoot()
        val card = await(hasTestTag("wallpaper-card")).getUnclippedBoundsInRoot()
        assertTrue("Wallpaper card $card outside editor $window", card.top >= window.top && card.bottom <= window.bottom)
        await(row("应用范围")).assertIsDisplayed().assertTextContains("全局")
        await(row("背景")).assertIsDisplayed().assertTextContains("主题纯色")
        choice("应用范围", COURSES.label); choice("背景", "主题纯色")

        // A deliberate scroll away and a preview change must win over the shortcut, even after recreation.
        field(pagePreview)
        val dockTab = hasTestTag("nav:${WORKSPACE.key}") and hasAnyAncestor(pagePreview)
        await(dockTab).assertIsDisplayed().performClick()
        await(dockTab and isSelected()).assertIsDisplayed()
        ui.activityRule.scenario.recreate(); attach(selection = picker)
        await(dockTab and isSelected()).assertIsDisplayed()
        assertEquals(original, saved()); assertEquals(before, revision())

        await(row("保存外观") and isEnabled()).performClick(); await(row("材质与动态"))
        assertEquals(original.copy(pageBackgrounds = original.pageBackgrounds + (COURSES to WallpaperSpec())), saved())
        assertEquals(before + 1, revision())
        openGroup("材质与动态"); await(pagePreview).assertIsDisplayed()
        choice("应用范围", COURSES.label); field(row("背景")).assertTextContains("主题纯色")
        ui.runOnIdle { assertEquals(0, picker.launches) }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun presetPixelsAndPageOverridesOnlyPersistOnSave() {
        val picker = Selection()
        val original = saved()
        val before = revision()
        val title = ui.activity.getString(R.string.wallpaper_title)
        val builtIn = ui.activity.getString(R.string.wallpaper_built_in)
        val zoom = hasContentDescription(ui.activity.getString(R.string.wallpaper_zoom))
        val blur = hasContentDescription(ui.activity.getString(R.string.wallpaper_blur))
        fun tile(preset: WallpaperPreset) = hasTestTag("wallpaper-preset:${preset.name}")
        fun select(preset: WallpaperPreset) {
            field(tile(preset)).assertTextContains(preset.label).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            await(tile(preset) and isSelected())
            ui.onAllNodes(hasAnyAncestor(hasTestTag("wallpaper-presets")) and isSelected()).assertCountEquals(1)
        }
        // PixelCopy samples the composed window. The same crop over different rows varies
        // by about one 8-bit channel step; compare the spatial pattern, not exact RGBA words.
        fun sameCrop(a: List<Color>, b: List<Color>): Boolean = a.indices.sumOf { i ->
            (abs(a[i].red - b[i].red) + abs(a[i].green - b[i].green) + abs(a[i].blue - b[i].blue)).toDouble()
        } / (a.size * 3) <= 3.0 / 255
        // Sample the actual cropped image, not the checkmark/label or the solid decoding placeholder.
        fun pixels(ready: (List<Color>) -> Boolean = { true }): List<Color> {
            val crop = field(hasTestTag("wallpaper-crop-preview"))
            var colors = emptyList<Color>()
            ui.waitUntil(12_000) {
                val image = crop.captureToImage().toPixelMap()
                colors = List(25) { i -> image[image.width * (i % 5 + 1) / 6, image.height * (i / 5 + 1) / 6] }
                colors.distinct().size > 1 && ready(colors)
            }
            return colors
        }
        attach(selection = picker); openSettings(); openGroup(title)
        await(tile(WallpaperPreset.BLUE)).assertIsSelected()
        capture("wallpaper-preset-gallery")
        val blue = pixels()
        val designs = mutableMapOf<WallpaperPreset, List<Color>>()
        for (preset in WallpaperPreset.entries) {
            select(preset)
            designs[preset] = pixels { sample -> designs.values.none { sameCrop(it, sample) } &&
                sameCrop(sample, blue) == (preset == WallpaperPreset.BLUE) }
        }
        assertEquals(4, designs.values.distinct().size)
        field(blur).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        await(row("取消")).performClick(); await(row("材质与动态"))
        assertEquals(original, saved()); assertEquals(before, revision())

        openGroup(title); await(tile(WallpaperPreset.BLUE)).assertIsSelected()
        select(WallpaperPreset.BROWN)
        field(blur).performSemanticsAction(SemanticsActions.SetProgress) { it(.5f) }
        val brownBlur = pixels { !sameCrop(it, designs.getValue(WallpaperPreset.BROWN)) }
        choice("应用范围", WORKSPACE.label)
        field(row("背景")).assertTextContains("继承全局背景")
        pixels { sameCrop(it, brownBlur) }
        ui.onNode(zoom).assertDoesNotExist()
        ui.onNode(blur).assertDoesNotExist()
        choice("背景", builtIn)
        await(tile(WallpaperPreset.BROWN)).assertIsSelected()
        select(WallpaperPreset.ORANGE)
        field(zoom).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        field(blur).performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        val orangeCrop = pixels { sample -> designs.values.none { sameCrop(it, sample) } }
        ui.activityRule.scenario.recreate(); attach(selection = picker)
        field(tile(WallpaperPreset.ORANGE)).assertIsSelected()
        pixels { sameCrop(it, orangeCrop) }
        assertEquals(original, saved()); assertEquals(before, revision())
        await(row("保存外观") and isEnabled()).performClick(); await(row("材质与动态"))
        assertEquals(original.copy(background = WallpaperSpec(preset = WallpaperPreset.BROWN, blur = .5f),
            pageBackgrounds = mapOf(WORKSPACE to WallpaperSpec(preset = WallpaperPreset.ORANGE, scale = 1.5f, blur = 1f))), saved())
        assertEquals(before + 1, revision())

        ui.activityRule.scenario.recreate(); attach(selection = picker)
        openGroup(title); field(tile(WallpaperPreset.BROWN)).assertIsSelected()
        pixels { sameCrop(it, brownBlur) }
        choice("应用范围", WORKSPACE.label)
        field(tile(WallpaperPreset.ORANGE)).assertIsSelected()
        pixels { sameCrop(it, orangeCrop) }
        ui.runOnIdle { assertEquals(0, picker.launches) }
        choice("背景", ui.activity.getString(R.string.wallpaper_inherit))
        pixels { sameCrop(it, brownBlur) }
        await(row("保存外观") and isEnabled()).performClick(); await(row("材质与动态"))
        assertEquals(original.copy(background = WallpaperSpec(preset = WallpaperPreset.BROWN, blur = .5f)), saved())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun presetLabelsRemainReachableOnANarrowPhoneAtDoubleFontScale() {
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 720.dp)) then DeviceConfigurationOverride.FontScale(2f)) {
                StackLifeApp(viewModel { WorkspaceViewModel(fixture.api) })
            }
        }
        openSettings()
        val shortcut = hasTestTag("settings-wallpaper")
        await(hasScrollToIndexAction()).performScrollToNode(shortcut)
        await(shortcut).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        await(hasTestTag("appearance-scroll"))
        for (preset in WallpaperPreset.entries) {
            val choice = hasText(preset.label) and hasClickAction() and hasAnyAncestor(hasTestTag("wallpaper-presets"))
            field(choice).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            ui.onNode(choice).assertIsSelected()
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun privatePhotoDraftSurvivesRecreationAndCancelKeepsOnlyCommittedImages() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "material-editor-").toFile()
        val dir = File(folder, "background-images")
        fun files() = dir.list().orEmpty().toSet()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) {
            fixture.db.withTransaction { fixture.db.plannerDao().settings()?.let { decodeAppearance(it.appearance).backgroundIds() }.orEmpty() }
        }
        val api = RoomPlannerApi(fixture.db, imageExists = photos::exists, collectBackgrounds = photos::collect)
        val picker = Selection()
        seedAppearance(saved().copy(background = WallpaperSpec(), showCourseGrid = true))
        val original = saved(); val initialRevision = revision()
        try {
            attach(api, photos, picker); openSettings(); openGroup("材质与动态")
            val solid = sample(page(TODAY), .22f)
            withChatPhoto(context, 180) { uri ->
                picker.uri = uri; choice("背景", "私有图片")
                await(row("保存外观") and isEnabled())
                val crop = field(hasTestTag("wallpaper-crop-preview"))
                ui.waitUntil(12_000) { blue(sample(crop, .5f)) }
                field(hasContentDescription("缩放")).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
            }
            val global = files().single(); assertTrue(photos.exists(global))
            choice("应用范围", WORKSPACE.label)
            field(row("背景")).assertTextContains("继承全局背景")
            preview(WORKSPACE, ::blue); capture("wallpaper-inherited-preview")
            withChatPhoto(context, 216) { uri ->
                picker.uri = uri; picker.hold = true; choice("背景", "私有图片")
                // A result delivered after recreation still targets the original page, even when
                // resizing/scrolling has moved its lazy settings row out of composition.
                field(row("减少透明度"))
                ui.activityRule.scenario.recreate(); attach(api, photos, picker)
                field(row("减少透明度")); await(row("保存外观")).assertIsNotEnabled()
                ui.runOnIdle { picker.finishSelection() }
                await(row("保存外观") and isEnabled())
                val crop = field(hasTestTag("wallpaper-crop-preview"))
                ui.waitUntil(12_000) { green(sample(crop, .5f)) }
                capture("wallpaper-crop-settings")
            }
            val own = (files() - global).single(); assertTrue(photos.exists(own))
            picker.uri = null
            field(row("重新选择图片")).performClick() // Cancelling the system picker leaves the current choice alone.
            preview(WORKSPACE, ::green); capture("wallpaper-own-preview")
            choice("应用范围", COURSES.label); choice("背景", "主题纯色")
            preview(COURSES) { it == solid }; capture("wallpaper-plain-preview")
            field(row("显示课表辅助线")).assertIsOn().performClick()
            assertEquals(original, saved()); assertEquals(initialRevision, revision())

            // Both source URIs have been removed. Only app-private copies and saveable draft IDs remain.
            ui.activityRule.scenario.recreate(); attach(api, photos, picker)
            field(row("显示课表辅助线")).assertIsOff()
            field(row("应用范围")).assertTextContains(COURSES.label)
            choice("应用范围", WORKSPACE.label); preview(WORKSPACE, ::green)
            choice("应用范围", TODAY.label); field(row("背景")).assertTextContains("继承全局背景"); preview(TODAY, ::blue)
            assertEquals(initialRevision, revision())
            await(row("保存外观") and isEnabled()).performClick(); await(row("材质与动态"))
            val expected = original.copy(background = WallpaperSpec(global, scale = 1.5f),
                pageBackgrounds = mapOf(WORKSPACE to WallpaperSpec(own), COURSES to WallpaperSpec()), showCourseGrid = false)
            assertEquals(expected, saved()); assertEquals(initialRevision + 1, revision()); assertEquals(setOf(global, own), files())

            openGroup("材质与动态"); preview(TODAY, ::blue)
            choice("应用范围", COURSES.label); preview(COURSES) { it == solid }
            choice("应用范围", WORKSPACE.label); preview(WORKSPACE, ::green)
            withChatPhoto(context, 180) { uri ->
                picker.uri = uri; field(row("重新选择图片")).performClick()
                await(row("保存外观") and isEnabled())
                val crop = field(hasTestTag("wallpaper-crop-preview")); ui.waitUntil(12_000) { blue(sample(crop, .5f)) }
            }
            val discarded = (files() - setOf(global, own)).single()
            choice("背景", "继承全局背景"); field(row("背景")).assertTextContains("继承全局背景")
            choice("应用范围", "全局"); choice("背景", "主题纯色")
            assertEquals(expected, saved()); assertTrue(photos.exists(discarded))
            await(row("取消")).performClick(); await(row("材质与动态"))
            assertEquals(expected, saved()); assertEquals(initialRevision + 1, revision()); assertEquals(setOf(global, own), files())
        } finally { folder.deleteRecursively() }
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test fun countdownGlobalAndPagePickersImportPrimaryStillsWithoutKeepingTheSourceGrant() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "primary-picker-").toFile()
        val dir = File(folder, "background-images")
        fun files() = dir.list().orEmpty().filterTo(mutableSetOf()) { !it.startsWith('.') }
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) {
            fixture.db.withTransaction { buildSet {
                addAll(fixture.db.countdownDao().backgroundIds())
                fixture.db.plannerDao().settings()?.let { addAll(decodeAppearance(it.appearance).backgroundIds()) }
            } }
        }
        val api = RoomPlannerApi(fixture.db, imageExists = photos::exists, collectBackgrounds = photos::collect)
        val picker = Selection()
        @Suppress("DEPRECATION")
        val sources = listOf("image/jpeg" to gainmapPhoto(), "image/jpeg" to motionPhoto(redPixels(Bitmap.CompressFormat.JPEG)),
            "image/png" to redPixels(Bitmap.CompressFormat.PNG, 128), "image/webp" to redPixels(Bitmap.CompressFormat.WEBP, 128),
            "image/heic" to photoFixture("red-blue-cw90.heic.b64"))
        fun pickAll(activate: (Boolean) -> Unit): String {
            var last = ""
            for ((index, source) in sources.withIndex()) {
                val before = files()
                val (mime, bytes) = source
                withSelectedPhoto(context, bytes, mime) { uri ->
                    picker.uri = uri
                    activate(index == 0)
                    ui.waitUntil(12_000) { (files() - before).size == 1 }
                    last = (files() - before).single()
                }
                val image = runBlocking { photos.bitmap(last, 64) }
                try {
                    val rotated = mime == "image/heic"
                    assertEquals(if (rotated) 32 else 64, image.width)
                    assertEquals(if (rotated) 64 else 32, image.height)
                    val top = image.getPixel(image.width / 2, 4)
                    assertTrue(android.graphics.Color.red(top) > 200)
                    if (rotated) assertTrue(android.graphics.Color.blue(image.getPixel(16, 55)) > 200)
                    assertFalse(image.hasGainmap())
                } finally { image.recycle() }
            }
            return last
        }
        val before = revision()
        try {
            attach(api, photos, picker)
            await(hasTestTag("nav:countdown")).performClick(); await(hasTestTag("countdown-add")).performClick()
            await(hasTestTag("countdown-name")).performTextReplacement("合成图片")
            val choose = row(context.getString(R.string.countdown_choose_photo))
            val countdown = pickAll { await(choose and isEnabled()).performScrollTo().performClick() }
            withSelectedPhoto(context, "<svg/>".toByteArray(), "image/jpeg") { uri ->
                picker.uri = uri; await(choose and isEnabled()).performScrollTo().performClick()
                await(hasText(io.github.nakanomk.stacklife.i18n.uiText("This system cannot decode this image.", "当前系统无法解码这张图片。")))
                    .performScrollTo().assertIsDisplayed()
            }
            assertEquals(before, revision())
            await(hasTestTag("countdown-save") and isEnabled()).performClick(); await(hasTestTag("countdown-list"))
            fun savedCountdown() = runBlocking { (api.observe(PlannerQuery.Countdowns).first() as CountdownsView).items.single().backgroundId }
            assertEquals(countdown, savedCountdown())
            openSettings(); openGroup("材质与动态")
            fun wallpaper() = pickAll { first ->
                if (first) choice("背景", "私有图片") else field(row("重新选择图片") and isEnabled()).performClick()
            }
            val global = wallpaper()
            choice("应用范围", WORKSPACE.label)
            val page = wallpaper()
            assertEquals(before + 1, revision())
            await(row("保存外观") and isEnabled()).performClick(); await(row("材质与动态"))
            ui.activityRule.scenario.recreate(); attach(api, photos, picker)
            assertEquals(global, saved().background.imageId); assertEquals(page, saved().pageBackgrounds[WORKSPACE]?.imageId)
            assertEquals(countdown, savedCountdown())
            assertEquals(setOf(global, page, countdown), files()); assertEquals(before + 2, revision())
        } finally { folder.deleteRecursively() }
    }

    private fun shown(dest: Destination) = field(hasTestTag("visibility:${dest.key}"))
    private fun move(dest: Destination, up: Boolean) {
        field(hasTestTag("move:${dest.key}")).performClick()
        await(row((if (up) "上移" else "下移") + dest.label)).performClick()
    }
    private fun dragBefore(from: Destination, above: Destination) {
        // Leave room below the last row: the edge strip is for auto-scroll, not a drop target.
        field(row("恢复默认设置"))
        fun center(dest: Destination): Offset {
            val node = ui.onNodeWithTag("move:${dest.key}").assertIsDisplayed().fetchSemanticsNode()
            return ui.runOnUiThread {
                val coords = node.layoutInfo.coordinates
                coords.localToScreen(Offset.Zero) + (node.boundsInRoot.center - coords.localToRoot(Offset.Zero))
            }
        }
        val start = center(from); val end = center(above)
        assertTrue(end.y < start.y)
        val input = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        var last = start
        fun send(action: Int, at: Offset) {
            last = at
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, at.x, at.y, 0)
            try { event.source = InputDevice.SOURCE_TOUCHSCREEN; input.sendPointerSync(event) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, start)
        try {
            // Observe the library's lifted row, not just a timestamp on a synthetic move event.
            await(hasTestTag("dragging:${from.key}")).assertIsDisplayed()
            for (step in 1..12) {
                SystemClock.sleep(16)
                send(MotionEvent.ACTION_MOVE, start + (end - start) * (step / 12f))
                // Deliver a frame per move, as on a real display; animation and measurement in
                // this Compose test host are driven by the test frame clock, not SystemClock.
                ui.mainClock.advanceTimeByFrame()
            }
            capture("navigation-drag")
            // It is the *other* row moving into the vacated slot that proves a reorder took
            // effect, rather than just the dragged item's graphics-layer translation.
            ui.waitUntil(5_000) { center(above).y > (end.y + start.y) / 2f }
        } catch (failure: Throwable) {
            send(MotionEvent.ACTION_CANCEL, last); throw failure
        }
        send(MotionEvent.ACTION_UP, last)
        ui.waitForIdle()
        assertTrue("Released ${from.key} must remain above ${above.key}", center(from).y < center(above).y)
    }

    @Test fun navigationRowsCommitDragVisibilityAndStartTogether() {
        val original = saved(); val before = revision()
        attach(); openSettings(); openGroup("布局")
        choice("启动页", GRAPHS.label)
        shown(GRAPHS).assertIsOn().performClick()
        field(row("启动页")).assertTextContains(COURSES.label)
        for (dest in listOf(TODAY, WORKSPACE, COURSES)) shown(dest).performClick()
        shown(COUNTDOWN).assertIsOn().assertIsNotEnabled().performTouchInput { click() }
        shown(COUNTDOWN).assertIsOn().assertIsNotEnabled()
        field(row("启动页")).assertTextContains(COUNTDOWN.label)
        shown(TODAY).performClick(); shown(GRAPHS).performClick()
        move(COURSES, up = true)    // today, workspace, courses, graphs, countdown (a hidden row can still be positioned)
        move(TODAY, up = false)   // workspace, today, courses, graphs, countdown
        dragBefore(COUNTDOWN, GRAPHS)
        choice("启动页", GRAPHS.label)
        shown(WORKSPACE).assertIsOff(); shown(COUNTDOWN).assertIsOn(); capture("navigation-settings")
        val order = listOf(WORKSPACE, TODAY, COURSES, COUNTDOWN, GRAPHS)
        val visible = setOf(TODAY, COUNTDOWN, GRAPHS)
        page(GRAPHS)
        val visiblePositions = order.filter { it in visible }.map { ui.onNode(hasTestTag("nav:${it.key}") and hasAnyAncestor(pagePreview))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot.left }
        assertEquals(visiblePositions.sorted(), visiblePositions)
        ui.onNode(hasTestTag("nav:workspace") and hasAnyAncestor(pagePreview)).assertDoesNotExist()
        capture("navigation-preview")
        assertEquals(original, saved()); assertEquals(before, revision())
        await(row("保存外观") and isEnabled()).performClick(); await(row("布局"))
        assertEquals(original.copy(order = order, visible = visible, start = GRAPHS), saved()); assertEquals(before + 1, revision())
        openGroup("布局"); shown(WORKSPACE).assertIsOff(); shown(COURSES).assertIsOff()
        field(row("启动页")).assertTextContains(GRAPHS.label); await(row("保存外观")).assertIsNotEnabled()
    }
}
