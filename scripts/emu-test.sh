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
        rowPx     = int(60 * d + 0.5)   # KeyboardDimens.rowHeightDp 默认值
        padTopPx  = int(0 * d + 0.5)    # KeyboardDimens.PAD_TOP_DP：第一排键上方的留白
        # 条区 = 编码行（BarView.PREEDIT_DP）+ 候选行（BarView.MAIN_ROW_DP）；工具栏占满两者
        codeBarPx = int(19 * d + 0.5)
        mainRowPx = int(38 * d + 0.5)
        # 底部 insets（手势条/导航栏）：InputRootView 的 bottomSpacer 高度
        insetPx   = int(48 * d + 0.5)
        kbBottom  = h - insetPx
        kbTop     = kbBottom - 4 * rowPx - padTopPx
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
    # 1234 · 。 ， 、 ？ ！ 半角句点 · ⌫
    "1.5 1 1 1 1 1 1 1.5"
    # 符号 · 返回 · 表情 · 空格 · 换行（与字母层底行同宽对齐；左下「符号」三层同位）
    "1.35 1.15 1 5.1 1.4"
)
# 九宫格数字键盘（number.toml）：左列 · 三列数字 · 右列，四行同权重；底行 符号 · 返回 · 0 · 句点
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
    # 限时：界面迟迟不空闲时 uiautomator dump 会一直挂着（实测一次挂了半小时，整轮测试跟着停）。
    # 超时就当这次没取到，交给调用方的重试
    timeout 90 "$ADB" -s "$SERIAL" shell uiautomator dump /sdcard/wt.xml >/dev/null 2>&1 \
        || "$ADB" -s "$SERIAL" shell pkill -f uiautomator >/dev/null 2>&1
    adb_ shell cat /sdcard/wt.xml
}

# 输入法窗口里的节点（键、候选、面板格子都报了无障碍节点）：dump 默认只导前台应用窗口，
# 输入法窗口要 --windows。按文字 / 描述找，比按几何算坐标稳——布局一改坐标就点偏
ime_xml() {
    timeout 90 "$ADB" -s "$SERIAL" shell uiautomator dump --windows /sdcard/a11y.xml >/dev/null 2>&1
    adb_ shell cat /sdcard/a11y.xml
}
ime_bounds() {   # 文字或描述为 $1 的节点 → "左 上 右 下"
    local node
    node="$(ime_xml | grep -oE '(text|content-desc)="'"$1"'"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | head -1)"
    [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]] || return 1
    echo "${BASH_REMATCH[1]} ${BASH_REMATCH[2]} ${BASH_REMATCH[3]} ${BASH_REMATCH[4]}"
}
tap_ime() {
    local l t r b
    read -r l t r b <<< "$(ime_bounds "$1")" || true
    [[ -n "$b" ]] || { echo "  ${D}找不到输入法里的「$1」${N}"; return 1; }
    adb_q shell input tap $(( (l + r) / 2 )) $(( (t + b) / 2 ))
    sleep 0.8
}
# 读输入框内容；输入框为空时 dump 会把 hint 填进 text，需剔除
# 先读测试台写的日志（TestPadActivity：内容一变就记一行 `text=…`，反斜杠与换行已转义），
# 几十毫秒；日志里没有（刚 logcat -c 过）才退回抓界面——一次 dump 0.2–3 秒，全量一百多次
get_pad_text() {
    local line
    line="$(adb_ shell logcat -d -v raw -s WindTestPad:I | grep '^text=' | tail -1)"
    if [[ -n "$line" ]]; then
        printf '%b' "${line#text=}"
        return 0
    fi
    get_pad_text_dump
}

# 等输入框内容匹配 pattern（最多约 3s），返回最后读到的文本。一次按键宿主可能分两次改
# 输入框（上屏 + 自动空格），机器忙时只 sleep 固定时长会读到中间态
wait_pad_match() {   # pattern
    local i t
    for ((i = 0; i < 10; i++)); do
        t="$(get_pad_text)"
        printf '%s' "$t" | grep -qP "$1" && break
        sleep 0.3
    done
    printf '%s' "$t"
}

