package com.windinput.ime.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windinput.ime.WindInputApp
import com.windinput.ime.core.EngineCmd
import com.windinput.ime.core.ImeSession
import com.windinput.ime.core.LayoutEntry
import com.windinput.ime.core.SchemaEntry
import com.windinput.ime.core.UserConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.io.File

/**
 * 设置页：由设置清单（assets/data/settings_manifest.toml）驱动生成，结构见
 * docs/mobile-ux-reference.md §6。
 *
 * 层级：首页四组（开始使用 / 清风引擎 / 手机键盘 / 其他），每行带图标；点进去是一页。
 * 项多的页（方案、输入，清单里 `subpages = true`）再分一层：页内每个 section 一个二级入口，
 * 点进去才是那组开关——首页和一级页都在一屏内看完，不用在一条长列表里找。
 * 方案列表、统计、词库、关于是代码画的专属区块，其余全部来自清单。
 *
 * 用到的新版系统能力：edge-to-edge（内容画到系统栏下，标题栏与底部留出 insets）、
 * 预测性返回（Android 13+ 侧滑返回时当前页跟手右移，松手才真正返回）、
 * 莫奈取色（Android 12+ 跟随壁纸主色，低版本回落 miuix 默认色）。
 */
class SettingsActivity : ComponentActivity() {

    /**
     * 输入法启用 / 当前状态。回到本页时重查：去系统设置启用后按返回是 onResume，
     * 而「切换输入法」弹的是系统对话框，本页不 pause、只失焦——两处都要查。
     */
    private val imeSetup = mutableStateOf(ImeSetup.ENABLE)

    private fun refreshImeSetup() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList.orEmpty().any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        imeSetup.value = ImeSetup.of(enabled, current)
    }

    override fun onResume() {
        super.onResume()
        refreshImeSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshImeSetup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val dirs = (application as WindInputApp).dataDirs
        val userConfig = UserConfig(File(dirs.userDir, "config.toml"))
        val manifest = SettingsManifest.load(this)
        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        // 莫奈取色要 Android 12（S）的系统调色板；更早的系统用 miuix 默认配色，深浅色照样跟系统
        val theme = ThemeController(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ColorSchemeMode.MonetSystem else ColorSchemeMode.System,
        )

        setContent {
            MiuixTheme(controller = theme) {
                SettingsApp(
                    setup = imeSetup.value,
                    manifest = manifest,
                    userConfig = userConfig,
                    versionName = versionName,
                    actions = SettingsActions(
                        enableIme = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                        // 国内系统把「切换输入法」藏得很深（导航栏没有键盘图标，要进好几层设置），
                        // 系统选择框本身却人人可调，这里直接弹
                        switchIme = {
                            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                                ?.showInputMethodPicker()
                        },
                        openTestPad = { startActivity(Intent(this, TestPadActivity::class.java)) },
                    ),
                )
            }
        }
    }
}

/** 首页「开始使用」的三个动作 */
private class SettingsActions(
    val enableIme: () -> Unit,
    val switchIme: () -> Unit,
    val openTestPad: () -> Unit,
)

/** 首页「开始使用」处在哪一步（docs/mobile-ux-reference.md §6.3） */
enum class ImeSetup {
    /** 第 1 步：去系统设置里启用 */
    ENABLE,

    /** 第 2 步：已启用，还没切成当前输入法 */
    SWITCH,

    /** 已在使用 */
    READY;

    companion object {
        fun of(enabled: Boolean, current: Boolean): ImeSetup = when {
            current -> READY // 能是当前输入法就一定已启用（个别系统的启用列表查询会漏）
            enabled -> SWITCH
            else -> ENABLE
        }
    }
}

/**
 * 导航位置：首页（[page] 为 null）→ 一级页 → 二级页（[sub]）。最多两层，用两个字符串
 * 存住即可（转屏、进程回收后 rememberSaveable 能恢复），不必上导航库。
 */
private data class Route(val page: String? = null, val sub: String? = null) {
    val depth: Int get() = if (page == null) 0 else if (sub == null) 1 else 2

    fun up(): Route = if (sub != null) copy(sub = null) else Route()

