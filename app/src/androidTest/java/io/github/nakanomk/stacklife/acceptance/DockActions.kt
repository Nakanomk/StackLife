package io.github.nakanomk.stacklife.acceptance

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import org.junit.Assert.assertTrue

/** A scrollable draws under the floating composer. Being inside its layout bounds is not enough to tap. */
internal fun SemanticsNodeInteractionsProvider.tapAboveDock(list: SemanticsNodeInteraction, target: SemanticsMatcher) {
    list.performScrollToNode(target)
    val control = onNode(target).performScrollTo()
    val dock = onNodeWithTag("assistant-composer").fetchSemanticsNode().boundsInRoot.top
    val bounds = control.fetchSemanticsNode().boundsInRoot
    if (bounds.center.y >= dock) list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, bounds.bottom - dock + 1f) }
    assertTrue("Target behind the composer: $target", control.fetchSemanticsNode().boundsInRoot.center.y < dock)
    control.performTouchInput { click() }
}
