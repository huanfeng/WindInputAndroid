//! 方案设置表单在**安卓随包数据**上的读写：读全拼表单、改一项、重载后读回新值、恢复后回到方案默认。

use std::sync::{Arc, Mutex};

use wind_android::{
    CandidateUpdate, ConfigValue, InputStatus, SchemeFormSet, UiEventListener, WindCore,
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

fn value(text: &str, key: &str) -> Option<String> {
    let t: toml::Table = toml::from_str(text).unwrap();
    t.get(key).map(|v| v.to_string())
}

#[test]
fn scheme_form_roundtrip_on_bundled_data() {
    let _g = LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let root = std::env::temp_dir().join("wind-android-test-scheme-form");
    let _ = std::fs::remove_dir_all(&root);
    let conf = std::env::temp_dir().join("wind-android-test-scheme-form-datadir.conf");
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
    let key = "schema.pinyin.sentence_count";
    let f = core.scheme_form("pinyin".into()).unwrap();
    let default = value(&f.default_values, key).expect("全拼表单应有智能组词条数");
    assert_eq!(value(&f.effective, key).as_deref(), Some(default.as_str()));

    core.apply_scheme_form(
        "pinyin".into(),
        vec![SchemeFormSet {
            key: key.into(),
            value: ConfigValue::Int { v: 5 },
        }],
        vec![],
    )
    .unwrap();
    let f = core.scheme_form("pinyin".into()).unwrap();
    assert_eq!(value(&f.effective, key).as_deref(), Some("5"));
    let sp = core.scheme_form("shuangpin".into()).unwrap();
    assert_eq!(
        value(&sp.effective, key).as_deref(),
        Some(default.as_str()),
        "双拼不受影响"
    );

    core.apply_scheme_form("pinyin".into(), vec![], vec![key.into()])
        .unwrap();
    let f = core.scheme_form("pinyin".into()).unwrap();
    assert_eq!(value(&f.effective, key).as_deref(), Some(default.as_str()));
}