    /** 存页面状态用的键（SaveableStateHolder 要求可放进 Bundle） */
    val key: String get() = "$page/$sub"
}

/** 词库页的两个二级页（不来自清单，代码画）；名字即路由，DictSection 的入口用同一份 */
internal const val DICT_WORDS = "我的词条"
internal const val DICT_ADD = "添加词条"

/** 方案页里代码画的二级页 */
internal const val SUB_LAYOUTS = "双拼布局"

/** 代码画的二级页名：清单里的 section 不能叫这些，否则会被遮住（SettingsManifestTest 核对） */
internal val RESERVED_SUBPAGES = setOf(DICT_WORDS, DICT_ADD, SUB_LAYOUTS)

/**
 * 方案与双拼布局的显示态，挂在设置页顶层（不随某一页的组合进出而丢）。
 *
 * **只在后台线程碰核心**：第一次切到某方案时核心要现编词库（白霜拼音模拟器上近 5 秒），
 * 这期间主线程若去取方案列表，可能等在核心的锁上——切完方案马上进出别的页、回首页看摘要，
 * 都会走到这里。[switching] 也放在这一层：页面重进后照样知道「正在切」，不会再排一次切换。
 */
private class SchemaState(private val core: ImeSession, private val scope: kotlinx.coroutines.CoroutineScope) {
    var schemas by mutableStateOf<List<SchemaEntry>>(emptyList())
    var layouts by mutableStateOf<List<LayoutEntry>>(emptyList())
    var switching by mutableStateOf<String?>(null)

    suspend fun refresh() {
        val (s, l) = withContext(Dispatchers.IO) { core.schemas() to core.shuangpinLayouts() }
        schemas = s
        layouts = l
    }

    /**
     * 切方案 / 换双拼布局，做完回读真值（切换可能被引擎拒绝，本地不先行打勾）。
     * 在设置页顶层的作用域里跑：发起切换的那一页离开了，切换照样做完、状态照样复位。
     */
    fun switchTo(label: String, cmd: EngineCmd) {
        if (switching != null) return
        switching = label
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.runCommand(cmd) }
                refresh()
            } finally {
                switching = null
            }
        }
    }

    val activeName: String? get() = schemas.firstOrNull { it.active }?.let { it.name.ifEmpty { it.id } }
}

