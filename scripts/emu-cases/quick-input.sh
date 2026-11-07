# 快捷输入的数字（被 emu-test.sh source，依赖它的 check / check_match / section / reset_pad /
# restart_pad / tap_key / tap_ime / key_center / get_pad_text / write_kb_cfg / with_comment_off /
# SUITE_KB_ORIG / GEO_ROWPX / adb_q 等）。
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
# 写回整套用例的基线配置
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restart_pad
