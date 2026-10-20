#!/usr/bin/env bash
# 模拟器端到端输入测试（Linux/macOS 版，Windows 见 emu-test.ps1）：
# 环境准备 → 点击键盘按键 → 断言输入框内容
# 用例 [15][16]（组合态同步）、[23]–[26]（123 层 / 九宫格 / 符号页）为 bash 版新增，emu-test.ps1 尚无。
#
# ── 三个必踩的坑（都已在脚本里处理，改动前先读）────────────────────────
# 1. 模拟器带硬件键盘，InputMethodService.onEvaluateInputViewShown() 默认返回
#    false → 软键盘视图**从不创建**。必须 settings put secure
#    show_ime_with_hard_keyboard 1。
# 2. `am force-stop` 掉当前 IME 时，系统会把默认输入法切回 Gboard。之后所有
#    点击都是 Gboard 在响应（表现为"输入法坏了"）。force-stop 后必须重新 ime set。
# 3. 输入框为空时 uiautomator dump 会把 hint 当作 text 输出，据此断言会误判。
#    脚本按 hint 属性剔除。
#
# ── bash 版独有的坑 ───────────────────────────────────────────────────
# 4. `adb shell` 的输出是 **CRLF**。不剥掉 \r，`[[ "$t" == " " ]]` 这类等值断言
#    会全线失败，而打印出来看起来一模一样——最难查的一类假失败。adb_ 统一 tr -d。
# 5. bash 没有浮点运算。按键宽度权重（1.5 / 3.4）必须走 awk，用 $(( )) 会被
#    静默截断成整数，坐标偏到隔壁键上。
#
# 键盘按键是 Canvas 自绘的，uiautomator 看不到按键节点，只能按坐标点击；
# 坐标由布局比例算出（与 KeyboardLayouts / KeyboardDimens 一致），布局改了要同步这里。
#
# 用法：scripts/emu-test.sh [-s emulator-5554] [--skip-install]
set -uo pipefail

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
SKIP_INSTALL=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        -s|--serial) SERIAL="$2"; shift 2 ;;
        --skip-install) SKIP_INSTALL=1; shift ;;
        -h|--help) sed -n '1,30p' "$0"; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
ADB="${ADB:-$SDK/platform-tools/adb}"
[[ -x "$ADB" ]] || ADB="$(command -v adb)" || { echo "找不到 adb" >&2; exit 1; }
PKG="com.windinput.ime"
IME="$PKG/.service.WindInputImeService"

# 坑 4：剥掉 CRLF 的 \r，否则等值断言全线假失败
adb_() { "$ADB" -s "$SERIAL" "$@" 2>/dev/null | tr -d '\r'; }
adb_q() { "$ADB" -s "$SERIAL" "$@" >/dev/null 2>&1; }

# ⚠ 致命错误要能从子 shell 里穿出来。reset_pad/get_edit_bounds 都在
# `t="$(probe_chinese)"` 这类命令替换里跑，那是**子 shell**——里面 `exit 1` 只结束
# 子 shell，主脚本毫不知情地继续跑下一个用例。实测现象是「找不到测试输入框」连打
# 七遍然后照常往下测，全是假结果。kill 主进程才是真的停。
TOP_PID=$$
trap 'exit 1' TERM
die() { echo "$*" >&2; kill -s TERM "$TOP_PID" 2>/dev/null; exit 1; }

# ── 键盘布局：与 KeyboardLayouts.qwerty 保持一致 ────────────────────────
ROW_KEYS=(
    "q w e r t y u i o p"
    # 第 2 行两侧各有半键占位（_SP_，不可点）
    "_SP_ a s d f g h j k l _SP_"
    "SHIFT z x c v b n m BS"
    # 底栏权重须与 assets/data/layouts/qwerty.toml 同步：符号 · 123 · 逗号 · 空格 · 句号 · 中英 · 回车
    "SYMPAGE SYM COMMA SPACE PERIOD LANG ENTER"
)
ROW_W=(
    "1 1 1 1 1 1 1 1 1 1"
    "0.5 1 1 1 1 1 1 1 1 1 0.5"
    "1.5 1 1 1 1 1 1 1 1.5"
    "1.35 1.15 1 2.95 1 1.15 1.4"
)
NROWS=${#ROW_KEYS[@]}

GEO_W=0; GEO_H=0; GEO_DENSITY=0; GEO_ROWPX=0
GEO_KBTOP=0; GEO_KBBOTTOM=0; GEO_BARY=0; GEO_CODEBARY=0
GEO_PANELHEADERY=0; GEO_PANELBACKX=0

init_geometry() {
    local size w h dpi
    size="$(adb_ shell wm size | sed 's/.*:[[:space:]]*//' | tr -d '[:space:]')"
    w="${size%%x*}"; h="${size##*x}"
    dpi="$(adb_ shell wm density | sed 's/.*:[[:space:]]*//' | tr -d '[:space:]')"
    GEO_W="$w"; GEO_H="$h"
    # 坑 5：所有带小数的量一次性交给 awk 算完，bash 只接整数
    read -r GEO_DENSITY GEO_ROWPX GEO_KBTOP GEO_KBBOTTOM GEO_BARY GEO_CODEBARY \
            GEO_PANELHEADERY GEO_PANELBACKX < <(awk -v h="$h" -v dpi="$dpi" 'BEGIN{
        d = dpi / 160.0
        rowPx     = int(56 * d + 0.5)   # KeyboardDimens.rowHeightDp 默认值
        # 条区 = 编码行（BarView.PREEDIT_DP）+ 候选行（BarView.MAIN_ROW_DP）；工具栏占满两者
        codeBarPx = int(20 * d + 0.5)
        mainRowPx = int(40 * d + 0.5)
        # 底部 insets（手势条/导航栏）：InputRootView 的 bottomSpacer 高度
        insetPx   = int(48 * d + 0.5)
        kbBottom  = h - insetPx
        kbTop     = kbBottom - 4 * rowPx
        # 候选行在条区下半：条区顶 = kbTop-(编码栏+主行)，候选行中心再下移半个主行
        barY      = kbTop - int(mainRowPx / 2)
        # 编码行中心（候选态才有，在条区顶上）
        codeBarY  = kbTop - mainRowPx - int(codeBarPx / 2)
        # 面板 header 中心：面板打开时**条区整个让位**，面板从条区位置起画，
        # 其 header 与条区等高（BarView.TOTAL_DP）。写成 kbTop+xx 会点进键盘区。
        panelHdrY = kbTop - codeBarPx - mainRowPx + int((codeBarPx + mainRowPx) / 2)
        # 返回图标中心：header 左侧固定 52dp 宽
        panelBackX = int(26 * d + 0.5)
        printf "%.4f %d %d %d %d %d %d %d\n", d, rowPx, kbTop, kbBottom, barY, codeBarY, panelHdrY, panelBackX
    }')
    echo "几何: ${GEO_W}x${GEO_H} density=$GEO_DENSITY 行高=$GEO_ROWPX 键盘顶=$GEO_KBTOP 候选y=$GEO_BARY 编码栏y=$GEO_CODEBARY"
}

# 键位中心坐标 "cx cy"；找不到返回空
key_center() {
    local name="$1" r idx keys weights cx cy
    for ((r = 0; r < NROWS; r++)); do
        read -ra keys <<< "${ROW_KEYS[$r]}"
        idx=-1
        local i
        for i in "${!keys[@]}"; do [[ "${keys[$i]}" == "$name" ]] && { idx=$i; break; }; done
        (( idx < 0 )) && continue
        weights="${ROW_W[$r]}"
        cx="$(awk -v ws="$weights" -v idx="$idx" -v W="$GEO_W" 'BEGIN{
            n = split(ws, a, " "); total = 0
            for (i = 1; i <= n; i++) total += a[i]
            unit = W / total; x = 0
            for (i = 1; i <= idx; i++) x += a[i] * unit
            printf "%d", int(x + a[idx+1] * unit / 2)
        }')"
        cy="$(awk -v kb="$GEO_KBBOTTOM" -v n="$NROWS" -v r="$r" -v rp="$GEO_ROWPX" \
              'BEGIN{ printf "%d", int(kb - (n - r - 0.5) * rp) }')"
        printf '%s %s' "$cx" "$cy"
        return 0
    done
}

