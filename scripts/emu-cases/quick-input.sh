# 快捷输入的数字（被 emu-test.sh source，依赖它的 check / check_match / section / reset_pad /
# restart_pad / tap_key / tap_cell / tap_ime / key_center / get_pad_text / write_kb_cfg / with_comment_off /
# SUITE_KB_ORIG / SYM_W / NUM_W / GEO_ROWPX / adb_q / die 等）。
#
# 真机反馈：进快捷输入后上滑出数字，若此刻已有「重复上屏」候选（先上屏过字），数字不进缓冲，
# 而是把重复候选顶上屏再接数字。快捷输入的缓冲是文本（算式、日期），数字是它的一部分；
# 普通五笔组码时数字仍是「首选上屏 + 数字」（不选词）。

# swipe_sym 定义在 [21e]，--only 只跑本文件时不存在，这里用自己的一份（放在第一个 section 之前）
qi_swipe() {   # key：从键中心上滑 1.5 行，等气泡抬手出字
    local kx ky
    read -r kx ky <<< "$(key_center "$1")"
    adb_q shell input swipe "$kx" "$ky" "$kx" $(( ky - GEO_ROWPX * 3 / 2 )) 250
    sleep 0.8
}
qi_cfg() {   # 在整套基线配置的 [ui.android] 段里再加几行（同名键先剥掉，免得重复键）
    local body
    body="$(printf '%s\n' "$SUITE_KB_ORIG" | grep -vE '^[[:space:]]*(touch_quick_input|toolbar_pins)[[:space:]]*=')"
    with_comment_off "$body" | awk -v add="$1" \
        '{ print } /^[[:space:]]*\[ui\.android\][[:space:]]*(#.*)?$/ { print add }'
}

# 数字页的数字、句点、九宫左列符号是「原样上屏」键（commit），平时绕过引擎；快捷输入里它们要进缓冲。
# 整套用例钉的是「123 先进全键盘数字页」（NUMBER_FULL_CFG），九宫从 123 层「1234」进
qi_pad9() {   # 字母层 → 123 层 → 九宫
    tap_key SYM; tap_cell 2 "${SYM_W[2]}" 0; sleep 0.4
}
qi_num() {    # 九宫数字键：1–9 按行列，0 在底行
    case "$1" in
        0) tap_cell 3 "$NUM_W" 2 ;;
        *) tap_cell $(( ($1 - 1) / 3 )) "$NUM_W" $(( ($1 - 1) % 3 + 1 )) ;;
    esac
}
qi_side() {   # 九宫左列符号（无障碍节点「左列·<符号>」；ERE 元字符要转义）
    tap_ime "左列·$1" || die "九宫左列找不到「$1」"
}
qi_letters() {   # 九宫底行「返回」回 123 层（从哪里进回哪里），123 层「返回」再回字母层
    tap_cell 3 "$NUM_W" 1; tap_cell 3 "${SYM_W[3]}" 1
}

# 候选来源（核心 schema.mix_modes 里 quick_mix 的 members）：设置页「快捷候选来源」开关与拖动排序。
# （--only 时第一个 section 之前的定义才保留，故放在这里）取样词 man：拼音（出厂在英文之前）出「慢 / 满…」，英文出 man。用例前后写回整份核心配置
qs_open() {   # 设置 → 输入 → 快捷输入 → 快捷候选来源
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
    sleep 2
    tap_text "输入"; tap_text "快捷输入"; tap_text "快捷候选来源"
    sleep 1
}
qs_type_man() {   # 回测试台，; 进快捷输入打 man（不上屏）
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
    sleep 1
    reset_pad
    qi_swipe n
    tap_key m; tap_key a; tap_key n; sleep 0.6
}
qs_has_man() {   # 候选条上有没有英文 man（候选的无障碍文字可能带「，编码 …」）
    ime_xml | grep -qE 'content-desc="man(，|")' && echo yes || echo no
}
qs_members() {   # 核心用户层 quick_mix 的 members，压成一行（数组可能分行写；没写过 = 空）
    read_core_cfg | awk '/^members = \[/ { on = 1 } on { printf "%s", $0 } on && /\]/ { exit }' \
        | sed -E 's/[[:space:]]+/ /g; s/\[ /[/; s/,? ?\]/]/'
}
qs_handle() {   # 拖动手柄「拖动排序 <来源>」的中心 "x y"
    local n
    n="$(dump_xml | sed 's/<node/\n<node/g' | grep -F "content-desc=\"拖动排序 $1\"" | head -1)"
    [[ "$n" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]] || die "找不到「拖动排序 $1」"
    echo "$(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))"
}

