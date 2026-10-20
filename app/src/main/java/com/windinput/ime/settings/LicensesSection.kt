package com.windinput.ime.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.tomlj.Toml
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 一条第三方声明。格式见 assets/licenses/notices.toml 头部注释 */
data class Notice(
    val group: String,
    val name: String,
    val usage: String,
    val license: String,
    val url: String,
)

object Notices {
    /** assets 下的目录：notices.toml、各许可证全文（<id>.txt）、Rust 依赖清单 */
    const val DIR = "licenses"

    /** 有全文的许可证 id（= DIR 下的文件名去掉 .txt） */
    val FULL_TEXTS = setOf("GPL-3.0", "LGPL-3.0", "Apache-2.0", "MIT", "MPL-2.0")

    fun parse(toml: String): List<Notice> {
        val result = Toml.parse(toml)
        require(!result.hasErrors()) { "TOML errors: ${result.errors()}" }
        val arr = result.getArray("notice") ?: return emptyList()
        return (0 until arr.size()).map { i ->
            val t = arr.getTable(i)
            val name = requireNotNull(t.getString("name")) { "notice 缺少 name" }
            Notice(
                group = t.getString("group") ?: "",
                name = name,
                usage = t.getString("usage") ?: "",
                license = requireNotNull(t.getString("license")) { "$name 缺少 license" },
                url = t.getString("url") ?: "",
            )
        }
    }
}

/**
 * 开源许可：随包分发的词库数据与程序库逐条列出（名称、用途、许可证、来源），
 * 点开许可证看全文。拼音词库是 GPL-3.0，原文分发并附带许可证全文是分发条件
 * （docs/architecture.md §3.5）。Rust 依赖条目多，单独一张卡按行列出。
 */
@Composable
internal fun LicensesSection() {
    val context = LocalContext.current
    val notices = remember {
        runCatching {
            Notices.parse(context.assets.open("${Notices.DIR}/notices.toml").bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
    }
    var openText by remember { mutableStateOf<String?>(null) }
    var showCrates by remember { mutableStateOf(false) }

    for ((group, items) in notices.groupBy { it.group }) {
        SectionTitle(group)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                for (n in items) {
                    val hasText = n.license in Notices.FULL_TEXTS
                    NavRow(
                        n.name,
                        "${n.usage}\n${n.license}${if (hasText) "（点开看全文）" else ""} · ${n.url}",
                        if (hasText) {
                            { openText = if (openText == n.license) null else n.license }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        val license = openText
        if (license != null && items.any { it.license == license }) {
            LicenseText(assetText("${Notices.DIR}/$license.txt"))
        }
    }

    SectionTitle("Rust 依赖库")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            NavRow(
                if (showCrates) "收起" else "展开全部 Rust 依赖",
                "输入法核心链接的第三方 crate 及各自的许可证（MIT / Apache-2.0 / MPL-2.0 等，全文见上）",
            ) { showCrates = !showCrates }
            if (showCrates) {
                LicenseText(
                    assetText("${Notices.DIR}/rust-crates.txt")
                        .lines()
                        .filterNot { it.startsWith("#") || it.isBlank() }
                        .joinToString("\n") { it.replace('\t', ' ') },
                )
            }
        }
    }
}

@Composable
private fun assetText(path: String): String {
    val context = LocalContext.current
    return remember(path) {
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
            .getOrDefault("（缺少 $path）")
    }
}

@Composable
private fun LicenseText(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