tap_key() {
    local name="$1" r idx keys weights cx cy
    for ((r = 0; r < NROWS; r++)); do
        read -ra keys <<< "${ROW_KEYS[$r]}"
        idx=-1
        local i
        for i in "${!keys[@]}"; do [[ "${keys[$i]}" == "$name" ]] && { idx=$i; break; }; done
        (( idx < 0 )) && continue
        weights="${ROW_W[$r]}"
        cx="$(awk -v ws="$weights" -v idx="$idx" -v W="$GEO_W" 'BEGIN{
            n = split(ws, a, " "); total = 0
            for (i = 1; i <= n; i++) total += a[i]
            unit = W / total; x = 0
            for (i = 1; i <= idx; i++) x += a[i] * unit
            printf "%d", int(x + a[idx+1] * unit / 2)
        }')"
        cy="$(awk -v kb="$GEO_KBBOTTOM" -v n="$NROWS" -v r="$r" -v rp="$GEO_ROWPX" \
              'BEGIN{ printf "%d", int(kb - (n - r - 0.5) * rp) }')"
        adb_q shell input tap "$cx" "$cy"
        sleep 0.4
        return 0
    done
    die "未知键位: $name"
}

# ── 非字母层：按「行号 + 行权重 + 下标」点（权重与布局 TOML 同步） ─────────
# 123 层（assets/data/layouts/symbols.toml）
SYM_W=(
    "1 1 1 1 1 1 1 1 1 1"
    "1 1 1 1 1 1 1 1 1 1"
    # 符号 · 。 ， 、 ？ ！ 半角句点 · ⌫
    "1.5 1 1 1 1 1 1 1.5"
    # 1234 · 返回 · 表情 · 空格 · 换行（与字母层底行同宽对齐）
    "1.35 1.15 1 5.1 1.4"
)
# 九宫格数字键盘（number.toml）：左列 · 三列数字 · 右列，四行同权重
NUM_W="1 1.4 1.4 1.4 1"

tap_cell() {   # 行号(0 起) 权重串 下标
    local cx cy
    cx="$(awk -v ws="$2" -v idx="$3" -v W="$GEO_W" 'BEGIN{
        n = split(ws, a, " "); total = 0
        for (i = 1; i <= n; i++) total += a[i]
        unit = W / total; x = 0
        for (i = 1; i <= idx; i++) x += a[i] * unit
        printf "%d", int(x + a[idx+1] * unit / 2)
    }')"
    cy="$(awk -v kb="$GEO_KBBOTTOM" -v r="$1" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", int(kb - (4 - r - 0.5) * rp) }')"
    adb_q shell input tap "$cx" "$cy"
    sleep 0.4
}

# 按文字点一个可见节点（符号页的格子和分类 tab、测试台按钮都是带 text 的 View）
# 轮询：Activity 刚重启、面板刚弹出时 dump 可能还拿不到节点
tap_text() {
    local node try
    for ((try = 0; try < 6; try++)); do
        # `|| -n "$node"`：dump 末尾没有换行，最后一个节点（测试台的按钮恰好是它）
        # 会被 read 丢掉——不加这句，按钮永远「找不到」
        while IFS= read -r node || [[ -n "$node" ]]; do
            [[ "$node" == *" text=\"$1\""* ]] || continue
            if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
                adb_q shell input tap $(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) \
                                      $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
                sleep 0.5
                return 0
            fi
        done < <(dump_xml | sed 's/<node/\n<node/g')
        sleep 0.7
    done
    die "找不到文字为「$1」的节点"
}

# 点候选条第 n 项（0 起，按等宽粗估；候选窄时够用）
tap_candidate() {
    adb_q shell input tap $(( 60 + $1 * 120 )) "$GEO_BARY"
    sleep 0.5
}

# 点第 n 项并把上屏文本读回来（读完清空），供「首屏候选有哪些」这类取样用
tap_candidate_text() {
    tap_candidate "$1"
    local t; t="$(get_pad_text)"
    reset_pad
    tap_key a
    sleep 0.5
    printf '%s' "$t"
}

# IME 窗口高度（px）。uiautomator 看不到 IME 窗口，只能从 window inset 取。
get_ime_height() {
    local v
    v="$(adb_ shell dumpsys window displays \
        | grep -oP 'mType=ime .*mInsetsHint=Insets\{[^}]*bottom=\K\d+' | head -1)"
    printf '%s' "${v:-0}"
}

dump_xml() {
    adb_q shell uiautomator dump /sdcard/wt.xml
    adb_ shell cat /sdcard/wt.xml
}

