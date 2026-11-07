# 快捷面板「编辑工具栏」的 e2e 用例（被 emu-test.sh source，依赖它的 check / section / tap_ime /
# ime_bounds / ime_xml / write_kb_cfg / with_comment_off / restart_pad / reset_pad / adb_ 等）。
#
# 编辑态顶部是工具栏预览：每项 content-desc「工具栏·<名称>」，右上角 ⊖「移除 <名称>」，按住拖动换位；
# 下方网格能常驻的格子右上角 ＋「加入 <名称>」/ ✓「已加入 <名称>」。
# 判据看两处：配置文件里的 toolbar_pins（顺序即工具栏顺序），以及预览行里各项的先后。
# 用例开头把常驻写成出厂三项，结尾写回整套用例的基线配置。

te_pins() {   # 配置里的常驻串（没写过 = 出厂默认）
    local v
    v="$(adb_ shell run-as "$PKG" cat "$KB_CFG" 2>/dev/null | grep -E '^[[:space:]]*toolbar_pins[[:space:]]*=' \
        | sed -E 's/^[^"]*"([^"]*)".*/\1/')"
    echo "${v:-keyboard,emoji,clipboard,text_edit}"
}
te_preview() {   # 预览行里的常驻项，按界面先后（无障碍树的子节点顺序即排列顺序）
    ime_xml | grep -oE 'content-desc="工具栏·[^"]*"' | sed -E 's/.*·([^"]*)"/\1/' | paste -sd, -
}
te_center() {   # 名称 → 预览里那一项的中心 "x y"
    local l t r b
    read -r l t r b <<< "$(ime_bounds "工具栏·$1")" || true
    [[ -n "$b" ]] || return 1
    echo "$(( (l + r) / 2 )) $(( (t + b) / 2 ))"
}
te_drag() {   # 把预览里的 $1 拖到 $2 所在的位置（按下即拖，不必长按）
    local fx fy tx ty
    read -r fx fy <<< "$(te_center "$1")" || true
    read -r tx ty <<< "$(te_center "$2")" || true
    [[ -n "$fy" && -n "$ty" ]] || { echo "  ${D}找不到预览里的「$1」或「$2」${N}"; return 1; }
    adb_q shell input swipe "$fx" "$fy" "$tx" "$fy" 700
    sleep 1.2
}

section "[62] 编辑工具栏：预览按顺序显示、拖动换位（往后、往前）"
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG" | grep -vE '^[[:space:]]*toolbar_pins[[:space:]]*=' \
    | sed 's/^candidate_comment = false # emu-test$/&\ntoolbar_pins = "emoji,clipboard,text_edit" # emu-test/')"
restart_pad
reset_pad
tap_ime 快捷功能
tap_ime 编辑工具栏
check "预览按工具栏顺序排" "表情,剪贴板,文本编辑" "$(te_preview)"
te_drag 表情 文本编辑
check "第一项拖到第三位：配置随之保存" "clipboard,text_edit,emoji" "$(te_pins)"
check "第一项拖到第三位：预览其余项让位" "剪贴板,文本编辑,表情" "$(te_preview)"
te_drag 表情 剪贴板
check "末项拖回第一位" "emoji,clipboard,text_edit" "$(te_pins)"

section "[63] 编辑工具栏：⊖ 移除、＋ 加入（排到末尾）"
tap_ime "移除 剪贴板"
check "⊖ 移除：配置里去掉" "emoji,text_edit" "$(te_pins)"
check "⊖ 移除：预览里没了，网格里出 ＋" "表情,文本编辑|yes" \
    "$(te_preview)|$(ime_bounds '加入 剪贴板' >/dev/null && echo yes || echo no)"
tap_ime "加入 剪贴板"
check "＋ 加入：排到末尾" "emoji,text_edit,clipboard" "$(te_pins)"
check "＋ 加入：网格那格变 ✓" "yes" "$(ime_bounds '已加入 剪贴板' >/dev/null && echo yes || echo no)"

section "[64] 编辑工具栏：满 5 个拒绝再加；完成后工具栏照新顺序摆"
tap_ime "加入 无痕"
tap_ime "加入 快捷输入"
check "加到 5 个" "emoji,text_edit,clipboard,incognito,quick_input" "$(te_pins)"
# 满了之后网格里还剩的 ＋ 任挑一个（当前方案没有临时拼音时，前两行不剩，往下翻一下）
te_more="$(ime_xml | grep -oE 'content-desc="加入 [^"]*"' | head -1 | sed -E 's/content-desc="(.*)"/\1/')"
if [[ -z "$te_more" ]]; then
    read -r _ gy _ _ <<< "$(ime_bounds '已加入 表情')" || true
    adb_q shell input swipe $(( GEO_W / 2 )) "${gy:-2000}" $(( GEO_W / 2 )) $(( ${gy:-2000} - 300 )) 300
    sleep 0.8
    te_more="$(ime_xml | grep -oE 'content-desc="加入 [^"]*"' | head -1 | sed -E 's/content-desc="(.*)"/\1/')"
fi
tap_ime "$te_more"
check "满 5 个再点 ＋（$te_more）：不加" "emoji,text_edit,clipboard,incognito,quick_input" "$(te_pins)"
check "满 5 个再点 ＋：提示最多 5 个" "yes" \
    "$(ime_xml | grep -q 'text="工具栏最多 5 个' && echo yes || echo no)"
tap_ime 完成
tap_ime 返回
# 真工具栏上的常驻项 content-desc 就是名称；按左边距排出先后
te_bar="$(for n in 表情 文本编辑 剪贴板 无痕 快捷输入; do
    read -r l _ <<< "$(ime_bounds "$n")" || true; echo "${l:-x} $n"; done | sort -n | awk '{print $2}' | paste -sd, -)"
check "工具栏照新顺序摆" "表情,文本编辑,剪贴板,无痕,快捷输入" "$te_bar"
# 写回整套用例的基线配置（常驻回到用户原来的）
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restart_pad
reset_pad