get_pad_text_dump() {
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

# 点输入框。试一试的输入框在底部、随键盘弹出 / 收起上下移动（聊天框的样子），
# 之前记下的坐标随时会过期——每次都现找再点
tap_edit() {
    get_edit_bounds
    adb_q shell input tap "$EDIT_CX" "$EDIT_CY"
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
    # 不必干等：tap_edit 找输入框本身就轮询重试（此前固定 sleep 2，全量五十多次就是两分钟）
    sleep 0.3
    tap_edit
    sleep 0.8
    clear_pad
}

# 清空输入框：光标挪到末尾 + 连删（比重启 Activity 快，且不会掉 IME）。换场景不会清空，
# 换完要接着用的也调它
clear_pad() {
    adb_q shell input keyevent KEYCODE_MOVE_END
    # 次数要够长：命令短语用例会上屏 36 字符的 UUID，删不干净会污染下一个用例
    #（表现为下一条断言"实得"里带着上一条的残字，极易误判成新 bug）。
    # 一条 input 命令带 40 个键码（0.1 秒）；逐个 input 每下都要起一次进程（40 下约 0.8 秒）
    adb_q shell "input keyevent $(printf 'KEYCODE_DEL %.0s' $(seq 1 40))"
    sleep 0.3
    [[ -z "$(get_pad_text)" ]] || die "clear_pad 未清空输入框，后续断言不可信"
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

# 用例标题 + 计时：每进一个新用例，记下上一个用了多少秒；收尾时按耗时排出最慢的几个
# （全量一轮二十多分钟，先用数据找出慢在哪，再谈提速）
SECTION_NAME=""; SECTION_T0=$SECONDS; SECTION_TIMES=()
section_end() {
    [[ -n "$SECTION_NAME" ]] || return 0
    local dt=$(( SECONDS - SECTION_T0 ))
    echo "  ${D}（${dt}s）${N}"
    SECTION_TIMES+=("$dt	$SECTION_NAME")
}
section() {
    section_end
    SECTION_NAME="$1"; SECTION_T0=$SECONDS
    echo; echo "${Y}$1${N}"
}

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
    # 装不上必须当场停：静默吞掉的话，后面的用例跑在设备上旧的包上，失败得莫名其妙
    # （实测：模拟器存储满，debug 包装不上，release 包留着，run-as 全部报 not debuggable）
    install_out="$("$ADB" -s "$SERIAL" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" 2>&1)"
    if ! grep -q Success <<< "$install_out"; then
        echo "${R}安装 debug 包失败：${N}" >&2
        grep -iE 'Failure|Exception|space' <<< "$install_out" | head -3 >&2
        exit 1
    fi
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
    # one_hand 同理剥掉：[43] 中途断掉留下单手模式的话，后面按全宽几何点键的用例全会点偏
    body="$(printf '%s\n' "$1" | grep -vE '^[[:space:]]*(candidate_comment|one_hand)[[:space:]]*=')"
    # 段头允许带空白与行尾注释（`[ui.android]  # 键盘`）；认不出就会再补一段，TOML 里是重复段
    if printf '%s\n' "$body" | grep -qE '^[[:space:]]*\[ui\.android\][[:space:]]*(#.*)?$'; then
        printf '%s\n' "$body" | awk -v line="$COMMENT_OFF" \
            '{ print } /^[[:space:]]*\[ui\.android\][[:space:]]*(#.*)?$/ { print line }'
    else
        printf '%s\n\n[ui.android]\n%s\n' "$body" "$COMMENT_OFF"
    fi
}
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
# [44] 会临时关掉「使用实体键盘时显示虚拟键盘」，中途断掉也要恢复，否则后面的用例看不到键盘
SHOW_IME_WITH_HARD_KB="$(adb_ shell settings get secure show_ime_with_hard_keyboard)"
restore_suite_kb() {   # 原来没有这个文件就删掉，不写回一个空文件
    [[ "$SHOW_IME_WITH_HARD_KB" =~ ^[01]$ ]] &&
        adb_q shell settings put secure show_ime_with_hard_keyboard "$SHOW_IME_WITH_HARD_KB"
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
section "[0] 前置：确保中文方案"
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

section "[1] 五笔组合上屏：aa + 空格"
reset_pad
tap_key a; tap_key a; tap_key SPACE
check_match "上屏汉字" "$CN" "$(get_pad_text)"

section "[2] 连续两轮组合（候选每轮都要出）"
reset_pad
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
check_match "两轮各上屏一字" '^[\x{4e00}-\x{9fa5}]{2}$' "$(get_pad_text)"

section "[3] 空缓冲功能键"
reset_pad
tap_key SPACE
check "空缓冲空格出空格" " " "$(get_pad_text)"
tap_key BS
check "退格删除" "" "$(get_pad_text)"

section "[4] 中文标点"
reset_pad
tap_key COMMA
check "中文模式逗号是全角" "，" "$(get_pad_text)"

section "[5] 候选点选"
reset_pad
tap_key a; tap_key a
tap_candidate 1
check_match "点选第二个候选" "$CN" "$(get_pad_text)"

section "[6] 组合中退格删码"
reset_pad
tap_key a; tap_key a; tap_key BS; tap_key SPACE
check_match "删一码后上屏" "$CN" "$(get_pad_text)"

section "[7] 系统短语（命令直通车）"
reset_pad
# uuid 是出厂命令短语（display = {uuid()} 求值结果）。探针不用 date：
# date 的五笔码表候选太多，短语被挤到第二页。
tap_key u; tap_key u; tap_key i; tap_key d
tap_candidate 0
# 首选未必是短语，只断言「打得出东西」+ 不崩；短语是否在首位属排序策略
check_match "短语码可上屏" '.+' "$(get_pad_text)"

section "[8] 条区总高恒定（编码栏出现不顶高键盘）"
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

section "[9] 快捷面板往返"
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

section "[10] 首键延迟（词库预热）"
# 惰性构建的反查/合并索引若没被后台预热，会同步阻塞用户首键（实测 2.8s）。
# 阈值取 100ms 而非一帧：16ms 级抖动是 GC/调度噪声，卡顿量级才是要守的。
slow="$(adb_ shell logcat -d -s RustSession:W \
    | grep -oP '阻塞主线程 \K\d+(?=µs)' | awk '$1 > 100000' | paste -sd, -)"
if [[ -n "$slow" ]]; then
    echo "  ${R}FAIL${N}  首键阻塞主线程 ${slow}µs"; FAILED=$((FAILED + 1))
else
    echo "  ${G}PASS${N}  无 100ms 级主线程阻塞"
fi

section "[11] 中英切换键"
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
# 物理键盘与软键共用宿主侧处理：英文空格上屏后同样自动补空格（首字母是否大写不论）
adb_q shell input keyevent KEYCODE_H KEYCODE_I KEYCODE_SPACE
check_match "物理键盘：英文空格上屏也自动补空格" '^hi\. [Hh]i $' "$(wait_pad_match '^hi\. [Hh]i $')"

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

section "[12] 快速点击的按下反馈"
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

section "[13] 候选栏滚动后可选中第 10 个以后的候选"
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

section "[15] 收起键盘后组合态不残留"
# 打到一半按返回收键盘，再点回输入框——之前那串码必须已经作废：接着按空格应上屏
# 一个空格，而不是把旧码拼出来的字。onFinishInputView 就是为这个加的。
reset_pad
tap_key a; tap_key a
adb_q shell input keyevent KEYCODE_BACK      # 收键盘（不失焦，不走 onFinishInput）
sleep 1
tap_edit   # 点回输入框，键盘重新弹出
sleep 1.5
tap_key SPACE
check "收键盘后空格不上屏旧码的字" " " "$(get_pad_text)"

section "[16] 点编辑框移动光标后组合态不残留"
# 键盘不收、直接点编辑框（用户挪光标的最常见方式）：同样要作废组合。
reset_pad
tap_key a; tap_key a
tap_edit   # onViewClicked / onUpdateEditorToolType
sleep 1
tap_key SPACE
check "点编辑框后空格不上屏旧码的字" " " "$(get_pad_text)"

section "[17] 编辑面板：选择 + 方向键 + 剪切"
# 选择模式此前靠带 Shift 的方向键事件，多数编辑器不理会 → 「选择无效」。现在用 setSelection。
# 面板网格 4 列 × 3 行 + 底行撤销 / 重做，铺满键盘四行区域（一格一键行高）；顶栏（返回）与条区等高。
reset_pad
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
before="$(get_pad_text)"
# ⚠ 上屏后条区被**联想候选**（标点）占着，工具栏不可见；这时点条区点到的是联想项
# （实测把「，」上了屏）。先点一下输入框：光标动 → 组合/联想作废 → 工具栏回来。
tap_edit; sleep 0.8
# 工具栏只剩入口和收起，文本编辑在快捷面板里：入口 → 按文字点「文本编辑」格（格子顺序、
# 行高随开关数与键盘高度变，按坐标点曾在多出一格后点进了表情面板，整轮级联失败）。
adb_q shell input tap "$(awk -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", 26 * d }')" "$GEO_BARY"
sleep 1
tap_ime 文本编辑
cell_h=$GEO_ROWPX
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

