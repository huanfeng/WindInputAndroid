package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun newerPatchAndMinor() {
        assertTrue(UpdateCheck.isNewer("0.5.3", "0.5.4"))
        assertTrue(UpdateCheck.isNewer("0.5.9", "0.6.0"))
        assertFalse(UpdateCheck.isNewer("0.5.4", "0.5.3"))
        assertFalse(UpdateCheck.isNewer("0.5.3", "0.5.3"))
    }

    @Test fun releaseBeatsItsOwnPrerelease() {
        assertTrue(UpdateCheck.isNewer("0.5.4-dev.3", "0.5.4"))
        assertFalse(UpdateCheck.isNewer("0.5.4", "0.5.4-dev.3"))
    }

    @Test fun devBuildAheadOfLatestReleaseIsNotOutdated() {
        assertFalse(UpdateCheck.isNewer("0.5.4-dev.3", "0.5.3"))
    }

    @Test fun prereleaseCompareBySuffixNumber() {
        assertTrue(UpdateCheck.isNewer("0.5.4-dev.3", "0.5.4-dev.10"))
        assertFalse(UpdateCheck.isNewer("0.5.4-dev.10", "0.5.4-dev.3"))
    }

    @Test fun unparsableMeansNoUpdate() {
        assertFalse(UpdateCheck.isNewer("0.5.3", "abc"))
        assertFalse(UpdateCheck.isNewer("", "0.5.4"))
    }

    @Test fun parseValid() {
        val l = UpdateCheck.parse(
            """{"version":"0.5.3","apkUrl":"https://dl.windinput.com/WindInputAndroid-0.5.3.apk","size":43929240,"releaseNotesUrl":"https://github.com/x"}""",
        )
        assertNotNull(l)
        assertEquals("0.5.3", l!!.version)
        assertEquals(43929240L, l.size)
    }

    @Test fun parseRejectsHtmlFallbackAndNonHttps() {
        assertNull(UpdateCheck.parse("<!doctype html><html></html>"))
        assertNull(UpdateCheck.parse("""{"version":"0.5.3","apkUrl":"http://x/a.apk"}"""))
        assertNull(UpdateCheck.parse("""{"apkUrl":"https://x/a.apk"}"""))
    }
}