@Composable
private fun SettingsApp(
    setup: ImeSetup,
    manifest: SettingsManifestData,
    userConfig: UserConfig,
    versionName: String,
    actions: SettingsActions,
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<ImeSession?>(null) }
    var values by remember { mutableStateOf(SettingsValues(userConfig, null)) }
    var routePage by rememberSaveable { mutableStateOf<String?>(null) }
    var routeSub by rememberSaveable { mutableStateOf<String?>(null) }
    val route = Route(routePage, routeSub)
    // 每一层的可保存状态（滚动位置、标题栏收起、搜索词…）按路由存住：进下一级再返回，
    // 上一级停在离开时的位置，而不是回到顶上
    val stateHolder = rememberSaveableStateHolder()
    fun go(r: Route) {
        // 退出的那一层不再留状态：下次进来从顶上开始，只有「还在栈里」的上级保持原位
        if (r.depth < route.depth) stateHolder.removeState(route.key)
        if (r.depth == 0 && route.depth == 2) stateHolder.removeState(route.up().key)
        routePage = r.page
        routeSub = r.sub
    }

    // 核心构造在后台线程（首次约 1 秒）。未就绪时键盘项照常可改，引擎项先不显示
    LaunchedEffect(Unit) {
        val (s, v) = withContext(Dispatchers.IO) {
            val core = CoreAccess.obtainCore(context)
            CoreAccess.obtain(context) to SettingsValues(userConfig, core).also { it.load() }
        }
        session = s
        values = v
    }

    val appScope = rememberCoroutineScope()
    val schemaState = remember(session) { session?.let { SchemaState(it, appScope) } }
    LaunchedEffect(schemaState) { schemaState?.refresh() }

    // 页面切换走可拖动的过渡：平时 animateTo 正常播放；预测性返回时按手势进度 seekTo 上一层，
    // 侧滑过程中上一层就画在下面、跟手滑出来（系统返回预览的样子），松手才真正退一层，
    // 中途放弃就弹回当前页
    val transitionState = remember { SeekableTransitionState(route) }
    LaunchedEffect(route) { transitionState.animateTo(route) }
    PredictiveBackHandler(enabled = route.depth > 0) { events ->
        val target = route.up()
        try {
            events.collect { transitionState.seekTo(it.progress, target) }
            go(target)
        } catch (e: CancellationException) {
            // 本协程已取消，回弹动画另起一个
            appScope.launch { transitionState.animateTo(route) }
            throw e
        }
    }
    val transition = rememberTransition(transitionState, label = "settings-route")

    transition.AnimatedContent(
        transitionSpec = {
            // 层叠式（HyperOS / 系统设置同款）：深的一层总压在上面、整页从右边进出，
            // 浅的一层在下面只带一点视差。侧滑返回时就是当前页被手指拖开、露出下面的上一级
            val forward = targetState.depth > initialState.depth
            if (forward) {
                (slideInHorizontally(tween(300)) { it } togetherWith
                    slideOutHorizontally(tween(300)) { -it / 4 })
                    .apply { targetContentZIndex = 1f }
            } else {
                (slideInHorizontally(tween(300)) { -it / 4 } togetherWith
                    slideOutHorizontally(tween(300)) { it })
                    .apply { targetContentZIndex = -1f }
            }
        },
    ) { r ->
        stateHolder.SaveableStateProvider(r.key) {
        Screen(
            route = r,
            title = screenTitle(manifest, r),
            onBack = if (r.depth > 0) ({ go(r.up()) }) else null,
        ) {
            val page = manifest.pages.firstOrNull { it.id == r.page }
            when {
                page == null -> HomeScreen(setup, manifest, values, schemaState, versionName, actions) { go(Route(it)) }
                r.sub == null -> PageScreen(manifest, page, values, session, schemaState, versionName) { go(r.copy(sub = it)) }
                else -> SubScreen(manifest, page, r.sub, values, session, schemaState)
            }
        }
        }
    }
}

private fun screenTitle(manifest: SettingsManifestData, r: Route): String =
    r.sub ?: manifest.pages.firstOrNull { it.id == r.page }?.title ?: "清风输入法"

/**
 * 一屏：miuix 大标题栏（随滚动收成小标题）+ 可滚动内容。内容画到导航栏下（edge-to-edge），
 * 末尾垫一段导航栏高度，最后一行不会被手势条挡住。
 */
