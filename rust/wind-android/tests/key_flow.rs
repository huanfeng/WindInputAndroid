//! Android FFI 通道的按键流诊断：模拟 Kotlin 侧真实调用序列，
//! 断言 KeyResult 与候选回调——设备上「候选只出一次 / 空格数字回车退格无效」
//! 这类问题必须先在此复现，别在模拟器上试错。

use std::sync::{Arc, Mutex};

use wind_android::{CandidateUpdate, InputStatus, UiEventListener, WindCore};

#[derive(Default)]
struct Recorder {
    /// 每次候选回调的 (preedit, items)
    candidates: Mutex<Vec<(String, Vec<String>)>>,
    hides: Mutex<usize>,
    status: Mutex<Vec<InputStatus>>,
    toasts: Mutex<Vec<String>>,
}

impl UiEventListener for Recorder {
    fn on_candidates(&self, u: CandidateUpdate) {
        self.candidates.lock().unwrap().push((u.preedit, u.items));
    }
    fn on_hide_candidates(&self) {
        *self.hides.lock().unwrap() += 1;
    }
    fn on_status(&self, status: InputStatus) {
        self.status.lock().unwrap().push(status);
    }
    fn on_toast(&self, text: String) {
        self.toasts.lock().unwrap().push(text);
    }
}

/// 出厂数据取**主仓的** `build_dev/data`（方案与词库的唯一真源，不在本仓复制一份）。
///
/// 路径假设两仓并列存放，与 `Cargo.toml` 里 wind-mobile 的 path 依赖同一个前提。
fn data_dir() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../WindInput/build_dev/data")
}

fn has_schemas() -> bool {
    data_dir().join("schemas/wubi86.schema.toml").exists()
}

/// 独立用户根：避免测试间共用 redb / 缓存。
///
/// ⚠ `tag` 必须每个测试各不相同。**redb 是进程级独占锁**：多个测试并行开同一个
/// `userdata.redb` 时只有一个能拿到，其余静默退化成 `store=None`（只 warn 一行），
/// 于是「系统短语层为空」这种症状会随测试调度随机出现——排查时极易误判成引擎问题。
fn setup(tag: &str) -> Option<(Arc<WindCore>, Arc<Recorder>)> {
    let (core, rec) = setup_no_focus(tag)?;
    core.focus_gained();
    Some((core, rec))
}

/// 进程级 XDG 环境变量的互斥。
///
/// `WindCore::new` 用 **进程级环境变量**（XDG_*）指定用户目录——那是骨架期的妥协，
/// 本就有 TODO。并行构造时后一个 `set_var` 会盖掉前一个，于是「持久化到用户配置」
/// 的测试（如模式开关）会写到别的测试的目录里，随调度随机失败。
/// 上游支持显式目录注入后可去掉本锁。
static SETUP_LOCK: Mutex<()> = Mutex::new(());

fn env_lock() -> std::sync::MutexGuard<'static, ()> {
    SETUP_LOCK.lock().unwrap_or_else(|e| e.into_inner())
}

/// 同 [`setup`] 但不建立焦点（用于判定按键路径对焦点的依赖）
fn setup_no_focus(tag: &str) -> Option<(Arc<WindCore>, Arc<Recorder>)> {
    let _guard = env_lock();
    build_core(tag, true)
}

/// **持锁到测试结束**的构造：凡是会写用户配置的测试都必须用它。
///
/// 只在构造期持锁是不够的：`Config::set_user_string`（方案选择、模式开关都会调）
/// 写的是**当前进程** `XDG_CONFIG_HOME` 指向的目录，而那个值已经被后一个并行测试的
/// 构造改掉了。实测症状：本文件的「重建后仍是上次选的方案」读到的是隔壁测试选的
/// `shuangpin`——看起来像持久化坏了，实际是写串了目录。
///
/// 返回的 guard 必须绑到具名变量（`let (_c, _r, _guard) = …`）；绑到 `_` 会当场析构。
fn setup_exclusive(
    tag: &str,
) -> Option<(
    Arc<WindCore>,
    Arc<Recorder>,
    std::sync::MutexGuard<'static, ()>,
)> {
    let guard = env_lock();
    let (core, rec) = build_core(tag, true)?;
    core.focus_gained();
    Some((core, rec, guard))
}