# 读输入框内容；输入框为空时 dump 会把 hint 填进 text，需剔除
get_pad_text() {
    local node text hint
    while IFS= read -r node; do
        [[ "$node" == *'content-desc="windinput-testpad"'* ]] || continue
        text=""; hint=""
        [[ "$node" =~ [[:space:]]text=\"([^\"]*)\" ]] && text="${BASH_REMATCH[1]}"
        [[ "$node" =~ [[:space:]]hint=\"([^\"]*)\" ]] && hint="${BASH_REMATCH[1]}"
        # 显示的是 hint = 实际为空
        [[ -n "$hint" && "$text" == "$hint" ]] && return 0
        printf '%s' "$text"
        return 0
    done < <(dump_xml | sed 's/<node/\n<node/g')
}

EDIT_CX=0; EDIT_CY=0
# 轮询重试：`force-stop` 后 Activity 要重建，固定 sleep 在慢设备（真机首启、
# 模拟器负载高时）不够，dump 会拿到还没布局好的层级。
get_edit_bounds() {
    # 真机上最常见的「重试 12 次仍无」不是布局慢，而是**屏幕休眠或通知栏挡着**：
    # Activity 明明是 mFocusedApp，dump 却拿不到节点，日志里只有十几行
    # 「intent has been delivered to currently running top-most instance」，
    # 看起来像 IME 坏了。先唤醒再说，省得又去查引擎。
    #   adb shell dumpsys power | grep mWakefulness    → Dozing 就是它
    #   adb shell dumpsys window | grep mCurrentFocus  → NotificationShade 就是它
    adb_q shell input keyevent KEYCODE_WAKEUP
    adb_q shell cmd statusbar collapse
    adb_q shell wm dismiss-keyguard

    local try node xml
    for ((try = 0; try < 12; try++)); do
        xml="$(dump_xml)"
        while IFS= read -r node; do
            [[ "$node" == *'content-desc="windinput-testpad"'* ]] || continue
            if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
                EDIT_CX=$(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 ))
                EDIT_CY=$(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
                return 0
            fi
        done < <(printf '%s' "$xml" | sed 's/<node/\n<node/g')
        # 第三种「dump 拿不到节点」的成因（前两种是休眠和通知栏）：**ANR 对话框**。
        # 它盖在最前面，dump 出来的整棵树都是那个弹窗，于是报「找不到测试输入框」，
        # 根因却和输入法毫无关系。从快照恢复时尤其容易撞上——恢复瞬间系统时间跳变，
        # watchdog 就判 SystemUI 无响应。认 aerr_close 这个按钮 id，点掉继续。
        dismiss_anr "$xml"
        sleep 0.7
        adb_q shell am start -n "$PKG/.settings.TestPadActivity"
    done
    die "找不到测试输入框（重试 12 次仍无）"
}

# 点掉 ANR 对话框的「关闭」按钮。传入已 dump 好的 xml，避免多跑一次 dump。
dismiss_anr() {
    local node
    [[ "$1" == *'android:id/aerr_close'* ]] || return 0
    while IFS= read -r node; do
        [[ "$node" == *'android:id/aerr_close'* ]] || continue
        if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
            echo "  检测到 ANR 对话框，点掉" >&2
            adb_q shell input tap $(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) \
                                  $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
            sleep 1.5
            return 0
        fi
    done < <(printf '%s' "$1" | sed 's/<node/\n<node/g')
}

reset_pad() {
    adb_q shell am start -n "$PKG/.settings.TestPadActivity"
    sleep 2
    # 清空：全选 + 删除（比重启 Activity 快，且不会掉 IME）
    get_edit_bounds
    adb_q shell input tap "$EDIT_CX" "$EDIT_CY"
    sleep 0.8
    adb_q shell input keyevent KEYCODE_MOVE_END
    # 次数要够长：命令短语用例会上屏 36 字符的 UUID，删不干净会污染下一个用例
    #（表现为下一条断言"实得"里带着上一条的残字，极易误判成新 bug）。
    # 一次 shell 里连发 40 下，逐次 exec 要多花 ~20s。
    adb_q shell 'for i in $(seq 1 40); do input keyevent KEYCODE_DEL; done'
    sleep 0.3
    [[ -z "$(get_pad_text)" ]] || die "reset_pad 未清空输入框，后续断言不可信"
}

# 重启测试台回到第 1 场景。`am start -S` 会 force-stop **整个包**，输入法也在其中
# ——系统随即切回 Gboard（坑 2），所以重启后必须重新 enable / set。
restart_pad() {
    adb_q shell am start -S -n "$PKG/.settings.TestPadActivity"
    adb_q shell ime enable "$IME"
    adb_q shell ime set "$IME"
    sleep 2
}

FAILED=0
G=$'\e[32m'; R=$'\e[31m'; Y=$'\e[33m'; C=$'\e[36m'; D=$'\e[90m'; N=$'\e[0m'

check() {   # case expected actual
    if [[ "$3" == "$2" ]]; then
        echo "  ${G}PASS${N}  $1 => '$3'"
    else
        echo "  ${R}FAIL${N}  $1 期望 '$2' 实得 '$3'"; FAILED=$((FAILED + 1))
    fi
}
check_match() {   # case pattern actual
    if printf '%s' "$3" | grep -qP "$2"; then
        echo "  ${G}PASS${N}  $1 => '$3'"
    else
        echo "  ${R}FAIL${N}  $1 期望匹配 /$2/ 实得 '$3'"; FAILED=$((FAILED + 1))
    fi
}
CN='^[\x{4e00}-\x{9fa5}]$'
CN_PLUS='^[\x{4e00}-\x{9fa5}]+$'

# ── 环境准备 ──────────────────────────────────────────────────────────
if (( ! SKIP_INSTALL )); then
    echo "${C}构建并安装...${N}"
    ( cd "$ROOT" && ./gradlew :app:assembleDebug ) >/dev/null || { echo "构建失败" >&2; exit 1; }
    adb_q install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
fi
# 坑 1：模拟器有硬件键盘 → 不显示软键盘视图
adb_q shell settings put secure show_ime_with_hard_keyboard 1
# 坑 2：force-stop 会让系统切回 Gboard。**enable 与 set 都必须排在 force-stop 之后**
# ——`pm clear` 还会把输入法重置成未启用，enable 若排在前面就被一起清掉了，
# 表现为「脚本说当前输入法是 Gboard」。
adb_q shell am force-stop "$PKG"
sleep 1
adb_q shell ime enable "$IME"
adb_q shell ime set "$IME"
# 系统写 secure setting 有延迟，轮询确认而不是睡固定时长
cur=""
for ((i = 0; i < 10; i++)); do
    sleep 0.5
    cur="$(adb_ shell settings get secure default_input_method | tr -d '[:space:]')"
    [[ "$cur" == "$PKG"* ]] && break
    adb_q shell ime set "$IME"
done
[[ "$cur" == "$PKG"* ]] || { echo "当前输入法不是本应用: $cur" >&2; exit 1; }
# 清日志：用例 10 靠 logcat 判性能，读到上一轮的旧告警会误报
adb_q logcat -c
init_geometry

# 活跃方案快照：跑完要比对（见文件末尾）。
# **方案选择是持久化的**，而面板上任何一个点错的坐标都可能把它改掉——改完当次用例
# 照样 PASS（不过是换了个中文方案，断言只看「是不是汉字」），污染留给**下一次**运行，
# 现象是「昨天好好的今天全挂」。故把「跑完方案没变」单列成一条不变量来守。
get_active_schema() {
    # 用户层没有 active 行 = 用的是出厂默认 wubi86：核心写配置时会把「等于出厂默认」的键
    # 从用户层删掉，切回五笔后这一行就消失了，不能当成「读不到」。
    # 非 debug 包没有 run-as → 空（读不到）
    local cfg
    cfg="$(adb_ shell run-as "$PKG" cat files/rust/config/WindInput/config.toml)" || return
    printf '%s\n' "$cfg" | grep -q 'run-as:' && return
    local line
    line="$(printf '%s\n' "$cfg" | grep -m1 '^active = ' | sed 's/[[:space:]]*$//')"
    printf '%s' "${line:-active = \"wubi86\"}"
}
CORE_CFG=files/rust/config/WindInput/config.toml
WUBI_SHADOW=files/rust/data/wubi86/shadow.dict.yaml

# 核心用户层整文件读 / 写（写经 stdin 灌进 run-as，免得引号层层转义）
read_core_cfg() { adb_ shell run-as "$PKG" cat "$CORE_CFG"; }
write_core_cfg() { printf '%s\n' "$1" | "$ADB" -s "$SERIAL" shell run-as "$PKG" sh -c "'cat > $CORE_CFG'"; }

# 去掉某个 [表] 段（到下一个 [ 开头的行为止）。直接追加同名段会写出重复表定义，核心整个解析失败
strip_section() {   # 文本 段名
    printf '%s\n' "$1" | awk -v sec="[$2]" '
        $0 == sec { skip = 1; next }
        /^\[/      { skip = 0 }
        !skip     { print }'
}

# 用例全按五笔写（aa → 式）。上一次运行若中途断掉（模拟器掉线、被中断），活跃方案可能
# 停在拼音或英文——它是持久化的，下一次所有用例连环失败，看着像功能坏了（实际遇到过：
# 拼音残留，aa 出「啊啊」）。开跑前钉回 wubi86，连同中英键记住的「上次中文方案」一起。
schema_now="$(get_active_schema)"
if [[ -n "$schema_now" && "$schema_now" != 'active = "wubi86"' ]]; then
    echo "${Y:-}残留方案 [$schema_now]，改回 wubi86 后重启${N:-}"
    adb_q shell "run-as $PKG sed -i 's/^active = .*/active = \"wubi86\"/' files/rust/config/WindInput/config.toml"
    adb_q shell run-as "$PKG" rm -f shared_prefs/ime_state.xml
    restart_pad
fi
# 同理，[35] / [36] 中途断掉会留下：简入繁出开着（之后所有中文断言拿到繁体）、
# aa 的置顶 / 隐藏规则（首选不再是「式」）。开跑前一并清掉。
cfg_now="$(read_core_cfg)"
if printf '%s\n' "$cfg_now" | grep -qx '\[input.s2t\]'; then
    echo "${Y}残留 [input.s2t]，清掉后重启${N}"
    write_core_cfg "$(strip_section "$cfg_now" input.s2t)"
    restart_pad
fi
if adb_ shell run-as "$PKG" cat "$WUBI_SHADOW" | grep -q $'^aa\t'; then
    echo "${Y}残留 aa 的候选调整规则，清掉后重启${N}"
    adb_q shell "run-as $PKG sed -i '/^aa\t/d' $WUBI_SHADOW"
    restart_pad
fi
# 首次使用的方案卡片会盖住键盘，别的用例全点不中：开跑前记成「已弹过」（[41] 专门测它）
ONBOARD_XML=shared_prefs/ime_onboarding.xml
mark_onboarded() {
    printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
        '<map><boolean name="first_pick_shown" value="true" /></map>' \
        | "$ADB" -s "$SERIAL" shell run-as "$PKG" sh -c "'mkdir -p shared_prefs && cat > $ONBOARD_XML'"
}
mark_onboarded
# [40] 中途断掉会留下 inline_preedit = true，之后所有读输入框的断言都带上内联编码
if adb_ shell run-as "$PKG" cat files/user/config.toml 2>/dev/null | grep -q '^inline_preedit = true'; then
    echo "${Y}残留 inline_preedit，清掉${N}"
    adb_q shell "run-as $PKG sed -i '/^inline_preedit = true/d' files/user/config.toml"
fi
# 候选字后的编码灰字（ui.android.candidate_comment，默认开）让每个候选变宽，tap_candidate
# 按固定步长点就会错位：整套用例关着它跑，跑完写回原配置。带 `# emu-test` 标记，
# 中途断掉留下的这一行下次开跑时先剥掉，SUITE_KB_ORIG 拿到的就是用户自己的配置
KB_CFG=files/user/config.toml
COMMENT_OFF='candidate_comment = false # emu-test'
write_kb_cfg() { printf '%s\n' "$1" | "$ADB" -s "$SERIAL" shell run-as "$PKG" sh -c "'mkdir -p files/user && cat > $KB_CFG'"; }
SUITE_KB_RAW="$(adb_ shell run-as "$PKG" cat "$KB_CFG" 2>/dev/null)"
SUITE_KB_EXISTED=1
[[ "$SUITE_KB_RAW" == *"No such file"* ]] && { SUITE_KB_EXISTED=0; SUITE_KB_RAW=""; }
SUITE_KB_ORIG="$(printf '%s\n' "$SUITE_KB_RAW" | grep -vxF "$COMMENT_OFF")"
with_comment_off() {   # 在 [ui.android] 段头后插一行；没有这一段就补一段
    # 用户自己写过的 candidate_comment 先去掉：留着就是重复键（TOML 错误、取值看解析器心情）
    local body
    body="$(printf '%s\n' "$1" | grep -vE '^[[:space:]]*candidate_comment[[:space:]]*=')"
    # 段头允许带空白与行尾注释（`[ui.android]  # 键盘`）；认不出就会再补一段，TOML 里是重复段
    if printf '%s\n' "$body" | grep -qE '^[[:space:]]*\[ui\.android\][[:space:]]*(#.*)?$'; then
        printf '%s\n' "$body" | awk -v line="$COMMENT_OFF" \
            '{ print } /^[[:space:]]*\[ui\.android\][[:space:]]*(#.*)?$/ { print line }'
    else
        printf '%s\n\n[ui.android]\n%s\n' "$body" "$COMMENT_OFF"
    fi
}
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restore_suite_kb() {   # 原来没有这个文件就删掉，不写回一个空文件
    if (( SUITE_KB_EXISTED )); then
        write_kb_cfg "$SUITE_KB_ORIG"
    else
        adb_q shell run-as "$PKG" rm -f "$KB_CFG"
    fi
}
trap 'restore_suite_kb; exit 1' TERM INT
restart_pad
SCHEMA_BEFORE="$(get_active_schema)"

# ── 建立前置条件：确保处于中文方案 ──────────────────────────────────────
# **方案选择是持久化的**（引擎会把 schema.active 写进用户配置）。上一次运行若在
# 英文用例中途失败，英文方案就留在那儿了，下次启动仍是英文——于是所有中文用例
# 连同系统短语一起失败，看起来像功能坏了。测试必须自己把前置条件建起来。
echo; echo "${Y}[0] 前置：确保中文方案${N}"
# 打一次中文探针；出汉字返回该字，否则返回空。
probe_chinese() {
    reset_pad
    tap_key a; tap_key a; tap_key SPACE
    local t; t="$(get_pad_text)"
    printf '%s' "$t" | grep -qP "$CN" && printf '%s' "$t"
}

# ⚠ **核心是异步构造的**（DeferredSession），未就绪时按键交还宿主、字母直接上屏。
# 那个结果（'aa '）与「当前不是中文方案」**完全一样**，此前这里把两者混为一谈：
# 重装后核心还没起来，[0] 就断定"非中文方案"去点中英键，**把输入法切进了英文**，
# 于是 [1]~[11] 的中文用例连环失败——失败现象在别处，根因是这一个判据。
# 先重试等就绪，重试到底仍不出汉字，才认为真是方案/模式不对。
wait_chinese() {
    local tries="${1:-6}" i t
    for ((i = 0; i < tries; i++)); do
        t="$(probe_chinese)"
        [[ -n "$t" ]] && { printf '%s' "$t"; return; }
        sleep 2
    done
}

probe="$(wait_chinese)"
if [[ -z "$probe" ]]; then
    echo "  ${Y}等不到中文候选，判定为非中文模式，点中英键切回${N}"
    reset_pad
    tap_key LANG
    sleep 4
    probe="$(wait_chinese 3)"
fi
check_match "已处于中文方案" "$CN" "$probe"

# 英文方案就绪探针：打 hi + 空格，得到 hi（可能首字母大写）即就绪。返回探到的文本
wait_english() {
    local i t
    for ((i = 0; i < 6; i++)); do
        reset_pad
        tap_key h; tap_key i; tap_key SPACE
        t="$(get_pad_text)"
        printf '%s' "$t" | grep -qP '^[Hh]i ' && { reset_pad; printf '%s' "$t"; return; }
        sleep 2
    done
}

echo; echo "${Y}[1] 五笔组合上屏：aa + 空格${N}"
reset_pad
tap_key a; tap_key a; tap_key SPACE
check_match "上屏汉字" "$CN" "$(get_pad_text)"

echo; echo "${Y}[2] 连续两轮组合（候选每轮都要出）${N}"
reset_pad
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
check_match "两轮各上屏一字" '^[\x{4e00}-\x{9fa5}]{2}$' "$(get_pad_text)"

echo; echo "${Y}[3] 空缓冲功能键${N}"
reset_pad
tap_key SPACE
check "空缓冲空格出空格" " " "$(get_pad_text)"
tap_key BS
check "退格删除" "" "$(get_pad_text)"

echo; echo "${Y}[4] 中文标点${N}"
reset_pad
tap_key COMMA
check "中文模式逗号是全角" "，" "$(get_pad_text)"

echo; echo "${Y}[5] 候选点选${N}"
reset_pad
tap_key a; tap_key a
tap_candidate 1
check_match "点选第二个候选" "$CN" "$(get_pad_text)"

echo; echo "${Y}[6] 组合中退格删码${N}"
reset_pad
tap_key a; tap_key a; tap_key BS; tap_key SPACE
check_match "删一码后上屏" "$CN" "$(get_pad_text)"

echo; echo "${Y}[7] 系统短语（命令直通车）${N}"
reset_pad
# uuid 是出厂命令短语（display = {uuid()} 求值结果）。探针不用 date：
# date 的五笔码表候选太多，短语被挤到第二页。
tap_key u; tap_key u; tap_key i; tap_key d
tap_candidate 0
# 首选未必是短语，只断言「打得出东西」+ 不崩；短语是否在首位属排序策略
check_match "短语码可上屏" '.+' "$(get_pad_text)"

echo; echo "${Y}[8] 条区总高恒定（编码栏出现不顶高键盘）${N}"
# ⚠ 编码栏本身**无法用 uiautomator 断言**：IME 窗口不进 dump（实测 dump 只有宿主
# Activity 的 9 个节点）。改测它必须守住的那条约束——组合态出现编码栏时，IME 窗口
# 总高不能变，否则每次起手打字键盘都会跳一下。高度取自 dumpsys 的 ime inset。
reset_pad
idle_h="$(get_ime_height)"
tap_key a; tap_key a
composing_h="$(get_ime_height)"
if (( idle_h > 0 && idle_h == composing_h )); then
    echo "  ${G}PASS${N}  空闲/组合两态窗口同高 ${idle_h}px"
else
    echo "  ${R}FAIL${N}  窗口高度跳变：空闲 ${idle_h}px → 组合 ${composing_h}px"; FAILED=$((FAILED + 1))
fi
tap_key BS; tap_key BS

echo; echo "${Y}[9] 快捷面板往返${N}"
# 同样绕开 dump：验证「打开面板 → 键盘让位 → 返回 → 键盘恢复」这条往返。
#
# ⚠ 不要用「敲原 'a' 键位应无反应」来验证面板已顶上：那个坐标落在面板的方案选择
# 区，实测正好点中「英」格，测试自己把引擎切成了英文模式，污染后面所有用例。
# 面板上任何坐标都可能是功能键，只点自己放的「返回」才安全。
reset_pad
# 工具栏只有两格：最左是快捷面板入口，宽 BarView.TOOLBAR_ICON_DP(52dp)，点它的中心。
# ⚠ 这里曾按「6 个等宽图标」算成 W/12；布局改了必须同步这行，否则可能点中别的入口，
# 用例照样 PASS（返回键位置相同），只是验的东西悄悄换了。
adb_q shell input tap "$(awk -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", 26 * d }')" "$GEO_BARY"
sleep 0.8
panel_h="$(get_ime_height)"
# ⚠ 坐标取自 GEO_PANELHEADERY/GEO_PANELBACKX，别自己算：面板打开时条区让位、面板
# 从条区位置起画，header 不在 kbTop 之下而在其**之上**。此处曾写成 kbTop+20dp，
# 点进了键盘区 → 面板没返回 → 后续用例的点击全落在面板上，级联成多个假失败。
adb_q shell input tap "$GEO_PANELBACKX" "$GEO_PANELHEADERY"
sleep 2
tap_key a; tap_key a; tap_key SPACE
check_match "返回后键盘可用" "$CN" "$(get_pad_text)"
if (( panel_h <= 0 )); then
    echo "  ${R}FAIL${N}  面板期间取不到 IME 窗口高度"; FAILED=$((FAILED + 1))
fi

echo; echo "${Y}[10] 首键延迟（词库预热）${N}"
# 惰性构建的反查/合并索引若没被后台预热，会同步阻塞用户首键（实测 2.8s）。
# 阈值取 100ms 而非一帧：16ms 级抖动是 GC/调度噪声，卡顿量级才是要守的。
slow="$(adb_ shell logcat -d -s RustSession:W \
    | grep -oP '阻塞主线程 \K\d+(?=µs)' | awk '$1 > 100000' | paste -sd, -)"
if [[ -n "$slow" ]]; then
    echo "  ${R}FAIL${N}  首键阻塞主线程 ${slow}µs"; FAILED=$((FAILED + 1))
else
    echo "  ${G}PASS${N}  无 100ms 级主线程阻塞"
fi

echo; echo "${Y}[11] 中英切换键${N}"
# 底栏中英键切到**英文方案**（带补全）：空格上屏所打原文并自动补空格，再点回中文恢复出汉字。
reset_pad
tap_key LANG
# 切换要走引擎，等待给足
sleep 4
tap_key h; tap_key i; tap_key SPACE
check "英文空格上屏原文 + 自动空格" "hi " "$(get_pad_text)"
tap_key PERIOD
# 句号键：英文模式出半角句点；自动补的空格挪到句点后面
check "英文句点吃掉自动空格" "hi. " "$(get_pad_text)"

tap_key LANG
sleep 3
reset_pad
tap_key a; tap_key a; tap_key SPACE
back_to_cn="$(get_pad_text)"
check_match "切回中文出汉字" "$CN" "$back_to_cn"
# ⚠ 收尾必须回到中文：方案选择持久化，留在英文会污染**下一次运行**的所有中文用例
if ! printf '%s' "$back_to_cn" | grep -qP "$CN"; then
    echo "  ${Y}切回失败，强制再切一次以免污染下次运行${N}"
    reset_pad; tap_key LANG; sleep 4
fi

echo; echo "${Y}[12] 快速点击的按下反馈${N}"
# 「点得快就没有按下效果」的回归。`input tap` 的 down/up 间隔近乎为零，是最坏情况：
# 两次 invalidate 落在同一帧内被合并，若不做保底，按下态一帧都不会被绘制。
# KeyboardView 在高亮结束时自检绘制帧数，未绘制即打 W 日志。
reset_pad
adb_q logcat -c
for ((i = 0; i < 10; i++)); do adb_q shell input tap 60 "$GEO_KBTOP"; done
sleep 0.8
missed="$(adb_ shell logcat -d -s WindKeyboard:W | grep -c "一帧未绘制")"
if (( missed == 0 )); then
    echo "  ${G}PASS${N}  10 次极限连点均绘制出按下态"
else
    echo "  ${R}FAIL${N}  $missed 次按下高亮未被绘制"; FAILED=$((FAILED + 1))
fi
reset_pad

echo; echo "${Y}[13] 候选栏滚动后可选中第 10 个以后的候选${N}"
# 此前点选靠**合成页内数字键 1-9**，第 10 个及以后永远点不到，候选栏做了滚动也是空转
# （一帧只有 6~9 条）。现在候选按窗口拉取、按绝对下标选词。
#
# 判据刻意宽松——只断言「上屏了一个汉字，且不是首屏那几个」：具体是哪个字取决于
# 滚动距离与字宽，钉死某个字会变成脆弱用例。要守的是「够得着远处的候选」。
reset_pad
tap_key a                         # 单码 a：五笔重码极多，必然远超一屏
sleep 0.6
near=()
for ((i = 0; i < 3; i++)); do near+=("$(tap_candidate_text "$i")"); done
# 甩两把把候选栏滚远（起点留出右边距，避开展开钮）
swipe_from="$(awk -v w="$GEO_W" 'BEGIN{ printf "%d", int(w * 0.75) }')"
for ((i = 0; i < 2; i++)); do
    adb_q shell input swipe "$swipe_from" "$GEO_BARY" 60 "$GEO_BARY" 120
    sleep 0.7
done
adb_q shell input tap "$(awk -v w="$GEO_W" 'BEGIN{ printf "%d", int(w * 0.4) }')" "$GEO_BARY"
sleep 0.7
far="$(get_pad_text)"
check_match "滚动后点选出汉字" "$CN_PLUS" "$far"
in_near=0
for t in "${near[@]}"; do [[ -n "$far" && "$t" == "$far" ]] && in_near=1; done
if (( in_near )); then
    echo "  ${R}FAIL${N}  滚动后选到的仍是首屏候选 '$far'（滚动没生效或仍受页内 9 项限制）"
    FAILED=$((FAILED + 1))
elif [[ -n "$far" ]]; then
    printf '  %sPASS%s  选到的是首屏之外的候选 => %s（首屏为 %s）\n' \
        "$G" "$N" "'$far'" "$(IFS=/; echo "${near[*]}")"
fi

echo; echo "${Y}[15] 收起键盘后组合态不残留${N}"
# 打到一半按返回收键盘，再点回输入框——之前那串码必须已经作废：接着按空格应上屏
# 一个空格，而不是把旧码拼出来的字。onFinishInputView 就是为这个加的。
reset_pad
tap_key a; tap_key a
adb_q shell input keyevent KEYCODE_BACK      # 收键盘（不失焦，不走 onFinishInput）
sleep 1
adb_q shell input tap "$EDIT_CX" "$EDIT_CY"   # 点回输入框，键盘重新弹出
sleep 1.5
tap_key SPACE
check "收键盘后空格不上屏旧码的字" " " "$(get_pad_text)"

echo; echo "${Y}[16] 点编辑框移动光标后组合态不残留${N}"
# 键盘不收、直接点编辑框（用户挪光标的最常见方式）：同样要作废组合。
reset_pad
tap_key a; tap_key a
adb_q shell input tap "$EDIT_CX" "$EDIT_CY"   # onViewClicked / onUpdateEditorToolType
sleep 1
tap_key SPACE
check "点编辑框后空格不上屏旧码的字" " " "$(get_pad_text)"

echo; echo "${Y}[17] 编辑面板：选择 + 方向键 + 剪切${N}"
# 选择模式此前靠带 Shift 的方向键事件，多数编辑器不理会 → 「选择无效」。现在用 setSelection。
# 面板网格 4 列 × 3 行铺满键盘四行区域；顶栏（返回）与条区等高。
reset_pad
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
before="$(get_pad_text)"
# ⚠ 上屏后条区被**联想候选**（标点）占着，工具栏不可见；这时点条区点到的是联想项
# （实测把「，」上了屏）。先点一下输入框：光标动 → 组合/联想作废 → 工具栏回来。
adb_q shell input tap "$EDIT_CX" "$EDIT_CY"; sleep 0.8
# 工具栏只剩入口和收起，文本编辑在快捷面板里：入口 → 面板网格第 2 行第 2 格
# （第 1 行：中文标点 · 全角 · 无痕 · 表情；第 2 行：剪贴板 · 文本编辑 …）。
# 网格行位置（键盘顶下 158dp）取自 QuickPanelWindow 实测：方案分段行 + 第 1 行开关格。
# ⚠ 格子顺序一变（多出「简入繁出」格 s2tShown、新增开关）文本编辑就挪位，这里要跟着改。
adb_q shell input tap "$(awk -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", 26 * d }')" "$GEO_BARY"
sleep 1
adb_q shell input tap $(( GEO_W * 3 / 8 )) "$(awk -v t="$GEO_KBTOP" -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", t + 158 * d }')"
sleep 1
cell_h=$(( GEO_ROWPX * 4 / 3 ))
edit_cell() { adb_q shell input tap $(( GEO_W * (2 * $2 + 1) / 8 )) $(( GEO_KBTOP + cell_h * (2 * $1 + 1) / 2 )); sleep 0.5; }
edit_cell 1 1      # 选择
edit_cell 1 0      # ←
edit_cell 1 0      # ←
edit_cell 0 2      # 剪切
sleep 0.6
after="$(get_pad_text)"
adb_q shell input tap "$GEO_PANELBACKX" "$GEO_PANELHEADERY"   # 返回键盘
sleep 1
if printf '%s' "$before" | grep -qP '^[\x{4e00}-\x{9fa5}]{3}$'; then
    check "选中末两字后剪切只剩一字" "${before:0:1}" "$after"
else
    echo "  ${R}FAIL${N}  前置三字未上屏：'$before'"; FAILED=$((FAILED + 1))
fi

echo; echo "${Y}[18] 有编码时滑退格清编码，不动正文${N}"
# 此前有编码时左滑退格删的是输入框里的字（意图相反）。现在等价于清空编码。
reset_pad
tap_key a; tap_key a; tap_key SPACE          # 先上屏一个字当"正文"
tap_key a; tap_key a                         # 再打两码，处于组合态
read -r bs_x bs_y <<< "$(key_center BS)"
adb_q shell input swipe "$bs_x" "$bs_y" $(( bs_x - GEO_W / 3 )) "$bs_y" 250
sleep 0.6
tap_key SPACE                                # 编码已清 → 空格出空格
check_match "正文保留一字且编码已清" '^[\x{4e00}-\x{9fa5}] $' "$(get_pad_text)"

echo; echo "${Y}[19] 有编码时回车上屏编码原文${N}"
# 大众布局：有编码时回车键写「确定」，按下上屏字母原文（键面文字 uiautomator 看不到，验行为）
reset_pad
tap_key a; tap_key a; tap_key ENTER
check "回车上屏编码原文" "aa" "$(get_pad_text)"

echo; echo "${Y}[20] 第 2 行缩进的半键空白归最近的键${N}"
# 第 2 行两侧各缩进半个键（布局 spacer），落在空白里的触点归同一行最近的键（a / l），不能点空
reset_pad
row2_y="$(awk -v kb="$GEO_KBBOTTOM" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", kb - 2.5 * rp }')"
adb_q shell input tap 8 "$row2_y"; sleep 0.4
adb_q shell input tap 8 "$row2_y"; sleep 0.4
tap_key SPACE
check_match "左侧空白两次 = aa 出字" "$CN" "$(get_pad_text)"

echo; echo "${Y}[21] 长按字母出大写${N}"
# 字母长按气泡是「小写 / 大写」，大写在右；按住不抬手右移一格再松开即选中大写。
# 用 motionevent 而不是 swipe：swipe 一按下就开始移动，气泡还没弹出手指已经划走了。
reset_pad
read -r ex ey <<< "$(key_center e)"
shift_px="$(awk -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", 25 * d }')"
adb_q shell input motionevent DOWN "$ex" "$ey"; sleep 0.8
adb_q shell input motionevent MOVE $(( ex + shift_px )) "$ey"; sleep 0.2
adb_q shell input motionevent UP $(( ex + shift_px )) "$ey"; sleep 0.6
check "长按 e 右移选中 E" "E" "$(get_pad_text)"

