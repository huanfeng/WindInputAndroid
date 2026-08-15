//! Android 绑定层：把 [`wind_mobile`] 的移动端门面投影成 UniFFI 类型。
//!
//! # 这一层只做投影
//!
//! 输入法的实际行为——三层配置加载、已装方案扫描、预热闸门、候选窗口、吃键判定——
//! 全在主仓的 `wind-mobile` 里，那些逻辑换成 iOS 也成立，不该住在名字里带 android 的
//! crate 中。本 crate 只负责三件 Android 专属的事：
//!
//! 1. **类型投影**：核心的 Rust 类型 ↔ UniFFI 的 Record/Enum（跨 FFI 的表达能力受限）
//! 2. **日志落地**：tracing → logcat
//! 3. **产物形态**：`cdylib` 与 Kotlin 绑定生成器
//!
//! 判据很简单：**写下的东西如果换成 iOS 仍然要写一遍，它就该在 wind-mobile 里。**
//!
//! # 通道形状
//!
//! - 正向：`key_down(vk)` → 同步 [`KeyResult`]（上屏文本/组合区走按键返回值）
//! - 反向：核心的泵线程 → [`UiEventListener`] 回调（候选/状态/提示）
//! - 候选：推送帧 [`CandidateUpdate`] 只给**当页**，列表本身由 Kotlin 按窗口
//!   [`WindCore::candidates`] 拉、按绝对下标 [`WindCore::select_candidate`] 选
//!
//! 详见 `wind_mobile` 的模块文档——通道语义与踩过的坑都记在那里，这里不复述。

use std::path::Path;
use std::sync::Arc;

use wind_mobile::{
    CandidateFrame, CandidateWindow as CoreCandidateWindow, EditOp as CoreEditOp,
    InputStatus as CoreStatus, KeyOutcome, LayoutEntry, MobileCommand, MobileCore, MobileEventSink,
    SchemaEntry,
};

uniffi::setup_scaffolding!();

/// 初始化 Rust 日志 → logcat（tag: WindRust）。重复调用无害。
///
/// 默认 `info`；调更细可在 Kotlin 侧传 "wind_coordinator=debug" 之类的过滤串。
#[uniffi::export]
pub fn init_logging(filter: String) {
    #[cfg(target_os = "android")]
    {
        use tracing_subscriber::layer::SubscriberExt;
        use tracing_subscriber::util::SubscriberInitExt;
        let filter = if filter.is_empty() {
            "info".to_string()
        } else {
            filter
        };
        let _ = tracing_subscriber::registry()
            .with(tracing_subscriber::EnvFilter::new(filter))
            .with(tracing_android::layer("WindRust").expect("android tracing layer"))
            .try_init();
    }
    #[cfg(not(target_os = "android"))]
    let _ = filter;
}

/// 一次编辑操作（[`wind_mobile::EditOp`] 的 FFI 投影）。
///
/// UniFFI 的枚举载荷支持有限，此处摊平成带 `kind` 判别位的 Record：
/// Kotlin 侧一个 `when(kind)` 分派到对应的 `InputConnection` 调用。
#[derive(Debug, uniffi::Record)]
pub struct EditOp {
    /// 0=Commit 1=SetComposition 2=DeleteBackward 3=ReplaceBackward 4=MoveCursor
    pub kind: u8,
    /// Commit/SetComposition/ReplaceBackward 的文本
    pub text: String,
    /// SetComposition 的插入符（字符数）；DeleteBackward/ReplaceBackward 的字符数
    pub count: u32,
    /// MoveCursor 的位移（正右负左）
    pub delta: i32,
}

/// 按键事件的同步结果。
///
/// 从「上屏文本 + 组合区」这种**三元组投影**改成**指令流**：前者只装得下 InsertText 族，
/// 智能符号配对、回退替换、配对跳出这些动作在移动端被静默丢弃（不报错、不掉键，
/// 单纯功能没有）。指令流由核心的穷尽映射产出，新增动作会在核心编译期报错。
#[derive(Debug, Default, uniffi::Record)]
pub struct KeyResult {
    /// 是否被引擎消费（false = 宿主执行默认行为，吃键契约与桌面一致）
    pub consumed: bool,
    /// 按序执行的编辑指令
    pub ops: Vec<EditOp>,
    /// 模式是否变化（宿主据此刷新指示器；具体状态走推送通道）
    pub mode_changed: bool,
}

