# 方案包导入的 e2e 用例（被 emu-test.sh source，依赖它的 check / section / tap_text / dump_xml / adb_ 等）。
#
# 入口走文件关联：`am start -n …SchemeImportActivity -a VIEW -d file://<应用私有目录>/...`（显式指定组件：设备上常装有别的能处理 zip 的应用，会弹选择器；intent-filter 本身的匹配另由 `cmd package query-activities` 在用例里验）。选用应用自己的 files 目录，
# 是因为 file:// 读公共存储（/sdcard/Download）在新系统上要存储权限，而本应用并不申请；
# 包经 `run-as` 推进去。设置页的 OpenDocument 选择器是系统 DocumentsUI，脚本驱动它不稳，
# 导入流程本身（预览 / 确认 / 结果 / 删除 / 恢复）两个入口共用同一套界面，走文件关联已覆盖。
#
# 样例：98 五笔（工作区 WindInputCodeTable/schemas/wubi98，APK 不自带）→ 导入、可启用、删除；
# 覆盖自带方案：用 APK 自带的 wubi86_pinyin.schema.toml 原样打成包（内容不变，覆盖无害）→
# 二次确认 → 导入 → 「恢复自带版本」。负例：不是方案包的 zip 要如实报失败。

SI_USER_SCHEMAS=files/rust/config/WindInput/schemas
SI_DIR=files/e2e-schema
SI_URI_BASE="file:///data/user/0/$PKG/$SI_DIR"
SI_TMP="$(mktemp -d)"

si_has_text() {   # 当前界面（含对话框窗口）有没有这段文字，最多约 4 秒
    local try
    for ((try = 0; try < 6; try++)); do
        dump_xml | grep -q " text=\"$1\"" && { echo yes; return; }
        sleep 0.7
    done
    echo no
}
si_push() {   # 本地文件 → 应用私有目录 files/e2e-schema/<名字>
    adb_q shell run-as "$PKG" mkdir -p "$SI_DIR"
    "$ADB" -s "$SERIAL" shell run-as "$PKG" sh -c "'cat > $SI_DIR/$2'" < "$1"
}
si_view() {   # 用文件关联打开 files/e2e-schema/<名字>
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SchemeImportActivity" -a android.intent.action.VIEW -d "$SI_URI_BASE/$1" -t application/octet-stream
    sleep 2
}
si_user_file_exists() { adb_ shell run-as "$PKG" ls "$SI_USER_SCHEMAS/$1" 2>/dev/null | grep -q "$1" && echo yes || echo no; }
si_open_enabled_schemas() {   # 设置 → 方案 → 启用的方案
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
    sleep 2
    tap_text "方案"
    tap_text "启用的方案"
}

# 预热核心：刚装完包的第一次冷启动里立刻走文件关联，核心可能还没建好（报「输入法核心未就绪」）
adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
sleep 8

section "[53b] 文件关联的 intent-filter 匹配：.wpkg / zip 的 VIEW 与 SEND 都能解析到导入 Activity"
si_resolves() {   # 参数原样交给 query-activities
    adb_ shell cmd package query-activities --brief "$@" | grep -q "$PKG/.settings.SchemeImportActivity" && echo yes || echo no
}
check "VIEW file://*.wpkg（无 MIME）" "yes" "$(si_resolves -a android.intent.action.VIEW -d file:///sdcard/x.wpkg -t application/vnd.unknown)"
check "VIEW content://*.a.b.wpkg（文件名带点）" "yes" "$(si_resolves -a android.intent.action.VIEW -d content://x.provider/doc/a.b.wpkg -t application/vnd.unknown)"
check "VIEW zip" "yes" "$(si_resolves -a android.intent.action.VIEW -d content://x.provider/doc/1 -t application/zip)"
check "SEND zip" "yes" "$(si_resolves -a android.intent.action.SEND -t application/zip)"
check "VIEW 无关类型不抢（图片）" "no" "$(si_resolves -a android.intent.action.VIEW -d content://x.provider/doc/1 -t image/png)"

