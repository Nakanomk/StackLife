// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Adapted from Miuix's Kyant-based LiquidGlassNavigationBar / CombinedBackdrop.
package io.github.nakanomk.stacklife.appearance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

@Composable
internal fun rememberCombinedBackdrop(base: Backdrop, overlay: Backdrop): Backdrop = remember(base, overlay) {
    object : Backdrop {
        override val isCoordinatesDependent = base.isCoordinatesDependent || overlay.isCoordinatesDependent
        override val offsetResidualX get() = base.offsetResidualX
        override val offsetResidualY get() = base.offsetResidualY
        override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?, downscaleFactor: Float) {
            with(base) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            with(overlay) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        }
    }
}
