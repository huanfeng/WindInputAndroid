package com.windinput.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MoreSettingsTest {

    private val bundled by lazy {
        SettingsManifest.parse(File("src/main/assets/data/settings_manifest.toml").readText())
    }

    @Test
    fun routeRoundTrip() {
        assertEquals("临时英文", moreSectionOf(moreDetail("临时英文")))
        assertNull(moreSectionOf("其它"))
        assertNull(moreSectionOf(null))
    }

    @Test
    fun summaryListsFirstTitles() {
        val items = bundled.itemsOf("input").filter { it.section == "临时英文" && it.advanced }
        val s = moreSummary(items)
        assertTrue(s, s.startsWith(items.first().title))
        assertTrue(s, s.endsWith("等 ${items.size} 项"))
    }

    /** 「更多设置」页挂在分组二级页下：进阶项只能出现在分子页的页面里，否则无处可去 */
    @Test
    fun advancedItemsLiveOnSubpagePages() {
        val pages = bundled.pages.associateBy { it.id }
        val stray = bundled.items.filter { it.advanced && pages[it.page]?.subpages != true }
        assertEquals(emptyList<String>(), stray.map { "${it.page}/${it.key}" })
    }
}