section "[18] 有编码时滑退格清编码，不动正文"
# 此前有编码时左滑退格删的是输入框里的字（意图相反）。现在等价于清空编码。
reset_pad
tap_key a; tap_key a; tap_key SPACE          # 先上屏一个字当"正文"
tap_key a; tap_key a                         # 再打两码，处于组合态
read -r bs_x bs_y <<< "$(key_center BS)"
adb_q shell input swipe "$bs_x" "$bs_y" $(( bs_x - GEO_W / 3 )) "$bs_y" 250
sleep 0.6
tap_key SPACE                                # 编码已清 → 空格出空格
check_match "正文保留一字且编码已清" '^[\x{4e00}-\x{9fa5}] $' "$(get_pad_text)"

section "[19] 有编码时回车上屏编码原文"
# 大众布局：有编码时回车键写「确定」，按下上屏字母原文（键面文字 uiautomator 看不到，验行为）
reset_pad
tap_key a; tap_key a; tap_key ENTER
check "回车上屏编码原文" "aa" "$(get_pad_text)"

section "[20] 第 2 行缩进的半键空白归最近的键"
# 第 2 行两侧各缩进半个键（布局 spacer），落在空白里的触点归同一行最近的键（a / l），不能点空
reset_pad
row2_y="$(awk -v kb="$GEO_KBBOTTOM" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", kb - 2.5 * rp }')"
adb_q shell input tap 8 "$row2_y"; sleep 0.4
adb_q shell input tap 8 "$row2_y"; sleep 0.4
tap_key SPACE
check_match "左侧空白两次 = aa 出字" "$CN" "$(get_pad_text)"

section "[21] 长按字母出大写"
# 字母长按气泡是「小写 / 大写」，大写在右；按住不抬手右移一格再松开即选中大写。
# 用 motionevent 而不是 swipe：swipe 一按下就开始移动，气泡还没弹出手指已经划走了。
reset_pad
read -r ex ey <<< "$(key_center e)"
shift_px="$(awk -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", 25 * d }')"
adb_q shell input motionevent DOWN "$ex" "$ey"; sleep 0.8
adb_q shell input motionevent MOVE $(( ex + shift_px )) "$ey"; sleep 0.2
adb_q shell input motionevent UP $(( ex + shift_px )) "$ey"; sleep 0.6
check "长按 e 右移选中 E" "E" "$(get_pad_text)"

section "[22] 有编码时切中英先上屏字母原文"
reset_pad
tap_key a; tap_key a; tap_key LANG
sleep 3
check "切英文前编码原文上屏" "aa" "$(get_pad_text)"
# 收尾切回中文（方案选择持久化，留在英文会污染后续运行），并确认真的回来了
tap_key LANG; sleep 3
reset_pad
tap_key a; tap_key a; tap_key SPACE
check_match "切回中文出汉字" "$CN" "$(get_pad_text)"

section "[23] 123 层：中文模式下的半角句点与中文句号"
# 第 3 行「。」走引擎（中文模式出全角），最后一格半角句点原样上屏（打小数用）；← 回字母层
reset_pad
tap_key SYM
tap_cell 0 "${SYM_W[0]}" 0; tap_cell 2 "${SYM_W[2]}" 6; tap_cell 0 "${SYM_W[0]}" 1; tap_cell 2 "${SYM_W[2]}" 1
check "1 . 2 。" "1.2。" "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1
tap_key a; tap_key a; tap_key SPACE
check_match "← 回到字母层继续打中文" '^1\.2。[\x{4e00}-\x{9fa5}]$' "$(get_pad_text)"

section "[24] 九宫格数字键盘"
# 123 层「1234」（第 3 行左端）进九宫格，算式不用切层：数字、句点在格子里，* 在左列第 3 项
reset_pad
tap_key SYM; tap_cell 2 "${SYM_W[2]}" 0
tap_cell 0 "$NUM_W" 1; tap_cell 0 "$NUM_W" 2; tap_cell 3 "$NUM_W" 3; tap_cell 1 "$NUM_W" 2
side_x="$(awk -v W="$GEO_W" 'BEGIN{ printf "%d", W / 6.2 / 2 }')"
side_y="$(awk -v top="$GEO_KBTOP" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", top + 0.75 * rp * 2.5 }')"
adb_q shell input tap "$side_x" "$side_y"; sleep 0.4
tap_cell 0 "$NUM_W" 3
check "12.5*3" "12.5*3" "$(get_pad_text)"
# 「返回」（底行第 2 格）回进来的那一层（123 层），再「返回」回字母层
tap_cell 3 "$NUM_W" 1; tap_cell 3 "${SYM_W[3]}" 1
# 数字框直接调起九宫格：测试台「数字」场景，按九宫格坐标打 5 0
restart_pad
tap_text "数字"
tap_edit; sleep 1
tap_cell 1 "$NUM_W" 2; tap_cell 3 "$NUM_W" 2
check "数字框直接是九宫格" "50" "$(get_pad_text)"
# 场景复位：点回「普通」（场景是分段，不必重启进程——重启会连输入法一起杀掉、核心冷启动）
tap_text "普通"

