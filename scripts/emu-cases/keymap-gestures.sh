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
