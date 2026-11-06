//! 方案包预览 / 导入 / 删除（`wind_android::scheme`）。
//!
//! 样例用社区 98 五笔（工作区 `WindInputCodeTable/schemas/wubi98`），在测试里现打 zip，
//! 不往仓库塞二进制。样例目录缺失时：CI（有 `CI` 环境变量）跳过，本机直接失败——
//! 本机默认就该有，缺了说明工作区没摆好，静默跳过只会让这组测试悄悄失效。
//! 负例全用合成小包，不依赖样例。

use std::io::Write;
use std::path::{Path, PathBuf};

use tempfile::TempDir;
use wind_android::scheme;

const PKG_TOML: &str = "[package]\nformat_version = 2\ntitle = \"98五笔\"\ndescription = \"社区 98 五笔\\n含拼音混输\"\n";
const MIN_SCHEMA: &str = "[schema]\nid = \"mini\"\nname = \"迷你\"\n";

/// 把 `(条目名, 内容)` 打成 zip 写到 `dir/name`，返回路径。
fn make_zip(dir: &Path, name: &str, entries: &[(&str, &[u8])]) -> PathBuf {
    let path = dir.join(name);
    let mut zw = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
    let opts = zip::write::SimpleFileOptions::default();
    for (n, data) in entries {
        zw.start_file(*n, opts).unwrap();
        zw.write_all(data).unwrap();
    }
    zw.finish().unwrap();
    path
}

fn codetable_wubi98() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../WindInputCodeTable/schemas/wubi98")
}

/// 98 五笔样例包；样例缺失时返回 None（CI）或 panic（本机）。
fn wubi98_pkg(dir: &Path) -> Option<PathBuf> {
    let src = codetable_wubi98();
    if !src.join("wubi98.schema.toml").exists() {
        if std::env::var_os("CI").is_some() {
            eprintln!("跳过：找不到 98 五笔样例 {}", src.display());
            return None;
        }
        panic!(
            "找不到 98 五笔样例 {}：工作区应并列存放 WindInputCodeTable",
            src.display()
        );
    }
    let read = |rel: &str| std::fs::read(src.join(rel)).unwrap();
    let (a, b, c) = (
        read("wubi98.schema.toml"),
        read("wubi98_pinyin.schema.toml"),
        read("wubi98/wubi98_cidian.dict.yaml"),
    );
    Some(make_zip(
        dir,
        "wubi98.wpkg",
        &[
            ("package.toml", PKG_TOML.as_bytes()),
            ("wubi98.schema.toml", &a),
            ("wubi98_pinyin.schema.toml", &b),
            ("wubi98/wubi98_cidian.dict.yaml", &c),
        ],
    ))
}

struct Dirs {
    _t: TempDir,
    work: PathBuf,
    user: PathBuf,
    system: Vec<PathBuf>,
}

fn dirs() -> Dirs {
    let t = tempfile::tempdir().unwrap();
    let work = t.path().join("work");
    let user = t.path().join("user_schemas");
    let system = t.path().join("data_schemas");
    for d in [&work, &system] {
        std::fs::create_dir_all(d).unwrap();
    }
    Dirs {
        work,
        user,
        system: vec![system],
        _t: t,
    }
}

#[test]
fn preview_wubi98_lists_files_and_meta() {
    let d = dirs();
    let Some(pkg) = wubi98_pkg(&d.work) else {
        return;
    };
    let p = scheme::preview(&pkg, &d.user, &d.system).unwrap();
    assert_eq!(p.title, "98五笔");
    assert_eq!(p.description, "社区 98 五笔\n含拼音混输");
    assert_eq!(p.schema_ids, ["wubi98", "wubi98_pinyin"]);
    assert_eq!(p.will_add.len(), 3);
    assert!(p.conflicts.is_empty());
    assert!(p.overrides_builtin.is_empty());
    assert!(!p.ignored_config_patch);
    assert!(!d.user.exists(), "预览不得落盘");
}

#[test]
fn preview_flags_override_of_builtin_schema() {
    let d = dirs();
    let Some(pkg) = wubi98_pkg(&d.work) else {
        return;
    };
    std::fs::write(
        d.system[0].join("wubi98.schema.toml"),
        "[schema]\nid=\"wubi98\"\n",
    )
    .unwrap();
    let p = scheme::preview(&pkg, &d.user, &d.system).unwrap();
    assert_eq!(p.overrides_builtin, ["wubi98"]);
    // 用户层没有同名文件，所以不算「冲突」：覆盖自带靠 overrides_builtin 提示
    assert!(p.conflicts.is_empty());
}

