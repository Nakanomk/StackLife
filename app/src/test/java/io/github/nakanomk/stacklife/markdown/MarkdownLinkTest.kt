package io.github.nakanomk.stacklife.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLinkTest {
    @Test fun linksNeedAnExplicitExternalSchemeAndDestination() {
        for (url in listOf("https://example.org/a#b", "HTTP://example.org:80", "mailto:student@example.org")) {
            assertTrue(url, allowedMarkdownLink(url))
        }
        for (url in listOf("//example.org", "http:notes", "https:///a", "mailto:", "javascript:noop", "intent:notes",
            "content://example/notes", "file:///notes", "data:text/plain,note", "https://example.org/\nnotes", "https://example.org\\notes")) {
            assertFalse(url, allowedMarkdownLink(url))
        }
    }
}
