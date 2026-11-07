# 键盘选择面板（照微信「键盘选择」）与「123 默认进九宫」的 e2e 用例（被 emu-test.sh source，依赖它的
# check / check_match / section / tap_ime / ime_bounds / ime_xml / tap_key / tap_cell / get_pad_text /
# reset_pad / restart_pad / back_to_pad / wait_chinese / has_text / write_kb_cfg / with_comment_off / adb_ 等）。
#
# 面板每格是「方框字 + 标签」，标签即可点的文字（全键盘拼音 / 五笔键盘 / 清风英文 / 英文直输 / 键盘管理）；
# 清风英文是英文方案（带补全候选），英文直输是引擎的英文模式（字母直出、无候选）；
# 当前方案那格的 content-desc 是「<标签>·当前」。入口：工具栏常驻的「键盘」（出厂常驻里排第一）
# 或快捷面板里的「键盘」格。用例开头剥掉 toolbar_pins（= 出厂常驻），结尾写回基线配置。

ks_current() {   # 面板里当前高亮的那格标签
    ime_xml | grep -oE 'content-desc="[^"]*·当前"' | head -1 | sed -E 's/content-desc="(.*)·当前"/\1/'
}
ks_left() {   # 节点左边距（找不到返回空）
    local l t r b
    read -r l t r b <<< "$(ime_bounds "$1")" || true
    echo "${l:-}"
}
ks_open() {   # 从工具栏的「键盘」打开键盘选择
    tap_ime 键盘
    sleep 0.6
}

section "[69] 键盘选择：工具栏出厂常驻「键盘」排第一，打开后列出方案、清风英文、英文直输、键盘管理，五笔高亮"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG" | grep -vE '^[[:space:]]*toolbar_pins[[:space:]]*=')"
restart_pad
wait_chinese >/dev/null
reset_pad
kb_l="$(ks_left 键盘)"; emo_l="$(ks_left 表情)"
check "工具栏「键盘」在「表情」左边" "yes" "$( [[ -n "$kb_l" && -n "$emo_l" ]] && (( kb_l < emo_l )) && echo yes || echo no)"
ks_open
ks_xml="$(ime_xml)"
ks_has() { grep -q "text=\"$1\"" <<< "$ks_xml" && echo yes || echo no; }
check "面板标题与各格" "yes yes yes yes yes" \
    "$(ks_has 键盘选择) $(ks_has 全键盘拼音) $(ks_has 清风英文) $(ks_has 英文直输) $(ks_has 键盘管理)"
check "不再有「数字九宫」「英文键盘」格" "no no" "$(ks_has 数字九宫) $(ks_has 英文键盘)"
check "当前方案（五笔）高亮" "五笔键盘" "$(ks_current)"
tap_ime 返回

section "[70] 键盘选择：点「全键盘拼音」切到拼音并回到键盘，再点「五笔键盘」切回"
reset_pad
ks_open
tap_ime 全键盘拼音
sleep 3
reset_pad
tap_key n; tap_key i; tap_key SPACE
check_match "切到拼音：ni 出汉字" "$CN" "$(get_pad_text)"
reset_pad   # 上屏后条区是联想候选，清空回到工具栏才有「键盘」
ks_open
check "再打开：全键盘拼音高亮" "全键盘拼音" "$(ks_current)"
tap_ime 五笔键盘
sleep 3
check_match "切回五笔：aa 出汉字" "$CN" "$(wait_chinese)"

section "[71] 键盘选择：点「英文直输」字母直出、无候选，切回五笔"
reset_pad
ks_open
tap_ime 英文直输
sleep 2
reset_pad
tap_key b; tap_key e; tap_key a; tap_key u
# 不进组合：每个字母按下即上屏（清风英文下这时输入框还是空的，见 [72]）
check "英文直输：字母逐个直接上屏" "beau" "$(get_pad_text)"
reset_pad
ks_open
check "英文直输高亮" "英文直输" "$(ks_current)"
tap_ime 五笔键盘
sleep 3
check_match "切回五笔：aa 出汉字" "$CN" "$(wait_chinese)"

section "[72] 键盘选择：点「清风英文」进英文方案出补全候选，中英键切回五笔"
reset_pad
ks_open
tap_ime 清风英文
sleep 3
reset_pad
tap_key b; tap_key e; tap_key a; tap_key u
check "清风英文：字母进组合，输入框暂空" "" "$(get_pad_text)"
# 候选条自绘，同 [27] 按宽度粗估：首项是原文 beau，屏幕中间落在补全词上
adb_q shell input tap $(( GEO_W / 2 )) "$GEO_BARY"; sleep 0.6
check_match "清风英文：点补全词上屏" '^[Bb]eau[a-z]+ $' "$(get_pad_text)"
reset_pad
ks_open
check "清风英文高亮" "清风英文" "$(ks_current)"
tap_ime 返回
# 面板切进英文方案时记下了切走前的中文方案：中英键原路回五笔
reset_pad
tap_key LANG; sleep 3
check_match "中英键切回五笔：aa 出汉字" "$CN" "$(wait_chinese)"

section "[73] 键盘选择：「键盘管理」打开设置的「启用的方案」页"
reset_pad
ks_open
tap_ime 键盘管理
sleep 2
check "进到启用的方案页" "yes" "$(has_text 启用的方案)"
back_to_pad
reset_pad

section "[74] 快捷面板：顶上不再有方案行，「键盘」格打开键盘选择，「设置」在最后一格"
reset_pad
tap_ime 快捷功能
qp_xml="$(ime_xml)"
# 方案行此前是一排「英 · 五笔 · 全拼…」分段：标签「全拼」「五笔」都不该再出现
check "没有方案分段" "no" "$(grep -qE 'text="(全拼|五笔|双拼)"' <<< "$qp_xml" && echo yes || echo no)"
# 「设置」排在「单手」之后：下一行，或同一行更靠右（--windows 的 dump 混着别的窗口，不按节点先后判）
read -r s_l s_t _ _ <<< "$(ime_bounds 设置)" || true
read -r h_l h_t _ _ <<< "$(ime_bounds 单手)" || true
check "「设置」在「单手」之后" "yes" "$( [[ -n "$s_t" && -n "$h_t" ]] &&
    (( s_t > h_t + 10 || (s_t >= h_t - 10 && s_t <= h_t + 10 && s_l > h_l) )) && echo yes || echo no)"
tap_ime 键盘
check "快捷面板「键盘」格打开键盘选择" "yes" "$(ime_xml | grep -q 'text="键盘选择"' && echo yes || echo no)"
tap_ime 返回

section "[75] 「123」默认先进九宫数字（不设 number_page 时）"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG" | grep -vxF "$NUMBER_FULL_CFG")"
restart_pad
wait_chinese >/dev/null
reset_pad
tap_key SYM
sleep 0.6
check "123 → 九宫数字层（左列符号露出来）" "yes" "$(ime_xml | grep -q 'content-desc="左列·' && echo yes || echo no)"
tap_cell 3 "$NUM_W" 1   # 底行「返回」回字母层
# 写回整套用例的基线配置
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restart_pad
wait_chinese >/dev/null
reset_pad
