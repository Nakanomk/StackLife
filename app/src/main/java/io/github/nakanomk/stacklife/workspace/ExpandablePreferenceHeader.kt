/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Adapted from AnimateBounds_usingKeyframes; see SettingsChrome-Notices.txt.
 */
package io.github.nakanomk.stacklife.workspace

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.nakanomk.stacklife.R
import io.github.nakanomk.stacklife.appearance.LocalMotionEnabled

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ExpandablePreferenceHeader(
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    leading: @Composable () -> Unit, title: @Composable () -> Unit, summary: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, expandedContent: @Composable () -> Unit,
) {
    val motion = LocalMotionEnabled.current
    val state = stringResource(if (expanded) R.string.assistant_expanded else R.string.assistant_collapsed)
    Column(modifier.fillMaxWidth().then(if (motion) Modifier.animateContentSize(
        MaterialTheme.motionScheme.defaultSpatialSpec(), alignment = Alignment.TopStart) else Modifier)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button) { onExpandedChange(!expanded) }
            .semantics { stateDescription = state }.heightIn(min = 52.dp).padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).testTag("expandable-preference-leading"), contentAlignment = Alignment.Center) {
                if (motion) androidx.compose.animation.AnimatedVisibility(visible = !expanded, enter = fadeIn(), exit = fadeOut()) { leading() }
                else if (!expanded) leading()
            }
            Column(Modifier.weight(1f)) {
                LookaheadScope {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // Keep one gutter of travel even for a title that wraps at 200%.
                        // Absolute endpoints intentionally make expansion rightward in RTL, too.
                        val endpoint = if (expanded) AbsoluteAlignment.TopRight else AbsoluteAlignment.TopLeft
                        Box(Modifier.align(endpoint).then(if (motion) Modifier.animateBounds(this@LookaheadScope) else Modifier)
                            .widthIn(max = (maxWidth - 8.dp).coerceAtLeast(0.dp)).testTag("expandable-preference-title")) { title() }
                    }
                }
                if (!expanded) summary()
            }
            Box(Modifier.size(20.dp)) {
                if (!expanded) Icon(painterResource(R.drawable.ic_chevron_right), null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (expanded) expandedContent()
    }
}