section "[25] 分类符号页：锁定后选符号上屏并停在本页、退格、返回"
# 符号页在 IME 窗口里，uiautomator 只 dump 应用窗口，格子和 tab 看不到，只能按坐标点。
# 先清掉最近用过的（debug 包可 run-as），进页面落在「常用」、内容只有出厂那一组：
# 第 1 行是 ， 。 ？ ！ 、 …（SymbolWindow.COMMON_DEFAULTS），每行 10 格。
adb_q shell run-as "$PKG" rm -f shared_prefs/recent_symbol.xml
restart_pad      # SharedPreferences 有进程内缓存，删文件后要重启进程才读得到
# 进程刚重启，核心异步加载；没就绪时字母直接上屏，下面「有编码」的前提就不成立
[[ -n "$(wait_chinese)" ]] || die "重启后中文引擎未就绪"
reset_pad
sym_cell() { adb_q shell input tap $(( GEO_W * (2 * $2 + 1) / 20 )) $(( GEO_KBTOP + GEO_ROWPX * (2 * $1 + 1) / 2 )); sleep 0.4; }
# 有编码时点符号：先上屏首选再接符号（与打标点的效果一致）
tap_key a; tap_key a
tap_key SYM; tap_cell 3 "${SYM_W[3]}" 0; sleep 0.6
# 默认点完符号回到进来前的层（不锁）；先点底行的锁定键锁上，才会停在本页（锁在 ui.android.symbol_lock）
sym_lock_x=$(( GEO_W * 80 / 100 ))
sym_lock_y=$(( GEO_KBBOTTOM - GEO_ROWPX / 2 ))
adb_q shell input tap "$sym_lock_x" "$sym_lock_y"; sleep 0.4
sym_cell 0 0; sym_cell 0 1
check_match "首选 + ，。（锁定后点完停在本页）" '^[\x{4e00}-\x{9fa5}]，。$' "$(get_pad_text)"
sym_bs_x=$(( GEO_W * 925 / 1000 )); sym_back_x=$(( GEO_W * 75 / 1000 ))
sym_bottom_y=$(( GEO_KBBOTTOM - GEO_ROWPX / 2 ))
adb_q shell input tap "$sym_bs_x" "$sym_bottom_y"; sleep 0.4
check_match "符号页退格" '^[\x{4e00}-\x{9fa5}]，$' "$(get_pad_text)"
# 解锁，恢复默认（锁定态会落盘，不解开会影响后面的用例）
adb_q shell input tap "$sym_lock_x" "$sym_lock_y"; sleep 0.4
# ← 回到 123 层（不是字母层）：点第 1 行第 1 格应出 1
adb_q shell input tap "$sym_back_x" "$sym_bottom_y"; sleep 0.6
tap_cell 0 "${SYM_W[0]}" 0
check_match "← 回到 123 层" '^[\x{4e00}-\x{9fa5}]，1$' "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1

section "[26] 符号页：最近用过的放条区（重启后保留），常用格子不重复；点条区的符号也自动退回"
restart_pad
reset_pad
tap_key SYM; tap_cell 3 "${SYM_W[3]}" 0; sleep 0.8
# 最近用过的摆在条区候选行（[25] 里最后点的是「。」）
bar_nodes="$(ime_xml | grep -oE 'class="android.widget.Button" package="'"$PKG"'" content-desc="[^"]*"' \
    | sed -E 's/.*content-desc="([^"]*)"/\1/')"
check "条区里有最近点的「。」" "yes" "$(grep -qE '^。(，|$)' <<< "$bar_nodes" && echo yes || echo no)"
# 点条区的符号：与点格子一样，没锁定就自动回到 123 层
adb_q shell input tap 60 "$GEO_BARY"; sleep 0.6
check "点条区的「。」上屏" "。" "$(get_pad_text)"
# 已回到 123 层：再进符号页，「常用」第一格始终是固定的「，」，不混入最近点的「。」
tap_cell 3 "${SYM_W[3]}" 0; sleep 0.6
sym_cell 0 0
check "常用第一格仍是固定的「，」" "。，" "$(get_pad_text)"
# 没锁定：点完符号已自动回到 123 层，直接点「返回」回字母层（再点面板的返回会重新进符号页）
tap_cell 3 "${SYM_W[3]}" 1

section "[27] 英文补全：点候选上屏补全词并自动加空格"
reset_pad
tap_key LANG; sleep 4
tap_key b; tap_key e; tap_key a; tap_key u
# 候选条是自绘的，按宽度粗估：首项是原文 beau，屏幕中间落在其后的补全词上（Beaumont / beautiful）
adb_q shell input tap $(( GEO_W / 2 )) "$GEO_BARY"; sleep 0.6
check_match "点补全词上屏" '^[Bb]eau[a-z]+ $' "$(get_pad_text)"

section "[28] 英文句首自动大写"
# 句子框（CAP_SENTENCES）：句首的 h 自动大写；句点后下一句又自动大写。[27] 收尾仍在英文方案
tap_text "句子"
tap_edit; sleep 1.5
[[ -n "$(wait_english)" ]] || die "英文方案未就绪"
tap_key h; tap_key i; tap_key SPACE; tap_key PERIOD
# 自动大写的 H 进的是英文组合（补全不断），空格上屏后才读得到
tap_key h; tap_key SPACE
check "句首大写 + 句点后下一句又大写" "Hi. H " "$(get_pad_text)"
read -r sp_x sp_y <<< "$(key_center SPACE)"

section "[29] 密码框不自动大写、不补全"
tap_text "密码"
tap_edit; sleep 1.5
clear_pad
tap_key h; tap_key i
adb_q shell "input tap $sp_x $sp_y; input tap $sp_x $sp_y"; sleep 0.5
check "密码框原样直输（不大写、不补全）" "hi  " "$(get_pad_text)"
tap_text "普通"
[[ -n "$(wait_english)" ]] || die "英文方案未就绪"
# 收尾回中文（方案选择持久化，留在英文会污染下次运行）
reset_pad; tap_key LANG; sleep 4
[[ -n "$(wait_chinese)" ]] || die "收尾切回中文失败"

section "[31] 上屏后联想：学会「我们 → 一直」后点联想首位"
# 下一词学习（主仓 learn_next_word）：相邻上屏两次「我们」「一直」，第三次上屏「我们」联想首位应是「一直」
reset_pad
for w in t r w u SPACE g g f h SPACE t r w u SPACE g g f h SPACE t r w u SPACE; do tap_key "$w"; done
sleep 0.5
tap_candidate 0
check_match "联想首位整词上屏" '我们一直我们一直我们一直$' "$(get_pad_text)"

section "[32] 联想态按空格出空格"
# 两家在联想态按空格都是收起联想、出空格，不选联想词
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
tap_key SPACE
check_match "联想态空格出空格" '^[\x{4e00}-\x{9fa5}] $' "$(get_pad_text)"

