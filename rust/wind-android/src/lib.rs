//! Android FFI 层（walking skeleton）：把 headless Coordinator 包成 UniFFI 对象。
//!
//! 通道形状（对应 WindInputAndroid docs/architecture.md §3.1）：
//! - 正向：`key_down(vk)` → `Coordinator::handle_key_event` → 同步 [`KeyResult`]
//!   （上屏文本/组合区走按键返回值，与 TSF 宿主同一条主输入路）
//! - 反向：`Receiver<UiCommand>` 泵线程 → [`UiEventListener`] 回调（候选/隐藏）
//! - 候选：推送帧只给**当页**（够画编码栏与页码），列表本身由宿主按窗口
//!   [`WindCore::candidates`] 拉、按绝对下标 [`WindCore::select_candidate`] 选。
//!   这两个口取代了骨架期「合成页内数字键」的做法——那把点选限死在前 9 个，
//!   于是候选栏的滚动也没有意义（一帧才 6~9 条）。桌面鼠标点选走 push 通道上屏，
//!   headless 没有消费端，所以这里改走「返回编辑指令流」，与 `key_down` 同一种形状。
//!
//! 骨架期已知妥协（均有 TODO 标注）：目录经 XDG 环境变量挂载、首显闸门用合成 caret 释放。
//!
//! **工具栏与功能菜单的呈现不属于这一层。** 桌面那棵功能菜单树（截图、打开日志目录、
//! 诊断 HUD、语言栏图标调试…）大半在手机上无意义，照搬过来只会把桌面形态的包袱带进
//! 移动端。此处只提供两样东西：
//! - [`EngineCommand`] —— **能调到**引擎的哪些开关（展示什么、怎么排、叫什么由 Kotlin 定）
//! - [`InputStatus`] —— 引擎当前的模式真值（中英/标点/全半角/简繁/方案短称）
//!
//! 状态**必须**由引擎推送而不是 Kotlin 自己记：方案切换会改写方案短称，密码框会强制
//! 英文，这些都发生在引擎内部；K 侧自记一份开关态必然与引擎漂移。

use std::path::Path;
use std::sync::Arc;

use wind_bridge::handler::KeyAction as BridgeKeyAction;
use wind_bridge::handler::{FocusData, KeyEventData, MessageHandler};
use wind_config::Config;
use wind_coordinator::Coordinator;
use wind_host::{EditOp as HostEditOp, KeyProbe, Modifiers};
use wind_ipc::protocol::{EVENT_KEY_DOWN, FocusLostReason, caret_source};
use wind_ui_types::{MenuCmd, MenuKind, UiCommand, UiEvent};

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

/// 一次编辑操作（[`wind_host::EditOp`] 的 FFI 投影）。
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

impl From<wind_host::KeyOutcome> for KeyResult {
    fn from(o: wind_host::KeyOutcome) -> Self {
        Self {
            consumed: o.consumed,
            // TimingHint 在移动端直接忽略：ops 里已含等价降级序列
            // （见 wind-host `TimingHint` 的说明）。
            ops: o.ops.into_iter().map(EditOp::from).collect(),
            mode_changed: o.mode_changed,
        }
    }
}

impl From<HostEditOp> for EditOp {
    fn from(op: HostEditOp) -> Self {
        match op {
            HostEditOp::Commit(text) => Self {
                kind: 0,
                text,
                count: 0,
                delta: 0,
            },
            HostEditOp::SetComposition { text, caret } => Self {
                kind: 1,
                text,
                count: caret as u32,
                delta: 0,
            },
            HostEditOp::DeleteBackward { count } => Self {
                kind: 2,
                text: String::new(),
                count: count as u32,
                delta: 0,
            },
            HostEditOp::ReplaceBackward { count, text } => Self {
                kind: 3,
                text,
                count: count as u32,
                delta: 0,
            },
            HostEditOp::MoveCursor { delta } => Self {
                kind: 4,
                text: String::new(),
                count: 0,
                delta,
            },
        }
    }
}

