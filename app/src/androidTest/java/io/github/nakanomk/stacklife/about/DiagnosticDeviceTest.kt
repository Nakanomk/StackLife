package io.github.nakanomk.stacklife.about

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nakanomk.stacklife.MainActivity
import io.github.nakanomk.stacklife.planner.CommitFailure
import io.github.nakanomk.stacklife.planner.CommitResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DiagnosticDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun measure(): CaptureState.Finished {
        val ready = CountDownLatch(1)
        var animate = false
        lateinit var view: View
        val drawn = CountDownLatch(12)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            activity.onActivity { a ->
                view = object : View(a) {
                    override fun onDraw(canvas: Canvas) {
                        canvas.drawColor(if (drawn.count % 2 == 0L) 0xff333333.toInt() else 0xff666666.toInt())
                        ready.countDown()
                        if (animate) {
                            drawn.countDown()
                            if (drawn.count > 0) postInvalidateOnAnimation()
                        }
                    }
                }
                a.addContentView(view, ViewGroup.LayoutParams(32, 32))
            }
            assertTrue("Window did not draw its first frame", ready.await(12, TimeUnit.SECONDS))
            activity.onActivity {
                AppDiagnostics.start()
                assertSame(CaptureState.Recording, AppDiagnostics.capture.value)
                animate = true
                view.invalidate()
            }
            assertTrue("Window did not draw new frames", drawn.await(12, TimeUnit.SECONDS))
            activity.moveToState(Lifecycle.State.STARTED)
            val measured = AppDiagnostics.capture.value as CaptureState.Finished
            assertTrue(measured.paused)
            assertTrue("No real frame timing reports", measured.report.frames > 0)
            assertTrue(measured.report.worstNs > 0)
            return measured
        }
    }

    @Test fun realMainWindowProducesTimingAndPauseStopsIt() {
        val sample = measure().report
        val csv = buildString { sample.writeTo(this) }
        assertTrue(csv.lineSequence().any { Regex("[1-9][0-9]*,[1-9][0-9]*,-?[0-9]+").matches(it) })
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun exportsBothSnapshotsIntoRealContentProviderDocuments() {
        val frames = DiagnosticDocument.Frames(measure())
        AppDiagnostics.recordCommit(CommitResult.Rejected(CommitFailure.STORAGE, "private URL https://private.invalid and session key abc"))
        val events = DiagnosticDocument.Events(AppDiagnostics.journal)
        for (document in listOf(frames, events)) withTestDocument(context) { uri ->
            val model = DiagnosticExportModel()
            instrumentation.runOnMainSync {
                model.pick(document) { assertEquals(document.filename, it) }
                assertEquals(ExportStatus.PICKING, model.status.value)
                model.result(context, uri)
            }
            awaitTerminal(model, ExportStatus.WRITTEN)
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            assertTrue(text.contains("version_code=${installedVersion(context).code}"))
            assertFalse(text.contains("private.invalid"))
            assertFalse(text.contains("session key"))
            if (document === frames) {
                assertTrue(text.contains("stopped_by=activity_pause"))
                assertTrue(text.lineSequence().any { Regex("[1-9][0-9]*,[1-9][0-9]*,-?[0-9]+").matches(it) })
            } else assertTrue(text.lineSequence().any { it.endsWith(",COMMIT_STORAGE_FAILURE") })
        }
    }

    @Test fun cancellationExpiredResultsAndUnwritableUriNeverClaimSuccess() {
        val model = DiagnosticExportModel()
        val events = DiagnosticDocument.Events(DiagnosticJournal())
        val denied = Uri.parse("content://io.github.nakanomk.stacklife.missing/document")
        instrumentation.runOnMainSync {
            model.pick(events) {}
            model.result(context, null)
            assertEquals(ExportStatus.CANCELED, model.status.value)
            model.result(context, denied)
            assertEquals(ExportStatus.EXPIRED, model.status.value)
            model.pick(events) {}
            model.result(context, denied)
        }
        awaitTerminal(model, ExportStatus.FAILED)
    }

    private fun awaitTerminal(model: DiagnosticExportModel, expected: ExportStatus) = runBlocking {
        val actual = withTimeout(10_000) { model.status.first { it != ExportStatus.PICKING && it != ExportStatus.WRITING } }
        assertEquals(expected, actual)
    }

    @androidx.annotation.RequiresApi(29)
    private fun withTestDocument(context: Context, check: (Uri) -> Unit) {
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "StackLife-test-${UUID.randomUUID()}.txt")
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try { check(uri) } finally { resolver.delete(uri, null, null) }
    }
}