section "[80] 快捷输入：已有重复上屏候选时，上滑数字进缓冲，算式空格上屏结果"
# 触摸 `;`（n 上滑）进快捷输入要打开设置项 ui.android.touch_quick_input
write_kb_cfg "$(qi_cfg 'touch_quick_input = true # emu-test')"
restart_pad
reset_pad
tap_key w; tap_key q; tap_key v; tap_key b; tap_key SPACE; sleep 0.4
check "前提：先上屏「你好」" "你好" "$(get_pad_text)"
qi_swipe n
qi_swipe q; qi_swipe w; qi_swipe k; qi_swipe e
check "上滑 1 2 * 3 都进缓冲，没有上屏" "你好" "$(get_pad_text)"
tap_key SPACE; sleep 0.5
check "空格上屏算式结果 12*3=36" "你好36" "$(get_pad_text)"

section "[81] 快捷输入：从工具栏进入，已有重复候选时上滑数字同样进缓冲"
write_kb_cfg "$(qi_cfg 'toolbar_pins = "keyboard,emoji,clipboard,text_edit,quick_input" # emu-test')"
restart_pad
reset_pad
# 先上屏「你好」留下重复候选；退格删掉「好」顺带收起联想，工具栏才露出来
tap_key w; tap_key q; tap_key v; tap_key b; tap_key SPACE; sleep 0.4
tap_key BS; sleep 0.4
check "前提：框里剩「你」" "你" "$(get_pad_text)"
tap_ime 快捷输入
qi_swipe q; qi_swipe w
check "上滑 1 2 没有把重复候选顶上屏" "你" "$(get_pad_text)"

section "[82] 普通五笔组码时上滑数字：首选上屏再接数字（不选词）"
reset_pad
tap_key a; tap_key a
qi_swipe q
check_match "首选上屏 + 1" '^[\x{4e00}-\x{9fa5}]1$' "$(get_pad_text)"
qi_swipe w
check_match "无编码时上滑 2 原样上屏" '^[\x{4e00}-\x{9fa5}]12$' "$(get_pad_text)"

section "[83] 快捷输入：九宫数字页打 12+3 进缓冲（不顶重复候选上屏），空格上屏 15"
write_kb_cfg "$(qi_cfg 'touch_quick_input = true # emu-test')"
restart_pad
reset_pad
tap_key w; tap_key q; tap_key v; tap_key b; tap_key SPACE; sleep 0.4
check "前提：先上屏「你好」" "你好" "$(get_pad_text)"
qi_swipe n
qi_pad9
qi_num 1; qi_num 2; qi_side '\+'; qi_num 3
check "九宫 1 2 + 3 都进缓冲，没有上屏" "你好" "$(get_pad_text)"
tap_cell 1 "$NUM_W" 4; sleep 0.5   # 九宫右列「空格」
check "空格上屏算式结果 12+3=15" "你好15" "$(get_pad_text)"
qi_letters

section "[84] 快捷输入：全键盘 123 层打 12-3.5（半角句点是原样上屏键）进缓冲，空格上屏 8.5"
write_kb_cfg "$(qi_cfg 'touch_quick_input = true # emu-test')"
restart_pad
reset_pad
tap_key w; tap_key q; tap_key v; tap_key b; tap_key SPACE; sleep 0.4
qi_swipe n
tap_key SYM; sleep 0.4
tap_cell 0 "${SYM_W[0]}" 0; tap_cell 0 "${SYM_W[0]}" 1   # 1 2
tap_cell 1 "${SYM_W[1]}" 0                               # -
tap_cell 0 "${SYM_W[0]}" 2                               # 3
tap_cell 2 "${SYM_W[2]}" 6                               # 半角句点
tap_cell 0 "${SYM_W[0]}" 4                               # 5
check "123 层 1 2 - 3 . 5 都进缓冲，没有上屏" "你好" "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 3; sleep 0.5                    # 空格
check "空格上屏算式结果 12-3.5=8.5" "你好8.5" "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1                               # 「返回」回字母层

section "[85] 无编码时九宫数字、句点、左列符号照旧原样上屏（中文模式句点不变「。」）"
reset_pad
qi_pad9
qi_num 1; tap_cell 3 "$NUM_W" 3; qi_num 2; qi_side '\+'
check "原样上屏 1.2+" "1.2+" "$(get_pad_text)"
qi_letters

section "[86] 五笔组码时九宫数字：首选上屏再接数字（不选词）"
reset_pad
tap_key a; tap_key a
qi_pad9
qi_num 1
check_match "首选上屏 + 1" '^[\x{4e00}-\x{9fa5}]1$' "$(get_pad_text)"
qi_letters

