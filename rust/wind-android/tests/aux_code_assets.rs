//! 辅助码在**安卓随包数据**上的端到端：数据目录取 `app/src/main/assets/data`（不是主仓的 build_dev），
//! 守住「码表随包 → 设置页出开关 → 开了真能用」这条链。任何一环缺了都不报错，只是功能悄悄没了。

use std::sync::{Arc, Mutex};

use wind_android::{
    CandidateUpdate, ConfigValue, EngineCommand, InputStatus, UiEventListener, WindCore,
};

struct Silent;
impl UiEventListener for Silent {
    fn on_candidates(&self, _u: CandidateUpdate) {}
    fn on_hide_candidates(&self) {}
    fn on_status(&self, _s: InputStatus) {}
    fn on_toast(&self, _t: String) {}
}

static LOCK: Mutex<()> = Mutex::new(());

fn assets() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../app/src/main/assets/data")
}

fn settle() {
    std::thread::sleep(std::time::Duration::from_millis(200));
}

fn vk(c: char) -> u32 {
    c.to_ascii_uppercase() as u32
}

fn type_str(core: &WindCore, s: &str) {
    for c in s.chars() {
        core.key_down(vk(c), 0);
    }
    settle();
}

fn texts(core: &WindCore) -> Vec<String> {
    core.candidates(0, 30).items
}

#[test]
fn bundled_aux_code_works_on_shuangpin() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let root = std::env::temp_dir().join("wind-android-test-aux-assets");
    let _ = std::fs::remove_dir_all(&root);
    let conf = std::env::temp_dir().join("wind-android-test-aux-datadir.conf");
    let user = root.join("user");
    std::fs::create_dir_all(&user).unwrap();
    std::fs::write(&conf, user.to_string_lossy().as_bytes()).unwrap();
    // SAFETY: 本测试进程内只有这一条测试，且持锁
    unsafe { std::env::set_var("WIND_DATADIR_CONF", &conf) };

    let core = WindCore::new(
        assets().to_string_lossy().into_owned(),
        root.join("u").to_string_lossy().into_owned(),
        root.join("l").to_string_lossy().into_owned(),
        "pinyin".into(),
        Arc::new(Silent),
    );
    core.focus_gained();
    let sp = core
        .schemas()
        .iter()
        .position(|s| s.id == "shuangpin")
        .expect("双拼方案应随包");
    core.run_command(EngineCommand::SelectSchema { index: sp as u32 });
    settle();

    let has_aux = |c: &WindCore, id: &str| {
        c.schema_settings(id.into())
            .unwrap()
            .items
            .iter()
            .any(|i| i.key == "engine.aux_code.enabled")
    };
    assert!(has_aux(&core, "shuangpin"), "双拼应列出辅助码开关（码表随包）");
    let sp_items = core.schema_settings("shuangpin".into()).unwrap().items;
    let src = sp_items
        .iter()
        .find(|i| i.key == "engine.aux_code.files")
        .expect("双拼应有辅助码来源下拉");
    assert_eq!(src.value, "aux_code/flypy_full.txt");
    for want in ["aux_code/ZRM-wanxiang.txt", "aux_code/shouyou.txt", "schema:stroke"] {
        assert!(src.options.iter().any(|o| o == want), "来源缺 {want}：{:?}", src.options);
    }
    assert!(has_aux(&core, "pinyin"), "全拼应列出辅助码开关（笔画方案随包）");

    // 开启前：gokqk 的首选不会是「国情」
    type_str(&core, "gokqk");
    let before = texts(&core);
    core.key_down(0x1B, 0);

    for (k, v) in [
        ("schema.pinyin.aux_code.enabled", true),
        ("schema.pinyin.aux_code.direct", true),
    ] {
        core.config_set(k.into(), ConfigValue::Bool { v }).unwrap();
    }
    core.run_command(EngineCommand::ReloadConfig);
    // 码表在后台构建，给它时间
    for _ in 0..40 {
        type_str(&core, "gokqk");
        let now = texts(&core);
        core.key_down(0x1B, 0);
        if now.first().map(String::as_str) == Some("国情") {
            return;
        }
        std::thread::sleep(std::time::Duration::from_millis(250));
    }
    type_str(&core, "gokqk");
    panic!("开启后 gokqk 首选应为 国情；开启前 {before:?}，开启后 {:?}", texts(&core));
}
