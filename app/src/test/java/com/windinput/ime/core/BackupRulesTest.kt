package com.windinput.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** 系统备份规则：旧版（fullBackupContent）与新版（dataExtractionRules 两段）排除项一致，且挡住隐私数据 */
class BackupRulesTest {

    private fun excludes(file: String, parent: String? = null): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/$file"))
        val root: Element = if (parent == null) {
            doc.documentElement
        } else {
            doc.getElementsByTagName(parent).item(0) as Element
        }
        val nodes = root.getElementsByTagName("exclude")
        return (0 until nodes.length).map { i ->
            val e = nodes.item(i) as Element
            "${e.getAttribute("domain")}:${e.getAttribute("path")}"
        }.toSet()
    }

    @Test
    fun allThreeRuleSetsAgree() {
        val legacy = excludes("backup_rules.xml")
        assertEquals(legacy, excludes("data_extraction_rules.xml", "cloud-backup"))
        assertEquals(legacy, excludes("data_extraction_rules.xml", "device-transfer"))
    }

    @Test
    fun clipboardHistoryAndCachesAreExcluded() {
        val rules = excludes("backup_rules.xml")
        for (must in listOf("file:clipboard.json", "file:data/", "file:rust/data/WindInput/cache/")) {
            assertTrue("缺少排除项 $must", must in rules)
        }
    }

    @Test
    fun userRootOnlyExcludesTheProcessLock() {
        val ext = excludes("backup_rules.xml").filter { it.startsWith("external:") }
        assertEquals(setOf("external:userdata.redb.state/lock"), ext.toSet())
    }

    /** 设置与个人词库在用户根（external 域），要进备份：别一刀把整个 external 域排掉 */
    @Test
    fun settingsAndUserDictsStayIncluded() {
        val rules = excludes("backup_rules.xml")
        for (keep in listOf("external:", "external:mobile/", "external:data/", "external:config.toml", "external:userdata.redb.state/")) {
            assertTrue("$keep 不该被排除", keep !in rules)
        }
    }
}