echo; echo "${Y}[22] 有编码时切中英先上屏字母原文${N}"
reset_pad
tap_key a; tap_key a; tap_key LANG
sleep 3
check "切英文前编码原文上屏" "aa" "$(get_pad_text)"
# 收尾切回中文（方案选择持久化，留在英文会污染后续运行），并确认真的回来了
tap_key LANG; sleep 3
reset_pad
tap_key a; tap_key a; tap_key SPACE
check_match "切回中文出汉字" "$CN" "$(get_pad_text)"

echo; echo "${Y}[23] 123 层：中文模式下的半角句点与中文句号${N}"
# 第 3 行「。」走引擎（中文模式出全角），最后一格半角句点原样上屏（打小数用）；← 回字母层
reset_pad
tap_key SYM
tap_cell 0 "${SYM_W[0]}" 0; tap_cell 2 "${SYM_W[2]}" 6; tap_cell 0 "${SYM_W[0]}" 1; tap_cell 2 "${SYM_W[2]}" 1
check "1 . 2 。" "1.2。" "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1
tap_key a; tap_key a; tap_key SPACE
check_match "← 回到字母层继续打中文" '^1\.2。[\x{4e00}-\x{9fa5}]$' "$(get_pad_text)"

echo; echo "${Y}[24] 九宫格数字键盘${N}"
# 123 层「1234」进九宫格，算式不用切层：数字、句点在格子里，* 在左列第 3 项
reset_pad
tap_key SYM; tap_cell 3 "${SYM_W[3]}" 0
tap_cell 0 "$NUM_W" 1; tap_cell 0 "$NUM_W" 2; tap_cell 3 "$NUM_W" 3; tap_cell 1 "$NUM_W" 2
side_x="$(awk -v W="$GEO_W" 'BEGIN{ printf "%d", W / 6.2 / 2 }')"
side_y="$(awk -v top="$GEO_KBTOP" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", top + 0.75 * rp * 2.5 }')"
adb_q shell input tap "$side_x" "$side_y"; sleep 0.4
tap_cell 0 "$NUM_W" 3
check "12.5*3" "12.5*3" "$(get_pad_text)"
# ← 回进来的那一层（123 层），再 ← 回字母层
tap_cell 3 "$NUM_W" 0; tap_cell 3 "${SYM_W[3]}" 1
# 数字框直接调起九宫格：第 3 场景（测试台场景按钮循环），按九宫格坐标打 5 0
restart_pad
tap_text "切换场景"; tap_text "切换场景"
get_edit_bounds; adb_q shell input tap "$EDIT_CX" "$EDIT_CY"; sleep 1
tap_cell 1 "$NUM_W" 2; tap_cell 3 "$NUM_W" 2
check "数字框直接是九宫格" "50" "$(get_pad_text)"
# 场景复位（重启 Activity 回到第 1 场景），后续用例照常
restart_pad