/// **把用户配置层钉到 temp**，整个测试二进制共用一份。
///
/// `WindCore::new` 里那套 XDG 环境变量挂载**只在类 unix 上生效**；测试跑在 Windows
/// 宿主上，`dirs::config_dir()` 根本不看 XDG，于是 `Config::set_user_string`
/// （方案选择、模式开关都会调）写的是开发者本机真实的
/// `%APPDATA%\WindInput\config.toml`。后果有两层，第二层更严重：
/// - 测试之间共用同一份用户配置，互相污染（实测：拼音测试写下的 `schema.active`
///   让隔壁测试的编码栏变成 `a'a`，而那条测试与拼音毫无关系）；
/// - 跑一次测试就把开发者自己桌面输入法的活跃方案改掉了。
///
/// `WIND_DATADIR_CONF` 指向一个 `datadir.conf`，其内容即用户数据目录绝对路径。
/// 该值在 wind-config 里由 `OnceLock` 缓存，故**必须在任何 Config 调用之前**设好——
/// 所有测试都经 [`build_core`] 进来，这里是唯一的入口。
fn isolated_user_layer() -> &'static std::path::PathBuf {
    static DIR: std::sync::OnceLock<std::path::PathBuf> = std::sync::OnceLock::new();
    DIR.get_or_init(|| {
        let dir = std::env::temp_dir().join("wind-android-test-userlayer");
        let _ = std::fs::create_dir_all(&dir);
        let conf = std::env::temp_dir().join("wind-android-test-datadir.conf");
        let _ = std::fs::write(&conf, dir.to_string_lossy().as_bytes());
        // SAFETY: 调用方持 SETUP_LOCK，且本闭包全进程只跑一次
        unsafe { std::env::set_var("WIND_DATADIR_CONF", &conf) };
        dir
    })
}

/// 构造内核，**不加锁**（调用方负责持锁）。
///
/// `fresh=true` 先删掉共享用户层的 `config.toml`，让每个测试从出厂默认（五笔）起步。
/// 只有「重建后仍是上次选的方案」那条要 `false`，它正是要复用上一次的落盘。
fn build_core(tag: &str, fresh: bool) -> Option<(Arc<WindCore>, Arc<Recorder>)> {
    if !has_schemas() {
        eprintln!("跳过：缺少 build_dev/data 方案");
        return None;
    }
    let user_layer = isolated_user_layer();
    if fresh {
        let _ = std::fs::remove_file(user_layer.join("config.toml"));
    }
    let rec = Arc::new(Recorder::default());
    let user_root = std::env::temp_dir().join(format!("wind-android-test-{tag}"));
    if fresh {
        let _ = std::fs::remove_dir_all(&user_root);
    }
    let core = WindCore::new(
        data_dir().to_string_lossy().into_owned(),
        user_root.to_string_lossy().into_owned(),
        "wubi86".into(),
        rec.clone(),
    );
    Some((core, rec))
}

/// 等泵线程把回调送达（跨线程通道）
/// 结果里是否有任何会改变文本的指令（Commit / ReplaceBackward）
fn has_text(r: &wind_android::KeyResult) -> bool {
    r.ops
        .iter()
        .any(|o| matches!(o.kind, 0 | 3) && !o.text.is_empty())
}

fn settle() {
    std::thread::sleep(std::time::Duration::from_millis(120));
}

const VK_A: u32 = 0x41;
const VK_SPACE: u32 = 0x20;
const VK_BACK: u32 = 0x08;
const VK_RETURN: u32 = 0x0D;
const VK_1: u32 = 0x31;

/// 连续两轮输入：候选必须每轮都下发（设备现象：第二轮不出候选）
#[test]
fn candidates_appear_on_every_round() {
    let Some((core, rec)) = setup("candidates_appear_on_every_round") else {
        return;
    };

    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let round1 = rec.candidates.lock().unwrap().len();
    assert!(round1 > 0, "第一轮必须有候选回调");

    // 空格上屏，回到空缓冲
    core.key_down(VK_SPACE, 0);
    settle();
    rec.candidates.lock().unwrap().clear();

    // 第二轮同样输入
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let got: Vec<_> = rec
        .candidates
        .lock()
        .unwrap()
        .iter()
        .filter(|(_, items)| !items.is_empty())
        .cloned()
        .collect();
    assert!(!got.is_empty(), "第二轮必须同样下发候选（设备实测缺失）");
}