/// 候选帧：编码栏与候选栏一次渲染所需的全部数据。
///
/// 拆成 Record 而非长参数列表——`UpdateCandidates` 还会继续加字段（翻页栏、注释行），
/// 每加一个都改 trait 方法签名会连带 Kotlin 实现全部返工。
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
/// 刻意是一张**手工挑选的小表**而非桌面 `MenuCmd` 的镜像：后者含截图、打开目录、
/// 诊断 HUD、语言栏图标等一批桌面专属项，暴露给移动端只会制造调不通的入口。
/// 新增移动端要用的开关时在此显式加一条。
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
    /// 设置双拼布局（`id` 取自 [`WindCore::shuangpin_layouts`]）。
    ///
    /// 不走 `MenuCmd`：桌面那套菜单里没有这一项（桌面在设置页里改），
    /// 而移动端没有设置页 RPC 通道，只能经本命令直达协调器。
    SetShuangpinLayout { id: String },
}

/// 候选全量的一个窗口（滚动候选栏 / 展开面板的数据源）。
///
/// 推送帧 [`CandidateUpdate`] 给的是**当页**候选，够画编码栏与页码，但装不下一条
/// 可滚动的长列表。宿主要更多时按 `(offset, limit)` 来拉——核心侧另有硬上限，
/// 见 `wind_coordinator::candidate_pull::MAX_WINDOW`。
#[derive(Debug, uniffi::Record)]
pub struct CandidateWindow {
    pub items: Vec<String>,
    /// 窗口起点（原样回送）
    pub offset: u32,
    /// **全量**候选总数：宿主据此判断还要不要续取
    pub total: u32,
    /// 当前高亮候选的**绝对**下标（空格上屏的目标）
    pub selected: u32,
}

/// 一个双拼布局（`schemas/shuangpin/<id>.toml`）。
#[derive(Debug, uniffi::Record)]
pub struct LayoutInfo {
    /// 文件名 stem，即写回 [`EngineCommand::SetShuangpinLayout`] 的值
    pub id: String,
    /// 显示名（布局文件 `[meta].name`），如「小鹤双拼」
    pub name: String,
    pub active: bool,
}

/// 反向通道回调（UiCommand 泵线程调用，**非主线程**，Kotlin 侧自行 post）
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

/// 输入法核心会话：一个实例对应一个 InputMethodService 生命周期
#[derive(uniffi::Object)]
pub struct WindCore {
    coord: Arc<Coordinator>,
    /// 预热进行中：泵线程据此丢弃预热产生的候选帧，不让它们闪到用户界面上
    warming: Arc<std::sync::atomic::AtomicBool>,
}

