# 用户键位覆盖（mobile/keymap.toml）：上滑改字符、下滑出文本、下滑绑功能（被 emu-test.sh source，
# 依赖它的 check / section / die / restart_pad / reset_pad / wait_chinese / tap_key / key_center /
# get_pad_text / adb_q / PKG / GEO_ROWPX 等）。
#
# 把一份 keymap.toml 推进用户层（Android/data/<pkg>/files/mobile/），重启测试台让键盘重读：
#   z 上滑 = 《；c 下滑 = ——；x 下滑 = 全选（再点退格清空，借此验证选中了全部）。
# 空缓冲下《、—— 走 Commit 原样上屏，与方案无关。收尾删文件并重启，不留覆盖影响后续用例。

KM_DIR="/sdcard/Android/data/$PKG/files/mobile"

km_swipe() {   # key dy_rows：从键中心竖滑 dy_rows 行（负 = 上滑），250ms，等抬手出字
    local kx ky
    read -r kx ky <<< "$(key_center "$1")"
    adb_q shell input swipe "$kx" "$ky" "$kx" $(( ky + GEO_ROWPX * $2 / 2 )) 250
    sleep 0.8
}

section "[95] 键位覆盖：z 上滑《、c 下滑——、x 下滑全选"
km_tmp="$(mktemp)"
cat > "$km_tmp" <<'TOML'
version = 1
[layer.letters.key.z]
swipe_up = { text = "《" }
[layer.letters.key.c]
swipe_down = { text = "——" }
[layer.letters.key.x]
swipe_down = { func = "select_all" }
TOML
adb_q shell mkdir -p "$KM_DIR"
adb_q push "$km_tmp" "$KM_DIR/keymap.toml" >/dev/null
rm -f "$km_tmp"
restart_pad
reset_pad
[[ -n "$(wait_chinese)" ]] || die "中文方案未就绪"
reset_pad
km_swipe z -3
check "z 上滑：出《" "《" "$(get_pad_text)"
km_swipe c 3
check "c 下滑：接上——" "《——" "$(get_pad_text)"
km_swipe x 3
tap_key BS
check "x 下滑全选，再退格：整个输入框清空" "" "$(get_pad_text)"
adb_q shell rm -f "$KM_DIR/keymap.toml"
restart_pad
reset_pad

# ── [96] 模板：z 上滑 = {date}（上屏时现算），与设备上的日期比对 ─────────────────────────
section "[96] 键位覆盖：z 上滑模板 {date}"
km_tmp="$(mktemp)"
cat > "$km_tmp" <<'TOML'
version = 1
[layer.letters.key.z]
swipe_up = { template = "{date}" }
TOML
adb_q shell mkdir -p "$KM_DIR"
adb_q push "$km_tmp" "$KM_DIR/keymap.toml" >/dev/null
rm -f "$km_tmp"
restart_pad
reset_pad
[[ -n "$(wait_chinese)" ]] || die "中文方案未就绪"
reset_pad
km_swipe z -3
check "z 上滑：上屏设备当天日期" "$(adb_ shell date +%Y-%m-%d | tr -d '\r')" "$(get_pad_text)"
adb_q shell rm -f "$KM_DIR/keymap.toml"
restart_pad
reset_pad

# ── [97] R7：空格上滑选第二候选、中英键上滑选第三候选（两个开关各管各的）─────────────────
section "[97] 有编码时空格上滑选第二候选、中英键上滑选第三候选"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG" | sed 's/^candidate_comment = false # emu-test$/&\nsecond_candidate_swipe = true # emu-test\nthird_candidate_swipe = true # emu-test/')"
restart_pad
[[ -n "$(wait_chinese)" ]] || die "开候选上滑后起不来中文"
reset_pad
tap_key a; tap_key a; tap_key SPACE
r7_first="$(get_pad_text)"
reset_pad
tap_key a; tap_key a; km_swipe SPACE -3
r7_second="$(get_pad_text)"
reset_pad
tap_key a; tap_key a; km_swipe LANG -3
r7_third="$(get_pad_text)"
check_match "空格上滑上屏一个汉字候选" '^[\x{4e00}-\x{9fa5}]+$' "$r7_second"
check_match "中英键上滑上屏一个汉字候选" '^[\x{4e00}-\x{9fa5}]+$' "$r7_third"
check "三者互不相同（首选 / 第二 / 第三）" "yes" \
    "$([[ "$r7_first" != "$r7_second" && "$r7_first" != "$r7_third" && "$r7_second" != "$r7_third" ]] && echo yes || echo "no: $r7_first $r7_second $r7_third")"
reset_pad
km_swipe SPACE -3
check "没有编码时空格上滑什么也不出" "" "$(get_pad_text)"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restart_pad
reset_pad
