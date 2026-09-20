package com.maslarski.iptv.data.remote.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleCleanerTest {

    @Test
    fun `strips provider prefix quality tags and extracts year`() {
        val c = TitleCleaner.clean("EN - Inception (2010) [4K] HD")
        assertEquals("Inception", c.title)
        assertEquals(2010, c.year)
    }

    @Test
    fun `keeps plain titles intact`() {
        val c = TitleCleaner.clean("The Godfather")
        assertEquals("The Godfather", c.title)
        assertNull(c.year)
    }

    @Test
    fun `drops season markers from series titles`() {
        val c = TitleCleaner.clean("Breaking Bad S01 MULTI")
        assertEquals("Breaking Bad", c.title)
    }

    @Test
    fun `replaces dots and underscores with spaces`() {
        assertEquals("Blade Runner 2049", TitleCleaner.clean("Blade.Runner.2049.1080p.WEB-DL").title)
    }

    @Test
    fun `normalise ignores punctuation and case`() {
        assertEquals(TitleCleaner.normalise("Spider-Man: No Way Home"), TitleCleaner.normalise("spider man no way home"))
    }
}