impl From<KeyOutcome> for KeyResult {
    fn from(o: KeyOutcome) -> Self {
        Self {
            consumed: o.consumed,
            // TimingHint 在移动端直接忽略：ops 里已含等价降级序列
            // （见 wind-host `TimingHint` 的说明）。
            ops: o.ops.into_iter().map(EditOp::from).collect(),
            mode_changed: o.mode_changed,
        }
    }
}

impl From<CoreEditOp> for EditOp {
    fn from(op: CoreEditOp) -> Self {
        match op {
            CoreEditOp::Commit(text) => Self {
                kind: 0,
                text,
                count: 0,
                delta: 0,
            },
            CoreEditOp::SetComposition { text, caret } => Self {
                kind: 1,
                text,
                count: caret as u32,
                delta: 0,
            },
            CoreEditOp::DeleteBackward { count } => Self {
                kind: 2,
                text: String::new(),
                count: count as u32,
                delta: 0,
            },
            CoreEditOp::ReplaceBackward { count, text } => Self {
                kind: 3,
                text,
                count: count as u32,
                delta: 0,
            },
            CoreEditOp::MoveCursor { delta } => Self {
                kind: 4,
                text: String::new(),
                count: 0,
                delta,
            },
        }
    }
}

/// 候选帧：编码栏与候选栏一次渲染所需的全部数据。
#[derive(Debug, uniffi::Record)]
pub struct CandidateUpdate {
    /// 编码区串（五笔码 / 拼音串），编码栏正文
    pub preedit: String,
    /// 编码区插入符位置：`preedit` 内的**字节**偏移（恒在字符边界）
    pub preedit_caret: u32,
    /// 模式指示文本（五/拼/英/符…），编码栏左侧角标；空 = 不显示
    pub mode_label: String,
    /// 当前页候选文本
    pub items: Vec<String>,
    /// 键盘选中项（页内下标），空格上屏目标
    pub selected: u32,
    /// 当前页（1 起）
    pub page: u32,
    /// 总页数
    pub total_pages: u32,
}

impl From<CandidateFrame> for CandidateUpdate {
    fn from(f: CandidateFrame) -> Self {
        Self {
            preedit: f.preedit,
            preedit_caret: f.preedit_caret as u32,
            mode_label: f.mode_label,
            items: f.items,
            selected: f.selected as u32,
            page: f.page as u32,
            total_pages: f.total_pages as u32,
        }
    }
}

/// 引擎当前模式真值。**数据，不是界面**——工具栏画几个格、放哪、什么图标由 Kotlin 决定。
#[derive(Debug, uniffi::Record)]
pub struct InputStatus {
    pub chinese_mode: bool,
    /// 方案短称：中文态取方案 icon_label（"五"/"拼"），英文态为 "英"/大写锁定 "A"
    pub icon_label: String,
    pub full_width: bool,
    pub chinese_punct: bool,
    pub s2t_enabled: bool,
    /// 是否该展示简繁开关（用户未启用简繁功能时为 false）
    pub s2t_shown: bool,
}

impl From<CoreStatus> for InputStatus {
    fn from(s: CoreStatus) -> Self {
        Self {
            chinese_mode: s.chinese_mode,
            icon_label: s.icon_label,
            full_width: s.full_width,
            chinese_punct: s.chinese_punct,
            s2t_enabled: s.s2t_enabled,
            s2t_shown: s.s2t_shown,
        }
    }
}

/// 输入方案条目（方案选择器的数据源）
#[derive(Debug, uniffi::Record)]
pub struct SchemaInfo {
    /// 方案 id（wubi86 / pinyin …）
    pub id: String,
    /// 显示名（方案文件 `[schema] name`）
    pub name: String,
    /// 短称（"五"/"拼"）
    pub icon_label: String,
    pub active: bool,
}

