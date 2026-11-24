package com.windinput.ime.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 官网隐私政策页（文档站 WindInputDocs 的 /privacy，覆盖电脑版与安卓版） */
internal const val WEBSITE_URL = "https://windinput.com/privacy"

/**
 * 隐私政策（纯文本）。每一条都要与代码事实一致，改功能时一起改：
 * - 联网：清单只申请了 VIBRATE 与 INTERNET，INTERNET 用于「检查更新」（用户点了才请求，UpdateCheck）
 *   与用户自己启用并配置的数据同步（SyncHub；PrivacyPolicyTest 核对 AndroidManifest）；
 * - 备份范围：见 res/xml/backup_rules.xml、data_extraction_rules.xml；
 * - 诊断信息的内容：见 [Diagnostics]。
 */
internal val PRIVACY_POLICY: List<Pair<String, String>> = listOf(
    "联网" to
        "清风输入法只在两种情况下联网：\n" +
        "· 你点「关于 → 检查更新」：向官网（windinput.com）请求一份很小的版本信息，" +
        "与用浏览器打开网页相同，请求里不带你输入的内容，也不带设备标识。\n" +
        "· 你在「数据同步」里启用并配置后：连接你自己填写的存储服务（WebDAV 或 S3），" +
        "同步的文件在本机用口令派生的密钥加密后才上传，我们不运营任何同步服务器，也看不到这些内容；" +
        "口令与存储密码只保存在本机私有目录，不进系统备份。键盘弹出时按需同步，至少间隔 10 分钟。\n" +
        "默认不启用同步，未启用时启动、打字、打开设置页都不联网。不上传你输入的任何内容，没有云输入、广告或统计上报。" +
        "网络权限可以在系统设置里随时收回，收回后输入功能不受影响，只是检查更新与同步会失败。\n" +
        "「关于」里的「反馈问题」「官网」会交给浏览器打开网页，「分享诊断信息」「导出诊断压缩包」由你在系统分享面板里选择发给谁，" +
        "这些都由你主动发起，经由别的应用完成。",
    "启用时的系统提示" to
        "在系统里启用任何第三方输入法时，系统都会提示「此输入法可能会收集您输入的所有文字，包括密码和信用卡号等个人数据」。" +
        "这是系统对所有输入法的统一提示：输入法确实能看到你打的每一个字，所以要选信得过的。" +
        "清风输入法看到的文字只用于在本机出候选、学习词频，输入法自己不会把它上传到任何地方（你主动启用的数据同步除外，同步内容先在本机加密；系统备份见下文）。" +
        "密码框与应用要求不学习的输入框里，不记词频、不造词、不计统计。",
    "数据只存本地" to
        "以下数据保存在本应用的私有目录里，其他应用读不到：\n" +
        "· 设置（键盘与输入设置）\n" +
        "· 数据同步的设置与密钥（口令派生的加密密钥、存储密码），不进系统备份\n" +
        "· 个人词库：你造的词、调整过的词频、自己添加的词条与命令短语\n" +
        "· 输入统计（每天打了多少字，不记内容）\n" +
        "· 剪贴板历史：复制过的文字，未置顶的最多保留 50 条；系统标记为敏感的内容（如密码管理器复制的密码）不记\n" +
        "· 崩溃记录：程序出错时的调用栈，不含你输入的文字",
    "系统备份" to
        "如果你在系统里开了备份（如 Google 备份或手机厂商的换机 / 云备份），设置与个人词库会随系统备份，" +
        "换机后可以恢复。剪贴板历史、崩溃记录、日志与可重建的缓存不进备份。",
    "权限用途" to
        "· 振动：按键振动反馈（强度可在设置里调，也可关闭）\n" +
        "· 网络：用于检查更新（你点了才请求），以及你启用的数据同步（只连你自己配置的存储服务）\n" +
        "· 输入法服务：系统要求输入法声明的绑定权限，只有系统能连接它\n" +
        "清风输入法不申请麦克风、通讯录、位置、存储等权限。语音输入是把你转到系统里另装的语音输入法，" +
        "本应用不录音。导入方案、导入导出词库时读写的文件，都由你在系统文件选择器里逐次选定，或由你从别的应用打开 / 分享过来。",
    "日志" to
        "程序日志写进系统日志（logcat），出厂只记警告与错误，不含输入的文字。" +
        "「高级 → 调试」里把级别调到「调试」「跟踪」时会记下输入内容，只供排查问题，用完请改回。" +
        "例外：设置首页「试一试」的测试输入框会把框里的文字记进系统日志（供自动化测试读取），" +
        "请不要在那里输入隐私内容；「分享诊断信息」与诊断压缩包不包含这部分日志。",
    "删除数据" to
        "· 剪贴板历史：剪贴板面板里逐条删除或清空\n" +
        "· 输入统计：统计页清空\n" +
        "· 个人词：词库页删除或清空\n" +
        "· 全部数据：系统设置 → 应用 → 清风输入法 → 存储 → 清除数据；卸载本应用也会删除全部数据",
)

@Composable
internal fun PrivacySection() {
    val context = LocalContext.current
    for ((title, body) in PRIVACY_POLICY) {
        SectionTitle(title)
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = body,
                modifier = Modifier.padding(16.dp),
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    SectionTitle("更多")
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("官网隐私政策", WEBSITE_URL, icon = SettingsIcons.website) {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))) }
        }
    }
}