section "[54] 导入方案：98 五笔（文件关联入口）预览 → 导入 → 可启用 → 删除"
SRC98="$ROOT/../WindInputCodeTable/schemas/wubi98"
if [[ ! -f "$SRC98/wubi98.schema.toml" ]] || ! command -v python3 >/dev/null; then
    echo "  ${R}FAIL${N}  找不到 98 五笔样例 $SRC98（或没有 python3）"; FAILED=$((FAILED + 1))
else
    python3 - "$SRC98" "$SI_TMP/wubi98.wpkg" <<'PY'
import sys, zipfile
src, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("package.toml", '[package]\nformat_version = 2\ntitle = "98五笔"\ndescription = "社区 98 五笔"\n')
    for rel in ("wubi98.schema.toml", "wubi98_pinyin.schema.toml", "wubi98/wubi98_cidian.dict.yaml"):
        z.write(f"{src}/{rel}", rel)
PY
    # 干净起点：上次中途断掉可能留着
    adb_q shell run-as "$PKG" sh -c "'rm -rf $SI_USER_SCHEMAS/wubi98 $SI_USER_SCHEMAS/wubi98.schema.toml $SI_USER_SCHEMAS/wubi98_pinyin.schema.toml'"
    si_push "$SI_TMP/wubi98.wpkg" wubi98.wpkg
    si_view wubi98.wpkg
    check "预览：标题" "yes" "$(si_has_text 98五笔)"
    check "预览：方案列表" "yes" "$(si_has_text '包含方案：wubi98、wubi98_pinyin')"
    check "预览：新增文件数" "yes" "$(si_has_text '新增 3 个文件')"
    check "预览：不是覆盖自带方案，无该提示" "no" "$(dump_xml | grep -q '将覆盖自带方案' && echo yes || echo no)"
    check "预览期间未落盘" "no" "$(si_user_file_exists wubi98.schema.toml)"
    tap_text "导入"
    check "结果页" "yes" "$(si_has_text 导入完成)"
    check "导入后方案文件落到用户层" "yes" "$(si_user_file_exists wubi98.schema.toml)"
    # 用户从未手选过启用列表时新方案默认已启用；否则要点「立即启用」
    if [[ "$(si_has_text 立即启用)" == yes ]]; then tap_text "立即启用"; fi
    tap_text "完成"
    sleep 1
    check "缓存文件已清掉" "0" "$(adb_ shell run-as "$PKG" ls cache/scheme-import 2>/dev/null | wc -l | tr -d ' ')"

    si_open_enabled_schemas
    check "已装方案列表出现 98 五笔" "yes" "$(si_has_text 五笔98)"
    check "用户导入的方案可删除" "yes" "$(si_has_text '用户导入 · 点此删除')"
    check "只有导入的两个方案有删除入口，自带方案没有" "2" "$(dump_xml | grep -o '点此删除\|点此恢复自带版本' | wc -l | tr -d ' ')"
    # 启用状态：「输入方案」页的单选列表只列启用的方案
    adb_q shell input keyevent BACK; sleep 1
    check "输入方案列表里能选 98 五笔（已启用）" "yes" "$(si_has_text 五笔98)"
    tap_text "启用的方案"
    # 五笔98 被「五笔98拼音」当主方案引用：先删被依赖的会因共享而保留，所以先删混输方案，再删它
    tap_text "五笔98拼音"
    check "删除前二次确认" "yes" "$(si_has_text 删除方案)"
    tap_text "删除"
    sleep 2
    check "删混输方案后文件已清掉" "no" "$(si_user_file_exists wubi98_pinyin.schema.toml)"
    tap_text "五笔98"
    tap_text "删除"
    sleep 2
    check "删除后列表里没有 98 五笔" "no" "$(si_has_text 五笔98)"
    check "删除后方案文件已清掉" "no" "$(si_user_file_exists wubi98.schema.toml)"
    check "删除后词库目录已清掉" "no" "$(si_user_file_exists wubi98)"
    adb_q shell input keyevent BACK; sleep 1
    adb_q shell input keyevent BACK; sleep 1