echo; echo "${Y}[25] 分类符号页：选符号上屏、停在本页、退格、返回${N}"
# 符号页在 IME 窗口里，uiautomator 只 dump 应用窗口，格子和 tab 看不到，只能按坐标点。
# 先清掉「最近」（debug 包可 run-as），进页面就固定落在第一个分类「中」：
# 第 1 行是 ， 。 、 ？ ！ …（assets/data/symbol_pages.toml），每行 10 格。
adb_q shell run-as "$PKG" rm -f shared_prefs/recent_symbol.xml
restart_pad      # SharedPreferences 有进程内缓存，删文件后要重启进程才读得到
# 进程刚重启，核心异步加载；没就绪时字母直接上屏，下面「有编码」的前提就不成立
[[ -n "$(wait_chinese)" ]] || die "重启后中文引擎未就绪"
reset_pad
sym_cell() { adb_q shell input tap $(( GEO_W * (2 * $2 + 1) / 20 )) $(( GEO_KBTOP + GEO_ROWPX * (2 * $1 + 1) / 2 )); sleep 0.4; }
# 有编码时点符号：先上屏首选再接符号（与打标点的效果一致）
tap_key a; tap_key a
tap_key SYM; tap_cell 2 "${SYM_W[2]}" 0; sleep 0.6
sym_cell 0 0; sym_cell 0 1
check_match "首选 + ，。（点完停在本页）" '^[\x{4e00}-\x{9fa5}]，。$' "$(get_pad_text)"
sym_bs_x=$(( GEO_W * 925 / 1000 )); sym_back_x=$(( GEO_W * 75 / 1000 ))
sym_bottom_y=$(( GEO_KBBOTTOM - GEO_ROWPX / 2 ))
adb_q shell input tap "$sym_bs_x" "$sym_bottom_y"; sleep 0.4
check_match "符号页退格" '^[\x{4e00}-\x{9fa5}]，$' "$(get_pad_text)"
# ← 回到 123 层（不是字母层）：点第 1 行第 1 格应出 1
adb_q shell input tap "$sym_back_x" "$sym_bottom_y"; sleep 0.6
tap_cell 0 "${SYM_W[0]}" 0
check_match "← 回到 123 层" '^[\x{4e00}-\x{9fa5}]，1$' "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1