@Composable
private fun Screen(
    route: Route,
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack, modifier = Modifier.padding(start = 12.dp)) {
                            Icon(MiuixIcons.Back, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        key(route) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(top = padding.calculateTopPadding())
                    .verticalScroll(rememberScrollState())
                    // 「添加词条」等有输入框的页：软键盘弹起时内容让出来，按钮不被挡
                    .imePadding()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

// ── 首页 ─────────────────────────────────────────────────────────

private val SEGMENT_TITLES = listOf(
    "shared" to "清风引擎",
    "android" to "手机键盘",
    "tail" to "其他",
)

@Composable
private fun HomeScreen(
    setup: ImeSetup,
    manifest: SettingsManifestData,
    values: SettingsValues,
    schemaState: SchemaState?,
    versionName: String,
    actions: SettingsActions,
    onOpen: (String) -> Unit,
) {
    // 「统计」一行写今日字数：比列卡片名（只有一张「统计设置」）有用。统计走 JNI，后台取
    val context = LocalContext.current
    val todayChars by androidx.compose.runtime.produceState<UInt?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { CoreAccess.obtainCore(context)?.statsSummary()?.todayChars }.getOrNull()
        }
    }
    // 开始使用：三行常在——装好了也要能再跳（换了系统设置、想在别的框里试打）。
    // 没完成的那一步用强调色写「下一步」，新用户不用猜自己卡在哪
    SmallTitle("开始使用")
    Card(modifier = Modifier.fillMaxWidth()) {
        val enabled = setup != ImeSetup.ENABLE
        NavRow(
            title = "启用输入法",
            summary = if (enabled) "已启用 ✓" else "下一步：在系统的输入法列表里打开「清风输入法」",
            icon = if (enabled) SettingsIcons.enabled else SettingsIcons.enable,
            highlight = !enabled,
            onClick = actions.enableIme,
        )
        NavRow(
            title = "切换输入法",
            summary = when (setup) {
                ImeSetup.READY -> "当前正在使用清风输入法 ✓"
                ImeSetup.SWITCH -> "下一步：在弹出的选择框里选「清风输入法」"
                ImeSetup.ENABLE -> "启用后再切换"
            },
            icon = SettingsIcons.switchIme,
            highlight = setup == ImeSetup.SWITCH,
            onClick = actions.switchIme,
        )
        NavRow(
            title = "试一试",
            summary = "普通 / 搜索 / 数字 / 密码 / 句子五种场景的输入框",
            icon = SettingsIcons.tryIt,
            onClick = actions.openTestPad,
        )
    }
    for ((segment, title) in SEGMENT_TITLES) {
        val pages = manifest.pages.filter { it.segment == segment }
        if (pages.isEmpty()) continue
        SmallTitle(title)
        Card(modifier = Modifier.fillMaxWidth()) {
            for (p in pages) {
                NavRow(
                    title = p.title,
                    summary = if (p.id == "stats" && statsOff(manifest, values)) {
                        "统计已关闭"
                    } else if (p.id == "stats" && todayChars != null) {
                        "今日已输入 $todayChars 字"
                    } else {
                        pageSummary(manifest, p, values, schemaState, versionName)
                    },
                    icon = SettingsIcons.page(p.icon),
                ) { onOpen(p.id) }
            }
        }
    }
}

/**
 * 首页每一行的摘要：几个页面给出当前状态（方案名、版本），
 * 其余列出前几张卡片的名字，让人知道点进去有什么。
 */
private fun pageSummary(
    manifest: SettingsManifestData,
    page: SettingPage,
    values: SettingsValues,
    schemaState: SchemaState?,
    versionName: String,
): String {
    @Suppress("UNUSED_EXPRESSION") values.version // 值变了摘要跟着变
    return when (page.id) {
        "schema" -> schemaState?.switching?.let { "正在切换到「$it」…" }
            ?: schemaState?.activeName?.let { "当前：$it" }
            ?: sectionList(manifest, page.id)
        "dict" -> "我的词条 · 添加词条"
        "about" -> "版本 $versionName"
        else -> sectionList(manifest, page.id)
    }
}

/** 输入统计关着时首页不写「今日 0 字」——那读起来像「今天没打字」 */
private fun statsOff(manifest: SettingsManifestData, values: SettingsValues): Boolean =
    manifest.items.firstOrNull { it.key == "stats.enabled" }
        ?.takeIf { values.available(it) }
        ?.let { !values.bool(it) } ?: false

private fun sectionList(manifest: SettingsManifestData, pageId: String): String =
    manifest.sectionsOf(pageId).map { it.first }.filter { it.isNotEmpty() }.take(4).joinToString(" · ")

// ── 一级页面 ─────────────────────────────────────────────────────

@Composable
private fun PageScreen(
    manifest: SettingsManifestData,
    page: SettingPage,
    values: SettingsValues,
    session: ImeSession?,
    schemaState: SchemaState?,
    versionName: String,
    onOpenSub: (String) -> Unit,
) {
    when (page.id) {
        "schema" -> SchemaSection(schemaState, onOpenSub)
        "stats" -> StatsSection()
        "dict" -> DictOverview(session, onOpen = onOpenSub)
        "about" -> AboutSection(versionName)
    }
    @Suppress("UNUSED_EXPRESSION") values.version // 开关变了，依赖它的项跟着显示 / 隐藏
    val sections = visibleSections(manifest, page.id, values)
    if (sections.isEmpty() && !values.engineReady && manifest.itemsOf(page.id).isNotEmpty()) {
        Text(text = "正在启动输入法核心…", modifier = Modifier.padding(start = 4.dp))
    }
    if (page.subpages) {
        // 每组一个入口，副标题列出组里前几项，点进去才是开关
        if (sections.isNotEmpty()) {
            SmallTitle(if (page.id == "schema") "方案设置" else "分类")
            Card(modifier = Modifier.fillMaxWidth()) {
                for ((name, items) in sections) {
                    NavRow(
                        title = name,
                        summary = items.take(3).joinToString(" · ") { it.title },
                        icon = SettingsIcons.section(name),
                    ) { onOpenSub(name) }
                }
            }
        }
    } else {
        for ((name, items) in sections) SettingsCard(name, items, values)
    }
}

