package io.github.nakanomk.stacklife.transitions

import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nakanomk.stacklife.StackLifeApp
import io.github.nakanomk.stacklife.WorkspaceViewModel
import io.github.nakanomk.stacklife.planner.PlannerQuery
import io.github.nakanomk.stacklife.planner.Workspace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal class EntityDetailActivity : ComponentActivity() {
    private var detail: DetailTarget? = null
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("kind")
        val id = intent.getStringExtra("id")
        if (kind == null || (kind != "task" && kind != "frame") || id.isNullOrBlank()) {
            super.finish()
            return
        }
        val target = DetailTarget(kind, id)
        detail = target
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { finish() }
        val planner = (application as KitApplication).planner
        setContent {
            StackLifeApp(viewModel { WorkspaceViewModel(planner) }, initialDetail = target, onDetailBack = ::finish)
        }
    }

    override fun finishAfterTransition() = finish()

    override fun finish() {
        val target = detail ?: return super.finish()
        if (leaving || isFinishing) return
        leaving = true
        val app = application as KitApplication
        lifecycleScope.launch {
            // The covered list may no longer collect Room. Never fly into its stale geometry after a write.
            val revision = if (app.sources.containsKey(SourceKey(taskId, target))) {
                try { (app.planner.observe(PlannerQuery.Workspace).first() as Workspace).revision }
                catch (_: SQLiteException) { null }
            } else null
            app.refreshBack(this@EntityDetailActivity, target, revision)
            super@EntityDetailActivity.finish()
        }
    }
}