echo; echo "${Y}[26] 最近使用置首、重启后保留${N}"
restart_pad
reset_pad
tap_key SYM; tap_cell 2 "${SYM_W[2]}" 0; sleep 0.6
# 有最近使用时默认进「最近」，第一格是最后点的那个（[25] 里最后点的是「。」）
sym_cell 0 0
check "最近第一格是上次点的「。」" "。" "$(get_pad_text)"
adb_q shell input tap "$sym_back_x" "$sym_bottom_y"; sleep 0.6
tap_cell 3 "${SYM_W[3]}" 1

echo; echo "${Y}[27] 英文补全：点候选上屏补全词并自动加空格${N}"
reset_pad
tap_key LANG; sleep 4
tap_key b; tap_key e; tap_key a; tap_key u
# 候选条是自绘的，按宽度粗估：首项是原文 beau，屏幕中间落在其后的补全词上（Beaumont / beautiful）
adb_q shell input tap $(( GEO_W / 2 )) "$GEO_BARY"; sleep 0.6
check_match "点补全词上屏" '^[Bb]eau[a-z]+ $' "$(get_pad_text)"

echo; echo "${Y}[28] 英文句首自动大写、双击空格出句号${N}"
# 句子框（第 5 场景，CAP_SENTENCES）：句首的 h 自动大写，打完 hi 双击空格得「. 」，下一句又自动大写
restart_pad
tap_text "切换场景"; tap_text "切换场景"; tap_text "切换场景"; tap_text "切换场景"
get_edit_bounds; adb_q shell input tap "$EDIT_CX" "$EDIT_CY"; sleep 1.5
[[ -n "$(wait_english)" ]] || die "重启后英文方案未就绪"
tap_key h; tap_key i
read -r sp_x sp_y <<< "$(key_center SPACE)"
# 两下空格要在 600ms 内：放进同一个 adb shell 里连发
adb_q shell "input tap $sp_x $sp_y; input tap $sp_x $sp_y"; sleep 0.5
# 自动大写的 H 进的是英文组合（补全不断），空格上屏后才读得到
tap_key h; tap_key SPACE
check "句首大写 + 双空格句号 + 下一句又大写" "Hi. H " "$(get_pad_text)"
restart_pad

