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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.acceptance.WorkspaceFixture
import io.github.nakanomk.stacklife.assistant.withChatPhoto
import io.github.nakanomk.stacklife.planner.*
import io.github.nakanomk.stacklife.planner.Destination.*
import io.github.nakanomk.stacklife.storage.RoomPlannerApi
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    private fun awaitSaved(expected: Appearance) = ui.waitUntil(10_000) { saved() == expected }
    private fun back() = ui.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
    private fun discard() = await(row(ui.activity.getString(R.string.appearance_discard)) and isEnabled()).performClick()
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
        val settings = hasAnyAncestor(hasTestTag("route:settings"))
        val list = await(hasScrollToIndexAction() and settings)
        val back = await(hasContentDescription(ui.activity.getString(R.string.ui_back)) and settings)
        val top = back.fetchSemanticsNode().boundsInRoot.bottom + with(ui.density) { 6.dp.toPx() }
        val bottom = ui.onAllNodes(hasTestTag("assistant-composer") and settings).fetchSemanticsNodes()
            .singleOrNull()?.boundsInRoot?.top ?: list.fetchSemanticsNode().boundsInRoot.bottom
        exposed(list, row(label) and settings, top, bottom).performTouchInput { click() }
        await(hasTestTag("appearance-scroll"))
    }
    private fun field(m: SemanticsMatcher): SemanticsNodeInteraction {
        await(hasTestTag("appearance-scroll")).performScrollToNode(m)
        return ui.onNode(m).performScrollTo().assertIsDisplayed()
    }
    private fun exposedField(m: SemanticsMatcher): SemanticsNodeInteraction {
        val list = await(hasTestTag("appearance-scroll"))
        val top = await(hasTestTag("appearance-header")).fetchSemanticsNode().boundsInRoot.bottom
        return exposed(list, m, top, list.fetchSemanticsNode().boundsInRoot.bottom)
    }
    private fun exposed(list: SemanticsNodeInteraction, m: SemanticsMatcher, top: Float, bottom: Float): SemanticsNodeInteraction {
        list.performScrollToNode(m)
        val node = ui.onNode(m).performScrollTo()
        val layout = node.fetchSemanticsNode()
        val y = layout.positionInRoot.y
        // The scrollable extends under pinned chrome. A node in its bounds can still be
        // untappable (or PixelCopy may capture the header instead of the preview photo).
        if (layout.size.height + 2 < bottom - top) {
            val desired = y.coerceIn(top + 1f, bottom - layout.size.height - 1f)
            if (abs(y - desired) > 1f) list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, y - desired) }
            val placed = node.fetchSemanticsNode()
            assertTrue("Covered $m at ${placed.boundsInRoot}; usable y=$top..$bottom",
                placed.positionInRoot.y >= top - 1f && placed.positionInRoot.y + placed.size.height <= bottom + 1f)
        }
        return node.assertIsDisplayed()
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

    @SdkSuppress(minSdkVersion = 33)
    @Test fun unchangedSizeColorControlsRepaintAsDayAndNightArePersisted() {
        val original = saved().copy(theme = ThemeMode.LIGHT)
        seedAppearance(original)
        attach(); openSettings(); openGroup(ui.activity.getString(R.string.ui_colors))
        val title = ui.activity.getString(R.string.ui_color_theme)
        val controls = field(row(title))
        val size = controls.fetchSemanticsNode().size
        fun paper() = sample(controls, .20f).luminance()
        ui.waitUntil(8_000) { paper() > .45f }
        for ((label, dark) in listOf(R.string.ui_dark to true, R.string.ui_light to false)) {
            controls.performClick()
            await(hasText(ui.activity.getString(label)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
            ui.waitUntil(8_000) { if (dark) paper() < .20f else paper() > .45f }
            awaitSaved(original.copy(theme = if (dark) ThemeMode.DARK else ThemeMode.LIGHT))
            assertEquals(size, controls.fetchSemanticsNode().size)
        }
        assertEquals(original, saved())
    }
    private fun page(destination: Destination): SemanticsNodeInteraction {
        val preview = exposedField(pagePreview)
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

    @OptIn(ExperimentalTestApi::class)
    @SdkSuppress(minSdkVersion = 29)
    @Test fun eachPinnedAppearanceHeaderUsesThePhotoThenItsOwnScrolledControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "header-photo-").toFile()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) { emptySet() }
        val api = RoomPlannerApi(fixture.db, imageExists = photos::exists)
        try {
            val id = withSelectedPhoto(context, redPixels(Bitmap.CompressFormat.PNG), "image/png") { uri ->
                runBlocking { photos.importImage(uri) }
            }
            val a = Appearance(theme = ThemeMode.LIGHT, palette = PaletteMode.SEED, background = WallpaperSpec(id, tint = 0f))
            runBlocking {
                val proposal = api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(a)))) as PreviewResult.Ready
                assertTrue(api.commit(proposal.proposalId) is CommitResult.Applied)
            }
            var solid: Color? = null
            ui.activityRule.scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalBackgroundStore provides photos) {
                    StackLifeTheme(a) {
                        val fallback = if (glassUnavailableReason(a) != null) MaterialTheme.colorScheme.surface else null
                        SideEffect { solid = fallback }
                        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 520.dp))) {
                            StackLifeApp(viewModel { WorkspaceViewModel(api) })
                        }
                    }
                }
            } }
            openSettings()
            val header = hasTestTag("appearance-header")
            fun matchesHeader(): Boolean {
                val color = sample(ui.onNode(header), .08f) // Above the title, below the status bar.
                return solid?.let { color.toArgb() == it.toArgb() }
                    ?: (color.red > color.green + .25f && color.red > color.blue + .25f)
            }
            for (title in listOf(R.string.ui_colors, R.string.ui_layout, R.string.ui_material_motion)) {
                openGroup(context.getString(title))
                ui.waitUntil(10_000) { matchesHeader() }
                val before = ui.onNode(header).captureToImage().toPixelMap()
                val bounds = ui.onNode(header).fetchSemanticsNode().boundsInRoot
                val heading = ui.onNode(hasText(context.getString(title)) and hasAnyAncestor(header), useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val discard = ui.onNode(hasText(context.getString(R.string.appearance_discard)) and hasClickAction())
                    .fetchSemanticsNode().boundsInRoot
                assertEquals("The discard action must not shift the heading", bounds.center.x, heading.center.x, 1f)
                assertFalse("The action must not cover title text", discard.overlaps(heading))
                field(row(context.getString(R.string.ui_restore_defaults)))
                assertEquals(bounds, ui.onNode(header).fetchSemanticsNode().boundsInRoot)
                ui.waitUntil(10_000) {
                    val after = ui.onNode(header).captureToImage().toPixelMap()
                    var changed = 0; var dark = 0; var samples = 0
                    for (x in 4 until after.width step 6) for (y in 3 until after.height step 6) {
                        samples++
                        val c = after[x, y]; val old = before[x, y]
                        if (c.luminance() < .03f) dark++
                        if (abs(c.green - old.green) + abs(c.blue - old.blue) > .06f) changed++
                    }
                    dark * 4 < samples && (if (solid == null) changed * 50 > samples else matchesHeader())
                }
                back()
                ui.waitUntil(5_000) { ui.onAllNodes(header).fetchSemanticsNodes().isEmpty() }
            }
        } finally { folder.deleteRecursively() }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun privatePhotoSeedPersistsOnlySuccessfulSamplesAndDiscardRestoresEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "seed-photos-").toFile()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) { emptySet() }
        val api = RoomPlannerApi(fixture.db, imageExists = photos::exists)
        fun importPhoto(bytes: ByteArray) = withSelectedPhoto(context, bytes, "image/png") { uri -> runBlocking { photos.importImage(uri) } }
        fun text(id: Int) = ui.activity.getString(id)
        val colors = text(R.string.ui_colors)
        val pick = row(text(R.string.wallpaper_seed_pick))
        val target = text(R.string.wallpaper_seed_source)
        val seed = hasTestTag("seed-color")
        try {
            val red = importPhoto(redPixels(Bitmap.CompressFormat.PNG))
            val missing = importPhoto(redPixels(Bitmap.CompressFormat.PNG))
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            val gray = try {
                bitmap.eraseColor(android.graphics.Color.GRAY)
                java.io.ByteArrayOutputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); importPhoto(it.toByteArray()) }
            } finally { bitmap.recycle() }
            val expected = runBlocking { photos.bitmap(red, 1) }.let {
                try { "#%06X".format(java.util.Locale.ROOT, it.getPixel(0, 0) and 0xffffff) } finally { it.recycle() }
            }
            val initial = Appearance(palette = PaletteMode.SEED, background = WallpaperSpec(red), highContrast = true,
                pageBackgrounds = mapOf(COURSES to WallpaperSpec(gray), GRAPHS to WallpaperSpec(missing), COUNTDOWN to WallpaperSpec()))
            runBlocking {
                val proposal = api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(initial)))) as PreviewResult.Ready
                assertTrue(api.commit(proposal.proposalId) is CommitResult.Applied)
            }
            val before = revision()
            assertTrue(File(File(folder, "background-images"), missing).delete())
            attach(api, photos); openSettings(); openGroup(colors)
            choice(target, COURSES.label); field(pick).performClick()
            await(hasText(text(R.string.wallpaper_seed_no_color))).performScrollTo().assertIsDisplayed()
            field(seed).assertTextContains(initial.seed)
            choice(target, GRAPHS.label); field(pick).performClick()
            await(hasText(text(R.string.wallpaper_seed_unavailable))).performScrollTo().assertIsDisplayed()
            choice(target, COUNTDOWN.label); field(pick).assertIsNotEnabled()
            field(hasText(text(R.string.wallpaper_seed_no_photo))).assertIsDisplayed()
            assertEquals(initial, saved()); assertEquals(before, revision())
            choice(target, WORKSPACE.label); field(pick).performClick() // inherits the global private file
            await(seed and hasText(expected)); awaitSaved(initial.copy(seed = expected))
            discard(); await(row(colors))
            assertEquals(initial, saved()); assertEquals(before + 2, revision())
            // The unreadable fixture and neutral source never wrote anything.
            File(File(folder, "background-images"), red).copyTo(File(File(folder, "background-images"), missing))
            openGroup(colors); field(seed).assertTextContains(initial.seed)
            field(pick).performClick(); await(seed and hasText(expected))
            back(); await(row(colors))
            assertEquals(initial.copy(seed = expected), saved()); assertEquals(before + 3, revision())
            openGroup(colors); field(seed).assertTextContains(expected)
        } finally { folder.deleteRecursively() }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun anOldPhotoReadCannotReplaceANewEditOfTheSameImage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = Files.createTempDirectory(context.cacheDir.toPath(), "seed-generation-").toFile()
        val holding = java.util.concurrent.atomic.AtomicBoolean(false)
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val photos = BackgroundStore(object : ContextWrapper(context) { override fun getNoBackupFilesDir() = folder }) {
            if (holding.get()) { entered.complete(Unit); resume.await() }
            emptySet()
        }
        val api = RoomPlannerApi(fixture.db, imageExists = photos::exists)
        fun text(id: Int) = ui.activity.getString(id)
        val colors = text(R.string.ui_colors); val target = text(R.string.wallpaper_seed_source)
        val pick = row(text(R.string.wallpaper_seed_pick)); val reading = hasText(text(R.string.wallpaper_seed_reading))
        val seed = hasTestTag("seed-color")
        try {
            val id = withSelectedPhoto(context, redPixels(Bitmap.CompressFormat.PNG), "image/png") { photos.importImage(it) }
            val initial = Appearance(palette = PaletteMode.SEED, background = WallpaperSpec(id))
            val ready = api.preview(listOf(PlannerCommand.EditAppearance(AppearancePatch.Replace(initial)))) as PreviewResult.Ready
            assertTrue(api.commit(ready.proposalId) is CommitResult.Applied)
            attach(api, photos); openSettings(); openGroup(colors)
            // Delay the actual file read on the store's collection mutex.
            holding.set(true)
            val collection = launch(Dispatchers.IO) { photos.collect() }
            withTimeout(5_000) { entered.await() }
            fun startRead() { field(pick).performClick(); field(reading) }
            suspend fun setWallpaper(a: Appearance) {
                val change = PlannerCommand.EditAppearance(AppearanceFields(AppearanceGroup.MATERIAL, AppearanceGroup.MATERIAL.project(a)))
                val p = api.preview(listOf(change)) as PreviewResult.Ready
                assertTrue(api.commit(p.proposalId) is CommitResult.Applied)
            }
            startRead(); setWallpaper(initial.copy(background = WallpaperSpec()))
            await(hasText(text(R.string.wallpaper_seed_no_photo)))
            setWallpaper(initial); await(pick and isEnabled())
            startRead(); choice(target, WORKSPACE.label) // different target, same inherited image
            startRead(); field(row(text(R.string.ui_restore_defaults))).performClick()
            choice(text(R.string.ui_palette), text(R.string.ui_custom_seed))
            startRead(); back(); await(row(colors))
            openGroup(colors); startRead(); field(seed).performTextReplacement("#13579B")
            resume.complete(Unit); collection.join()
            back(); await(row(colors))
            assertEquals(initial.copy(seed = "#13579B"), saved())
        } finally { resume.complete(Unit); folder.deleteRecursively() }
    }

    @Test fun discardColorReturnsOnlyToSettingsThenBackReturnsToTheWorkspace() {
        val entry = saved().copy(start = WORKSPACE, theme = ThemeMode.LIGHT)
        seedAppearance(entry)
        attach(); openSettings(); openGroup(ui.activity.getString(R.string.ui_colors))
        choice(ui.activity.getString(R.string.ui_color_theme), ui.activity.getString(R.string.ui_dark))
        awaitSaved(entry.copy(theme = ThemeMode.DARK))
        discard(); awaitSaved(entry)
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("route:appearance:color").fetchSemanticsNodes().isEmpty() }
        val settings = hasTestTag("route:settings")
        await(settings).assertIsDisplayed()
        await(hasContentDescription(ui.activity.getString(R.string.ui_back)) and hasAnyAncestor(settings)).performClick()
        await(hasTestTag("nav:workspace") and isSelected()).assertIsDisplayed()
        ui.onNode(settings).assertDoesNotExist()
    }

    @Test fun currentTimeControlsPersistOnBackAndDiscardUsesTheEntryBeforeRotation() {
        val original = saved()
        val before = revision()
        fun text(id: Int) = ui.activity.getString(id)
        val layout = text(R.string.ui_layout)
        val line = hasText(text(R.string.now_marker_line)) and isToggleable()
        val arrow = hasText(text(R.string.now_marker_arrow)) and isToggleable()
        fun edit() {
            exposedField(line).assertIsOn().performTouchInput { click() }
            exposedField(arrow).assertIsOn().performTouchInput { click() }
        }
        attach(); openSettings(); openGroup(layout); edit()
        val edited = original.copy(showNowLine = false, nowArrow = false)
        awaitSaved(edited)
        // A writer changed both an unrelated group and an unedited setting of this group.
        val external = edited.copy(compact = !original.compact, theme = ThemeMode.DARK)
        seedAppearance(external)
        ui.activityRule.scenario.recreate(); attach()
        discard(); await(row(layout))
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("appearance-scroll").fetchSemanticsNodes().isEmpty() }
        assertEquals(original.copy(compact = external.compact, theme = external.theme), saved())
        openGroup(layout); edit()
        field(line).assertIsOff(); field(arrow).assertIsOff()
        back(); await(row(layout))
        assertEquals(external, saved())
        assertTrue(revision() > before)
        openGroup(layout)
        field(line).assertIsOff(); field(arrow).assertIsOff()
        ui.activityRule.scenario.close(); fixture.reopen()
        assertEquals(external, saved())
    }

    @Test fun invalidSeedAndAConflictingWriterNeverOverwriteTheStoredColor() {
        val entry = saved().copy(palette = PaletteMode.SEED)
        seedAppearance(entry)
        val before = revision()
        val rejected = CompletableDeferred<String>()
        val api = object : PlannerApi by fixture.api {
            override suspend fun preview(commands: List<PlannerCommand>): PreviewResult = fixture.api.preview(commands).also {
                if (it is PreviewResult.Rejected) rejected.complete(it.message)
            }
        }
        attach(api); openSettings(); openGroup(ui.activity.getString(R.string.ui_colors))
        val seed = hasTestTag("seed-color")
        exposedField(seed).performTextReplacement("#12")
        assertEquals(entry, saved()); assertEquals(before, revision())
        exposedField(seed).performTextReplacement("#123456")
        awaitSaved(entry.copy(seed = "#123456"))
        val external = entry.copy(seed = "#ABCDEF", showNotes = !entry.showNotes)
        seedAppearance(external)
        val concurrent = revision()
        exposedField(seed).performTextReplacement("#13579B")
        ui.waitUntil(10_000) { rejected.isCompleted }
        val reason = runBlocking { rejected.await() }
        assertEquals(io.github.nakanomk.stacklife.i18n.uiText(
            "Appearance settings have changed. Edit the latest settings instead.", "外观设置已变更，请按最新设置重新编辑。"), reason)
        await(hasText(reason) and hasAnyAncestor(hasTestTag("appearance-header"))).assertIsDisplayed()
        exposedField(seed).assertTextContains(external.seed)
        assertEquals(external, saved()); assertEquals(concurrent, revision())
        discard(); await(row(ui.activity.getString(R.string.ui_colors)))
        assertEquals(external, saved()); assertEquals(concurrent, revision())
    }

    @Test fun rapidWallpaperEditsFlushOnlyTheLatestQueuedCropOnBack() {
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        val api = object : PlannerApi by fixture.api {
            override suspend fun preview(commands: List<PlannerCommand>): PreviewResult {
                if (commands.singleOrNull() is PlannerCommand.EditAppearance && entered.complete(Unit)) resume.await()
                return fixture.api.preview(commands)
            }
        }
        val entry = saved().copy(theme = ThemeMode.LIGHT, background = WallpaperSpec(preset = WallpaperPreset.BLUE, tint = .45f))
        seedAppearance(entry); val before = revision()
        attach(api); openSettings(); openGroup(ui.activity.getString(R.string.wallpaper_title))
        val zoom = field(hasContentDescription(ui.activity.getString(R.string.wallpaper_zoom)))
        // The list's 16dp side padding exposes the actual page donor, not the thumbnail.
        // With this light wash the patterned gutter cannot contain a black or blank frame.
        fun showsPlaid(): Boolean {
            val px = ui.onNodeWithTag("appearance-scroll").captureToImage().toPixelMap()
            val strip = (4..28).map { px[2, px.height * it / 32] }
            return strip.all { it.luminance() > .10f } &&
                strip.maxOf { it.luminance() } - strip.minOf { it.luminance() } > .008f
        }
        ui.waitUntil(8_000) { showsPlaid() }
        try {
            zoom.performSemanticsAction(SemanticsActions.SetProgress) { it(1.25f) }
            ui.waitUntil(8_000) { entered.isCompleted }
            for (value in listOf(2.75f, 1.5f, 2.25f, 2f)) {
                zoom.performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
                assertTrue("Global wallpaper disappeared at zoom $value", showsPlaid())
            }
            assertEquals(entry, saved())
            back()
            await(hasTestTag("appearance-header")).assertIsDisplayed()
        } finally { resume.complete(Unit) }
        await(row(ui.activity.getString(R.string.ui_material_motion)))
        assertEquals(entry.copy(background = entry.background.copy(scale = 2f)), saved())
        assertEquals(before + 2, revision())
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
        val expected = original.copy(pageBackgrounds = original.pageBackgrounds + (COURSES to WallpaperSpec()))
        assertEquals(expected, saved())
        back(); await(row("材质与动态"))
        assertEquals(expected, saved())
        assertEquals(before + 1, revision())
        openGroup("材质与动态"); await(pagePreview).assertIsDisplayed()
        choice("应用范围", COURSES.label); field(row("背景")).assertTextContains("主题纯色")
        ui.runOnIdle { assertEquals(0, picker.launches) }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun presetPixelsAndPageOverridesPersistWithoutASeparateSave() {
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
        awaitSaved(original.copy(background = WallpaperSpec(preset = WallpaperPreset.ORANGE, blur = 1f)))
        discard(); await(row("材质与动态"))
        assertEquals(original, saved()); assertTrue(revision() > before)

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
        val both = original.copy(background = WallpaperSpec(preset = WallpaperPreset.BROWN, blur = .5f),
            pageBackgrounds = mapOf(WORKSPACE to WallpaperSpec(preset = WallpaperPreset.ORANGE, scale = 1.5f, blur = 1f)))
        assertEquals(both, saved())
        val committed = revision()
        back(); await(row("材质与动态"))
        assertEquals(both, saved()); assertEquals(committed, revision())

        ui.activityRule.scenario.recreate(); attach(selection = picker)
        openGroup(title); field(tile(WallpaperPreset.BROWN)).assertIsSelected()
        pixels { sameCrop(it, brownBlur) }
        choice("应用范围", WORKSPACE.label)
        field(tile(WallpaperPreset.ORANGE)).assertIsSelected()
        pixels { sameCrop(it, orangeCrop) }
        ui.runOnIdle { assertEquals(0, picker.launches) }
        choice("背景", ui.activity.getString(R.string.wallpaper_inherit))
        pixels { sameCrop(it, brownBlur) }
        back(); await(row("材质与动态"))
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
    @Test fun privatePhotosSurviveRecreationAndDiscardRestoresOnlyOriginalImages() {
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
                ui.waitUntil(10_000) { saved().background.imageId != null }
                val crop = field(hasTestTag("wallpaper-crop-preview"))
                ui.waitUntil(12_000) { blue(sample(crop, .5f)) }
                field(hasContentDescription("缩放")).performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
            }
            val global = files().single(); assertTrue(photos.exists(global))
            choice("应用范围", WORKSPACE.label)
            field(row("背景")).assertTextContains("继承全局背景")
            val inherited = original.copy(background = WallpaperSpec(global, scale = 1.5f))
            awaitSaved(inherited)
            preview(WORKSPACE, ::blue); capture("wallpaper-inherited-preview")
            withChatPhoto(context, 216) { uri ->
                picker.uri = uri; picker.hold = true; choice("背景", "私有图片")
                // A result delivered after recreation still targets the original page, even when
                // resizing/scrolling has moved its lazy settings row out of composition.
                field(row("减少透明度"))
                ui.activityRule.scenario.recreate(); attach(api, photos, picker)
                field(row("减少透明度")); assertEquals(inherited, saved())
                ui.runOnIdle { picker.finishSelection() }
                ui.waitUntil(10_000) { saved().pageBackgrounds[WORKSPACE]?.imageId != null }
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
            val expected = inherited.copy(pageBackgrounds = mapOf(WORKSPACE to WallpaperSpec(own), COURSES to WallpaperSpec()), showCourseGrid = false)
            awaitSaved(expected); assertTrue(revision() > initialRevision)

            // Both source URIs have been removed. Only app-private copies remain.
            ui.activityRule.scenario.recreate(); attach(api, photos, picker)
            field(row("显示课表辅助线")).assertIsOff()
            field(row("应用范围")).assertTextContains(COURSES.label)
            choice("应用范围", WORKSPACE.label); preview(WORKSPACE, ::green)
            choice("应用范围", TODAY.label); field(row("背景")).assertTextContains("继承全局背景"); preview(TODAY, ::blue)
            val committed = revision()
            back(); await(row("材质与动态"))
            assertEquals(expected, saved()); assertEquals(committed, revision()); assertEquals(setOf(global, own), files())

            openGroup("材质与动态"); preview(TODAY, ::blue)
            choice("应用范围", COURSES.label); preview(COURSES) { it == solid }
            choice("应用范围", WORKSPACE.label); preview(WORKSPACE, ::green)
            withChatPhoto(context, 180) { uri ->
                picker.uri = uri; field(row("重新选择图片")).performClick()
                ui.waitUntil(10_000) { saved().pageBackgrounds[WORKSPACE]?.imageId != own }
                val crop = field(hasTestTag("wallpaper-crop-preview")); ui.waitUntil(12_000) { blue(sample(crop, .5f)) }
            }
            val discarded = (files() - setOf(global, own)).single()
            choice("背景", "继承全局背景"); field(row("背景")).assertTextContains("继承全局背景")
            choice("应用范围", "全局"); choice("背景", "主题纯色")
            awaitSaved(expected.copy(background = WallpaperSpec(), pageBackgrounds = expected.pageBackgrounds - WORKSPACE))
            runBlocking { photos.collect() }
            assertEquals(setOf(global, own, discarded), files())
            ui.activityRule.scenario.recreate(); attach(api, photos, picker)
            discard(); await(row("材质与动态"))
            assertEquals(expected, saved()); assertEquals(setOf(global, own), files())

            openGroup("材质与动态"); choice("背景", "主题纯色")
            val kept = expected.copy(background = WallpaperSpec())
            awaitSaved(kept)
            assertTrue(photos.exists(global))
            back(); await(row("材质与动态"))
            assertEquals(kept, saved()); assertEquals(setOf(own), files())
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
            ui.waitUntil(10_000) { saved().background.imageId == global && saved().pageBackgrounds[WORKSPACE]?.imageId == page }
            val committed = revision()
            back(); await(row("材质与动态"))
            ui.activityRule.scenario.recreate(); attach(api, photos, picker)
            assertEquals(global, saved().background.imageId); assertEquals(page, saved().pageBackgrounds[WORKSPACE]?.imageId)
            assertEquals(countdown, savedCountdown())
            assertEquals(setOf(global, page, countdown), files()); assertEquals(committed, revision())
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

    @Test fun navigationRowsPersistDragVisibilityAndStartBeforeReturning() {
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
        val expected = original.copy(order = order, visible = visible, start = GRAPHS)
        awaitSaved(expected); val committed = revision(); assertTrue(committed > before)
        back(); await(row("布局"))
        assertEquals(expected, saved()); assertEquals(committed, revision())
        openGroup("布局"); shown(WORKSPACE).assertIsOff(); shown(COURSES).assertIsOff()
        field(row("启动页")).assertTextContains(GRAPHS.label); await(row(ui.activity.getString(R.string.appearance_discard))).assertIsNotEnabled()
    }
}