private fun visibleSections(
    manifest: SettingsManifestData,
    pageId: String,
    values: SettingsValues,
): List<Pair<String, List<SettingItem>>> =
    manifest.sectionsOf(pageId)
        .map { (name, items) -> name to items.filter { values.available(it) && values.visible(it, manifest.items) } }
        .filter { it.second.isNotEmpty() }

// ── 二级页面 ─────────────────────────────────────────────────────

@Composable
private fun SubScreen(
    manifest: SettingsManifestData,
    page: SettingPage,
    sub: String,
    values: SettingsValues,
    session: ImeSession?,
    schemaState: SchemaState?,
) {
    when {
        page.id == "schema" && sub == SUB_LAYOUTS -> ShuangpinLayouts(schemaState)
        page.id == "dict" && sub == DICT_WORDS -> DictWords(session)
        page.id == "dict" && sub == DICT_ADD -> DictAdd(session)
        else -> {
            @Suppress("UNUSED_EXPRESSION") values.version
            val items = visibleSections(manifest, page.id, values).firstOrNull { it.first == sub }?.second.orEmpty()
            if (items.isNotEmpty()) SettingsCard("", items, values)
            // 方案的附加词库挂在对应的二级页底下：拼音细胞词库在「拼音」，五笔附加库在「码表」
            if (page.id == "schema" && sub == "拼音") SchemaDictsSection("细胞词库", listOf("pinyin", "shuangpin"))
            if (page.id == "schema" && sub == "码表") SchemaDictsSection("五笔附加词库", listOf("wubi86"))
            // Emoji 开着才列附加模块（同电脑版的 enabled_when）
            if (sub == EMOJI_SECTION &&
                manifest.items.firstOrNull { it.key == EMOJI_ENABLED_KEY }?.let(values::bool) == true
            ) {
                EmojiModulesSection()
            }
        }
    }
}

/** 一张卡片：常用项直接列出，`advanced` 项收进底部的「进阶」折叠区 */
@Composable
private fun SettingsCard(title: String, items: List<SettingItem>, values: SettingsValues) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val (advanced, normal) = items.partition { it.advanced }
    if (title.isNotEmpty()) SmallTitle(title)
    Card(modifier = Modifier.fillMaxWidth()) {
        // 按键名定身份：enabled_when 让中间某行出现 / 消失时，各行记住的状态（滑块草稿等）
        // 不能按位置串到别的行上
        for (item in normal) key(item.key) { ItemRow(item, values) }
        if (advanced.isNotEmpty()) {
            NavRow(if (expanded) "进阶 ▴" else "进阶 ▾", "工程参数，一般不用改") { expanded = !expanded }
            if (expanded) for (item in advanced) key(item.key) { ItemRow(item, values) }
        }
    }
}

@Composable
private fun ItemRow(item: SettingItem, values: SettingsValues) {
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") values.version // 写入后重组
    fun write(v: Any) {
        scope.launch { withContext(Dispatchers.IO) { values.set(item, v) } }
    }
    when (item.type) {
        "toggle" -> ToggleLine(item.title, item.summary, values.bool(item)) { write(it) }
        "int" -> IntRow(item, values.int(item)) { write(it) }
        "select" -> SelectRow(item, values.choice(item)) { write(it) }
    }
}

// ── 方案页的专属区块：方案列表与双拼布局 ─────────────────────────