echo; echo "${Y}[29] 密码框不自动大写、不出句号${N}"
restart_pad
tap_text "切换场景"; tap_text "切换场景"; tap_text "切换场景"
get_edit_bounds; adb_q shell input tap "$EDIT_CX" "$EDIT_CY"; sleep 1.5
tap_key h; tap_key i
adb_q shell "input tap $sp_x $sp_y; input tap $sp_x $sp_y"; sleep 0.5
check "密码框原样直输（不大写、不补全、不出句号）" "hi  " "$(get_pad_text)"
restart_pad
[[ -n "$(wait_english)" ]] || die "重启后英文方案未就绪"
# 收尾回中文（方案选择持久化，留在英文会污染下次运行）
reset_pad; tap_key LANG; sleep 4
[[ -n "$(wait_chinese)" ]] || die "收尾切回中文失败"

echo; echo "${Y}[30] 中文模式双击空格出「。」${N}"
# 数字后面也要成立（汉字后面的情形见 [32]）：从 123 层打一个数字再回字母层，
# 光标前是「1」，双击空格应换成「。」
reset_pad
tap_key SYM; tap_cell 0 "${SYM_W[0]}" 0; tap_cell 3 "${SYM_W[3]}" 1
adb_q shell "input tap $sp_x $sp_y; input tap $sp_x $sp_y"; sleep 0.5
check "数字后双空格换成句号" "1。" "$(get_pad_text)"

echo; echo "${Y}[31] 上屏后联想：学会「我们 → 一直」后点联想首位${N}"
# 下一词学习（主仓 learn_next_word）：相邻上屏两次「我们」「一直」，第三次上屏「我们」联想首位应是「一直」
reset_pad
for w in t r w u SPACE g g f h SPACE t r w u SPACE g g f h SPACE t r w u SPACE; do tap_key "$w"; done
sleep 0.5
tap_candidate 0
check_match "联想首位整词上屏" '我们一直我们一直我们一直$' "$(get_pad_text)"

echo; echo "${Y}[32] 联想态按空格出空格，双击出「。」${N}"
# 两家在联想态按空格都是收起联想、出空格，不选联想词
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
tap_key SPACE
check_match "联想态空格出空格" '^[\x{4e00}-\x{9fa5}] $' "$(get_pad_text)"
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
adb_q shell "input tap $sp_x $sp_y; input tap $sp_x $sp_y"; sleep 0.5
check_match "汉字后双击空格出「。」" '^[\x{4e00}-\x{9fa5}]。$' "$(get_pad_text)"

echo; echo "${Y}[33] 联想态按退格先收联想、不删正文${N}"
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
tap_key BS
check_match "第一下退格只收联想" '^[\x{4e00}-\x{9fa5}]$' "$(get_pad_text)"
tap_key BS
check "第二下退格才删字" "" "$(get_pad_text)"

echo; echo "${Y}[34] 收起键盘后联想不残留${N}"
# 联想还在的话，数字键会选联想词；收起再弹出后数字应原样上屏
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
adb_q shell input keyevent KEYCODE_BACK; sleep 1
get_edit_bounds; adb_q shell input tap "$EDIT_CX" "$EDIT_CY"; sleep 1.2
tap_key SYM; tap_cell 0 "${SYM_W[0]}" 0; tap_cell 3 "${SYM_W[3]}" 1
check_match "收起后数字原样上屏" '^[\x{4e00}-\x{9fa5}]1$' "$(get_pad_text)"

echo; echo "${Y}[35] 简入繁出：开启后「我们」上屏为「我們」${N}"
# 要 APK 带上 opencc 数据才有效——没带时开关照样能开，上屏却还是简体，且不报错。
# 直接写核心用户层再重启进程（等价于设置页写入 + 重载，但不依赖设置页的坐标）；
# 跑完把原文件写回去，不污染后续用例与下次运行；中途退出（die / Ctrl-C）也由 trap 写回。
cfg_orig="$(read_core_cfg)"
restore_cfg() { write_core_cfg "$cfg_orig"; }
trap 'restore_cfg; restore_suite_kb; exit 1' TERM INT
write_core_cfg "$(strip_section "$cfg_orig" input.s2t)
[input.s2t]
enabled = true"
restart_pad
wait_chinese >/dev/null
reset_pad
for w in t r w u SPACE; do tap_key "$w"; done
check "简入繁出上屏繁体" "我們" "$(get_pad_text)"
restore_cfg
trap 'restore_suite_kb; exit 1' TERM INT
restart_pad
wait_chinese >/dev/null

echo; echo "${Y}[36] 候选长按：提示卡 →「更多操作」→ 置顶第三个候选，再恢复默认${N}"
# 菜单是 IME 窗口里的浮层，uiautomator 看不到，按版式算坐标（CandidateMenuPopup）：
# 卡片在条区下方 4dp、左右各让 8dp，内边距 10dp，标题一行之后是 4 列格子，
# 第 1 行中心约在键盘顶下 62dp，行距 46dp（实测）。
menu_cell() {   # 行 列
    adb_q shell input tap \
        "$(awk -v w="$GEO_W" -v d="$GEO_DENSITY" -v c="$2" 'BEGIN{ inner = w - 36 * d; printf "%d", 18 * d + inner / 4 * (c + 0.5) }')" \
        "$(awk -v t="$GEO_KBTOP" -v d="$GEO_DENSITY" -v r="$1" 'BEGIN{ printf "%d", t + (62 + 46 * r) * d }')"
    sleep 0.6
}
long_press_candidate() {
    local x=$(( 60 + $1 * 120 ))
    adb_q shell input swipe "$x" "$GEO_BARY" "$x" "$GEO_BARY" 900
    sleep 0.8
    # 长按先出提示卡（编码 / 读音…），「更多操作」在标题行最右，约键盘顶下 30dp、
    # 右边距 60dp 处；点它换成菜单。汉字候选都有提示（读音段），这里的候选恒为汉字。
    # 卡没弹出的话这一下落在第 1 行字母上，后面的菜单格子断言随之失败，不会假通过
    adb_q shell input tap \
        "$(awk -v w="$GEO_W" -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", w - 60 * d }')" \
        "$(awk -v t="$GEO_KBTOP" -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", t + 30 * d }')"
    sleep 0.8
}
# 先记下第 3 个候选是哪个字：只断言「首选不是式」的话，菜单没弹出、那一下穿透到键盘
# 多补了一个码，上屏的也是别的字，会假通过
reset_pad
tap_key a; tap_key a
tap_candidate 2
third="$(get_pad_text)"
reset_pad
tap_key a; tap_key a
long_press_candidate 2
check "长按候选不上屏" "" "$(get_pad_text)"
menu_cell 0 0          # 置顶
tap_key SPACE
check "置顶后首选就是第 3 个候选「$third」" "$third" "$(get_pad_text)"
reset_pad
tap_key a; tap_key a
long_press_candidate 0
menu_cell 1 0          # 恢复默认（第 2 行第 1 格）
tap_key SPACE
check "恢复默认后首选回到「式」" "式" "$(get_pad_text)"

