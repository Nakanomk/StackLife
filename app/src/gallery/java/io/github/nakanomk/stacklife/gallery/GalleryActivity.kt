/*
 * Copyright 2021 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 * Adapted from the AndroidX Material3 catalog at 841411143e849ef66c3e8d49cc0dd70d62d7a3c0.
 * StackLife: stable example IDs, local inputs, live production composables and offline source notes.
 */
package io.github.nakanomk.stacklife.gallery

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.StackLifeTheme
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.appearance.*
import io.github.nakanomk.stacklife.i18n.LocalizedActivity
import io.github.nakanomk.stacklife.i18n.uiText
import io.github.nakanomk.stacklife.markdown.LocalMarkdownReadCache
import io.github.nakanomk.stacklife.markdown.MarkdownReadCache
import io.github.nakanomk.stacklife.planner.Appearance
import kotlinx.coroutines.CancellationException

import io.github.nakanomk.stacklife.updates.STACKLIFE_REPOSITORY
internal enum class GalleryGroup { COMPONENT, MOTION, MATERIAL }

internal class GalleryEntry(val id: String, val group: GalleryGroup, val name: String, val file: String, val source: String,
    val license: String, val adaptation: String, val exercise: String, val content: @Composable () -> Unit)

internal val LocalGalleryWorkspace = staticCompositionLocalOf<WorkspaceViewModel> { error("Missing Gallery workspace") }
internal val LocalOpenGallerySettings = staticCompositionLocalOf<() -> Unit> { error("Missing Gallery theme picker") }

class GalleryActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val gallery = application as GalleryApplication
        val prefs = getSharedPreferences("catalog", MODE_PRIVATE)
        setContent {
            var options by remember { mutableStateOf(GalleryOptions.load(prefs)) }
            val appearance = remember(options) { Appearance(theme = options.theme, reduceMotion = options.still,
                highContrast = options.contrast, glass = options.glass) }
            StackLifeTheme(appearance) {
                val model = viewModel { WorkspaceViewModel(gallery.planner) }
                var loaded by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(gallery) {
                    try { gallery.ready.await(); loaded = true }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.localizedMessage ?: e.javaClass.simpleName }
                }
                Surface(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
                    if (!loaded) Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
                        error?.let { Text(uiText("Cannot prepare examples: $it", "示例准备失败：$it")) } ?: CircularProgressIndicator()
                    } else CompositionLocalProvider(LocalGalleryWorkspace provides model,
                        LocalMarkdownReadCache provides remember { MarkdownReadCache() },
                        LocalBackgroundStore provides gallery.backgrounds, LocalPlannerGlassAllowed provides true,
                        LocalGlassLight provides rememberGlassLight()) {
                        // After process death the old entry IDs and drafts point into a discarded in-memory DB.
                        key(gallery.session) { Catalog(options, { options = it; it.save(prefs) }) }
                    }
                }
            }
        }
    }
}

private val GalleryGroup.label: String get() = when (this) {
    GalleryGroup.COMPONENT -> uiText("Components", "组件")
    GalleryGroup.MOTION -> uiText("Motion", "动效")
    GalleryGroup.MATERIAL -> uiText("Material", "材质")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Catalog(options: GalleryOptions, update: (GalleryOptions) -> Unit) {
    val entries = remember { componentExamples + motionExamples }
    var group by rememberSaveable { mutableStateOf<GalleryGroup?>(null) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    val entry = entries.firstOrNull { it.id == selected }
    val back = { if (selected != null) selected = null else group = null }
    BackHandler(enabled = group != null, onBack = back)
    val title = entry?.name ?: group?.label ?: "StackLife Gallery"
    val listState = key(group) { rememberLazyListState() }
    // Size and direction overrides stay inside the example, so controls to undo them remain reachable.
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        TopAppBar(title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            windowInsets = WindowInsets(0), navigationIcon = {
                if (group != null) IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), uiText("Back", "返回")) }
            }, actions = {
                if (entry != null) IconButton(onClick = { info = true }) {
                    Icon(painterResource(R.drawable.ic_info), uiText("Source and exercise", "来源与验收用例"))
                }
                IconButton(onClick = { sheet = true }) { Icon(painterResource(R.drawable.ic_palette), uiText("Gallery options", "预览设置")) }
            })
        if (entry == null) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                val current = group
                if (current == null) items(GalleryGroup.entries, key = { it.name }) { category ->
                    ListItem(headlineContent = { Text(category.label) }, supportingContent = {
                        val n = entries.count { it.group == category }
                        Text(uiText(if (n == 1) "1 example" else "$n examples", "$n 个示例"))
                    }, modifier = Modifier.testTag("category:${category.name}").clickable { group = category })
                } else items(entries.filter { it.group == current }, key = { it.id }) { example ->
                    ListItem(headlineContent = { Text(example.name) }, supportingContent = {
                        Text(example.exercise, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }, modifier = Modifier.testTag("example:${example.id}").clickable { selected = example.id })
                }
            }
        } else {
            val deviceDensity = LocalDensity.current
            val platform = LocalPlatformAppearance.current
            val density = if (!options.large) deviceDensity else remember(deviceDensity.density) { Density(deviceDensity.density, 2f) }
            val env = if (options.noAcceleration) remember(platform) { platform.copy(accelerated = false) } else platform
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box((if (options.narrow) Modifier.widthIn(max = 320.dp) else Modifier).fillMaxSize().clipToBounds()) {
                    CompositionLocalProvider(LocalDensity provides density, LocalPlatformAppearance provides env,
                        LocalLayoutDirection provides if (options.rtl) LayoutDirection.Rtl else LocalLayoutDirection.current,
                        LocalOpenGallerySettings provides { sheet = true }) {
                        WallpaperHost(LocalAppearance.current.background) {
                            PlannerPopupHost { key(entry.id) { entry.content() } }
                        }
                    }
                }
            }
        }
    }
    if (sheet) GalleryThemePicker(options, update) { sheet = false }
    if (info && entry != null) {
        val context = LocalContext.current
        val url = "$STACKLIFE_REPOSITORY/blob/v0.11.2/app/src/main/java/io/github/nakanomk/stacklife/${entry.file}"
        var noBrowser by remember(entry) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { info = false },
        title = { Text(uiText("Source and exercise", "来源与验收用例")) },
        text = { SelectionContainer { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.source); Text(entry.license); Text(entry.adaptation)
            TextButton(onClick = {
                noBrowser = try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); false }
                catch (_: ActivityNotFoundException) { true }
                catch (_: SecurityException) { true }
            }) { Text(uiText("Open 0.11.2 source in browser", "用浏览器查看 0.11.2 源码")) }
            if (noBrowser) Text(url)
            Text(uiText("Try it", "验收输入"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(entry.exercise)
        } } }, confirmButton = { TextButton(onClick = { info = false }) { Text(uiText("Close", "关闭")) } })
    }
}
