package io.github.nakanomk.stacklife.transitions

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.workspace.SharedModifier

/** Implemented only in .gallery and .bench. Ordinary builds keep their existing navigation. */
internal interface TaskCardTrial {
    @Composable fun Show(stack: NavBackStack<NavKey>, model: WorkspaceViewModel, edit: (String, String?, Long) -> Unit,
        home: @Composable (TrialQueueLinks) -> Unit)
}

internal class TrialQueueLinks(val open: (String) -> Unit, val cards: SharedModifier)
