//! Android FFI 通道的按键流诊断：模拟 Kotlin 侧真实调用序列，
//! 断言 KeyResult 与候选回调——设备上「候选只出一次 / 空格数字回车退格无效」
//! 这类问题必须先在此复现，别在模拟器上试错。

use std::sync::{Arc, Mutex};

use wind_android::{CandidateUpdate, InputStatus, UiEventListener, WindCore};

#[derive(Default)]
struct Recorder {
    /// 每次候选回调的 (preedit, has_candidates)。候选文本不在帧里，要看文本用 [`items_now`]
    candidates: Mutex<Vec<(String, bool)>>,
    /// 每次候选回调的 composing 标志，与 `candidates` 一一对应
    composing: Mutex<Vec<bool>>,
    /// 每次候选回调的 (page, total_pages)，与 `candidates` 一一对应
    paging: Mutex<Vec<(u32, u32)>>,
    hides: Mutex<usize>,
    status: Mutex<Vec<InputStatus>>,
    toasts: Mutex<Vec<String>>,
}

impl UiEventListener for Recorder {
    fn on_candidates(&self, u: CandidateUpdate) {
        self.composing.lock().unwrap().push(u.composing);
        self.paging.lock().unwrap().push((u.page, u.total_pages));
        self.candidates
            .lock()
            .unwrap()
            .push((u.preedit, u.has_candidates));
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

/// 全部候选文本（至多一个 `MAX_WINDOW`），**此刻**从核心拉取（帧里不再带文本）。
///
/// 范围是全窗口而非首页：「必须包含 / 首项是」类断言只更宽于「当页」的部分来自
/// 后面页的候选，「不得包含」类断言则更严。
fn items_now(core: &WindCore) -> Vec<String> {
    core.candidates(0, wind_mobile::MAX_WINDOW as u32).items
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
        .filter(|(_, has)| *has)
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
    let Some((core, _rec)) = setup("system_phrase_and_cmdbar_reach_candidates") else {
        return;
    };
    for vk in [0x55u32, 0x55, 0x49, 0x44] {
        core.key_down(vk, 0);
    }
    settle();
    let all: Vec<String> = items_now(&core);
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
    let Some((preedit, has_candidates)) = got else {
        panic!("无候选回调")
    };
    assert_eq!(preedit, "aa", "编码栏正文应为当前编码缓冲");
    assert!(has_candidates);
    assert!(!items_now(&core).is_empty());
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
        .filter(|(_, has)| *has)
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
        .filter(|(_, has)| *has)
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
    for (p, has) in rec.candidates.lock().unwrap().iter() {
        println!("  preedit={p:?} has_candidates={has}");
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
/// 这条守的是「滚动候选栏有东西可滚」：推送帧只给当页，此前宿主拿不到更多，
/// 候选栏做了滚动也是空转、展开面板号称全部候选却只有一屏。
/// 帧里已无候选文本，故用帧的 `total_pages` 证明「不止一页」，再证明窗口与绝对下标选词都越过第一页。
#[test]
fn candidate_window_reaches_beyond_current_page() {
    let Some((core, rec)) = setup("candidate_window_reaches_beyond_current_page") else {
        return;
    };
    // 单码 a：五笔下重码极多，必然不止一页
    core.key_down(VK_A, 0);
    settle();
    let (page, total_pages) = rec
        .paging
        .lock()
        .unwrap()
        .last()
        .copied()
        .expect("应有候选帧");
    assert_eq!(page, 1, "页码从 1 起");
    assert!(total_pages > 1, "前提：候选不止一页，实得 {total_pages} 页");

    let w = core.candidates(0, 100);
    assert_eq!(w.offset, 0);
    assert_eq!(
        w.items.len(),
        (w.total as usize).min(100),
        "窗口应装满 limit 或全量，实得 {}",
        w.items.len(),
    );
    // 帧说有 total_pages(>1) 页，即全量 > 每页条数；窗口末项的下标 >= 每页条数，
    // 所以它必在第二页或更后——选中它就是「越过第一页」的直接证明。
    assert!(w.items.len() > 1, "窗口应不止一条");
    let last = w.items.len() - 1;
    let target = w.items[last].clone();
    let r = core.select_candidate(last as u32);
    let text: String = r
        .ops
        .iter()
        .filter(|o| o.kind == 0)
        .map(|o| o.text.clone())
        .collect();
    assert_eq!(text, target, "应能选中窗口末尾（第一页之外）的候选");
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
    let items = items_now(&core);
    eprintln!("beau 候选：{items:?}");
    assert_eq!(
        items.first().map(String::as_str),
        Some("beau"),
        "首候选应为原文"
    );
    assert!(
        items
            .iter()
            .any(|w| w.to_ascii_lowercase().starts_with("beau") && w.len() > 4),
        "应有以 beau 开头的补全，实得 {items:?}",
    );
    let r = core.key_down(VK_SPACE, 0);
    let text: String = r
        .ops
        .iter()
        .filter(|o| o.kind == 0)
        .map(|o| o.text.clone())
        .collect();
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
    eprintln!(
        "Shift+H：consumed={} ops={:?}",
        first.consumed,
        first
            .ops
            .iter()
            .map(|o| (o.kind, o.text.clone()))
            .collect::<Vec<_>>()
    );
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
        .find(|(_, has)| *has)
        .map(|(preedit, _)| (preedit.clone(), items_now(&core)))
        .unwrap_or_default();
    eprintln!("Hell 编码={:?} 候选={:?}", last.0, last.1);
    assert!(
        first.consumed && first.ops.iter().all(|o| o.kind != 0),
        "Shift+H 应进组合而不是直接上屏"
    );
    assert_eq!(
        last.1.first().map(String::as_str),
        Some("Hell"),
        "首候选应为原文（保留大小写）"
    );
    assert!(
        last.1.iter().any(|w| w.starts_with("Hello")),
        "应补全出 Hello，实得 {:?}",
        last.1
    );
}

/// 设置页写引擎项：`config_set` 写进核心的用户层，快照里读得回来；
/// 不认识的键当场报错（设置清单写错键名不能「点了没反应」）。
#[test]
fn config_set_roundtrips_and_rejects_unknown_key() {
    use wind_android::{ConfigError, ConfigValue};
    let Some((core, _rec, _env)) = setup_exclusive("config_set_roundtrip") else {
        return;
    };
    core.config_set(
        "input.association.max_count".into(),
        ConfigValue::Int { v: 7 },
    )
    .expect("已注册的键应写入成功");
    let snap = core.config_snapshot();
    let in_section = snap
        .split("\n[")
        .find(|s| s.starts_with("input.association]"))
        .unwrap_or_default();
    assert!(
        in_section.lines().any(|l| l.trim() == "max_count = 7"),
        "快照的 [input.association] 里应读回 max_count = 7"
    );
    let err = core.config_set("no.such.key".into(), ConfigValue::Bool { v: true });
    assert!(
        matches!(err, Err(ConfigError::Rejected { .. })),
        "未注册的键应报错，实得 {err:?}"
    );
}

/// 注册表里报成 enum 的键都带值域（设置页据此画选择器）。
#[test]
fn config_registry_enums_have_options() {
    let Some((core, _rec)) = setup("config_registry_enums") else {
        return;
    };
    let reg = core.config_registry();
    assert!(reg.iter().any(|f| f.key == "input.association.kind"));
    let empty: Vec<_> = reg
        .iter()
        .filter(|f| f.ty == "enum" && f.options.is_empty())
        .map(|f| f.key.clone())
        .collect();
    assert!(empty.is_empty(), "以下枚举键没有选项：{empty:?}");
}

/// 统计：打字上屏后今日字数随之增加（读的是采集器内存，不必等 flush）。
#[test]
fn stats_summary_counts_commits() {
    let Some((core, _rec, _env)) = setup_exclusive("stats_summary") else {
        return;
    };
    let before = core.stats_summary().map_or(0, |s| s.today_chars);
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    core.key_down(VK_SPACE, 0);
    let after = core.stats_summary().expect("有用户库时应有统计");
    assert!(
        after.today_chars > before,
        "上屏一个字后今日字数应增加：{before} → {}",
        after.today_chars
    );
    let today = core.stats_daily("2000-01-01".into(), "2000-01-03".into());
    assert_eq!(today.len(), 3, "区间内每天一条，缺的补零");
}

/// 词库页：手动加的词立刻能打出来、列表里搜得到、删掉后两边都消失。
///
/// 「加了词却打不出」是词库页最要命的失败——列表看着有，用户以为功能坏了。
/// 所以不只断言列表，还要真按键看候选。
#[test]
fn user_word_add_list_type_remove() {
    let Some((core, rec, _env)) = setup_exclusive("user_words") else {
        return;
    };
    let schema = core
        .schemas()
        .into_iter()
        .find(|s| s.active)
        .expect("应有当前方案")
        .id;
    let (code, text) = ("xxyy", "清风测词");
    core.user_word_add(schema.clone(), code.into(), text.into())
        .expect("加词应成功");

    let page = core.user_words(schema.clone(), code.into(), 0, 20);
    assert!(
        page.items.iter().any(|w| w.text == text && w.code == code),
        "按编码搜应搜到刚加的词，实得 {:?}",
        page.items
    );

    for vk in [0x58u32, 0x58, 0x59, 0x59] {
        core.key_down(vk, 0);
    }
    settle();
    let typed: Vec<String> = items_now(&core);
    assert!(
        typed.iter().any(|t| t == text),
        "打 {code} 应出刚加的词，实得 {typed:?}"
    );
    core.key_down(0x1B, 0); // Esc 清掉编码

    core.user_word_remove(schema.clone(), code.into(), text.into())
        .expect("删词应成功");
    let page = core.user_words(schema, code.into(), 0, 20);
    assert!(
        !page.items.iter().any(|w| w.text == text),
        "删掉后列表里不应还有，实得 {:?}",
        page.items
    );

    // 候选那边也得跟着没：只删了列表、引擎缓存没失效的回归，看列表是看不出来的
    rec.candidates.lock().unwrap().clear();
    for vk in [0x58u32, 0x58, 0x59, 0x59] {
        core.key_down(vk, 0);
    }
    settle();
    let typed: Vec<String> = items_now(&core);
    assert!(
        !typed.iter().any(|t| t == text),
        "删掉后打 {code} 不应再出这个词，实得 {typed:?}"
    );
}

/// 无痕：暂停学习时打字照常上屏，但统计不涨；恢复后照常记。
///
/// 对照组（恢复后会涨）保证「不涨」不是因为统计本来就没在记。
#[test]
fn learning_suspended_records_nothing_until_resumed() {
    let Some((core, _rec, _env)) = setup_exclusive("learning_suspended") else {
        return;
    };
    let today = |c: &WindCore| c.stats_summary().map_or(0, |s| s.today_chars);
    core.set_learning_suspended(true);
    let before = today(&core);
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    core.key_down(VK_SPACE, 0);
    assert_eq!(today(&core), before, "暂停学习时统计不应增加");

    core.set_learning_suspended(false);
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    core.key_down(VK_SPACE, 0);
    assert!(today(&core) > before, "恢复后统计应照常增加");
}

/// 候选长按菜单：按绝对下标取菜单、置顶后它成为首选、恢复默认后回原位。
#[test]
fn candidate_menu_move_top_then_reset() {
    let Some((core, _rec, _env)) = setup_exclusive("candidate_menu") else {
        return;
    };
    let first_of = |c: &WindCore| {
        c.candidates(0, 3)
            .items
            .first()
            .cloned()
            .unwrap_or_default()
    };
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    let before = core.candidates(0, 5).items;
    assert!(before.len() >= 3, "aa 应有多个候选，实得 {before:?}");
    let target = before[2].clone();

    let menu = core.candidate_menu(2);
    assert!(
        menu.iter().any(|m| m.action == "move_top" && m.enabled),
        "普通词条的菜单应有可用的置顶，实得 {menu:?}"
    );
    assert!(core.candidate_menu(9999).is_empty(), "越界下标应无菜单");

    assert!(core.candidate_menu_run(2, "move_top".into()), "置顶应执行");
    assert!(
        !core.candidate_menu_run(0, "copy".into()),
        "复制由宿主做，核心不执行"
    );
    // 重打一遍码：规则落盘后对新的组合也生效
    core.key_down(0x1B, 0);
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    assert_eq!(first_of(&core), target, "置顶后应成为首选");

    assert!(core.candidate_menu_run(0, "reset".into()), "恢复默认应执行");
    core.key_down(0x1B, 0);
    core.key_down(VK_A, 0);
    core.key_down(VK_A, 0);
    settle();
    assert_eq!(first_of(&core), before[0], "恢复默认后首选回到原来的");
}

/// Emoji 附加模块：列表里有内置模块；开关写进去后读得回来，不存在的模块报错。
#[test]
fn emoji_module_toggle_roundtrips() {
    use wind_android::ConfigError;
    let Some((core, _rec, _env)) = setup_exclusive("emoji_modules") else {
        return;
    };
    let state = |c: &WindCore, id: &str| c.emoji_modules().into_iter().find(|m| m.id == id);
    let before = state(&core, "frost_greek").expect("内置模块「希腊字母」应在列表里");
    core.set_emoji_module("frost_greek".into(), !before.enabled)
        .expect("开关应写入成功");
    assert_eq!(
        state(&core, "frost_greek").map(|m| m.enabled),
        Some(!before.enabled),
        "写入后应读回新值"
    );
    assert!(matches!(
        core.set_emoji_module("no_such".into(), true),
        Err(ConfigError::Rejected { .. })
    ));
}

/// 方案附加词库：拼音列出细胞词库，开关写入后读回；不存在的库报错。
#[test]
fn schema_dict_toggle_roundtrips() {
    use wind_android::ConfigError;
    let Some((core, _rec, _env)) = setup_exclusive("schema_dicts") else {
        return;
    };
    let idiom = |c: &WindCore| {
        c.schema_dicts("pinyin".into())
            .into_iter()
            .find(|d| d.id == "cell_idiom")
    };
    let before = idiom(&core).expect("拼音方案应列出成语细胞词库");
    core.set_schema_dict_enabled("pinyin".into(), "cell_idiom".into(), !before.enabled)
        .expect("开关应写入成功");
    assert_eq!(
        idiom(&core).map(|d| d.enabled),
        Some(!before.enabled),
        "写入后应读回新值"
    );
    core.set_schema_dict_enabled("pinyin".into(), "cell_idiom".into(), before.enabled)
        .expect("恢复原值");
    assert!(matches!(
        core.set_schema_dict_enabled("pinyin".into(), "no_such".into(), true),
        Err(ConfigError::Rejected { .. })
    ));
}

/// 词库备份往返：加词 → 导出（.wdict，头部带引擎类型）→ 清空 → 导入 → 列表与按键都回来。
/// 文件格式与电脑版同一份（主仓 dict_transfer_api），这里只验绑定层这一段接通。
#[test]
fn dict_export_clear_import_round_trip() {
    let Some((core, _rec, _env)) = setup_exclusive("dict_transfer") else {
        return;
    };
    let schema = core
        .schemas()
        .into_iter()
        .find(|s| s.active)
        .expect("应有当前方案")
        .id;
    let (code, text) = ("xxyy", "清风备份");
    core.user_word_add(schema.clone(), code.into(), text.into())
        .expect("加词应成功");

    let file = core.dict_export(schema.clone()).expect("导出应成功");
    assert!(
        file.contains("engine_type"),
        "文件头应带引擎类型，便于导入时校验"
    );
    assert!(file.contains(text), "导出文件里应有刚加的词");

    core.user_words_clear(schema.clone()).expect("清空应成功");
    assert!(
        core.user_words(schema.clone(), code.into(), 0, 20)
            .items
            .is_empty(),
        "前提：清空后列表为空"
    );

    let report = core
        .dict_import(schema.clone(), file, false)
        .expect("导入应成功");
    let words = report
        .sections
        .iter()
        .find(|s| s.key == "userWords")
        .expect("结果里应有个人词段");
    assert!(words.added >= 1, "个人词应至少新增 1 条，实得 {words:?}");

    let page = core.user_words(schema.clone(), code.into(), 0, 20);
    assert!(
        page.items.iter().any(|w| w.text == text),
        "导入后列表里应有，实得 {:?}",
        page.items
    );
    for vk in [0x58u32, 0x58, 0x59, 0x59] {
        core.key_down(vk, 0);
    }
    settle();
    let typed: Vec<String> = items_now(&core);
    assert!(
        typed.iter().any(|t| t == text),
        "导入后打 {code} 应立即出词，实得 {typed:?}"
    );
    core.key_down(0x1B, 0);
    core.user_word_remove(schema, code.into(), text.into())
        .expect("清理应成功");
}

/// 重载配置。放到 8MB 栈的线程里：debug 构建下整套重载（重建引擎集）要 2~3MB 栈，
/// 超过测试线程默认的 2MB 会直接栈溢出中止。release 下 1MB 栈也够（实测），
/// 设置页在 IO 线程上调它没有问题——这只是 debug 测试的事。
fn reload_config(core: &Arc<WindCore>) {
    let c = core.clone();
    std::thread::Builder::new()
        .stack_size(8 << 20)
        .spawn(move || c.run_command(wind_android::EngineCommand::ReloadConfig))
        .expect("起重载线程")
        .join()
        .expect("重载线程不应 panic");
}

/// 长按提示的段开关：「拆字」出厂关着；打开并重载后候选提示里多出拆字段，关回去后状态复原。
/// 段名按原文定位（`编码{(${code_source})}` 这类模板名也能开关），显示名去掉了可选组。
#[test]
fn tooltip_section_toggle_reaches_candidate_tooltip() {
    let Some((core, _rec, _env)) = setup_exclusive("tooltip_sections") else {
        return;
    };
    let sections = core.tooltip_sections();
    let chaizi = sections
        .iter()
        .find(|s| s.title == "拆字")
        .expect("出厂段里应有「拆字」");
    assert!(!chaizi.enabled, "拆字出厂关着");
    assert!(
        sections
            .iter()
            .any(|s| s.title == "编码" && s.label.contains('{')),
        "模板段名应给出去掉可选组的显示名：{sections:?}"
    );
    let label = chaizi.label.clone();

    core.set_tooltip_section_enabled(label.clone(), true)
        .expect("打开拆字应成功");
    reload_config(&core);
    assert!(
        core.tooltip_sections()
            .iter()
            .any(|s| s.label == label && s.enabled),
        "打开后应读回开着"
    );
    for vk in [0x57u32, 0x51, 0x56, 0x42] {
        core.key_down(vk, 0); // wqvb → 你好
    }
    settle();
    let tip = core.candidate_tooltip(0);
    assert!(
        tip.iter().any(|s| s.title.as_deref() == Some("拆字")),
        "打开后首选的提示里应有拆字段，实得 {tip:?}"
    );
    core.key_down(0x1B, 0);

    core.set_tooltip_section_enabled(label.clone(), false)
        .expect("关闭拆字应成功");
    reload_config(&core);
    assert!(
        core.tooltip_sections()
            .iter()
            .any(|s| s.label == label && !s.enabled),
        "关回去后应读回关着"
    );
    assert!(
        core.set_tooltip_section_enabled("没有这一段".into(), true)
            .is_err(),
        "不存在的段名要报错，不能静默成功"
    );
}

/// 启用的方案：重载配置后拼音 / 双拼还在（真机：快捷面板一操作它们就没了——重载读回出厂
/// `available = ["wubi86", "wubi86_pinyin"]`）；关掉双拼并重载后它不在可选里，英文关不掉。
#[test]
fn enabled_schemas_survive_reload_and_can_be_toggled() {
    let Some((core, _rec, _env)) = setup_exclusive("enabled_schemas") else {
        return;
    };
    let ids = |core: &Arc<WindCore>| core.schemas().into_iter().map(|s| s.id).collect::<Vec<_>>();
    let installed = core.installed_schemas();
    if !installed.iter().any(|s| s.id == "shuangpin") || !installed.iter().any(|s| s.id == "pinyin")
    {
        eprintln!("跳过：未装拼音 / 双拼");
        return;
    }
    reload_config(&core);
    let after_reload = ids(&core);
    assert!(
        after_reload.contains(&"pinyin".to_string())
            && after_reload.contains(&"shuangpin".to_string()),
        "重载后拼音、双拼应仍可选，实得 {after_reload:?}"
    );

    core.set_schema_enabled("shuangpin".into(), false)
        .expect("关双拼应成功");
    reload_config(&core);
    assert!(
        !ids(&core).contains(&"shuangpin".to_string()),
        "关掉后不该在可选里"
    );
    assert!(
        core.installed_schemas()
            .iter()
            .any(|s| s.id == "shuangpin" && !s.enabled),
        "已装列表里仍有它，标为未启用"
    );
    assert!(
        core.set_schema_enabled("english".into(), false).is_err(),
        "英文是中英键切换的目标，不能关"
    );

    core.set_schema_enabled("shuangpin".into(), true)
        .expect("再开应成功");
    reload_config(&core);
    assert!(
        ids(&core).contains(&"shuangpin".to_string()),
        "重新启用后回到可选里"
    );
}

/// 手机端「首单元读法」：列出的读法随编码变，选读法只重算候选、不改编码；
/// 选 `xi` 读法后 `xian` 变成 `xi'an`，首选应是「西安」而非「先」；选回默认读法恢复原样。
#[test]
fn leading_readings_follow_the_pinyin_buffer() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("leading_readings") else {
        return;
    };
    let schemas = core.schemas();
    let Some(idx) = schemas.iter().position(|s| s.id == "pinyin") else {
        eprintln!("跳过：没有拼音方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: idx as u32 });
    // 首次编译拼音词库要几秒，期间按键直接上屏字母
    std::thread::sleep(std::time::Duration::from_secs(8));
    for vk in [0x58u32, 0x49, 0x41, 0x4E] {
        core.key_down(vk, 0); // x i a n
    }
    settle();

    let heads = |core: &WindCore| -> Vec<(String, bool)> {
        core.leading_readings()
            .into_iter()
            .map(|r| (r.head, r.selected))
            .collect()
    };
    let readings = heads(&core);
    assert_eq!(
        readings.first(),
        Some(&("xian".to_string(), true)),
        "默认读法在最前且选中：{readings:?}"
    );
    assert!(
        readings.iter().any(|(h, _)| h == "xi"),
        "另一种切法 xi'an：{readings:?}"
    );
    assert!(
        readings.iter().any(|(h, _)| h == "x"),
        "只取声母：{readings:?}"
    );

    let default_items = core.candidates(0, 30).items;
    assert!(
        default_items.iter().any(|t| t == "先"),
        "默认读法有「先」：{default_items:?}"
    );

    // 选 xi 读法：编码不动，候选换成 xi'an 的
    let xi_len = readings.iter().find(|(h, _)| h == "xi").map(|_| 2).unwrap();
    assert!(core.set_leading_reading("xi".into(), xi_len).is_some());
    settle();
    let xi_items = core.candidates(0, 30).items;
    assert!(
        xi_items.iter().any(|t| t == "西安"),
        "xi 读法下应有「西安」：{xi_items:?}"
    );
    let after = heads(&core);
    assert!(
        after.iter().any(|(h, sel)| h == "xi" && *sel),
        "选中态跟着变：{after:?}"
    );
    let preedit = rec
        .candidates
        .lock()
        .unwrap()
        .last()
        .map(|(p, _)| p.clone())
        .unwrap_or_default();
    assert!(
        preedit.replace('\'', "") == "xian",
        "编码栏仍是用户打的那串：{preedit}"
    );

    // 选「西安」：整串消费，直接上屏
    let at = xi_items.iter().position(|t| t == "西安").unwrap();
    let r = core.select_candidate(at as u32);
    assert!(has_text(&r), "选「西安」应上屏：{r:?}");

    // 选回默认读法 = 清除选择（先重新打一遍）
    for vk in [0x58u32, 0x49, 0x41, 0x4E] {
        core.key_down(vk, 0);
    }
    settle();
    assert!(core.set_leading_reading("xi".into(), 2).is_some());
    assert!(core.set_leading_reading("xian".into(), 4).is_some());
    settle();
    assert!(
        core.candidates(0, 30).items.iter().any(|t| t == "先"),
        "选回默认读法恢复原候选"
    );
}

/// 双拼下的首单元读法：首单元是一个击键对，**显示拼音、写进查询用击键**。
/// 小鹤 `dg` = deng：默认读法显示 deng、回传击键 dg；另有「只取声母」d。
/// 选「只取声母」后后面的键重新起单元，候选随之改变；选回默认恢复。
#[test]
fn shuangpin_leading_readings_display_pinyin_and_query_with_keys() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("leading_readings_sp") else {
        return;
    };
    let schemas = core.schemas();
    let Some(idx) = schemas.iter().position(|s| s.id == "shuangpin") else {
        eprintln!("跳过：没有双拼方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: idx as u32 });
    std::thread::sleep(std::time::Duration::from_secs(8));
    for vk in [0x44u32, 0x47, 0x46, 0x47] {
        core.key_down(vk, 0); // d g f g
    }
    settle();

    let readings = core.leading_readings();
    let view: Vec<(String, String, u32, bool)> = readings
        .iter()
        .map(|r| (r.label.clone(), r.head.clone(), r.orig_len, r.selected))
        .collect();
    assert_eq!(
        view.first(),
        Some(&("deng".to_string(), "dg".to_string(), 2, true)),
        "默认读法：显示拼音 deng、查询用击键 dg：{view:?}"
    );
    assert!(
        view.iter()
            .any(|(l, h, n, _)| l == "d" && h == "d" && *n == 1),
        "只取声母：{view:?}"
    );

    let default_items = core.candidates(0, 30).items;
    assert!(
        default_items.iter().any(|t| t == "登封"),
        "默认读法有「登封」：{default_items:?}"
    );

    assert!(core.set_leading_reading("d".into(), 1).is_some());
    settle();
    let initial_items = core.candidates(0, 30).items;
    assert_ne!(initial_items, default_items, "选声母读法后候选应变化");
    let preedit = rec
        .candidates
        .lock()
        .unwrap()
        .last()
        .map(|(p, _)| p.clone())
        .unwrap_or_default();
    assert_eq!(
        preedit.replace('\'', ""),
        "dgfg",
        "编码栏仍是用户打的击键：{preedit}"
    );

    assert!(core.set_leading_reading("dg".into(), 2).is_some());
    settle();
    assert!(
        core.candidates(0, 30).items.iter().any(|t| t == "登封"),
        "选回默认读法恢复原候选"
    );
}

/// 帧里带语义字段：打字中 `composing=true`；上屏进联想后 `composing=false`，
/// 但联想态仍有候选（`has_candidates=true`）。
#[test]
fn frame_reports_composing_and_candidates() {
    let Some((core, rec)) = setup("frame_reports_composing_and_candidates") else {
        return;
    };
    for vk in [0x57u32, 0x51, 0x56, 0x42] {
        core.key_down(vk, 0); // w q v b
    }
    settle();
    assert_eq!(
        rec.composing.lock().unwrap().last().copied(),
        Some(true),
        "输入编码后应为 composing"
    );
    assert_eq!(
        rec.candidates.lock().unwrap().last().map(|(_, has)| *has),
        Some(true)
    );

    core.key_down(VK_SPACE, 0);
    settle();
    let (preedit, has) = rec.candidates.lock().unwrap().last().cloned().unwrap();
    assert!(!preedit.is_empty(), "联想态的组合区显示联想标识，非空");
    assert!(has, "联想态有候选");
    assert_eq!(
        rec.composing.lock().unwrap().last().copied(),
        Some(false),
        "联想态不是打字中"
    );
}

/// 状态里带 `latin_input`：切英文方案后为真，切回中文方案为假。
#[test]
fn status_reports_latin_input() {
    use wind_android::EngineCommand;
    let Some((core, rec, _env)) = setup_exclusive("status_reports_latin_input") else {
        return;
    };
    let schemas = core.schemas();
    let Some(en) = schemas.iter().position(|s| s.id == "english") else {
        eprintln!("跳过：未安装英文方案");
        return;
    };
    let zh = schemas.iter().position(|s| s.id != "english").unwrap();
    let last_latin = || rec.status.lock().unwrap().last().map(|s| s.latin_input);

    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();
    assert_eq!(last_latin(), Some(true), "英文方案下键按拉丁字母直出");

    core.run_command(EngineCommand::SelectSchema { index: zh as u32 });
    settle();
    assert_eq!(last_latin(), Some(false), "切回中文方案后不再直出");
}

/// `press` 经 FFI：英文词 + 空格补空格；句号把补的空格换到标点后；关自动空格后不换位。
#[test]
fn press_english_word_space_period_swaps_auto_space() {
    use wind_android::{EngineCommand, KeySource, PressKey};
    let Some((core, _rec, _env)) = setup_exclusive("press_english_swap") else {
        return;
    };
    let schemas = core.schemas();
    let Some(en) = schemas.iter().position(|s| s.id == "english") else {
        eprintln!("跳过：未安装英文方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();

    let ch = |c: char| PressKey::Char {
        ch: c.to_string(),
        shifted: false,
    };
    for c in "hello".chars() {
        core.press(ch(c), KeySource::Touch);
    }
    let sp = core.press(PressKey::Space, KeySource::Touch);
    assert!(sp.consumed);
    assert!(
        sp.ops.iter().any(|o| o.kind == 0 && o.text == " "),
        "空格后补一个空格: {:?}",
        sp.ops
    );
    let dot = core.press(ch('.'), KeySource::Touch);
    assert!(dot.consumed);
    assert_eq!(dot.ops.len(), 1, "句号只换位: {:?}", dot.ops);
    assert_eq!(dot.ops[0].kind, 3, "ReplaceBackward");
    assert_eq!(dot.ops[0].text, ". ");

    // 关自动空格：同一序列不再补空格
    core.set_auto_space(false);
    for c in "world".chars() {
        core.press(ch(c), KeySource::Touch);
    }
    let sp = core.press(PressKey::Space, KeySource::Touch);
    assert!(sp.consumed);
    let dot = core.press(ch('.'), KeySource::Touch);
    assert!(
        !dot.ops.iter().any(|o| o.kind == 3),
        "关闭后不换位: {:?}",
        dot.ops
    );
}

/// `select_candidate_plain`：英文组码选词不补空格，普通版补。
#[test]
fn select_candidate_plain_skips_auto_space_in_english() {
    use wind_android::{EngineCommand, KeySource, PressKey};
    let Some((core, _rec, _env)) = setup_exclusive("plain_select_english") else {
        return;
    };
    let schemas = core.schemas();
    let Some(en) = schemas.iter().position(|s| s.id == "english") else {
        eprintln!("跳过：未安装英文方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();
    let type_hel = || {
        for c in "hel".chars() {
            core.press(
                PressKey::Char {
                    ch: c.to_string(),
                    shifted: false,
                },
                KeySource::Touch,
            );
        }
    };
    type_hel();
    let plain = core.select_candidate_plain(0);
    assert!(plain.consumed);
    assert!(
        !plain.ops.iter().any(|o| o.kind == 0 && o.text == " "),
        "plain 不补空格: {:?}",
        plain.ops
    );
    type_hel();
    let normal = core.select_candidate(0);
    assert!(
        normal.ops.iter().any(|o| o.kind == 0 && o.text == " "),
        "普通版补空格: {:?}",
        normal.ops
    );
}

/// 自动配对（出厂默认开，配对表含引号）：触摸按 `"` 一次出 “”，光标回退一格落在两者之间；
/// 英文模式按 `(` 同理。配对表与 app/src/main/assets/data/config.toml 的出厂值保持一致。
#[test]
fn auto_pair_quotes_and_brackets_put_cursor_between() {
    use wind_android::{EngineCommand, KeySource, PressKey};
    let _guard = env_lock();
    if !has_schemas() {
        eprintln!("跳过：缺少 build_dev/data 方案");
        return;
    }
    // 出厂值写进用户层（与 app/src/main/assets/data/config.toml 的 [input.auto_pair] 一致），再构造核心
    let user_layer = isolated_user_layer();
    std::fs::write(
        user_layer.join("config.toml"),
        "[input.auto_pair]\nchinese = true\nenglish = true\n\
         chinese_pairs = [\"“”\", \"‘’\", \"（）\", \"【】\", \"《》\"]\n\
         english_pairs = [\"\\\"\\\"\", \"''\", \"()\", \"[]\", \"{}\"]\n",
    )
    .unwrap();
    let user_root = std::env::temp_dir().join("wind-android-test-auto_pair_quotes");
    let _ = std::fs::remove_dir_all(&user_root);
    let rec = Arc::new(Recorder::default());
    let core = WindCore::new(
        data_dir().to_string_lossy().into_owned(),
        user_root.to_string_lossy().into_owned(),
        "wubi86".into(),
        rec.clone(),
    );
    core.focus_gained();
    let ch = |c: char| PressKey::Char {
        ch: c.to_string(),
        shifted: false,
    };
    let committed = |r: &wind_android::KeyResult| -> String {
        r.ops.iter().filter(|o| o.kind == 0).map(|o| o.text.as_str()).collect()
    };
    let back: i32 = -1;

    let r = core.press(ch('"'), KeySource::Touch);
    assert_eq!(committed(&r), "“”", "中文模式 \" 一次出一对: {:?}", r.ops);
    assert!(r.ops.iter().any(|o| o.kind == 4 && o.delta == back), "光标回退一格: {:?}", r.ops);

    let r = core.press(ch('\''), KeySource::Touch);
    assert_eq!(committed(&r), "‘’", "中文模式 ' 一次出一对: {:?}", r.ops);
    assert!(r.ops.iter().any(|o| o.kind == 4 && o.delta == back), "光标回退一格: {:?}", r.ops);

    let r = core.press(ch('('), KeySource::Touch);
    assert_eq!(committed(&r), "（）", "中文模式 ( 出全角一对: {:?}", r.ops);
    assert!(r.ops.iter().any(|o| o.kind == 4 && o.delta == back), "光标回退一格: {:?}", r.ops);

    // 英文模式（中英键切到英文方案）
    let schemas = core.schemas();
    let Some(en) = schemas.iter().position(|s| s.id == "english") else {
        eprintln!("跳过英文部分：未安装英文方案");
        return;
    };
    core.run_command(EngineCommand::SelectSchema { index: en as u32 });
    settle();
    for (c, pair) in [('(', "()"), ('"', "\"\"")] {
        let r = core.press(ch(c), KeySource::Touch);
        assert_eq!(committed(&r), pair, "英文模式 {c} 一次出一对: {:?}", r.ops);
        assert!(r.ops.iter().any(|o| o.kind == 4 && o.delta == back), "光标回退一格: {:?}", r.ops);
    }
}