#[uniffi::export]
impl WindCore {
    /// 构造核心。
    ///
    /// - `data_dir`: 出厂数据目录（schemas/ 所在，即 Android filesDir/data）
    /// - `user_root`: 用户/缓存数据根（Android filesDir/rust，内部分 config/data/cache）
    /// - `schema`: 激活方案 id（骨架期单方案，如 "wubi86"）
    #[uniffi::constructor]
    pub fn new(
        data_dir: String,
        user_root: String,
        schema: String,
        listener: Arc<dyn UiEventListener>,
    ) -> Arc<Self> {
        // TODO(上游): wind-config 支持显式目录注入后移除环境变量挂载。
        // dirs crate 在 unix 系按 XDG 变量解析，这里把三类目录钉进 App 私有路径。
        unsafe {
            std::env::set_var("HOME", &user_root);
            std::env::set_var("XDG_CONFIG_HOME", format!("{user_root}/config"));
            std::env::set_var("XDG_DATA_HOME", format!("{user_root}/data"));
            std::env::set_var("XDG_CACHE_HOME", format!("{user_root}/cache"));
        }

        // 三层配置加载（代码默认 ⊕ data/config.toml ⊕ 用户层），与桌面同一条路径——
        // 「配置格式三端统一」是移植的硬约束（多端同步的前提），这里若退回
        // `Config::default()`，出厂 config.toml 里的短语/命令栏/候选行为全部失效。
        let data_path = Path::new(&data_dir);
        let mut cfg = Config::load(Some(data_path)).unwrap_or_else(|e| {
            tracing::warn!("配置加载失败，回落代码默认值: {e}");
            Config::default()
        });
        // 可选方案 = **assets 里实际装了的方案**，而不是 config.toml 里列的那份。
        //
        // 两个方向都要修正：
        // - 删：出厂清单列的是桌面全量，装不出来的条目会在方案选择器里变成死项；
        // - 加：清单**没列但装了**的照样要能用（英文方案就是这样——桌面把它当作
        //   临时英文的目标引擎、不放进 available，移动端却要把它当一等方案切换）。
        // 移动端的语义就是「装了什么就能用什么」，以文件系统为准最省心。
        let mut available = scan_installed_schemas(data_path);
        if !available.contains(&schema) {
            available.push(schema.clone());
        }

        // 激活方案：**用户上次选的优先**，`schema` 参数只是首装兜底。
        //
        // ⚠ 此前这里无条件 `cfg.schema.active = schema`，把三层加载刚读出来的用户层
        // 选择直接覆盖掉——`select_schema` 明明把 `schema.active` 写进了用户层，
        // 表现却是「切到拼音，杀掉进程再进来又回五笔」，而且切换当场是好的，
        // 只有重启才复现，极难归因。
        //
        // 仍要校验「装了没有」：用户层可能留着上一版装过、这一版删掉的方案 id，
        // 照用会让引擎起不来（可选方案里没有它，`select_schema` 也选不回去）。
        let active = if cfg.schema.active.is_empty() || !available.contains(&cfg.schema.active) {
            schema
        } else {
            cfg.schema.active.clone()
        };
        tracing::info!("已装方案: {:?}，激活: {}", available, active);
        cfg.schema.available = available;
        cfg.schema.active = active;

        // 编码区归候选区自绘（Android 必须如此，非偏好）：默认的 `app_inline` 是把编码
        // 塞进宿主组合区、协调器**不下发 preedit** 给候选窗。移动端没有桌面那种浮在光标
        // 旁的候选窗，编码要显示在键盘上方的编码栏里，就必须让协调器把 preedit 发出来。
        // 「配置格式三端统一、取值分端」的典型一例——项本身是共用的。
        cfg.ui.candidate.preedit_display = "candidate_top".to_string();

        // 用户数据根：redb 落在这里。**不能传 None**——那样系统短语层会整段为空
        // （详见 `new_headless_with_ui_at` 的说明），词频与自造词也不落盘。
        let user_path = Path::new(&user_root);
        let (coord, rx) =
            Coordinator::new_headless_with_ui_at(cfg, Some(data_path), Some(user_path));

        let warming = Arc::new(std::sync::atomic::AtomicBool::new(true));

        // 反向通道泵：Receiver<UiCommand> → Kotlin 回调
        let pump_warming = Arc::clone(&warming);
        std::thread::Builder::new()
            .name("wind-ui-pump".into())
            .spawn(move || {
                for cmd in rx {
                    // 预热喂的假按键会产生候选帧，丢掉——否则键盘刚起来会闪一下候选。
                    // 只挡候选类，状态/提示类照常（预热不改模式，本来也不会产生）。
                    if pump_warming.load(std::sync::atomic::Ordering::Relaxed)
                        && matches!(
                            cmd,
                            UiCommand::UpdateCandidates { .. } | UiCommand::HideCandidates
                        )
                    {
                        continue;
                    }
                    match cmd {
                        UiCommand::UpdateCandidates {
                            preedit,
                            preedit_caret,
                            mode_label,
                            candidates,
                            selected,
                            page,
                            total_pages,
                            ..
                        } => {
                            listener.on_candidates(CandidateUpdate {
                                preedit,
                                preedit_caret: preedit_caret as u32,
                                mode_label,
                                items: candidates.into_iter().map(|c| c.text).collect(),
                                selected: selected as u32,
                                page: page as u32,
                                total_pages: total_pages as u32,
                            });
                        }
                        UiCommand::HideCandidates => listener.on_hide_candidates(),
                        UiCommand::UpdateToolbar(st) => {
                            listener.on_status(InputStatus {
                                chinese_mode: st.chinese_mode,
                                icon_label: st.icon_label,
                                full_width: st.full_width,
                                chinese_punct: st.chinese_punct,
                                s2t_enabled: st.s2t_enabled,
                                s2t_shown: st.s2t_shown,
                            });
                        }
                        // 状态提示气泡在桌面是独立浮窗，移动端并入 toast 一条通路
                        UiCommand::ShowToast { text, .. }
                        | UiCommand::ShowStatusTip { text, .. } => listener.on_toast(text),
                        _ => {}
                    }
                }
            })
            .expect("spawn wind-ui-pump");

        // 自绘候选条，不提供光标坐标——关掉桌面用的首显闸门等待
        coord.set_caret_independent(true);
        // ⚠ 这里**保持开启**启动预热（`set_eager_prewarm(true)` 是默认值）。
        //
        // 曾经关掉过，理由是「手机上编译所有方案要几秒 CPU、会撞上用户打字」——那个
        // 判断错了两次：ANR 的真根因是**核心构造在主线程**（已由 Kotlin 侧
        // DeferredSession 移到后台），与预热无关；而关掉预热的代价远超收益——
        // 未预热的方案**不会变成「按需加载一次」，而是每次查询都重解析整份 yaml**
        // （`CachedDict` 写不出 wdat 缓存时退化为内存模式），实测每次按键多花 200ms，
        // 触摸事件因此堆积、连点被误判成上滑。
        //
        // 要改回按需加载，先确认词库能落成 wdat 缓存，否则就是把一次性开销摊成永久开销。
        // 惰性构建搬到后台（真机冷启动首键实测阻塞 2.8 秒）。走核心的就绪契约，
        // 宿主不再自己猜「喂哪几个键能触发惰性构建」。
        coord.prepare();

        Arc::new(Self { coord, warming })
    }