#[test]
fn import_then_merge_skips_and_replace_overwrites() {
    let d = dirs();
    let Some(pkg) = wubi98_pkg(&d.work) else {
        return;
    };
    let r = scheme::import(&pkg, &d.user, false).unwrap();
    assert_eq!(r.imported.len(), 3);
    assert_eq!(r.schema_ids, ["wubi98", "wubi98_pinyin"]);
    assert!(d.user.join("wubi98/wubi98_cidian.dict.yaml").is_file());
    assert!(!d.user.join("package.toml").exists(), "package.toml 不落盘");

    let target = d.user.join("wubi98.schema.toml");
    let original = std::fs::read(&target).unwrap();
    std::fs::write(&target, b"edited").unwrap();

    let p = scheme::preview(&pkg, &d.user, &d.system).unwrap();
    assert_eq!(p.conflicts.len(), 3);
    assert!(p.will_add.is_empty());

    let merged = scheme::import(&pkg, &d.user, false).unwrap();
    assert!(merged.imported.is_empty());
    assert_eq!(merged.conflicts.len(), 3);
    assert_eq!(std::fs::read(&target).unwrap(), b"edited", "Merge 不覆盖");

    let replaced = scheme::import(&pkg, &d.user, true).unwrap();
    assert_eq!(replaced.imported.len(), 3);
    assert_eq!(std::fs::read(&target).unwrap(), original, "Replace 覆盖");
}

#[test]
fn config_patch_is_reported_ignored_and_never_written() {
    let d = dirs();
    let pkg = make_zip(
        &d.work,
        "cfg.wpkg",
        &[
            ("package.toml", b"[package]\nformat_version = 2\n"),
            ("mini.schema.toml", MIN_SCHEMA.as_bytes()),
            ("config_patch.toml", b"[ui]\nfont_size = 20\n"),
        ],
    );
    let p = scheme::preview(&pkg, &d.user, &d.system).unwrap();
    assert!(p.ignored_config_patch);
    assert_eq!(
        p.will_add,
        ["mini.schema.toml"],
        "config_patch 不算载荷文件"
    );
    scheme::import(&pkg, &d.user, false).unwrap();
    assert!(d.user.join("mini.schema.toml").is_file());
    assert!(!d.user.join("config_patch.toml").exists());
}

#[test]
fn rejects_path_traversal_without_writing() {
    let d = dirs();
    let pkg = make_zip(
        &d.work,
        "evil.zip",
        &[
            ("mini.schema.toml", MIN_SCHEMA.as_bytes()),
            ("../escaped.txt", b"x"),
        ],
    );
    assert!(scheme::preview(&pkg, &d.user, &d.system).is_err());
    assert!(scheme::import(&pkg, &d.user, true).is_err());
    assert!(!d.user.join("mini.schema.toml").exists(), "校验失败零落盘");
    assert!(!d.user.parent().unwrap().join("escaped.txt").exists());
}

#[test]
fn rejects_backup_package() {
    let d = dirs();
    let pkg = make_zip(
        &d.work,
        "backup.zip",
        &[
            ("manifest.toml", b"kind = \"backup\"\n"),
            ("mini.schema.toml", MIN_SCHEMA.as_bytes()),
        ],
    );
    let e = scheme::preview(&pkg, &d.user, &d.system).unwrap_err();
    assert!(e.contains("备份"), "{e}");
}

#[test]
fn rejects_package_without_root_schema() {
    let d = dirs();
    let pkg = make_zip(
        &d.work,
        "noschema.zip",
        &[("sub/a.schema.toml", MIN_SCHEMA.as_bytes())],
    );
    let e = scheme::import(&pkg, &d.user, false).unwrap_err();
    assert!(e.contains("schema.toml"), "{e}");
}

#[test]
fn rejects_newer_format_version() {
    let d = dirs();
    let pkg = make_zip(
        &d.work,
        "future.zip",
        &[
            ("package.toml", b"[package]\nformat_version = 99\n"),
            ("mini.schema.toml", MIN_SCHEMA.as_bytes()),
        ],
    );
    let e = scheme::preview(&pkg, &d.user, &d.system).unwrap_err();
    assert!(e.contains("版本过高"), "{e}");
    assert!(scheme::import(&pkg, &d.user, false).is_err());
}