section "[33] 联想态按退格直接删字、回车直接换行（移动端不让联想拦正事）"
# 出厂 [mobile.association] backspace/enter_cancels_only = false：联想只是建议，不吞这两个键
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
tap_key BS
check "一下退格就删掉刚上屏的字" "" "$(get_pad_text)"
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
tap_key ENTER
# 回车后再打一个字：中间必须有换行（$(...) 会吃掉末尾换行，所以不能只看回车之后那一刻）
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
check "一下回车就换行" $'式\n式' "$(get_pad_text)"

section "[33b] 英文下数字进编码栏（mp3、win10），中文下仍是首选上屏 + 数字"
reset_pad; tap_key LANG; sleep 4
tap_key h; tap_key e; tap_key l
tap_key SYM; tap_cell 0 "${SYM_W[0]}" 0; tap_cell 3 "${SYM_W[3]}" 1
# 数字进了编码：此刻输入框里还没有任何字（编码在条区），空格才上屏整串
check "数字没有顶屏" "" "$(get_pad_text)"
tap_key SPACE; sleep 0.4
check "空格上屏 hel1" "hel1 " "$(get_pad_text)"
tap_key LANG; sleep 4
reset_pad
tap_key a; tap_key a
tap_key SYM; tap_cell 0 "${SYM_W[0]}" 0
check_match "中文：首选上屏再出数字" '^[\x{4e00}-\x{9fa5}]1$' "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1

section "[34] 收起键盘后联想不残留"
# 联想还在的话，数字键会选联想词；收起再弹出后数字应原样上屏
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
adb_q shell input keyevent KEYCODE_BACK; sleep 1
tap_edit; sleep 1.2
tap_key SYM; tap_cell 0 "${SYM_W[0]}" 0; tap_cell 3 "${SYM_W[3]}" 1
check_match "收起后数字原样上屏" '^[\x{4e00}-\x{9fa5}]1$' "$(get_pad_text)"

section "[49] 智能联想：前缀词只显示补出的一截、出下一词；可开「空格选首个联想」"
# 上屏「我」（五笔 q）后，前缀词「我们」在候选条上该是「们」，不该整词「我们」（与下一词、标点同栏）。
# 候选是自绘的，读输入法窗口的无障碍节点（文字即 content-desc，可能带「，编码 …」）
reset_pad
tap_key q; tap_key SPACE
sleep 0.5
assoc_nodes="$(ime_xml | grep -oE 'class="android.widget.Button" package="'"$PKG"'" content-desc="[^"]*"' \
    | sed -E 's/.*content-desc="([^"]*)"/\1/')"
check "候选条上没有以「我」开头的整词" "no" "$(grep -q '^我' <<< "$assoc_nodes" && echo yes || echo no)"
check "前缀词「我们」只显示「们」" "yes" "$(grep -qE '^们(，|$)' <<< "$assoc_nodes" && echo yes || echo no)"
# 下一词表（data/assoc/assoc_bigram.txt）：「我」后常接「的」
check "联想里有下一词「的」" "yes" "$(grep -qE '^的(，|$)' <<< "$assoc_nodes" && echo yes || echo no)"
# 打开 mobile.association.space_commits：联想态空格上屏首个联想，而不是出空格
cfg_orig="$(read_core_cfg)"
restore_cfg() { write_core_cfg "$cfg_orig"; }
trap 'restore_cfg; restore_suite_kb; exit 1' TERM INT
write_core_cfg "$(strip_section "$cfg_orig" mobile.association)
[mobile.association]
space_commits = true"
restart_pad
wait_chinese >/dev/null
reset_pad
tap_key a; tap_key a; tap_key SPACE; sleep 0.4
tap_key SPACE
check_match "开启后联想态空格选首个联想" '^式[^ ]+$' "$(get_pad_text)"
restore_cfg
trap 'restore_suite_kb; exit 1' TERM INT
restart_pad
wait_chinese >/dev/null

section "[50] 联想看光标前文：逐字上屏「今」「天」，下一词按「今天」接"
# 只看「天」的话下一词表里没有「早上」；「今天」后面有。前文由核心随上屏跟踪、宿主在
# onUpdateSelection 里报真值（setPrecedingText）
reset_pad
for w in w y n b; do tap_key "$w"; done
sleep 0.4
# 四码唯一可能已自动上屏；没上屏再按空格（联想态空格是出空格，不能无条件按）
[[ "$(get_pad_text)" == "今" ]] || tap_key SPACE
for w in g d i SPACE; do tap_key "$w"; done
sleep 0.5
check "逐字上屏「今」「天」" "今天" "$(get_pad_text)"
assoc_nodes="$(ime_xml | grep -oE 'class="android.widget.Button" package="'"$PKG"'" content-desc="[^"]*"' \
    | sed -E 's/.*content-desc="([^"]*)"/\1/')"
check "下一词按「今天」接（有「早上」）" "yes" "$(grep -qE '^早上(，|$)' <<< "$assoc_nodes" && echo yes || echo no)"

section "[35] 简入繁出：开启后「我们」上屏为「我們」"
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

section "[36] 候选长按：提示卡 →「更多操作」→ 置顶第三个候选，再恢复默认"
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

section "[37] 逗号、句号各占一键；五笔 emoj 出表情"
# 默认布局（豆包式底栏）：中文模式下逗号键出「，」、句号键出「。」，不用上滑
reset_pad
tap_key COMMA; tap_key PERIOD
check "逗号键与句号键" "，。" "$(get_pad_text)"
# 五笔 Emoji 库默认加载：emoj 首选是 😂（库里按权重排的第一个）。曾因 APK 没带这个库而什么都不出
reset_pad
tap_key e; tap_key m; tap_key o; tap_key j; tap_key SPACE
# uiautomator 把 BMP 以外的字符写成 XML 数字实体：😂（U+1F602）读回来是 &#128514;
check "emoj 上屏表情" "😂" "$(get_pad_text)"

section "[38] 设置页层级：首页 → 方案 → 码表 → 返回；词库 → 我的词条、词库统计；联想学习数据"
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
adb_q shell input keyevent BACK; sleep 1.5
# 词库统计：整表扫一遍在 IO 线程，算完才铺卡片（has_text 轮询约 4 秒，五笔前面已编译过）
tap_text "词库统计"
check "词库统计出各词库分行" "yes" "$(has_text 各词库)"
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
# 联想输入页底部：学习数据可清空（条数由核心算，读完才出这一行）
tap_text "联想输入"
check "联想输入页有「清空联想学习」" "yes" "$(has_text 清空联想学习)"
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
adb_q shell input keyevent BACK; sleep 1
restart_pad