/// Kotlin 可下达的引擎命令。
///
/// 刻意是一张**手工挑选的小表**而非桌面菜单的镜像：后者含截图、打开目录、诊断 HUD、
/// 语言栏图标等一批桌面专属项，暴露给移动端只会制造调不通的入口。
/// 新增移动端要用的开关时，先在 [`wind_mobile::MobileCommand`] 加一条，再在此投影。
#[derive(Debug, uniffi::Enum)]
pub enum EngineCommand {
    /// 切到英文模式（中→英）
    English,
    /// 选中第 N 个方案（下标对齐 [`WindCore::schemas`]），并回到中文模式（英→中）
    SelectSchema { index: u32 },
    /// 中文标点 ↔ 英文标点
    TogglePunct,
    /// 全角 ↔ 半角
    ToggleWidth,
    /// 简入繁出开关
    ToggleS2t,
    /// 重载用户配置（设置页改完即时生效）
    ReloadConfig,
    /// 设置双拼布局（`id` 取自 [`WindCore::shuangpin_layouts`]）
    SetShuangpinLayout { id: String },
}

impl From<EngineCommand> for MobileCommand {
    fn from(cmd: EngineCommand) -> Self {
        match cmd {
            EngineCommand::English => MobileCommand::English,
            EngineCommand::SelectSchema { index } => MobileCommand::SelectSchema(index as usize),
            EngineCommand::TogglePunct => MobileCommand::TogglePunct,
            EngineCommand::ToggleWidth => MobileCommand::ToggleWidth,
            EngineCommand::ToggleS2t => MobileCommand::ToggleS2t,
            EngineCommand::ReloadConfig => MobileCommand::ReloadConfig,
            EngineCommand::SetShuangpinLayout { id } => MobileCommand::SetShuangpinLayout(id),
        }
    }
}

/// 候选全量的一个窗口（滚动候选栏 / 展开面板的数据源）。
///
/// 推送帧 [`CandidateUpdate`] 给的是**当页**候选，够画编码栏与页码，但装不下一条
/// 可滚动的长列表。宿主要更多时按 `(offset, limit)` 来拉——核心侧另有硬上限。
#[derive(Debug, uniffi::Record)]
pub struct CandidateWindow {
    pub items: Vec<String>,
    /// 窗口起点（原样回送）
    pub offset: u32,
    /// **全量**候选总数：Kotlin 据此判断还要不要续取
    pub total: u32,
    /// 当前高亮候选的**绝对**下标（空格上屏的目标）
    pub selected: u32,
}

impl From<CoreCandidateWindow> for CandidateWindow {
    fn from(w: CoreCandidateWindow) -> Self {
        Self {
            items: w.items,
            offset: w.offset as u32,
            total: w.total as u32,
            selected: w.selected as u32,
        }
    }
}

/// 一个双拼布局。
#[derive(Debug, uniffi::Record)]
pub struct LayoutInfo {
    /// 文件名 stem，即写回 [`EngineCommand::SetShuangpinLayout`] 的值
    pub id: String,
    /// 显示名（布局文件 `[meta].name`），如「小鹤双拼」
    pub name: String,
    pub active: bool,
}

impl From<LayoutEntry> for LayoutInfo {
    fn from(l: LayoutEntry) -> Self {
        Self {
            id: l.id,
            name: l.name,
            active: l.active,
        }
    }
}

impl From<SchemaEntry> for SchemaInfo {
    fn from(s: SchemaEntry) -> Self {
        Self {
            id: s.id,
            name: s.name,
            icon_label: s.icon_label,
            active: s.active,
        }
    }
}

/// 反向通道回调（核心的泵线程调用，**非主线程**，Kotlin 侧自行 post）
#[uniffi::export(with_foreign)]
pub trait UiEventListener: Send + Sync {
    /// 候选/编码区更新
    fn on_candidates(&self, update: CandidateUpdate);
    /// 候选清空/隐藏
    fn on_hide_candidates(&self);
    /// 模式真值变化（中英/标点/全半角/简繁/方案短称）
    fn on_status(&self, status: InputStatus);
    /// 一次性提示（方案切换/模式切换/词库就绪/错误）
    fn on_toast(&self, text: String);
}

/// 把核心的 [`MobileEventSink`] 接到 UniFFI 的外部实现回调上。
///
/// 这层适配存在的唯一理由是**方向**：`MobileEventSink` 是普通 Rust trait，
/// `UiEventListener` 是 UniFFI 导出的外部可实现 trait，两者不能是同一个类型
/// （否则 wind-mobile 就得依赖 uniffi，"接口层"退化成"Android 实现层"）。
struct ListenerSink(Arc<dyn UiEventListener>);

impl MobileEventSink for ListenerSink {
    fn on_candidates(&self, frame: CandidateFrame) {
        self.0.on_candidates(frame.into());
    }

