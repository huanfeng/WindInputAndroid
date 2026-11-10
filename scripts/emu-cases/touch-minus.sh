# 触屏键盘打出的 - 不当翻页键、也不进编码缓冲（被 emu-test.sh source，依赖它的 check /
# check_match / section / die / restart_pad / reset_pad / wait_chinese / tap_key / tap_cell /
# key_center / get_pad_text / set_core_active / adb_q / PKG / GEO_ROWPX / SYM_W / WUBI_PIN 等）。
#
# 真机反馈：组码中上滑打 -，看着像被当成了上翻页。实际是电脑版出厂的两条规则：- 是翻页键
# （keys.page_keys 含 minus_equal），首页时又被 input.buffer_symbol_chars 收进编码缓冲
# （为电脑上打 sun-panel）——候选整条消失，空格上屏一串 nihao-。手机出厂两项都关掉，
# - 与其它上滑符号一样：首选上屏再接 -。

tm_swipe() {   # key：从键中心上滑 1.5 行，等气泡抬手出字（同 [21e] 的 swipe_sym）
    local kx ky
    read -r kx ky <<< "$(key_center "$1")"
    adb_q shell input swipe "$kx" "$ky" "$kx" $(( ky - GEO_ROWPX * 3 / 2 )) 250
    sleep 0.8
}

section "[94] 组码中上滑 / 123 层打 -：首选上屏再接 -，不进编码、不翻页"
restart_pad
reset_pad
[[ -n "$(wait_chinese)" ]] || die "中文方案未就绪"
reset_pad   # wait_chinese 探测时打的字留在框里
tap_key a; tap_key a
tm_swipe c
check "五笔 aa 上滑 -：首选上屏再接 -" "式-" "$(get_pad_text)"
reset_pad
tap_key a; tap_key a
tap_key SYM; tap_cell 1 "${SYM_W[1]}" 0
check "五笔 aa 点 123 层 -：同上" "式-" "$(get_pad_text)"
tap_cell 3 "${SYM_W[3]}" 1
reset_pad
tm_swipe c
check "空闲时上滑 - 照常出 -" "-" "$(get_pad_text)"
reset_pad

# 出厂默认方案是全拼，真机用户多半在这里碰到
set_core_active 'active = "pinyin" # emu-test'
adb_q shell run-as "$PKG" rm -f shared_prefs/ime_state.xml
restart_pad; reset_pad; sleep 3
for k in n i h a o; do tap_key $k; done
tm_swipe c
check "全拼 nihao 上滑 -：首选上屏再接 -" "你好-" "$(get_pad_text)"
reset_pad
set_core_active "$WUBI_PIN"
adb_q shell run-as "$PKG" rm -f shared_prefs/ime_state.xml
restart_pad