    /// 按下键（vk = wind-keys VK 码，`modifiers` 见 [`wind_host::Modifiers`]）。
    ///
    /// 抬起事件骨架期不喂（协调器按键链以 down 为主）。
    pub fn key_down(&self, vk: u32, modifiers: u32) -> KeyResult {
        // 用户真按键了 → 预热窗口立即关闭。没有这一行，用户在预热完成前抢先打字时，
        // 他的**真实候选帧**会被泵线程当成预热帧丢掉（表现为「打了字没候选」）。
        // 预热线程末尾那次 store(false) 因此是幂等的补充，不是唯一出口。
        self.warming
            .store(false, std::sync::atomic::Ordering::Relaxed);

        // ★ 吃键判定走**核心的唯一真相源**（`wind_coordinator::key_gate`）。
        // 此处曾有一份手写谓词，一个会话里被同形 bug 打脸三次（空缓冲功能键失效、
        // 英文模式字母失效…），根因就是它与核心判据漂移。宿主不再自己判。
        let probe = KeyProbe::new(vk).with_modifiers(Modifiers(modifiers));
        if !self.coord.should_handle_key(&probe) {
            return KeyResult::default();
        }
        KeyResult::from(wind_coordinator::edit_ops::to_outcome(dispatch_key(
            &self.coord,
            vk,
        )))
    }

    /// 焦点获得（对应 onStartInput）。首次调用时顺带启动后台预热。
    pub fn focus_gained(&self) {
        let _ = self.coord.handle_focus_gained(&FocusData {
            x: 0,
            y: 0,
            height: 0,
            composition_start_x: 0,
            composition_start_y: 0,
            client_token: 1,
            input_scope_mask: 0,
            disabled: false,
            reason: 0,
            caret_source: caret_source::TSF_SELECTION,
            bundle_id: String::new(),
        });
    }

    /// 焦点丢失（对应 onFinishInput）。Thread 语义 = 真正离开，清输入态。
    pub fn focus_lost(&self) {
        self.coord.handle_focus_lost(1, FocusLostReason::Thread);
    }

