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

section "[55] 导入方案：覆盖自带方案要二次确认，可「恢复自带版本」；非方案包报失败"
python3 - "$ROOT/app/src/main/assets/data/schemas/wubi86_pinyin.schema.toml" "$SI_TMP/override.wpkg" "$SI_TMP/notpkg.zip" <<'PY'
import sys, zipfile
src, out, bad = sys.argv[1:4]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("package.toml", '[package]\nformat_version = 2\ntitle = "覆盖测试"\n')
    z.write(src, "wubi86_pinyin.schema.toml")
with zipfile.ZipFile(bad, "w") as z:
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
check "覆盖了自带的显示「恢复自带版本」" "yes" "$(si_has_text '覆盖了自带方案 · 点此恢复自带版本')"
tap_text "覆盖了自带方案 · 点此恢复自带版本"
check "恢复前二次确认" "yes" "$(si_has_text 恢复自带版本)"
tap_text "恢复"
sleep 2
check "恢复后不再显示覆盖项" "no" "$(si_has_text '覆盖了自带方案 · 点此恢复自带版本')"
check "恢复后用户层覆盖文件已删" "no" "$(si_user_file_exists wubi86_pinyin.schema.toml)"
check "自带方案仍在列表里（回落到自带层）" "yes" "$(dump_xml | grep -q 'wubi86\|五笔拼音\|五笔' && echo yes || echo no)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1

si_push "$SI_TMP/notpkg.zip" notpkg.zip
si_view notpkg.zip
check "非方案包 zip：如实报失败" "yes" "$(si_has_text 导入失败)"
check "失败原因写明不是方案包" "yes" "$(dump_xml | grep -q '不是有效的方案包' && echo yes || echo no)"
tap_text "知道了"
sleep 1
adb_q shell run-as "$PKG" rm -rf "$SI_DIR"
rm -rf "$SI_TMP"
back_to_pad 2>/dev/null || adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