/// 空缓冲下的功能键：必须交还宿主（consumed=false）而不是被静默吞掉
#[test]
fn empty_buffer_function_keys_not_swallowed() {
    let Some((core, _rec)) = setup("empty_buffer_function_keys_not_swallowed") else {
        return;
    };

    let space = core.key_down(VK_SPACE, 0);
    assert!(
        !space.consumed || has_text(&space),
        "空缓冲空格：要么交还宿主、要么自己上屏空格，不能既消费又无输出",
    );

    let enter = core.key_down(VK_RETURN, 0);
    assert!(
        !enter.consumed || has_text(&enter),
        "空缓冲回车：不能既消费又无输出（设备现象：回车无效）",
    );

    let back = core.key_down(VK_BACK, 0);
    assert!(
        !back.consumed || has_text(&back),
        "空缓冲退格：不能既消费又无输出（设备现象：删除无效）",
    );

    let digit = core.key_down(VK_1, 0);
    assert!(
        !digit.consumed || has_text(&digit),
        "空缓冲数字：不能既消费又无输出（设备现象：数字打不出）",
    );
}

/// 有候选时数字键 = 选词上屏
#[test]
fn digit_selects_candidate_when_composing() {
    let Some((core, _rec)) = setup("digit_selects_candidate_when_composing") else {
        return;
    };

    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    let r = core.key_down(VK_1, 0);
    assert!(has_text(&r), "组合中按 1 应上屏首选，实得 {r:?}",);
}

/// 系统短语层 + 命令直通车求值器都必须接通。
///
/// 守的是**出厂数据与 store 到位**而不是短语引擎本身：短语层在协调器构造期从
/// `data/system.phrases.toml` 同步进 redb，文件缺失或 store 未开时都只 warn 一句，
/// 运行期表现为「短语一条也不出」——没有这条断言就只能靠肉眼在设备上发现。
///
/// 探针用 `uuid` 而不是 `date`：两者都是出厂短语，但 `date` 在五笔下的码表候选
/// 极多，短语被挤到第二页之后，首页断言不到——那是**排序**问题（短语 tier 权重，
/// 桌面同样如此），不是通路问题，别拿它当通路探针。`uuid` 的四码在五笔里冷门，
/// 短语直接落在首页。
///
/// 这条同时守两件事：短语数据进了 redb（store 为 `None` 时短语层整段为空），
/// 以及 `{uuid()}` 被求值（命令短语求值失败会整条被丢弃，表现同样是「什么也不出」）。
///
/// ⚠ 断言只看**前缀形态**：候选文本在下发前会按显示宽度截断（实测形如
/// `db9877f2-53f3-4e…`），按完整 36 字符 UUID 断言必然失败。
#[test]
fn system_phrase_and_cmdbar_reach_candidates() {
    let Some((core, rec)) = setup("system_phrase_and_cmdbar_reach_candidates") else {
        return;
    };
    for vk in [0x55u32, 0x55, 0x49, 0x44] {
        core.key_down(vk, 0);
    }
    settle();
    let all: Vec<String> = rec
        .candidates
        .lock()
        .unwrap()
        .iter()
        .flat_map(|(_, items)| items.clone())
        .collect();
    let looks_like_uuid = |t: &String| {
        let head: String = t.chars().take(9).collect();
        head.len() == 9 && head.ends_with('-') && head[..8].chars().all(|c| c.is_ascii_hexdigit())
    };
    assert!(
        all.iter().any(looks_like_uuid),
        "输入 uuid 应出命令短语求值结果，实得 {all:?}\
         （无任何短语候选 = system.phrases.toml 未部署或 store 未开）",
    );
}

