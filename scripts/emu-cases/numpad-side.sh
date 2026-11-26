# 九宫格数字键盘左列符号（数字框）的 e2e 用例（被 emu-test.sh source，依赖它的 check / section /
# tap_text / has_text / row_y_of / dump_xml / ime_xml / ime_bounds / tap_ime / tap_key / tap_cell /
# tap_edit / get_pad_text / reset_pad / back_to_pad / write_kb_cfg / with_comment_off / adb_ 等）。
#
# 列表存在 ui.android.number_side_symbols（每项之间 \n）；设置页「键盘布局 → 数字键盘符号」里
# 每行有「删除 <符号>」按钮和「拖动排序 <符号>」手柄。键盘左列的格子报无障碍节点「左列·<符号>」，
# 最底下一格「自定义」。用例开头写回基线配置（with_comment_off 会剥掉这个键 = 出厂列表），结尾同样写回。

NS_DEFAULTS='+|-|*|/|%|=|,|:|@|#|(|)'

ns_cfg() {   # 配置里的列表，项之间用 | 连（没写过这个键 = 「默认」）
    local line
    line="$(adb_ shell cat "$KB_CFG" 2>/dev/null | grep -E '^[[:space:]]*number_side_symbols[[:space:]]*=')"
    [[ -n "$line" ]] || { echo 默认; return; }
    printf '%s' "$line" | sed -E 's/^[^"]*"(.*)"[^"]*$/\1/; s/\\n/|/g'
}
ns_page() {   # 设置页列表里的符号，按界面先后，用空格连
    dump_xml | grep -oE 'content-desc="拖动排序 [^"]*"' | sed -E 's/content-desc="拖动排序 (.*)"/\1/' | paste -sd' ' -
}
ns_side() {   # 键盘左列露出来的格子，按上下先后，用空格连
    ime_xml | grep -oE 'content-desc="(左列·[^"]*|自定义)"' | sed -E 's/content-desc="(左列·)?(.*)"/\2/' | paste -sd' ' -
}
ns_desc_xy() {   # 设置页里 content-desc 为 $1 的节点中心 "x y"
    local node try
    for ((try = 0; try < 6; try++)); do
        node="$(dump_xml | sed 's/<node/\n<node/g' | grep -F "content-desc=\"$1\"" | head -1)"
        if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
            echo "$(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))"
            return 0
        fi
        sleep 0.7
    done
    return 1
}
ns_on_page() {   # 当前是不是数字键盘符号页（列表标题「左列符号 · N 个」；列表长，「恢复默认」在屏外）
    local try
    for ((try = 0; try < 6; try++)); do
        dump_xml | grep -q 'text="左列符号 · ' && { echo yes; return; }
        sleep 0.7
    done
    echo no
}
ns_bottom() {   # 翻到页底（添加框、恢复默认在列表下面）
    local i
    for ((i = 0; i < 3; i++)); do
        adb_q shell input swipe $(( GEO_W / 2 )) 1800 $(( GEO_W / 2 )) 600 200
    done
    sleep 0.8
}
ns_open() {   # 新开设置页，进「键盘布局 → 数字键盘符号」
    local y
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
    sleep 2
    # 首页一屏放不下「手机键盘」组，往下翻着找
    y="$(row_y_of 键盘布局)" || die "设置首页找不到「键盘布局」"
    adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1.2
    y="$(row_y_of 数字键盘符号)" || die "键盘布局页找不到「数字键盘符号」"
    adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1.2
    [[ "$(ns_on_page)" == yes ]] || die "没进到数字键盘符号页"
}
ns_type_one() {   # 点添加框，用本输入法 123 层打一个「1」，收起键盘
    local node
    ns_bottom
    node="$(dump_xml | sed 's/<node/\n<node/g' | grep -F 'class="android.widget.EditText"' | head -1)"
    [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]] || die "找不到添加框"
    adb_q shell input tap $(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
    sleep 1.2
    tap_key SYM
    tap_cell 0 "${SYM_W[0]}" 0
    adb_q shell input keyevent BACK; sleep 1
}
ns_numpad() {   # 普通输入框里从 123 层「1234」进九宫格（数字框会滤掉 / # 等符号，不在那里测上屏）
    back_to_pad
    reset_pad
    tap_key SYM; tap_cell 2 "${SYM_W[2]}" 0
    sleep 0.6
}