fi

section "[55] 导入方案：覆盖自带方案要二次确认、列表与显示名随之更新，「恢复自带版本」回到自带名"
# 覆盖包 = APK 自带的 wubi86_pinyin 只改了显示名：能证明引擎缓存真的失效（同 id 覆盖不改 config.schema，
# 只发 ReloadConfig 的话旧定义会一直生效，名字不会变）
python3 - "$ROOT/app/src/main/assets/data/schemas" "$SI_TMP" <<'PY'
import sys, zipfile
src, tmp = sys.argv[1], sys.argv[2]
def pkg(out, files):
    with zipfile.ZipFile(f"{tmp}/{out}", "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("package.toml", '[package]\nformat_version = 2\n')
        for rel, data in files:
            z.writestr(rel, data)
t = open(f"{src}/wubi86_pinyin.schema.toml", encoding="utf-8").read()
assert 'name = "五笔拼音"' in t
pkg("override.wpkg", [("wubi86_pinyin.schema.toml", t.replace('name = "五笔拼音"', 'name = "覆盖版五笔拼音"', 1))])
# 覆盖被自带混输引用的 pinyin，并带一份同路径的词库副本（遮住自带词库）
p = open(f"{src}/pinyin.schema.toml", encoding="utf-8").read()
assert 'name = "全拼"' in p
d = open(f"{src}/pinyin/rime_frost.dict.yaml", "rb").read()
pkg("override2.wpkg", [("pinyin.schema.toml", p.replace('name = "全拼"', 'name = "新全拼"', 1)), ("pinyin/rime_frost.dict.yaml", d)])
mix = '[schema]\nid = "mymix"\nname = "我的混输"\n\n[engine]\ntype = "mixed"\n\n[engine.mixed]\nprimary_schema = "pinyin"\nsecondary_schema = "pinyin"\n'
pkg("mymix.wpkg", [("mymix.schema.toml", mix)])
with zipfile.ZipFile(f"{tmp}/notpkg.zip", "w") as z:
    z.writestr("readme.txt", "not a scheme package")
PY
adb_q shell run-as "$PKG" rm -f "$SI_USER_SCHEMAS/wubi86_pinyin.schema.toml"
si_push "$SI_TMP/override.wpkg" override.wpkg
si_view override.wpkg
check "预览：醒目写明将覆盖自带方案" "yes" "$(dump_xml | grep -q '将覆盖自带方案：wubi86_pinyin' && echo yes || echo no)"
tap_text "导入"
check "覆盖自带方案先二次确认" "yes" "$(si_has_text 确认覆盖)"
check "二次确认前未落盘" "no" "$(si_user_file_exists wubi86_pinyin.schema.toml)"
tap_text "返回"
check "返回后仍在预览" "yes" "$(si_has_text '导入')"
tap_text "导入"
tap_text "覆盖"
check "确认后导入完成" "yes" "$(si_has_text 导入完成)"
check "用户层写入了同名覆盖文件" "yes" "$(si_user_file_exists wubi86_pinyin.schema.toml)"
tap_text "完成"
sleep 1

si_open_enabled_schemas
check "覆盖后列表显示新名字（引擎缓存已失效）" "yes" "$(si_has_text 覆盖版五笔拼音)"
check "覆盖了自带的显示「恢复自带版本」" "yes" "$(si_has_text '覆盖了自带方案 · 点此恢复自带版本')"
tap_text "覆盖了自带方案 · 点此恢复自带版本"
check "恢复前二次确认" "yes" "$(si_has_text 恢复自带版本)"
tap_text "恢复"
sleep 2
check "恢复后不再显示覆盖项" "no" "$(si_has_text '覆盖了自带方案 · 点此恢复自带版本')"
check "恢复后用户层覆盖文件已删" "no" "$(si_user_file_exists wubi86_pinyin.schema.toml)"
check "恢复后列表回到自带名字" "yes" "$(si_has_text 五笔拼音)"
check "恢复后新名字消失" "no" "$(si_has_text 覆盖版五笔拼音)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1

section "[56] 导入方案：覆盖被自带混输引用的自带方案（pinyin）并遮住自带词库，可恢复"
adb_q shell run-as "$PKG" sh -c "'rm -rf $SI_USER_SCHEMAS/pinyin $SI_USER_SCHEMAS/pinyin.schema.toml'"
si_push "$SI_TMP/override2.wpkg" override2.wpkg
si_view override2.wpkg
check "预览：将覆盖自带方案 pinyin" "yes" "$(dump_xml | grep -q '将覆盖自带方案：pinyin' && echo yes || echo no)"
check "预览：将遮住自带资源文件" "yes" "$(dump_xml | grep -q '将遮住自带资源文件 1 个' && echo yes || echo no)"
tap_text "导入"
check "遮住自带资源也要二次确认" "yes" "$(si_has_text 确认覆盖)"
check "确认页写明遮住自带资源" "yes" "$(dump_xml | grep -q '将遮住应用自带的资源文件 1 个' && echo yes || echo no)"
tap_text "覆盖"
check "导入完成" "yes" "$(si_has_text 导入完成)"
check "用户层有遮住自带词库的副本" "yes" "$(si_user_file_exists pinyin/rime_frost.dict.yaml)"
tap_text "完成"
sleep 1
si_open_enabled_schemas
check "覆盖 pinyin 后列表显示新名字" "yes" "$(si_has_text 新全拼)"
# 再导入引用 pinyin 的用户混输，然后删掉它：用户刻意做的 pinyin 覆盖不能被连带恢复
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
adb_q shell run-as "$PKG" rm -f "$SI_USER_SCHEMAS/mymix.schema.toml"
si_push "$SI_TMP/mymix.wpkg" mymix.wpkg
si_view mymix.wpkg
tap_text "导入"
check "用户混输导入完成" "yes" "$(si_has_text 导入完成)"
tap_text "完成"
sleep 1
si_open_enabled_schemas
tap_text "我的混输"
tap_text "删除"
sleep 2
check "删用户混输后混输已清掉" "no" "$(si_user_file_exists mymix.schema.toml)"
check "删用户混输后 pinyin 覆盖仍在" "yes" "$(si_user_file_exists pinyin.schema.toml)"
check "删用户混输后遮住自带词库的副本仍在" "yes" "$(si_user_file_exists pinyin/rime_frost.dict.yaml)"
check "删用户混输后列表仍显示新全拼" "yes" "$(si_has_text 新全拼)"
tap_text "覆盖了自带方案 · 点此恢复自带版本"
tap_text "恢复"
sleep 3
# 自带混输 wubi86_pinyin 引用 pinyin：此前共享保护会让恢复什么都删不掉
check "恢复后列表回到自带名字「全拼」" "yes" "$(si_has_text 全拼)"
check "恢复后新名字消失" "no" "$(si_has_text 新全拼)"
check "恢复后方案覆盖文件已删" "no" "$(si_user_file_exists pinyin.schema.toml)"
check "恢复后遮住自带词库的副本一并清掉" "no" "$(si_user_file_exists pinyin/rime_frost.dict.yaml)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1

section "[57] 导入方案：非方案包 zip 如实报失败"
si_push "$SI_TMP/notpkg.zip" notpkg.zip
si_view notpkg.zip
check "非方案包 zip：如实报失败" "yes" "$(si_has_text 导入失败)"
check "失败原因写明不是方案包" "yes" "$(dump_xml | grep -q '不是有效的方案包' && echo yes || echo no)"
tap_text "知道了"
sleep 1
adb_q shell run-as "$PKG" rm -rf "$SI_DIR"
rm -rf "$SI_TMP"
back_to_pad 2>/dev/null || adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