/// 预热必须**不留状态**。
///
/// `focus_gained` 会在后台喂一次真实按键去触发惰性构建（否则用户首键同步阻塞 2.8 秒）。
/// 那次假按键若没清干净，用户接着打 "aa" 会得到 "aaa" ——编码栏、候选、上屏全错位。
/// 本测试在预热窗口之后立刻检查缓冲为空。
#[test]
fn warmup_leaves_no_residue() {
    let Some((core, rec)) = setup("warmup_leaves_no_residue") else {
        return;
    };
    settle(); // 让预热线程跑完
    rec.candidates.lock().unwrap().clear();

    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let preedit = rec
        .candidates
        .lock()
        .unwrap()
        .last()
        .map(|(p, _)| p.clone())
        .unwrap_or_default();
    assert_eq!(preedit, "aa", "预热残留了编码（缓冲未清空）");
}

/// 组合中的编码变化必须以 `SetComposition` 指令下发。
///
/// 旧的三元组投影（commit_text + composition + changed）只装得下 InsertText 族，
/// 其余动作在 FFI 层被 `_ =>` 压成「已消费、无输出」。现在指令流由核心的**穷尽**映射
/// 产出，本用例守住最基本的一条：打字要产出组合区指令，而不是什么都不产出。
#[test]
fn composing_emits_composition_ops() {
    let Some((core, _rec)) = setup("composing_emits_composition_ops") else {
        return;
    };
    let r = core.key_down(VK_A, 0);
    assert!(r.consumed, "中文模式字母应被吃");
    assert!(
        r.ops.iter().any(|o| o.kind == 1),
        "应产出 SetComposition 指令，实得 {:?}",
        r.ops,
    );
}

/// 编码栏所需字段必须有值：preedit 跟着缓冲走，页码从 1 起。
#[test]
fn candidate_update_carries_preedit_and_paging() {
    let Some((core, rec)) = setup("candidate_update_carries_preedit_and_paging") else {
        return;
    };
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let got = rec.candidates.lock().unwrap().last().cloned();
    let Some((preedit, items)) = got else {
        panic!("无候选回调")
    };
    assert_eq!(preedit, "aa", "编码栏正文应为当前编码缓冲");
    assert!(!items.is_empty());
}

/// 模式命令走得通，且状态经推送回报（K 侧不自记开关态的前提）。
#[test]
fn engine_command_toggles_status() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("engine_command_toggles_status") else {
        return;
    };
    rec.status.lock().unwrap().clear();

    core.run_command(EngineCommand::TogglePunct);
    settle();
    let punct_after = rec.status.lock().unwrap().last().map(|s| s.chinese_punct);
    assert_eq!(punct_after, Some(false), "中文标点应被关掉并推送新状态");

    core.run_command(EngineCommand::English);
    settle();
    let chinese = rec.status.lock().unwrap().last().map(|s| s.chinese_mode);
    assert_eq!(chinese, Some(false), "切英文应推送 chinese_mode=false");
}

/// 诊断：不调 `focus_gained` 时按键还出不出候选。
///
/// 决定预热能否提前到**构造期**（比 `focus_gained` 早约 1.3 秒，真机上这段差距
/// 正好是用户点开输入框到敲第一个键的窗口）。若无焦点也能出候选，说明当初
/// 「构造期预热只花 3ms」的原因是漏了 caret 而不是缺焦点，预热就该往前挪。
#[test]
fn diag_candidates_without_focus() {
    let Some((core, rec)) = setup_no_focus("diag_candidates_without_focus") else {
        return;
    };
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let n = rec
        .candidates
        .lock()
        .unwrap()
        .iter()
        .filter(|(_, items)| !items.is_empty())
        .count();
    println!("无焦点时的候选帧数 = {n}");
    assert!(n > 0, "无焦点也应出候选 → 预热可提前到构造期");
}

/// 英文模式下字母必须能出字。
///
/// 契约同「空缓冲功能键」那条：引擎要么自己上屏、要么交还宿主（consumed=false），
/// **不能既消费又无输出**——那是设备上「切到英文就打不出字」的形态。
#[test]
fn english_mode_letters_produce_text() {
    use wind_android::EngineCommand;
    let Some((core, _rec, _env)) = setup_exclusive("english_mode_letters_produce_text") else {
        return;
    };
    core.run_command(EngineCommand::English);
    settle();

    for (name, vk) in [("a", 0x41u32), ("b", 0x42), ("z", 0x5A)] {
        let r = core.key_down(vk, 0);
        assert!(
            !r.consumed || has_text(&r),
            "英文模式下 '{name}' 既被消费又无输出：{r:?}",
        );
    }
}