    /// 下达引擎命令。状态变化经 [`UiEventListener::on_status`] 异步回报，不在此处同步返回
    /// ——同一个变化也可能由按键或焦点事件引发，只留推送一条路，K 侧才不会有两份真值。
    pub fn run_command(&self, cmd: EngineCommand) {
        let menu_cmd = match cmd {
            // 布局设置不是菜单动作，直接落协调器后返回
            EngineCommand::SetShuangpinLayout { id } => {
                self.coord.set_shuangpin_layout(&id);
                return;
            }
            EngineCommand::English => MenuCmd::SchemaEnglish,
            EngineCommand::SelectSchema { index } => MenuCmd::SchemaSelect(index as usize),
            EngineCommand::TogglePunct => MenuCmd::TogglePunct,
            EngineCommand::ToggleWidth => MenuCmd::ToggleWidth,
            EngineCommand::ToggleS2t => MenuCmd::ToggleS2t,
            EngineCommand::ReloadConfig => MenuCmd::ReloadConfig,
        };
        self.coord
            .inject_ui_event(UiEvent::MenuAction(MenuKind::Command(menu_cmd)));
    }

    /// 可选方案列表。下标即 [`EngineCommand::SelectSchema`] 的 `index`
    /// ——顺序取自引擎管理器，与协调器内部的选择下标同源，K 侧不要自行排序。
    pub fn schemas(&self) -> Vec<SchemaInfo> {
        let active = self.coord.active_schema_id();
        self.coord
            .schema_entries()
            .into_iter()
            .map(|(id, name, icon_label)| SchemaInfo {
                active: id == active,
                id,
                name,
                icon_label,
            })
            .collect()
    }

    /// 拉取候选全量的一个窗口。**纯读**，不改状态，可随时调。
    ///
    /// 别在每次按键都拉一大段：序列化与宿主侧的文字测量都在主线程上。
    /// 起手拉够铺满一两屏即可，用户滚到尾部再续取。
    pub fn candidates(&self, offset: u32, limit: u32) -> CandidateWindow {
        let w = self.coord.candidate_window(offset as usize, limit as usize);
        CandidateWindow {
            items: w.items,
            offset: w.offset as u32,
            total: w.total as u32,
            selected: w.selected as u32,
        }
    }

    /// 按**绝对下标**选词，返回编辑指令流（与 [`Self::key_down`] 同一种形状）。
    ///
    /// 取代此前"合成数字键"的做法——那条路把选词限死在页内 1-9，第 10 个及以后
    /// 永远点不到。
    pub fn select_candidate(&self, index: u32) -> KeyResult {
        self.coord.select_candidate(index as usize).into()
    }

    /// 可选双拼布局。清单由核心扫描目录得出，**K 侧不要硬编码**——
    /// 用户可以往 `schemas/shuangpin/` 里丢自己的布局文件，硬编码清单看不见它。
    pub fn shuangpin_layouts(&self) -> Vec<LayoutInfo> {
        let active = self.coord.active_shuangpin_layout();
        self.coord
            .shuangpin_layouts()
            .into_iter()
            .map(|(id, name)| LayoutInfo {
                active: id == active,
                id,
                name,
            })
            .collect()
    }
}

/// 扫描 `data/schemas/*.schema.toml`，返回已安装的方案 id。
///
/// 顺序按文件名排序以保证**稳定**：方案下标要回送给引擎选择（`SelectSchema`），
/// 顺序随目录遍历漂移会让「上次选的第 2 个」这次指向另一个方案。
fn scan_installed_schemas(data_dir: &Path) -> Vec<String> {
    let Ok(entries) = std::fs::read_dir(data_dir.join("schemas")) else {
        return Vec::new();
    };
    let mut ids: Vec<String> = entries
        .flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().into_owned();
            name.strip_suffix(".schema.toml").map(str::to_owned)
        })
        .collect();
    ids.sort();
    ids
}

/// 喂键。**不再喂合成 caret**：核心侧 `set_caret_independent(true)` 已让首显闸门
/// 对自绘候选的宿主直接放行。此前这里必须编造一组非零坐标去骗过闸门
/// （`height` 写 0 还会被判为「宿主尚未 reflow」整帧丢弃，候选一次都不下发）。
fn dispatch_key(coord: &Coordinator, vk: u32) -> BridgeKeyAction {
    coord.handle_key_event(&KeyEventData {
        key_code: vk,
        scan_code: 0,
        modifiers: 0,
        event_type: EVENT_KEY_DOWN,
        toggles: 0,
        event_seq: 0,
        prev_char: 0,
    })
}
