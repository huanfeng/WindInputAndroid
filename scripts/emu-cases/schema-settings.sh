# 单个方案设置（方案详情页）的 e2e 用例（被 emu-test.sh source，依赖它的 check / section / tap_text /
# has_text / row_y_of / lang_menu_pick / reset_pad / tap_key / back_to_pad / adb_ 等）。
#
# 入口：设置 → 方案 → 启用的方案 → 某行的设置图标（content-desc「「方案名」的设置」）。
# 判据看两处：覆盖文件 schema_overrides/<id>.toml 写了什么（与 PC 同一份），以及打字时真的变了。
# 用例前后把五笔的覆盖文件备份 / 还原，不碰别的用例留下的扩展词库开关。

SS_OV_DIR="$EXT/schema_overrides"
SS_OV_WUBI="$(adb_ shell cat "$SS_OV_DIR/wubi86.toml" 2>/dev/null | grep -v 'No such file')"
adb_q shell rm -f "$SS_OV_DIR/wubi86.toml"

ss_ov() { adb_ shell cat "$SS_OV_DIR/$1.toml" 2>/dev/null | grep -v 'No such file'; }
ss_tap_desc() {   # 按 content-desc 点（图标按钮没有 text）
    local node try
    for ((try = 0; try < 6; try++)); do
        node="$(dump_xml | sed 's/<node/\n<node/g' | grep -F "content-desc=\"$1\"" | head -1)"
        if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
            adb_q shell input tap $(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))
            sleep 1
            return 0
        fi
        sleep 0.7
    done
    die "找不到描述为「$1」的节点"
}
ss_open_detail() {   # 方案显示名：从设置首页一路点进它的详情页
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
    sleep 2
    tap_text "方案"
    tap_text "启用的方案"
    ss_tap_desc "「$1」的设置"
    [[ "$(has_text 自定义行为)" == yes ]] || die "没进到「$1」的详情页"
}
ss_tap_row() {   # 往下翻到这一行并点它（开关行点了即切换，下拉行点了弹出选项）
    local y
    y="$(row_y_of "$1")" || die "详情页找不到「$1」"
    adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1.2
}
ss_type_wubi() {   # 在五笔下打一串码，返回输入框内容（不按空格）
    local k
    for k in $(grep -o . <<< "$1"); do tap_key "$k"; done
    sleep 0.6
    get_pad_text
}

section "[58] 方案详情页：启用的方案每行有设置入口，详情按方案类型列项，返回回到列表"
ss_open_detail 五笔
check "信息头：类型 · 来源 · 版本" "yes" "$(dump_xml | grep -q '码表 · 应用自带 · 版本' && echo yes || echo no)"
check "码表方案有「方案覆盖」" "yes" "$(row_y_of 方案覆盖 >/dev/null && echo yes || echo no)"
check "码表方案有「整句输入」" "yes" "$(row_y_of 整句输入 >/dev/null && echo yes || echo no)"
check "码表覆盖项标题取设置清单" "yes" "$(has_text 调频策略)"
check "码表覆盖项未改时没有「已自定义」" "no" "$(dump_xml | grep -q '已自定义' && echo yes || echo no)"
adb_q shell input keyevent BACK; sleep 1.2
check "返回回到启用的方案列表" "yes" "$(has_text 启用)"
ss_tap_desc "「清风英文」的设置"
check "英文方案：标点显示方案自带的英文标点" "yes" "$(has_text 英文标点)"
check "英文方案没有码表覆盖" "no" "$(dump_xml | grep -q 'text="方案覆盖"' && echo yes || echo no)"
check "英文方案没有整句输入" "no" "$(dump_xml | grep -q 'text="整句输入"' && echo yes || echo no)"
check "手机上不列候选横竖排" "no" "$(dump_xml | grep -q 'text="候选排列"' && echo yes || echo no)"
back_to_pad