/// 切回中文后仍能正常组合（英↔中往返不留坏状态）
#[test]
fn switch_back_to_chinese_still_composes() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("switch_back_to_chinese_still_composes") else {
        return;
    };
    core.run_command(EngineCommand::English);
    settle();
    core.key_down(0x41, 0);
    core.run_command(EngineCommand::SelectSchema { index: 0 });
    settle();
    rec.candidates.lock().unwrap().clear();

    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let got: Vec<_> = rec
        .candidates
        .lock()
        .unwrap()
        .iter()
        .filter(|(_, items)| !items.is_empty())
        .cloned()
        .collect();
    assert!(!got.is_empty(), "切回中文后应重新出候选");
}

/// 切到**英文方案**后，上屏必须走英文而不是中文码表。
///
/// 真机现象（主仓已修，2026-10 起本测试转绿）：切到英文方案后候选正确显示
/// `hi / Hi-Fi / hibernate`，按空格上屏的却是「步」——`hi` 在五笔里的字。根因是
/// `select_schema` 一律把 `chinese_mode` 置真，制造出「english 引擎 + chinese_mode=true」
/// 这个核心未定义的组合：候选走英文引擎、上屏走中文路径。
///
/// 桌面从没暴露这个问题，因为英文引擎在那边只经「临时英文」进入（本就 false）。
#[test]
fn english_schema_commits_latin_not_hanzi() {
    use wind_android::EngineCommand;
    let Some((core, _rec, _env)) = setup_exclusive("english_schema_commits_latin_not_hanzi") else {
        return;
    };
    let Some(en) = core.schemas().iter().position(|s| s.id == "english") else {
        eprintln!("跳过：未安装英文方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();
    // h, i
    core.key_down(0x48, 0);
    core.key_down(0x49, 0);
    let r = core.key_down(VK_SPACE, 0);
    let text: String = r
        .ops
        .iter()
        .filter(|o| o.kind == 0)
        .map(|o| o.text.clone())
        .collect();
    assert!(
        !text.is_empty() && text.is_ascii(),
        "英文方案下上屏应为拉丁文本，实得 {text:?}（非 ASCII = 走了中文码表）",
    );
}

/// 方案列表非空且下标可回送（方案选择器的数据源）
#[test]
fn schema_list_is_indexable() {
    let Some((core, _rec)) = setup("schema_list_is_indexable") else {
        return;
    };
    let list = core.schemas();
    assert!(!list.is_empty(), "方案列表不应为空");
    assert!(
        list.iter().any(|s| s.active),
        "必须有且仅有一个 active 方案，实得 {list:?}",
    );
}

/// 诊断：打印各键的原始返回，定位「消费但无输出」的来源
#[test]
fn diag_dump_key_results() {
    let Some((core, rec)) = setup("diag_dump_key_results") else {
        return;
    };
    for (name, vk) in [
        ("SPACE(空缓冲)", VK_SPACE),
        ("RETURN(空缓冲)", VK_RETURN),
        ("BACK(空缓冲)", VK_BACK),
        ("1(空缓冲)", VK_1),
        ("A", VK_A),
        ("A", VK_A),
        ("SPACE(有候选)", VK_SPACE),
    ] {
        let r = core.key_down(vk, 0);
        println!("{name:>16} -> consumed={} ops={:?}", r.consumed, r.ops);
    }
    settle();
    println!(
        "候选回调次数={} 隐藏={}",
        rec.candidates.lock().unwrap().len(),
        rec.hides.lock().unwrap()
    );
    for (p, items) in rec.candidates.lock().unwrap().iter() {
        println!("  preedit={p:?} items={:?}", &items[..items.len().min(5)]);
    }
}

// ── 拼音系方案（拼音 / 双拼 / 五笔拼音混输）────────────────────────────

/// 切到全拼后应出汉字候选并能上屏。
///
/// 这条守的是**方案数据随包发**这件事本身：拼音方案的词库有近 20MB、且由
/// `rime_frost.dict.yaml` 的 `import_tables` 间接引用 `cn_dicts/*`——少拷一个子目录
/// 不会报错，只会静默地零候选。设备上表现为「切到拼音就打不出字」，
/// 而日志里什么也没有。
#[test]
fn pinyin_schema_produces_hanzi() {
    use wind_android::EngineCommand;
    let Some((core, _rec, _env)) = setup_exclusive("pinyin_schema_produces_hanzi") else {
        return;
    };
    let Some(idx) = core.schemas().iter().position(|s| s.id == "pinyin") else {
        eprintln!("跳过：未安装全拼方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: idx as u32 });
    settle();
    for vk in [0x4E, 0x49, 0x48, 0x41, 0x4F] {
        // n i h a o
        core.key_down(vk, 0);
    }
    let r = core.key_down(VK_SPACE, 0);
    let text: String = r
        .ops
        .iter()
        .filter(|o| o.kind == 0)
        .map(|o| o.text.clone())
        .collect();
    assert!(
        text.chars().any(|c| ('\u{4e00}'..='\u{9fff}').contains(&c)),
        "全拼打 nihao 后空格应上屏汉字，实得 {text:?}",
    );
}

/// 双拼与五笔拼音混输方案至少要能装出来、能选中。
///
/// 不断言具体候选：双拼要按布局键位喂键、混输的首选取决于两个引擎的融合权重，
/// 都不该由「方案装没装」这条测试来承担。这里只守「列表里有、选得中」。
#[test]
fn shuangpin_and_mixed_schemas_are_selectable() {
    use wind_android::EngineCommand;
    let Some((core, _rec, _env)) = setup_exclusive("shuangpin_and_mixed_schemas_are_selectable")
    else {
        return;
    };
    for id in ["shuangpin", "wubi86_pinyin"] {
        let Some(idx) = core.schemas().iter().position(|s| s.id == id) else {
            panic!("方案 {id} 未安装：{:?}", core.schemas());
        };
        core.run_command(EngineCommand::SelectSchema { index: idx as u32 });
        settle();
        let active = core.schemas().into_iter().find(|s| s.active);
        assert_eq!(
            active.as_ref().map(|s| s.id.as_str()),
            Some(id),
            "选中 {id} 后 active 应为它本身",
        );
    }
}

/// **用户选的方案要活过进程重建。**
///
/// `InputMethodService` 会被反复重建，每次都新建一个 `WindCore`。构造参数里的
/// `schema` 只是首装兜底，此前却无条件覆盖了三层加载读出的用户选择——现象是
/// 「切到拼音、杀掉输入法再进来又回五笔」，切换当场却完全正常，只有重启才复现。
#[test]
fn active_schema_survives_reconstruction() {
    use wind_android::EngineCommand;
    let tag = "active_schema_survives_reconstruction";
    // 两次构造之间必须一直持锁：中途放手会让别的测试改掉 XDG_CONFIG_HOME，
    // 第二个核心就从别人的用户目录里读方案（实测读到过隔壁的 shuangpin）。
    let _env = env_lock();
    let Some((core, _rec)) = build_core(tag, true) else {
        return;
    };
    core.focus_gained();
    let Some(idx) = core.schemas().iter().position(|s| s.id == "pinyin") else {
        eprintln!("跳过：未安装全拼方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: idx as u32 });
    settle();
    drop(core); // redb 是进程级独占锁，必须先放掉再开第二个核心

    // fresh=false：本测试要的就是「读到上一个核心落盘的选择」
    let (core2, _rec2) = build_core(tag, false).expect("同 tag 复用用户目录");
    let active = core2.schemas().into_iter().find(|s| s.active);
    assert_eq!(
        active.as_ref().map(|s| s.id.as_str()),
        Some("pinyin"),
        "重建后应仍是上次选的方案（构造参数 wubi86 只是首装兜底）",
    );
}

/// 双拼布局清单可枚举，且切换后能读回。
///
/// 守两件事：布局文件随包发了（`schemas/shuangpin/*.toml` 漏拷不会报错，只会让
/// 设置页的下拉是空的），以及「写覆盖 → 读回」这条回路是通的——覆盖文件写的是
/// `schema_overrides/shuangpin.toml`，读的是合并后的方案，两侧任一处笔误都表现为
/// 「选了没反应」。
#[test]
fn shuangpin_layout_is_listable_and_settable() {
    use wind_android::EngineCommand;
    let Some((core, _rec, _env)) = setup_exclusive("shuangpin_layout_is_listable_and_settable")
    else {
        return;
    };
    let layouts = core.shuangpin_layouts();
    assert!(
        layouts.len() >= 2,
        "双拼布局清单应含多份布局，实得 {layouts:?}",
    );
    assert_eq!(
        layouts.iter().filter(|l| l.active).count(),
        1,
        "应恰有一个当前布局，实得 {layouts:?}",
    );

    // 选一个当前没在用的
    let target = layouts
        .iter()
        .find(|l| !l.active)
        .map(|l| l.id.clone())
        .expect("清单里应有非当前布局");
    core.run_command(EngineCommand::SetShuangpinLayout { id: target.clone() });
    settle();
    let after = core.shuangpin_layouts();
    assert_eq!(
        after.iter().find(|l| l.active).map(|l| l.id.as_str()),
        Some(target.as_str()),
        "设置后应读回新布局，实得 {after:?}",
    );
}

// ── 候选拉取与绝对下标选词 ────────────────────────────────────────────

/// 候选窗口能读到**超出当页**的候选，且总数与推送帧的页数自洽。
///
/// 这条守的是「滚动候选栏有东西可滚」：推送帧只给当页 6~9 条，此前宿主拿不到更多，
/// 候选栏做了滚动也是空转、展开面板号称全部候选却只有一屏。
#[test]
fn candidate_window_reaches_beyond_current_page() {
    let Some((core, rec)) = setup("candidate_window_reaches_beyond_current_page") else {
        return;
    };
    // 单码 a：五笔下重码极多，必然不止一页
    core.key_down(VK_A, 0);
    settle();
    let page_items = rec
        .candidates
        .lock()
        .unwrap()
        .last()
        .map(|(_, items)| items.len())
        .unwrap_or(0);
    assert!(page_items > 0, "应有候选");

    let w = core.candidates(0, 100);
    assert!(
        (w.total as usize) > page_items,
        "全量({})应多于当页({})，否则拉取接口没有意义",
        w.total,
        page_items,
    );
    assert_eq!(
        w.items.len(),
        (w.total as usize).min(100),
        "窗口应装满 limit 或全量，实得 {}",
        w.items.len(),
    );
    assert_eq!(w.offset, 0);
}

/// 窗口硬上限：宿主要多少都不会真把全量搬过去（性能护栏）。
#[test]
fn candidate_window_is_capped() {
    let Some((core, _rec)) = setup("candidate_window_is_capped") else {
        return;
    };
    core.key_down(VK_A, 0);
    settle();
    let w = core.candidates(0, u32::MAX);
    assert!(
        w.items.len() <= wind_mobile::MAX_WINDOW,
        "单次拉取不得超过 MAX_WINDOW，实得 {}",
        w.items.len(),
    );
}

/// 越界 offset 返回空 items，但 total 仍有效（宿主据此知道是自己越界而非候选没了）。
#[test]
fn candidate_window_past_end_is_empty_not_lying() {
    let Some((core, _rec)) = setup("candidate_window_past_end_is_empty_not_lying") else {
        return;
    };
    core.key_down(VK_A, 0);
    settle();
    let total = core.candidates(0, 1).total;
    assert!(total > 0);
    let w = core.candidates(total + 10, 20);
    assert!(w.items.is_empty(), "越界应为空");
    assert_eq!(w.total, total, "越界不该谎报总数为 0");
}

/// **能选到第 10 个及以后**——合成数字键时代做不到的那件事。
#[test]
fn select_candidate_beyond_ninth() {
    let Some((core, _rec)) = setup("select_candidate_beyond_ninth") else {
        return;
    };
    core.key_down(VK_A, 0);
    settle();
    let w = core.candidates(0, 40);
    assert!(
        w.items.len() > 12,
        "本用例需要至少 13 个候选，实得 {}",
        w.items.len(),
    );
    let target = w.items[12].clone();
    let r = core.select_candidate(12);
    let text: String = r
        .ops
        .iter()
        .filter(|o| o.kind == 0)
        .map(|o| o.text.clone())
        .collect();
    assert_eq!(text, target, "选第 13 个应上屏它本身，实得 {text:?}");
}

/// 越界选词不能既消费又无输出（与吃键契约同一条规矩）。
#[test]
fn select_candidate_out_of_range_is_passthrough() {
    let Some((core, _rec)) = setup("select_candidate_out_of_range_is_passthrough") else {
        return;
    };
    core.key_down(VK_A, 0);
    settle();
    let total = core.candidates(0, 1).total;
    let r = core.select_candidate(total + 5);
    assert!(!r.consumed, "越界选词应交还宿主");
    assert!(r.ops.is_empty(), "越界选词不应产生编辑指令");
}

/// 英文方案的补全是**前缀匹配**，首候选恒为所打原文（v0.3 Stage 3 的前置核对）。
///
/// 移动端英文输入靠它：打 `beau` 候选要出 `beautiful`，而空格要上屏 `beau` 本身
/// （手机上的习惯是空格出原文、点候选才换词）。
#[test]
fn english_schema_prefix_completion() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("english_schema_prefix_completion") else {
        return;
    };
    let Some(en) = core.schemas().iter().position(|s| s.id == "english") else {
        eprintln!("跳过：未安装英文方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();
    rec.candidates.lock().unwrap().clear();
    for vk in [0x42u32, 0x45, 0x41, 0x55] {
        core.key_down(vk, 0); // b e a u
    }
    settle();
    let items = rec
        .candidates
        .lock()
        .unwrap()
        .iter()
        .rev()
        .find(|(_, items)| !items.is_empty())
        .map(|(_, items)| items.clone())
        .unwrap_or_default();
    eprintln!("beau 候选：{items:?}");
    assert_eq!(items.first().map(String::as_str), Some("beau"), "首候选应为原文");
    assert!(
        items.iter().any(|w| w.to_ascii_lowercase().starts_with("beau") && w.len() > 4),
        "应有以 beau 开头的补全，实得 {items:?}",
    );
    let r = core.key_down(VK_SPACE, 0);
    let text: String = r.ops.iter().filter(|o| o.kind == 0).map(|o| o.text.clone()).collect();
    eprintln!("空格上屏：{text:?}");
    assert!(text.starts_with("beau"), "空格应上屏原文，实得 {text:?}");
}

/// 英文方案下 Shift+字母（句首自动大写、手按 ⇧）要进引擎组合，不能直出：
/// 直出的话大写字母先上屏、后面的字母另起一段编码，`Hello` 的补全就断了。
#[test]
fn english_schema_shifted_letter_composes() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("english_schema_shifted_letter_composes") else {
        return;
    };
    let Some(en) = core.schemas().iter().position(|s| s.id == "english") else {
        eprintln!("跳过：未安装英文方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();
    rec.candidates.lock().unwrap().clear();
    let first = core.key_down(0x48, 0x0001); // Shift+H
    eprintln!("Shift+H：consumed={} ops={:?}", first.consumed, first.ops.iter().map(|o| (o.kind, o.text.clone())).collect::<Vec<_>>());
    for vk in [0x45u32, 0x4C, 0x4C] {
        core.key_down(vk, 0); // e l l
    }
    settle();
    let last = rec
        .candidates
        .lock()
        .unwrap()
        .iter()
        .rev()
        .find(|(_, items)| !items.is_empty())
        .cloned()
        .unwrap_or_default();
    eprintln!("Hell 编码={:?} 候选={:?}", last.0, last.1);
    assert!(first.consumed && first.ops.iter().all(|o| o.kind != 0), "Shift+H 应进组合而不是直接上屏");
    assert_eq!(last.1.first().map(String::as_str), Some("Hell"), "首候选应为原文（保留大小写）");
    assert!(last.1.iter().any(|w| w.starts_with("Hello")), "应补全出 Hello，实得 {:?}", last.1);
}
