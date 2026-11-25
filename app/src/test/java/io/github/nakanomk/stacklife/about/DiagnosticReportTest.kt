package io.github.nakanomk.stacklife.about

import io.github.nakanomk.stacklife.planner.CommitFailure
import io.github.nakanomk.stacklife.planner.CommitResult
import org.junit.Assert.*
import org.junit.Test

class DiagnosticReportTest {
    @Test fun exportingARejectedCommitCannotRevealItsMessageOrAnAppliedRevision() {
        val journal = DiagnosticJournal()
        val sensitive = "private title; Bearer secret; https://private.invalid/notes?id=23456"
        for (reason in CommitFailure.entries) journal.record(CommitResult.Rejected(reason, sensitive).diagnosticEvent())
        journal.record(CommitResult.Applied(87654321, sensitive).diagnosticEvent())
        val rows = journal.snapshot().lines().drop(3).filter(String::isNotEmpty)
        assertEquals(listOf("COMMIT_STALE_PREVIEW", "COMMIT_UNKNOWN_PROPOSAL", "COMMIT_STORAGE_FAILURE", "COMMIT_APPLIED"),
            rows.map { it.substringAfterLast(',') })
        rows.forEach { assertTrue(it.matches(Regex("[0-9]+,[0-9]+,[A-Z_]+"))) }
        assertFalse(journal.snapshot().contains("87654321"))
        assertFalse(journal.snapshot().contains(sensitive))
    }

    @Test fun journalRetainsOnlyTheLatestEventsInSequenceOrder() {
        val journal = DiagnosticJournal()
        repeat(DiagnosticJournal.CAPACITY + 7) { journal.record(DiagnosticEvent.WINDOW_RESUMED) }
        val rows = journal.snapshot().trimEnd().lines().drop(3)
        assertEquals((8..DiagnosticJournal.CAPACITY + 7).toList(), rows.map { it.substringBefore(',').toInt() })
    }

    @Test fun unavailableDeadlinesAndLostReportsAreNotEstimatedAsMissedFrames() {
        val report = FrameReport()
        report.accept(500L, -1L, -1L, 4)
        report.accept(600L, 2_000L, -1L, 1)
        assertEquals(1L, report.frames)
        assertEquals(5L, report.lostReports)
        val text = buildString { report.writeTo(this) }
        assertTrue(text.contains("frames_over_deadline=unavailable"))
        assertTrue(text.contains("600,2000,-1"))
    }

    @Test fun aggregatesCoverAllFramesButExportContainsOnlyTheChronologicalTimingTail() {
        val report = FrameReport()
        repeat(FrameReport.CAPACITY + 1) { i -> report.accept(i.toLong(), if (i == 0) 100 else 10, 20, 0) }
        val text = buildString { report.writeTo(this) }
        val rows = text.substringAfter("intended_vsync_ns,total_duration_ns,deadline_ns\n").trimEnd().lines()
        assertEquals(FrameReport.CAPACITY, rows.size)
        assertEquals("1,10,20", rows.first())
        assertEquals("${FrameReport.CAPACITY},10,20", rows.last())
        assertEquals(FrameReport.CAPACITY + 1L, report.frames)
        assertEquals(report.frames, report.withDeadline)
        assertEquals(100L, report.worstNs)
        assertEquals(1L, report.overDeadline)
    }
}