section "[59] 方案详情页：五笔改成英文标点，切走再切回后逗号出半角；选回「跟随全局」后恢复"
ss_open_detail 五笔
ss_tap_row 标点
tap_text 英文标点
sleep 1
check "覆盖文件写了 [punct] mode = english" "yes" "$(ss_ov wubi86 | grep -q '^mode = "english"' && echo yes || echo no)"
check "行内注明已自定义及默认值" "yes" "$(dump_xml | grep -q '已自定义 · 默认：跟随全局' && echo yes || echo no)"
back_to_pad
reset_pad
# 标点态是方案代际同步的（同 PC：改的是当前方案时，本代际内用户手动切的标点态胜出），切一次才生效
lang_menu_pick 1; sleep 3
lang_menu_pick 5; sleep 2
tap_key COMMA
check "五笔下逗号出半角" "," "$(get_pad_text)"
ss_open_detail 五笔
ss_tap_row 标点
tap_text 跟随全局
sleep 1
check "选回默认值即删掉覆盖" "no" "$(ss_ov wubi86 | grep -q 'punct' && echo yes || echo no)"
back_to_pad
reset_pad
lang_menu_pick 1; sleep 3
lang_menu_pick 5; sleep 2
tap_key COMMA
check "恢复后逗号出全角" "，" "$(get_pad_text)"

section "[60] 方案详情页：五笔覆盖「全码唯一自动上屏」，打 wynb 立即生效；拨回默认后恢复"
ss_open_detail 五笔
ss_tap_row 全码唯一自动上屏
ov="$(ss_ov wubi86)"
if grep -q '^auto_commit_at_full = true' <<< "$ov"; then ac_on=yes
elif grep -q '^auto_commit_at_full = false' <<< "$ov"; then ac_on=no
else ac_on=""; fi
check "覆盖文件写了 [engine.codetable] auto_commit_at_full" "yes" "$([[ -n "$ac_on" ]] && echo yes || echo no)"
back_to_pad
reset_pad
got="$(ss_type_wubi wynb)"
check "覆盖后（自动上屏=$ac_on）：wynb 不按空格" "$([[ $ac_on == yes ]] && echo 今)" "$got"
[[ -n "$got" ]] || tap_key SPACE
ss_open_detail 五笔
ss_tap_row 全码唯一自动上屏
check "拨回默认值即删掉覆盖" "no" "$(ss_ov wubi86 | grep -q 'auto_commit_at_full' && echo yes || echo no)"
back_to_pad
reset_pad
got="$(ss_type_wubi wynb)"
check "恢复后行为翻回来：wynb 不按空格" "$([[ $ac_on == yes ]] || echo 今)" "$got"
[[ -n "$got" ]] || tap_key SPACE
reset_pad

section "[61] 方案详情页：全部恢复默认清掉本页的覆盖，扩展词库开关不受影响"
ss_open_detail 五笔
ss_tap_row 行政区域   # 扩展词库开关：与本页设置写同一个覆盖文件
check "扩展词库开关写进覆盖文件" "yes" "$(ss_ov wubi86 | grep -q 'dictionaries' && echo yes || echo no)"
ss_tap_row 标点
tap_text 英文标点
sleep 1
ss_tap_row 整句输入
check "两项都写进了覆盖文件" "yes" "$(ov="$(ss_ov wubi86)"; grep -q 'mode = "english"' <<< "$ov" && grep -q 'sentence_input = true' <<< "$ov" && echo yes || echo no)"
y="$(row_y_of 全部恢复默认)" || die "详情页找不到「全部恢复默认」"
check "计数：已自定义 2 项" "yes" "$(dump_xml | grep -q '已自定义 2 项' && echo yes || echo no)"
adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1.2
check "全部恢复先确认" "yes" "$(has_text 恢复)"
tap_text 恢复
sleep 1.5
ov="$(ss_ov wubi86)"
check "全部恢复后本页的键清掉" "no" "$(grep -qE 'punct|sentence_input' <<< "$ov" && echo yes || echo no)"
check "全部恢复后扩展词库开关仍在" "yes" "$(grep -q 'dictionaries' <<< "$ov" && echo yes || echo no)"
check "全部恢复后显示没有自定义的项" "yes" "$(has_text 没有自定义的项)"

# 还原五笔覆盖文件（别的用例 / 用户留下的扩展词库开关等）
if [[ -n "$SS_OV_WUBI" ]]; then
    printf '%s\n' "$SS_OV_WUBI" | "$ADB" -s "$SERIAL" shell sh -c "'mkdir -p $SS_OV_DIR && cat > $SS_OV_DIR/wubi86.toml'"
else
    adb_q shell rm -f "$SS_OV_DIR/wubi86.toml"
fi
back_to_pad
