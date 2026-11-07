# 退格下滑 = 恢复刚删的内容（被 emu-test.sh source，依赖它的 check / section / reset_pad / tap_key /
# tap_cell / SYM_W / key_center / bs_gesture / get_pad_text / adb_q / GEO_* 等）。
#
# 输入框里的字用 123 层的数字打（1–5 各不相同，光标位置看得出来；汉字用例全是「式」分不清）。
# 123 层的退格与字母层同一行最右，字母层算出的退格中心落在它里面，bs_gesture 直接用。
# 下滑 60dp 过阈值（40dp），22dp 只到「未选中」。
# 没有可恢复的内容时下滑不进恢复模式，行为同改动前：手指滑出键外抬手，什么都不做。
# 每个用例收尾先点 123 层的「返回」回字母层：层不随换输入框复位，下一个用例的 tap_key SYM 会切反。

bu_digits() {   # 在 123 层依次点数字 $1 里的每一位（1–9）
    local i d
    for ((i = 0; i < ${#1}; i++)); do
        d="${1:i:1}"
        tap_cell 0 "${SYM_W[0]}" $(( d - 1 ))
    done
}

bu_start() {   # 新输入框里打 12345，停在 123 层；退格中心放进 bs_x / bs_y
    reset_pad
    read -r bs_x bs_y <<< "$(key_center BS)"
    tap_key SYM
    bu_digits 12345
    [[ "$(get_pad_text)" == 12345 ]] || die "[68] 打不出 12345，后续断言不可信"
}

bu_tap_bs() { adb_q shell input tap "$bs_x" "$bs_y"; sleep 0.4; }

bu_end() { tap_cell 3 "${SYM_W[3]}" 1; reset_pad; }

section "[68] 退格下滑恢复：两次按压删的两个字一次恢复，恢复后不能再恢复"
bu_start
bu_tap_bs; bu_tap_bs
check "点两下退格" "123" "$(get_pad_text)"
bs_gesture "0,60"
check "下滑过阈值抬手：两字一起回来" "12345" "$(get_pad_text)"
bs_gesture "0,60"
check "已恢复过：再下滑不再放回一遍" "12345" "$(get_pad_text)"
bu_end

section "[68a] 清空后下滑恢复，光标回到原位"
bu_start
adb_q shell input keyevent KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT; sleep 0.4   # 123|45
bs_gesture "0,-60" "-45,-60"
check "上滑 + 左移清空" "" "$(get_pad_text)"
bs_gesture "0,60"
check "下滑恢复整段" "12345" "$(get_pad_text)"
bu_digits 9
check "光标仍在 3 与 4 之间" "123945" "$(get_pad_text)"
bu_end

section "[68b] 删后打字再下滑：不恢复"
bu_start
bu_tap_bs
bu_digits 6
check "删一字再打 6" "12346" "$(get_pad_text)"
bs_gesture "0,60"
check "下滑不把 5 插回来" "12346" "$(get_pad_text)"
bu_end

section "[68c] 下滑未过阈值 = 反悔；扩选删、删光标前也能恢复"
bu_start
bu_tap_bs
bs_gesture "0,22"
check "未过阈值抬手：不恢复也不删" "1234" "$(get_pad_text)"
bs_gesture "0,60"
check "再下滑过阈值恢复" "12345" "$(get_pad_text)"
# 注入的 MOVE 有重采样，扩选具体删几个字不稳（见 [48e]），只验恢复后原样
bs_gesture "-20,0"
check_match "左滑扩选删掉了光标前的字" '^(123|1234)$' "$(get_pad_text)"
bs_gesture "0,60"
check "扩选删后下滑恢复" "12345" "$(get_pad_text)"
adb_q shell input keyevent KEYCODE_DPAD_LEFT; sleep 0.4   # 1234|5
bs_gesture "0,-60"
check "上滑删光标前" "5" "$(get_pad_text)"
bs_gesture "0,60"
check "删光标前后下滑恢复" "12345" "$(get_pad_text)"
bu_end

section "[68d] 符号页（面板）退格同样支持下滑恢复"
bu_start
tap_cell 3 "${SYM_W[3]}" 0; sleep 0.6   # 123 层左下「符号」进符号页
sym_bs_x=$(( GEO_W * 925 / 1000 )); sym_bottom_y=$(( GEO_KBBOTTOM - GEO_ROWPX / 2 ))
adb_q shell input tap "$sym_bs_x" "$sym_bottom_y"; sleep 0.4
adb_q shell input tap "$sym_bs_x" "$sym_bottom_y"; sleep 0.4
check "符号页退格点两下" "123" "$(get_pad_text)"
sym_down_y="$(awk -v y="$sym_bottom_y" -v d="$GEO_DENSITY" 'BEGIN{ printf "%d", y + 60 * d }')"
adb_q shell input swipe "$sym_bs_x" "$sym_bottom_y" "$sym_bs_x" "$sym_down_y" 300
sleep 0.6
check "符号页退格下滑恢复" "12345" "$(get_pad_text)"
adb_q shell input tap $(( GEO_W * 75 / 1000 )) "$sym_bottom_y"; sleep 0.6   # 符号页左下 ← 回到 123 层
bu_end