section "[65] 数字键盘符号：设置页删除、拖动排序，键盘左列跟着变"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
ns_open
check "设置页列出厂列表" "+ - * / % = , : @ # ( )" "$(ns_page)"
read -r nx ny <<< "$(ns_desc_xy '删除 +')" || die "找不到「删除 +」"
adb_q shell input tap "$nx" "$ny"; sleep 1
check "删除：配置随之保存" '-|*|/|%|=|,|:|@|#|(|)' "$(ns_cfg)"
read -r fx fy <<< "$(ns_desc_xy '拖动排序 /')" || die "找不到「/」的拖动手柄"
read -r _ ty <<< "$(ns_desc_xy '拖动排序 -')" || die "找不到「-」的拖动手柄"
adb_q shell input swipe "$fx" "$fy" "$fx" "$ty" 900; sleep 1.2
check "第 3 项拖到第 1 位：配置随之保存" '/|-|*|%|=|,|:|@|#|(|)' "$(ns_cfg)"
check "第 3 项拖到第 1 位：列表其余项让位" "/ - * % = , : @ # ( )" "$(ns_page)"
ns_numpad
check "键盘左列按新列表排" "/ - * %" "$(ns_side | cut -d' ' -f1-4)"
tap_ime "左列·/"
check "点左列新的第 1 格上屏" "/" "$(get_pad_text)"

section "[66] 数字键盘符号：添加（重复拒绝、排到末尾）；恢复默认弹框确认"
ns_open
ns_type_one
tap_text "添加"
check "添加：排到末尾" '/|-|*|%|=|,|:|@|#|(|)|1' "$(ns_cfg)"
ns_type_one
tap_text "添加"
check "重复的拒绝并提示" "yes" "$(has_text '已有「1」')"
check "重复的不写进配置" '/|-|*|%|=|,|:|@|#|(|)|1' "$(ns_cfg)"
y="$(row_y_of 恢复默认)" || die "找不到「恢复默认」"
adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1
check "恢复默认先弹确认框、不动配置" "yes|/|-|*|%|=|,|:|@|#|(|)|1" "$(has_text 恢复)|$(ns_cfg)"
tap_text 恢复; sleep 1
check "确认后写回出厂列表" "$NS_DEFAULTS" "$(ns_cfg)"

section "[67] 键盘左列：惯性滑到底露出「自定义」，点开直达设置页"
ns_numpad
check "滑动前「自定义」在列外" "no" "$(ns_side | grep -qw 自定义 && echo yes || echo no)"
# 一下快滑（50ms）只拖了两行多：不靠惯性滑行到不了底
ns_x="$(awk -v W="$GEO_W" 'BEGIN{ printf "%d", W / 6.2 / 2 }')"
ns_y0="$(awk -v top="$GEO_KBTOP" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", top + rp * 2.6 }')"
ns_y1="$(awk -v top="$GEO_KBTOP" -v rp="$GEO_ROWPX" 'BEGIN{ printf "%d", top + rp * 0.4 }')"
adb_q shell input swipe "$ns_x" "$ns_y0" "$ns_x" "$ns_y1" 50; sleep 2
check "惯性滑到底：最底下是「自定义」" "自定义" "$(ns_side | awk '{print $NF}')"
check "滑动不上屏" "" "$(get_pad_text)"
tap_ime 自定义
sleep 1.5
check "点「自定义」直达数字键盘符号页" "yes|yes" "$(has_text 数字键盘符号)|$(ns_on_page)"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
back_to_pad
reset_pad