echo; echo "${Y}[37] 逗号、句号各占一键；五笔 emoj 出表情${N}"
# 默认布局（豆包式底栏）：中文模式下逗号键出「，」、句号键出「。」，不用上滑
reset_pad
tap_key COMMA; tap_key PERIOD
check "逗号键与句号键" "，。" "$(get_pad_text)"
# 五笔 Emoji 库默认加载：emoj 首选是 😂（库里按权重排的第一个）。曾因 APK 没带这个库而什么都不出
reset_pad
tap_key e; tap_key m; tap_key o; tap_key j; tap_key SPACE
# uiautomator 把 BMP 以外的字符写成 XML 数字实体：😂（U+1F602）读回来是 &#128514;
check "emoj 上屏表情" "&#128514;" "$(get_pad_text)"

echo; echo "${Y}[38] 设置页层级：首页 → 方案 → 码表 → 返回；词库 → 我的词条${N}"
# 设置页分了两层（方案 / 输入按组进二级页，词库拆成入口），返回走预测性返回的回调。
# 这里只验「点得进、退得回、每层该有的东西在」，不验视觉。
has_text() {   # 当前界面有没有这段文字（等界面稳定，最多约 4 秒）
    local try
    for ((try = 0; try < 6; try++)); do
        dump_xml | grep -q " text=\"$1\"" && { echo yes; return; }
        sleep 0.7
    done
    echo no
}
adb_q shell am start -n "$PKG/.settings.SettingsActivity"
sleep 3
check "首页有「开始使用」" "yes" "$(has_text 开始使用)"
tap_text "方案"
check "方案页有「码表」入口" "yes" "$(has_text 码表)"
tap_text "码表"
check "码表二级页有开关" "yes" "$(has_text 全码唯一自动上屏)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
check "返回两层回到首页" "yes" "$(has_text 开始使用)"
tap_text "词库"
tap_text "我的词条"
check "我的词条页有清空入口" "yes" "$(has_text 清空个人词)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
# 下拉项：miuix 的下拉弹窗要 activity ≥ 1.12 提供的 NavigationEventDispatcher，
# 缺了一点开就崩（dev.17 及以前所有下拉都这样）。只打开看选项、按返回收起，不改值
tap_text "输入"
tap_text "默认状态"
tap_text "初始语言模式"
check "下拉项点开不崩、列出选项" "yes" "$(has_text 英文)"
adb_q shell input keyevent BACK; sleep 1
check "返回先收起下拉，仍在本页" "yes" "$(has_text 初始字符宽度)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
restart_pad

echo; echo "${Y}[39] 长按中英键弹方案卡片：切到全拼打 ni，再切回五笔${N}"
# 卡片与候选长按菜单同一组件，格子坐标同 menu_cell；方案顺序按核心给的列表：
# 清风英文 · 全拼 · 双拼 · 五笔 · 五笔拼音（第 1 行 4 格）
reset_pad
read -r lx ly <<< "$(key_center LANG)"
adb_q shell input swipe "$lx" "$ly" "$lx" "$ly" 900; sleep 0.8
menu_cell 0 1          # 全拼
sleep 6                # 模拟器上首次编译拼音词库近 5 秒，期间按键直接上屏字母
tap_key n; tap_key i; tap_key SPACE
check "切到全拼后 ni 出「你」" "你" "$(get_pad_text)"
reset_pad
adb_q shell input swipe "$lx" "$ly" "$lx" "$ly" 900; sleep 0.8
menu_cell 0 3          # 五笔
sleep 2
tap_key a; tap_key a; tap_key SPACE
check "切回五笔 aa 出「式」" "式" "$(get_pad_text)"

echo; echo "${Y}[40] 开着「编码也显示在输入框里」：上屏后输入框不留占位符${N}"
# 核心在联想态挂一个占位组合（空格等，桌面 TSF 给候选窗定位用）；手机上写进来就是输入框里
# 一个带下划线的空格（真机反馈）。键盘侧把纯占位的组合文本挡掉。开关是键盘自己的配置文件。
kb_orig="$(adb_ shell run-as "$PKG" cat "$KB_CFG" 2>/dev/null | grep -v 'No such file')"
write_kb_cfg "$(strip_section "$kb_orig" ui.android)
[ui.android]
$COMMENT_OFF
inline_preedit = true"
restart_pad
wait_chinese >/dev/null
reset_pad
tap_key a; tap_key a
check "内联编码写进输入框" "aa" "$(get_pad_text)"
tap_key SPACE
check "上屏后不留占位空格" "式" "$(get_pad_text)"
# 内联编码打一码再退格：码要被删掉，不能留成正文（finishComposingText 只去下划线）
reset_pad
tap_key a; tap_key BS
check "内联编码退格清空后不留残码" "" "$(get_pad_text)"
write_kb_cfg "$kb_orig"
restart_pad
wait_chinese >/dev/null

echo; echo "${Y}[41] 首次使用：第一次弹键盘先出方案卡片，选完不再出${N}"
# 点卡片里的「全拼」再打 ni：卡片在就出「你」；卡片没出来的话那一下落在字母键上，对不上
# 当成全新用户：连 ime_state（记着上次中文方案）一起删——记过它的会被当成升级上来的老用户、不弹卡片
adb_q shell run-as "$PKG" rm -f "$ONBOARD_XML" shared_prefs/ime_state.xml
restart_pad            # am start -S：进程重起，SharedPreferences 的进程内缓存一并丢掉
sleep 2
# 点输入框把键盘叫出来。卡片可能已随测试台的 stateVisible 弹出——首次卡片不因点应用区域
# 而收（真机上用户第一次几乎一定会再点一下文本框），所以这一下点不掉它
adb_q shell input tap "$EDIT_CX" "$EDIT_CY"
sleep 3
menu_cell 0 1          # 全拼
sleep 3
tap_key n; tap_key i; tap_key SPACE
check "首次卡片里选全拼后 ni 出「你」" "你" "$(get_pad_text)"
# 切回五笔，并确认第二次弹键盘不再出卡片（出了的话 aa 会被卡片挡住）
reset_pad
read -r lx ly <<< "$(key_center LANG)"
adb_q shell input swipe "$lx" "$ly" "$lx" "$ly" 900; sleep 0.8
menu_cell 0 3          # 五笔
sleep 2
adb_q shell am force-stop "$PKG"
restart_pad
wait_chinese >/dev/null
reset_pad
tap_key a; tap_key a; tap_key SPACE
check "第二次弹键盘不再出卡片" "式" "$(get_pad_text)"
mark_onboarded

echo; echo "${Y}[14] 跑完不改变活跃方案${N}"
schema_after="$(get_active_schema)"
if [[ -z "$SCHEMA_BEFORE" ]]; then
    echo "  ${D}SKIP  首次运行，无基线可比${N}"
else
    check "活跃方案未被用例改动" "$SCHEMA_BEFORE" "$schema_after"
fi

restore_suite_kb
echo; echo "${C}================================${N}"
if (( FAILED == 0 )); then echo "${G}全部通过${N}"; exit 0; fi
echo "${R}${FAILED} 项失败${N}"
exit 1