    fn on_hide_candidates(&self) {
        self.0.on_hide_candidates();
    }

    fn on_status(&self, status: CoreStatus) {
        self.0.on_status(status.into());
    }

    fn on_toast(&self, text: String) {
        self.0.on_toast(text);
    }
}

/// 输入法核心会话：一个实例对应一个 InputMethodService 生命周期
#[derive(uniffi::Object)]
pub struct WindCore {
    core: Arc<MobileCore>,
}

#[uniffi::export]
impl WindCore {
    /// 构造核心。
    ///
    /// - `data_dir`: 出厂数据目录（schemas/ 所在，即 Android filesDir/data）
    /// - `user_root`: 用户/缓存数据根（Android filesDir/rust，内部分 config/data/cache）
    /// - `schema`: **首装兜底**方案 id（如 "wubi86"）；用户上次选过的优先
    #[uniffi::constructor]
    pub fn new(
        data_dir: String,
        user_root: String,
        schema: String,
        listener: Arc<dyn UiEventListener>,
    ) -> Arc<Self> {
        let core = MobileCore::new(
            Path::new(&data_dir),
            Path::new(&user_root),
            &schema,
            Arc::new(ListenerSink(listener)),
        );
        Arc::new(Self { core })
    }

    /// 按下键（vk = wind-keys VK 码，`modifiers` 为位掩码）。
    ///
    /// 抬起事件骨架期不喂（协调器按键链以 down 为主）。吃键判定在核心内部完成，
    /// K 侧**不要**自己判断哪些键该拦——`consumed=false` 就照系统默认行为走。
    pub fn key_down(&self, vk: u32, modifiers: u32) -> KeyResult {
        self.core.key_down(vk, modifiers).into()
    }

    /// 焦点获得（对应 onStartInput）。
    ///
    /// 词库预热在核心构造时就已启动（不在这里），所以本调用是廉价的。
    pub fn focus_gained(&self) {
        self.core.focus_gained();
    }

    /// 焦点丢失（对应 onFinishInput）。语义 = 真正离开，清输入态。
    pub fn focus_lost(&self) {
        self.core.focus_lost();
    }

    /// 下达引擎命令。状态变化经 [`UiEventListener::on_status`] 异步回报，不在此处同步返回
    /// ——同一个变化也可能由按键或焦点事件引发，只留推送一条路，K 侧才不会有两份真值。
    pub fn run_command(&self, cmd: EngineCommand) {
        self.core.run_command(cmd.into());
    }

    /// 可选方案列表。下标即 [`EngineCommand::SelectSchema`] 的 `index`
    /// ——顺序取自引擎管理器，与协调器内部的选择下标同源，K 侧不要自行排序。
    pub fn schemas(&self) -> Vec<SchemaInfo> {
        self.core
            .schemas()
            .into_iter()
            .map(SchemaInfo::from)
            .collect()
    }

    /// 拉取候选全量的一个窗口。**纯读**，不改状态，可随时调。
    ///
    /// 别在每次按键都拉一大段：序列化与 K 侧的文字测量都在主线程上。
    /// 起手拉够铺满一两屏即可，用户滚到尾部再续取。
    pub fn candidates(&self, offset: u32, limit: u32) -> CandidateWindow {
        self.core.candidates(offset as usize, limit as usize).into()
    }

    /// 按**绝对下标**选词，返回编辑指令流（与 [`Self::key_down`] 同一种形状）。
    ///
    /// `index` 是全量候选里的绝对下标，不是页内的——取自 [`Self::candidates`] 拉到的
    /// 窗口。取代此前「合成数字键」的做法：那条路把选词限死在页内 1-9，第 10 个及
    /// 以后永远点不到，候选栏的滚动也就失去了意义。
    pub fn select_candidate(&self, index: u32) -> KeyResult {
        self.core.select_candidate(index as usize).into()
    }

    /// 可选双拼布局。清单由核心扫描目录得出，**K 侧不要硬编码**——
    /// 用户可以往 `schemas` 的 `shuangpin` 子目录里丢自己的布局文件，硬编码清单看不见它。
    pub fn shuangpin_layouts(&self) -> Vec<LayoutInfo> {
        self.core
            .shuangpin_layouts()
            .into_iter()
            .map(LayoutInfo::from)
            .collect()
    }
}