/**
 * 方案与双拼布局：**数据全部来自核心**，本页不留一份自己的清单。
 *
 * 装了什么方案由文件系统说了算（核心扫 `data/schemas` 下的 `.schema.toml`），双拼有哪些布局
 * 同理（扫 `schemas/shuangpin` 下的 `.toml`）。K 侧写死清单的后果是用户往目录里放的东西看不见，
 * 以及删掉某个方案后设置页还列着一个选不动的死项。
 */
@Composable
private fun SchemaSection(state: SchemaState?, onOpenSub: (String) -> Unit) {
    val st = state ?: return
    if (st.schemas.isEmpty()) return

    SmallTitle(st.switching?.let { "正在切换到「$it」…（首次使用要准备词库，稍候）" } ?: "输入方案")
    Card(modifier = Modifier.fillMaxWidth()) {
        for (s in st.schemas) {
            val label = s.name.ifEmpty { s.id }
            ChoiceRow(
                title = label,
                selected = s.active,
                onClick = { st.switchTo(label, EngineCmd.SelectSchema(s.index)) },
            )
        }
    }

    // 双拼布局是二级页：十几个布局摊在方案页上只会把下面的设置挤出屏幕。
    // 只在**当前用的是双拼**时给入口——别的方案用不上它
    if (st.layouts.isNotEmpty() && st.schemas.any { it.id == SHUANGPIN_SCHEMA_ID && it.active }) {
        Card(modifier = Modifier.fillMaxWidth()) {
            NavRow(
                title = SUB_LAYOUTS,
                summary = st.layouts.firstOrNull { it.active }?.let { "当前：${it.name.ifEmpty { it.id }}" },
                icon = SettingsIcons.section(SUB_LAYOUTS),
            ) { onOpenSub(SUB_LAYOUTS) }
        }
    }
}

@Composable
private fun ShuangpinLayouts(state: SchemaState?) {
    val st = state ?: return
    st.switching?.let { SmallTitle("正在切换到「$it」…") }
    Card(modifier = Modifier.fillMaxWidth()) {
        for (l in st.layouts) {
            val label = l.name.ifEmpty { l.id }
            ChoiceRow(
                title = label,
                selected = l.active,
                onClick = { st.switchTo(label, EngineCmd.SetShuangpinLayout(l.id)) },
            )
        }
    }
}

/** Emoji 候选总开关（附加模块卡片跟着它显示）与它所在的二级页 */
private const val EMOJI_ENABLED_KEY = "input.emoji.enabled"
private const val EMOJI_SECTION = "Emoji/OpenCC"

/** 双拼方案 id（与 assets/data/schemas/shuangpin.schema.toml 一致） */
private const val SHUANGPIN_SCHEMA_ID = "shuangpin"

// ── 关于 ─────────────────────────────────────────────────────────

@Composable
private fun AboutSection(versionName: String) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        NavRow("清风输入法", "版本 $versionName", icon = SettingsIcons.page("Info"))
        NavRow(
            if (showLicenses) "开源许可 ▴" else "开源许可 ▾",
            "随包分发的词库数据与程序库的来源和许可证",
            icon = SettingsIcons.licenses,
        ) { showLicenses = !showLicenses }
    }
    if (showLicenses) LicensesSection()
}

// ── 通用行（miuix-preference）────────────────────────────────────

@Composable
internal fun SectionTitle(text: String) = SmallTitle(text)

/** 行首图标：统一大小与色调 */
@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 14.dp).size(24.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}

/**
 * 标题 + 摘要的行。[onClick] 为 null 时是纯展示行（无箭头、不可点）；
 * [highlight] 把摘要染成强调色（「开始使用」里没完成的那一步）。
 */
@Composable
internal fun NavRow(
    title: String,
    summary: String?,
    icon: ImageVector? = null,
    highlight: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val start: (@Composable () -> Unit)? = icon?.let { { RowIcon(it) } }
    val summaryColor = if (highlight) {
        top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.primary)
    } else {
        top.yukonga.miuix.kmp.basic.BasicComponentDefaults.summaryColor()
    }
    if (onClick != null) {
        ArrowPreference(
            title = title,
            summary = summary?.ifEmpty { null },
            summaryColor = summaryColor,
            startAction = start,
            onClick = onClick,
        )
    } else {
        BasicComponent(title = title, summary = summary?.ifEmpty { null }, summaryColor = summaryColor, startAction = start)
    }
}