section "[39] 长按中英键竖排菜单：上滑选全拼打 ni，再选回五笔；不滑就松手什么都不做"
# 菜单从中英键上方往上排，左沿对齐键位，行高 44dp，下沿离键顶 8dp。项按核心给的方案列表
# 再加末项「切换输入法」：清风英文 · 全拼 · 双拼 · 五笔 · 五笔拼音 · 切换输入法（共 6 项）
lang_menu_pick() {   # 第几项（从 0 起）：按住中英键等菜单弹出，上滑到该项再松手
    local lx ly ty
    read -r lx ly <<< "$(key_center LANG)"
    ty="$(awk -v y="$ly" -v r="$GEO_ROWPX" -v d="$GEO_DENSITY" -v i="$1" \
        'BEGIN{ printf "%d", y - r / 2 - 8 * d - (6 - i - 0.5) * 44 * d }')"
    adb_q shell input motionevent DOWN "$lx" "$ly"; sleep 0.8
    adb_q shell input motionevent MOVE "$lx" $(( (ly + ty) / 2 )); sleep 0.1
    adb_q shell input motionevent MOVE "$lx" "$ty"; sleep 0.2
    adb_q shell input motionevent UP "$lx" "$ty"; sleep 0.6
}
reset_pad
lang_menu_pick 1       # 全拼
sleep 6                # 模拟器上首次编译拼音词库近 5 秒，期间按键直接上屏字母
tap_key n; tap_key i; tap_key SPACE
check "切到全拼后 ni 出「你」" "你" "$(get_pad_text)"
reset_pad
lang_menu_pick 3       # 五笔
sleep 2
tap_key a; tap_key a; tap_key SPACE
check "切回五笔 aa 出「式」" "式" "$(get_pad_text)"
# 按住不滑（手指原地轻微抖动）再松手：不弹卡片、不换方案，接着打字照常
reset_pad
read -r lx ly <<< "$(key_center LANG)"
adb_q shell input motionevent DOWN "$lx" "$ly"; sleep 0.8
adb_q shell input motionevent MOVE $(( lx + 3 )) $(( ly + 2 )); sleep 0.2
adb_q shell input motionevent UP $(( lx + 3 )) $(( ly + 2 )); sleep 0.8
tap_key a; tap_key a; tap_key SPACE
check "长按中英键不滑松手：不弹卡片、仍是五笔" "式" "$(get_pad_text)"

section "[47] 拼音首单元读法：xian 展开后左栏点 xi，候选换成 xi'an 的（西安）"
# 展开页左栏列最前面那个单元的几种读法（xian / xi / x）；点一项候选按它重算，编码不动。
# 默认读法 xian 的候选是「先、现、县…」，点 xi 读法后 xi'an 的「西安」才进候选。
# 判据：点「西安」能上屏——默认读法下它不在首批里就点不到（核心侧另有集成测试守逻辑）。
reset_pad
lang_menu_pick 1       # 全拼
sleep 6
tap_key x; tap_key i; tap_key a; tap_key n
sleep 0.8
tap_ime 展开候选
sleep 1
tap_ime xi
sleep 1
tap_ime 西安
sleep 0.6
check "点 xi 读法后选「西安」上屏" "西安" "$(get_pad_text)"
reset_pad
lang_menu_pick 3       # 切回五笔，别影响后面的用例
sleep 2

section "[40] 开着「编码也显示在输入框里」：上屏后输入框不留占位符"
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

section "[41] 首次使用：第一次弹键盘先出方案卡片，选完不再出"
# 点卡片里的「全拼」再打 ni：卡片在就出「你」；卡片没出来的话那一下落在字母键上，对不上
# 当成全新用户：连 ime_state（记着上次中文方案）一起删——记过它的会被当成升级上来的老用户、不弹卡片
adb_q shell run-as "$PKG" rm -f "$ONBOARD_XML" shared_prefs/ime_state.xml
restart_pad            # am start -S：进程重起，SharedPreferences 的进程内缓存一并丢掉
sleep 2
# 点输入框把键盘叫出来。卡片可能已随测试台的 stateVisible 弹出——首次卡片不因点应用区域
# 而收（真机上用户第一次几乎一定会再点一下文本框），所以这一下点不掉它
tap_edit
sleep 3
menu_cell 0 1          # 全拼
sleep 3
tap_key n; tap_key i; tap_key SPACE
check "首次卡片里选全拼后 ni 出「你」" "你" "$(get_pad_text)"
# 切回五笔，并确认第二次弹键盘不再出卡片（出了的话 aa 会被卡片挡住）
reset_pad
lang_menu_pick 3       # 五笔
sleep 2
adb_q shell am force-stop "$PKG"
restart_pad
wait_chinese >/dev/null
reset_pad
tap_key a; tap_key a; tap_key SPACE
check "第二次弹键盘不再出卡片" "式" "$(get_pad_text)"
mark_onboarded

section "[42] 无障碍：键与候选是读屏看得见、可按的虚拟节点"
# 键盘与候选栏都是 Canvas 自绘，靠 ExploreByTouchHelper 报虚拟节点。uiautomator 默认只导出
# 前台应用窗口，输入法窗口要 --windows 才在里面。节点没报出来时读屏用户整个键盘是空的
ime_nodes() {   # 输入法窗口里所有虚拟按钮的 content-desc，一行一个
    adb_q shell uiautomator dump --windows /sdcard/a11y.xml
    adb_ shell cat /sdcard/a11y.xml \
        | grep -oE 'class="android.widget.Button" package="'"$PKG"'" content-desc="[^"]*"' \
        | sed -E 's/.*content-desc="([^"]*)"/\1/'
}
reset_pad
tap_key a
nodes="$(ime_nodes)"
for name in 上档 删除 空格 中英切换 符号 123; do
    check "功能键「$name」有无障碍节点" "yes" "$(grep -qxF "$name" <<< "$nodes" && echo yes || echo no)"