#[test]
fn rejects_oversized_unpack() {
    let d = dirs();
    // 声明 257 MiB（上限 256 MiB）。deflate 全零压缩比极高，包本身只有几百 KB
    let path = d.work.join("bomb.zip");
    let mut zw = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
    let opts = zip::write::SimpleFileOptions::default();
    zw.start_file("mini.schema.toml", opts).unwrap();
    zw.write_all(MIN_SCHEMA.as_bytes()).unwrap();
    zw.start_file("big.dict.yaml", opts).unwrap();
    let chunk = vec![0u8; 1 << 20];
    for _ in 0..257 {
        zw.write_all(&chunk).unwrap();
    }
    zw.finish().unwrap();
    let e = scheme::preview(&path, &d.user, &d.system).unwrap_err();
    assert!(e.contains("过大"), "{e}");
    assert!(scheme::import(&path, &d.user, false).is_err());
    assert!(!d.user.join("mini.schema.toml").exists());
}

#[test]
fn rejects_too_many_entries() {
    let d = dirs();
    let path = d.work.join("many.zip");
    let mut zw = zip::ZipWriter::new(std::fs::File::create(&path).unwrap());
    let opts = zip::write::SimpleFileOptions::default();
    zw.start_file("mini.schema.toml", opts).unwrap();
    zw.write_all(MIN_SCHEMA.as_bytes()).unwrap();
    for i in 0..2001 {
        zw.start_file(format!("f/{i}.txt"), opts).unwrap();
    }
    zw.finish().unwrap();
    let e = scheme::preview(&path, &d.user, &d.system).unwrap_err();
    assert!(e.contains("条目过多"), "{e}");
}

#[test]
fn missing_file_is_an_error() {
    let d = dirs();
    assert!(scheme::preview(&d.work.join("nope.zip"), &d.user, &d.system).is_err());
}

#[test]
fn delete_user_schema_keeps_shared_and_never_touches_system() {
    let d = dirs();
    let Some(pkg) = wubi98_pkg(&d.work) else {
        return;
    };
    // 系统层有一份同名自带方案，用户导入后覆盖它
    let sys_file = d.system[0].join("wubi98.schema.toml");
    std::fs::write(&sys_file, "[schema]\nid=\"wubi98\"\n").unwrap();
    scheme::import(&pkg, &d.user, false).unwrap();

    assert_eq!(
        scheme::layers_of("wubi98", Some(&d.user), &d.system),
        (true, true)
    );
    assert_eq!(
        scheme::layers_of("wubi98_pinyin", Some(&d.user), &d.system),
        (true, false)
    );
    assert_eq!(
        scheme::layers_of("nope", Some(&d.user), &d.system),
        (false, false)
    );

    // wubi98_pinyin 把 wubi98 当主方案引用：删 wubi98 时共享部分必须保留
    let keep = vec!["wubi98_pinyin".to_string()];
    let r = scheme::delete("wubi98", &d.user, &d.system, &keep).unwrap();
    assert!(
        r.kept_shared.iter().any(|f| f == "wubi98.schema.toml"),
        "被混输方案引用的 wubi98 方案文件应保留: {r:?}"
    );
    assert!(d.user.join("wubi98.schema.toml").is_file());
    assert!(sys_file.is_file(), "系统层文件永不删");

    // 没有其它方案引用后，删除生效；系统层仍在（回落自带）
    let r = scheme::delete("wubi98", &d.user, &d.system, &[]).unwrap();
    assert!(r.deleted.iter().any(|f| f == "wubi98.schema.toml"), "{r:?}");
    assert!(!d.user.join("wubi98.schema.toml").exists());
    assert!(sys_file.is_file());
    assert_eq!(
        scheme::layers_of("wubi98", Some(&d.user), &d.system),
        (false, true)
    );
}

#[test]
fn delete_purely_user_schema_removes_its_files() {
    let d = dirs();
    let Some(pkg) = wubi98_pkg(&d.work) else {
        return;
    };
    scheme::import(&pkg, &d.user, false).unwrap();
    // 混输方案递归引用 wubi98：删它会连带清掉只被它引用的用户方案与词库
    let r = scheme::delete("wubi98_pinyin", &d.user, &d.system, &[]).unwrap();
    assert!(
        r.deleted
            .iter()
            .any(|f| f == "wubi98/wubi98_cidian.dict.yaml"),
        "{r:?}"
    );
    assert!(!d.user.join("wubi98.schema.toml").exists());
    assert!(!d.user.join("wubi98_pinyin.schema.toml").exists());
    assert!(!d.user.join("wubi98").exists(), "空目录一并清掉");
}