section "[87] 设置「输入 → 快捷输入 → 小数保留位数」拖到 2：快捷输入里 1/3 上屏 0.33"
# 引擎项写进核心用户层（CORE_CFG）并即时 ReloadConfig；用例前后备份 / 写回整份核心配置
qi_core_orig="$(read_core_cfg)"
write_kb_cfg "$(qi_cfg 'touch_quick_input = true # emu-test')"
restart_pad
adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
sleep 2
tap_text "输入"
tap_text "快捷输入"
sleep 0.8
qi_seek="$(dump_xml | sed 's/<node/\n<node/g' | grep -F 'class="android.widget.SeekBar"' | head -1)"
[[ "$qi_seek" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]] || die "快捷输入页找不到小数位数滑块"
# 0–6 七档：从最右（出厂 6）拖到 2/6 处
qi_y=$(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
adb_q shell input swipe $(( BASH_REMATCH[3] - 20 )) "$qi_y" \
    $(( BASH_REMATCH[1] + (BASH_REMATCH[3] - BASH_REMATCH[1]) * 2 / 6 )) "$qi_y" 600
sleep 1.2
check_match "写进核心配置 decimal_places = 2" '^decimal_places = 2$' \
    "$(read_core_cfg | grep -E '^decimal_places' | head -1)"
# 回测试台（back_to_pad 定义在 [46]，--only 时不在）：NEW_TASK | CLEAR_TASK，不杀进程，核心照旧
adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
sleep 1
reset_pad
tap_key w; tap_key q; tap_key v; tap_key b; tap_key SPACE; sleep 0.4
qi_swipe n
tap_key SYM; sleep 0.4
tap_cell 0 "${SYM_W[0]}" 0; tap_cell 1 "${SYM_W[1]}" 1; tap_cell 0 "${SYM_W[0]}" 2   # 1 / 3
tap_cell 3 "${SYM_W[3]}" 3; sleep 0.5                                                # 空格
check "1/3 按两位小数上屏" "你好0.33" "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1
write_core_cfg "$qi_core_orig"

section "[88] 快捷候选来源：把「英文」拖到「拼音」之前，快捷输入 man 空格上屏英文"
qs_core_orig="$(read_core_cfg)"
write_kb_cfg "$(qi_cfg 'touch_quick_input = true # emu-test')"
restart_pad
qs_type_man
tap_key SPACE; sleep 0.5
check_match "前提：出厂拼音在英文前，空格上屏汉字" '^[\x{4e00}-\x{9fa5}]+$' "$(get_pad_text)"
qs_open
read -r qs_x qs_y <<< "$(qs_handle 英文)"
read -r _ qs_ty <<< "$(qs_handle 拼音)"
# 慢拖：按住手柄上移一整行（越过拼音那行的一半即换位），抬手保存
adb_q shell input swipe "$qs_x" "$qs_y" "$qs_x" $(( qs_ty - 20 )) 1500
sleep 1.2
check_match "members 里 english 排到 \$primary_pinyin 之前" '"english", "\$primary_pinyin"' "$(qs_members)"
check "触发键没被写空" "no" "$(read_core_cfg | grep -qE '^trigger_keys = \[\]' && echo yes || echo no)"
qs_type_man
check "候选里有 man" "yes" "$(qs_has_man)"
tap_key SPACE; sleep 0.5
check "英文在前：空格上屏 man" "man" "$(get_pad_text)"

section "[89] 快捷候选来源：关掉「英文」后快捷输入里不再出英文候选"
qs_open
tap_text "英文"
sleep 1
check "members 里去掉了 english" "no" "$(qs_members | grep -qF '"english"' && echo yes || echo no)"
qs_type_man
check "关掉英文后候选里没有 man" "no" "$(qs_has_man)"
tap_key SPACE; sleep 0.5
check_match "空格上屏汉字" '^[\x{4e00}-\x{9fa5}]+$' "$(get_pad_text)"

section "[90] 快捷候选来源：恢复默认（弹框确认）回到出厂来源与顺序"
qs_open
# 恢复默认在列表底下：先滚到底
adb_q shell input swipe 540 1900 540 500 300; sleep 0.6
adb_q shell input swipe 540 1900 540 500 300; sleep 0.8
tap_text "恢复默认"
sleep 0.4
tap_text "恢复"
sleep 1.2
check "与出厂一致：用户层不留 members" "" "$(qs_members)"
qs_type_man
tap_key SPACE; sleep 0.5
check_match "恢复后拼音在前：空格上屏汉字" '^[\x{4e00}-\x{9fa5}]+$' "$(get_pad_text)"

section "[91] 快捷候选来源：打开「快捷输入历史」才出现「历史条数上限」"
qs_open
check "历史关着：没有历史条数上限" "no" "$(dump_xml | grep -qF 'text="历史条数上限"' && echo yes || echo no)"
adb_q shell input swipe 540 1900 540 900 300; sleep 0.8
tap_text "快捷输入历史"
sleep 1
adb_q shell input swipe 540 1900 540 500 300; sleep 0.8
check "历史开着：出现历史条数上限" "yes" "$(dump_xml | grep -qF 'text="历史条数上限"' && echo yes || echo no)"
check_match "members 末尾加上了 quick_input.history" '"english", "quick_input.history"\]' "$(qs_members)"
write_core_cfg "$qs_core_orig"
adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
sleep 1

# 写回整套用例的基线配置
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restart_pad