done
check "字母键有无障碍节点（Q）" "yes" "$(grep -qxF Q <<< "$nodes" && echo yes || echo no)"
check "候选栏的候选有无障碍节点（式）" "yes" "$(grep -qE '^式(，编码 .*)?$' <<< "$nodes" && echo yes || echo no)"
reset_pad

section "[43] 单手模式：快捷面板开、收窄后照常打字、换边、退出"
# 单手时键盘几何变了，tap_key 的全宽坐标不能用——全程按无障碍节点（文字 / 描述）点
# 开出来靠哪边由 one_hand_last 记着（跨运行持久化），所以不假定起始边：先看开在哪边，再断言换边后到了另一边
reset_pad
q_full="$(ime_bounds Q | cut -d' ' -f1)"
qw_full="$(ime_bounds Q | awk '{print $3 - $1}')"
tap_ime 快捷功能; tap_ime 单手; tap_ime 返回
q_open="$(ime_bounds Q | cut -d' ' -f1)"
qw_open="$(ime_bounds Q | awk '{print $3 - $1}')"
check "开单手：键盘收窄" "yes" "$( (( ${qw_open:-999} < ${qw_full:-0} - 10 )) && echo yes || echo no)"
if (( ${q_open:-0} > ${q_full:-0} + 100 )); then side_open=right; else side_open=left; fi
echo "  ${D}开在${side_open}边${N}"
tap_ime A; tap_ime A; tap_ime 空格
check "单手下照常打字" "式" "$(get_pad_text)"
tap_ime 换边
q_flip="$(ime_bounds Q | cut -d' ' -f1)"
if (( ${q_flip:-0} > ${q_full:-0} + 100 )); then side_flip=right; else side_flip=left; fi
check "换边：键盘到了另一边" "yes" "$([[ "$side_flip" != "$side_open" ]] && echo yes || echo no)"
tap_ime '退出&#10;单手'
q_back="$(ime_bounds Q | cut -d' ' -f1)"
check "退出单手：恢复满宽" "${q_full:-?}" "${q_back:-}"
reset_pad