/** 单选的一行：选中项右侧打勾（方案、双拼布局） */
@Composable
private fun ChoiceRow(title: String, selected: Boolean, onClick: () -> Unit) {
    RadioButtonPreference(
        title = title,
        selected = selected,
        onClick = onClick,
        radioButtonLocation = RadioButtonLocation.End,
    )
}

/**
 * 单选设置项：一行，右侧显示当前值，点开是下拉菜单。此前把选项全部摊开成多行打勾，
 * 一个三选一就占四行，二级页一屏装不下；下拉保持一项一行。
 */
@Composable
private fun SelectRow(item: SettingItem, value: String, onValueChange: (String) -> Unit) {
    val labels = item.options.map { it.second }
    val index = item.options.indexOfFirst { it.first == value }.coerceAtLeast(0)
    WindowDropdownPreference(
        items = labels,
        selectedIndex = index,
        title = item.title,
        summary = item.summary,
        onSelectedIndexChange = { i -> item.options.getOrNull(i)?.let { onValueChange(it.first) } },
    )
}

/**
 * 数值项：标题与当前值一行，下面一条滑块。拖动时只改本地草稿（预览跟着变），
 * 松手才写入——拖一下就写几十次配置文件没有意义。
 * 带 [SettingItem.preview] 的项在拖动后显示预览：行高画出四行键，字号写一行候选。
 */
@Composable
private fun IntRow(item: SettingItem, value: Int, onValueChange: (Int) -> Unit) {
    val min = item.min ?: 0
    val max = item.max ?: 100
    var draft by remember(value) { mutableIntStateOf(value) }
    var touched by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.title)
                item.summary?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(top = 2.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Text(text = if (item.unit != null) "$draft ${item.unit}" else draft.toString())
        }
        Slider(
            value = draft.toFloat(),
            onValueChange = {
                draft = snapToStep(it, min, max, item.step)
                touched = true
            },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / item.step - 1).coerceAtLeast(0),
            onValueChangeFinished = { if (draft != value) onValueChange(draft) },
        )
        when (item.preview) {
            "keyboard_rows" -> if (touched) KeyboardRowsPreview(draft)
            "candidate_text" -> CandidateTextPreview(draft)
        }
    }
}

/** 滑块值吸附到步长网格上（min 起算） */
internal fun snapToStep(raw: Float, min: Int, max: Int, step: Int): Int {
    val s = step.coerceAtLeast(1)
    val snapped = min + Math.round((raw - min) / s) * s
    return snapped.coerceIn(min, max)
}

/** 按所选行高（dp，真实尺寸）画四行键：10 / 9 / 9 / 5 个，只为让人看出键盘有多高 */
@Composable
private fun KeyboardRowsPreview(rowHeightDp: Int) {
    val keyColor = MiuixTheme.colorScheme.dividerLine
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height((rowHeightDp * PREVIEW_ROWS.size).dp),
    ) {
        val rowH = size.height / PREVIEW_ROWS.size
        val gap = 3.dp.toPx()
        PREVIEW_ROWS.forEachIndexed { r, keys ->
            val slot = size.width / 10f
            val indent = (10 - keys) * slot / 2
            for (k in 0 until keys) {
                drawRoundRect(
                    color = keyColor,
                    topLeft = Offset(indent + k * slot + gap, r * rowH + gap),
                    size = Size(slot - gap * 2, rowH - gap * 2),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
    }
}

private val PREVIEW_ROWS = listOf(10, 9, 9, 5)

@Composable
private fun CandidateTextPreview(textSp: Int) {
    Text(
        text = "清风 输入法 候选 示例",
        modifier = Modifier.padding(top = 12.dp),
        fontSize = textSp.sp,
    )
}

/** 标题 + 说明 + 开关的一行；整行可点 */
@Composable
internal fun ToggleLine(title: String, summary: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        checked = checked,
        onCheckedChange = onCheckedChange,
        title = title,
        summary = summary,
    )
}
