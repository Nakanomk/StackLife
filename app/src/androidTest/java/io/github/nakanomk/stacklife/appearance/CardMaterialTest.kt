package io.github.nakanomk.stacklife.appearance

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.github.nakanomk.stacklife.about.AboutIdentity
import io.github.nakanomk.stacklife.graphs.GraphCanvas
import io.github.nakanomk.stacklife.planner.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class CardMaterialTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    private val split = mutableFloatStateOf(.5f)
    private val appearance = mutableStateOf(Appearance(glass = true))
    private val platform = mutableStateOf(PlatformAppearance(accelerated = true))
    private val blue = Color(0xff4466cc)

    private fun show(content: @Composable BoxScope.() -> Unit) {
        ui.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primaryContainer = blue, surface = Color.White)) {
                val layer = rememberGraphicsLayer()
                val donor = remember(layer) { PlannerBackdrop(layer, Color.White, null, null) }
                Box(Modifier.size(240.dp, 240.dp)) {
                    Canvas(Modifier.matchParentSize().plannerBackdrop(donor)) {
                        drawRect(Color.White)
                        drawRect(Color.Black, size = Size(size.width * split.floatValue, size.height))
                    }
                    CompositionLocalProvider(LocalWallpaperRecording provides donor, LocalCapsuleBackdrop provides donor,
                        LocalAppearance provides appearance.value, LocalMotionEnabled provides false,
                        LocalPlannerGlassAllowed provides true, LocalPlatformAppearance provides platform.value) {
                        content()
                    }
                }
            }
        }
    }

    @Test fun collectionBlursTheWallpaperEdgeButKeepsForegroundAndOpaqueFallbacksSharp() {
        show {
            // A page-foreground recording would paint magenta under the card, even though the
            // real wallpaper is black/white. The green edge belongs to the card foreground.
            Spacer(Modifier.matchParentSize().background(Color.Magenta))
            CardFrostGroup(true, Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    FrostedCard(Modifier.size(160.dp, 60.dp).testTag("card"), RectangleShape, Color.White) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawRect(Color.Green, Offset(0f, size.height * .5f), Size(size.width, size.height / 2))
                        }
                    }
                }
            }
        }
        fun image() = ui.onNodeWithTag("card").captureToImage().toPixelMap()
        val glass = image()
        val mid = glass.width / 2; val y = glass.height / 4
        val dark = glass[mid / 2, y]; val light = glass[mid + mid / 2, y]
        val edge = glass[mid, y]
        assertTrue("The shared plane did not transmit the two wallpaper regions", light.red - dark.red > .35f)
        assertTrue("The wallpaper edge stayed hard", edge.red > dark.red + .1f && edge.red < light.red - .1f)
        assertEquals(edge.red, edge.green, .02f)
        assertEquals(Color.Green, glass[mid, glass.height / 2 + 1])
        assertTrue(glass[mid, glass.height / 2 - 2].green < .9f)
        ui.runOnIdle { split.floatValue = 0f }
        assertTrue(image()[mid / 2, y].red > .95f)
        ui.runOnIdle { split.floatValue = 1f; appearance.value = Appearance(glass = false) }
        assertEquals(Color.White, image()[mid, y])
        for (fallback in listOf(PlatformAppearance(accelerated = true, lowRam = true),
            PlatformAppearance(accelerated = true, powerSave = true), PlatformAppearance(accelerated = true, contrast = .5f))) {
            ui.runOnIdle { appearance.value = Appearance(glass = true); platform.value = fallback }
            assertEquals(Color.White, image()[mid, y])
        }
    }

    @Test fun aboutIdentityRetainsItsThemeTintAndSamplesTheCropUnderItsText() {
        show {
            Box(Modifier.fillMaxWidth().align(Alignment.Center).testTag("identity")) { AboutIdentity { Text("0.11.2") } }
        }
        fun probe(): Color = ui.onNodeWithTag("identity").captureToImage().toPixelMap().let { it[it.width / 2, it.height - 4] }
        ui.runOnIdle { split.floatValue = 1f }
        val dark = probe()
        ui.runOnIdle { split.floatValue = 0f }
        val light = probe()
        assertTrue("Theme blue was lost", dark.blue > dark.red + .05f)
        assertTrue("The hero did not sample the new crop", light.red - dark.red > .25f)
        ui.runOnIdle { appearance.value = Appearance(glass = false) }
        assertEquals(blue, probe())
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun aboutIdentityStacksTheFullNameAndVersionAt320DpAndDoubleFontScale() {
        val version = ui.activity.getString(R.string.about_version, "0.11.2", 38)
        ui.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 740.dp)) then
                DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme(colorScheme = lightColorScheme(primaryContainer = Color.White, onPrimaryContainer = Color.Black)) {
                    CompositionLocalProvider(LocalCapsuleBackdrop provides remember { UniformPagePaper(Color.White) },
                        LocalAppearance provides Appearance(glass = true), LocalPlannerGlassAllowed provides true,
                        LocalPlatformAppearance provides platform.value, LocalMotionEnabled provides false) {
                        Column(Modifier.fillMaxSize().background(Color.White)) {
                            Box(Modifier.fillMaxWidth().testTag("identity")) {
                                AboutIdentity { Text(version, style = MaterialTheme.typography.labelLarge) }
                            }
                            // The same type, scale and available line width, with no hero or material clip.
                            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                                Text("StackLife", Modifier.fillMaxWidth().testTag("reference-name"),
                                    color = Color.Black, style = MaterialTheme.typography.headlineMedium)
                                Text(version, Modifier.fillMaxWidth().testTag("reference-version"),
                                    color = Color.Black, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        val face = ui.onNodeWithTag("about-fumo").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        for ((label, reference) in listOf("StackLife" to "reference-name", version to "reference-version")) {
            val text = ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag("identity"))).assertIsDisplayed()
            assertTrue(text.fetchSemanticsNode().boundsInRoot.top >= face.bottom)
            assertArrayEquals("Missing or clipped glyphs in $label",
                visibleInk(ui.onNodeWithTag(reference)), visibleInk(text))
        }
    }

    /** Text's semantics action rebuilds a MultiParagraph at the parent's maximum width.
     * Its hasVisualOverflow may be true for wrap-content Text with every glyph visible.
     * Compare the actual raster, ignoring margins but retaining every line and glyph. */
    private fun visibleInk(node: SemanticsNodeInteraction): IntArray {
        val pixels = node.captureToImage().toPixelMap()
        var left = pixels.width; var right = -1
        var top = pixels.height; var bottom = -1
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].red < .5f) {
                left = minOf(left, x); right = maxOf(right, x)
                top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        }
        assertTrue("Expected rendered text ink", right >= left && bottom >= top)
        val width = right - left + 1
        val mask = IntArray(1 + width * (bottom - top + 1))
        mask[0] = width
        for (y in top..bottom) for (x in left..right) {
            if (pixels[x, y].red < .5f) mask[1 + (y - top) * width + x - left] = 1
        }
        return mask
    }

    @Test fun canvasNodeSamplesTheVisiblePaperAtItsNewPositionWithoutPaintingItsNeighbour() {
        val first = GraphNode("a", "A", "", 20, 0, 10f, 40f)
        var x by mutableFloatStateOf(first.x)
        var overlap by mutableStateOf(true)
        show {
            val node = first.copy(x = x)
            // The second body overlaps this node after it moves. Sampling the whole canvas
            // would feed its body/text back into A's blank strip.
            val other = first.copy(id = "b", title = "BBBBBBBB", x = 90f)
            val nodes = if (overlap) listOf(other, node) else listOf(node)
            GraphCanvas(GraphDetail(1, GraphSummary("g", "G"), nodes, emptyList()), null, {}, {},
                { _, _ -> }, { _, _ -> false }, Modifier.fillMaxSize())
        }
        fun probe() = ui.onNodeWithTag("canvas-node:a").captureToImage().toPixelMap().let { it[it.width / 2, 2] }
        val left = probe()
        ui.runOnIdle { x = 150f }
        val right = probe()
        assertTrue("Node did not follow the actual wallpaper/grid plane", right.red - left.red > .3f)
        ui.runOnIdle { overlap = false }
        assertEquals("An underlying node affected A's material", right.red, probe().red, .01f)
        ui.runOnIdle { x = first.x }
        assertEquals(left.red, probe().red, .02f)
    }
}