section "[44] 物理键盘：按键进引擎、快捷键放行、只露条区"
# adb 的 keyevent 走的就是物理键盘那条路（InputMethodService.onKeyDown），不经软键盘
reset_pad
adb_q shell input keyevent KEYCODE_A KEYCODE_A KEYCODE_SPACE
sleep 0.8
check "物理键 aa + 空格" "式" "$(get_pad_text)"
adb_q shell input keycombination KEYCODE_SHIFT_LEFT KEYCODE_B
sleep 0.8
check "Shift+字母：中文方案下直接上屏大写" "式B" "$(get_pad_text)"
# 系统设置「使用实体键盘时显示虚拟键盘」关掉：只在打字中露条区，键盘面板不出
adb_q shell settings put secure show_ime_with_hard_keyboard 0
reset_pad
check "只用物理键盘：空闲时不弹软键盘" "no" "$(ime_bounds Q >/dev/null && echo yes || echo no)"
adb_q shell input keyevent KEYCODE_A KEYCODE_A
sleep 0.8
bar_xml="$(ime_xml)"
# 候选的读屏描述是「式」或「式，编码 …」（开了编码提示时）
check "只用物理键盘：打字中露出候选" "yes" "$(grep -qE 'content-desc="式(，|")' <<< "$bar_xml" && echo yes || echo no)"
check "只用物理键盘：打字中键盘面板仍收着" "no" "$([[ "$bar_xml" == *'content-desc="Q"'* ]] && echo yes || echo no)"
adb_q shell input keyevent KEYCODE_SPACE
sleep 0.8
check "只用物理键盘：空格上屏" "式" "$(get_pad_text)"
# 空闲时屏幕上没有输入法，返回键要一下就交给应用（不能先被隐形的输入法窗口吃掉）
adb_q shell input keyevent KEYCODE_BACK
sleep 1
check "只用物理键盘：空闲时返回键一下退出页面" "no" \
    "$(adb_ shell dumpsys window | grep -m1 mCurrentFocus | grep -q TestPadActivity && echo yes || echo no)"
adb_q shell settings put secure show_ime_with_hard_keyboard "${SHOW_IME_WITH_HARD_KB:-1}"
reset_pad

section "[45] 软键盘数字不选候选、工具栏常驻按钮"
# 有编码时上滑 q 出 1：是要数字 1，不是选第 1 个候选——先上屏首选再接 1
reset_pad
tap_key a; tap_key a
read -r qx qy <<< "$(key_center q)"
adb_q shell input swipe "$qx" "$qy" "$qx" $(( qy - GEO_ROWPX * 3 / 2 )) 250
sleep 0.8
check_match "上滑数字：首选 + 数字" '^[\x{4e00}-\x{9fa5}]1$' "$(get_pad_text)"
# 工具栏出厂常驻剪贴板、文本编辑；点文本编辑直接进编辑面板（面板标题「编辑」）
reset_pad
check "工具栏常驻「剪贴板」" "yes" "$(ime_bounds 剪贴板 >/dev/null && echo yes || echo no)"
tap_ime 文本编辑
check "工具栏「文本编辑」直达编辑面板" "yes" "$(ime_xml | grep -q 'text="编辑"' && echo yes || echo no)"
tap_ime 返回
reset_pad

section "[46] 快捷短语：打开出厂关闭的时间戳 tmts，打出来，再恢复默认"
# 时间戳出厂关闭（主仓 system.phrases.toml 的 enabled = false），设置页「词库 → 快捷短语」能打开。
# 设置页与输入法同一进程、共用一个核心：设置里改了短语，输入法立刻生效，不必重启测试台。
# 滑动找某段文字所在行的纵坐标（列表长，只往下翻；要从顶上找就先 scroll_top）；按字面匹配（文字里有 $）
row_y_of() {
    local try node
    # 系统短语七十多条、带预览每条两三行：翻到 tmts 要十几下
    for ((try = 0; try < 25; try++)); do
        node="$(dump_xml | sed 's/<node/\n<node/g' | grep -F " text=\"$1\"" | head -1)"
        if [[ "$node" =~ bounds=\"\[[0-9]+,([0-9]+)\]\[[0-9]+,([0-9]+)\]\" ]]; then
            echo $(( (BASH_REMATCH[1] + BASH_REMATCH[2]) / 2 )); return 0
        fi
        adb_q shell input swipe $(( GEO_W / 2 )) 1800 $(( GEO_W / 2 )) 900 300; sleep 0.8
    done
    return 1
}
scroll_top() {
    local i
    for ((i = 0; i < 4; i++)); do
        adb_q shell input swipe $(( GEO_W / 2 )) 600 $(( GEO_W / 2 )) 2000 150
    done
    sleep 0.6
}
# 打开设置的快捷短语页：NEW_TASK | CLEAR_TASK 新开一个设置（总从首页起；前面的用例可能把设置
# 留在别的页上，如 [38] 停在「我的词条」——那里点不到「词库」），不杀进程，输入法与核心照旧
open_phrases_page() {
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
    sleep 2
    tap_text "词库"
    # 词库总览先显示「正在启动…」、方案读到后才铺卡片：布局还在动时点，会点到挪过来的别的行
    [[ "$(has_text 添加词条)" == yes ]] || die "词库页没出来"
    sleep 0.8
    tap_text "快捷短语"
    [[ "$(has_text 添加短语)" == yes ]] || die "没进到快捷短语页"
}
# 从设置回测试台：设置在前台时普通的 am start 拉不回测试台（两者可能同在一个任务栈里），
# 用 NEW_TASK | CLEAR_TASK 新开一个——不杀进程，输入法与核心照旧
back_to_pad() {
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
    sleep 1
}
phrases_restore_defaults() {
    open_phrases_page
    scroll_top   # 「恢复默认」在系统短语列表之前
    local y
    y="$(row_y_of '系统短语恢复默认')" || die "找不到「系统短语恢复默认」"
    adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1.5
}
# 先恢复默认：上一次运行若中途断掉，tmts 可能还开着，下面「点一下开关」就成了关掉它
phrases_restore_defaults
scroll_top
ts_y="$(row_y_of '$ts')" || die "快捷短语页找不到 tmts"
# 开关在行尾、与两行字（编码 / 内容）居中对齐：内容那行的中线往上挪一点
adb_q shell input tap $(( GEO_W - 90 )) $(( ts_y - 20 )); sleep 1
back_to_pad
reset_pad
tap_key t; tap_key m; tap_key t; tap_key s; tap_key SPACE
check_match "打开后 tmts 上屏 10 位时间戳" '^[0-9]{10}$' "$(get_pad_text)"
phrases_restore_defaults
back_to_pad
reset_pad
tap_key t; tap_key m; tap_key t; tap_key s; tap_key SPACE
check_match "恢复默认后 tmts 不再出时间戳" '^[^0-9]*$' "$(get_pad_text)"
reset_pad

section "[47] 横屏：键盘铺满整宽（挖孔在侧边）、输入框不被挤出屏外、照常打字"
set_rotation() {   # 0 竖屏 / 1 横屏
    adb_q shell settings put system accelerometer_rotation 0
    adb_q shell settings put system user_rotation "$1"
    sleep 2.5
}
set_rotation 1
reset_pad
# 输入法窗口的 frame 应横跨整个屏宽：默认不进挖孔区时左缘是挖孔宽度（模拟器 128px）
ime_frame="$(adb_ shell dumpsys window windows \
    | awk '/Window\{.* InputMethod\}:/ { on = 1 } on && /Frames:/ { print; exit }' \
    | grep -o ' frame=\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')"
read -r fl ft fr fb <<< "$(grep -oE '[0-9]+' <<< "$ime_frame" | tr '\n' ' ')"
screen_w="$(adb_ shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | awk -Fx '{ print ($1 > $2) ? $1 : $2 }')"
check "输入法窗口左缘贴屏边" "0" "${fl:-?}"
check "输入法窗口右缘贴屏边" "$screen_w" "${fr:-?}"
# 输入框整个落在键盘上方（「试一试」横屏时页签让位）
edit_b="$(dump_xml | sed 's/<node/\n<node/g' | grep 'windinput-testpad' | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' | grep -oE '[0-9]+' | sed -n 4p)"
check "输入框在键盘上方可见" "yes" "$([[ -n "$edit_b" && -n "$ft" && "$edit_b" -le "$ft" ]] && echo yes || echo "no(框底 ${edit_b:-?} 键盘顶 ${ft:-?})")"
adb_q shell input keyevent KEYCODE_A KEYCODE_A KEYCODE_SPACE
check_match "横屏打字出汉字" "$CN" "$(wait_pad_match "$CN")"
set_rotation 0
reset_pad

section "[48] 退格上滑清空输入框，编辑面板撤销找回、重做再清"
reset_pad
tap_key a; tap_key a; tap_key SPACE
tap_key a; tap_key a; tap_key SPACE
tap_edit; sleep 0.8   # 收起联想，免得条区挡着工具栏
before="$(get_pad_text)"
read -r bs_x bs_y <<< "$(key_center BS)"
adb_q shell input swipe "$bs_x" "$bs_y" "$bs_x" $(( bs_y - GEO_ROWPX * 2 )) 300
sleep 0.6
check "退格上滑清空输入框" "" "$(get_pad_text)"
adb_q shell input tap "$(awk -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", 26 * d }')" "$GEO_BARY"
sleep 1
tap_ime 文本编辑
tap_ime 撤销
check "撤销找回清空前的字" "$before" "$(get_pad_text)"
tap_ime 重做
check "重做再次清空" "" "$(get_pad_text)"
adb_q shell input tap "$GEO_PANELBACKX" "$GEO_PANELHEADERY"   # 返回键盘
sleep 1
reset_pad

section "[14] 跑完不改变活跃方案"
schema_after="$(get_active_schema)"
if [[ -z "$SCHEMA_BEFORE" ]]; then
    echo "  ${D}SKIP  首次运行，无基线可比${N}"
else
    check "活跃方案未被用例改动" "$SCHEMA_BEFORE" "$schema_after"
fi

section_end
restore_suite_kb
echo; echo "${C}最慢的用例：${N}"
printf '%s\n' "${SECTION_TIMES[@]}" | sort -rn | head -10 | awk -F'\t' '{ printf "  %4ds  %s\n", $1, $2 }'
echo "  合计 ${SECONDS}s"
echo; echo "${C}================================${N}"
if (( FAILED == 0 )); then echo "${G}全部通过${N}"; exit 0; fi
echo "${R}${FAILED} 项失败${N}"
exit 1
