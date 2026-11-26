# 单个方案设置（方案设置页）的 e2e 用例（被 emu-test.sh source，依赖它的 check / section / tap_text /
# has_text / row_y_of / lang_menu_pick / reset_pad / tap_key / back_to_pad / adb_ 等）。
#
# 入口：设置 → 方案 → 方案列表某行的齿轮（content-desc「「方案名」的设置」）→ 分组子页。
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
ss_open_detail() {   # 方案显示名：从设置首页一路点进它的方案设置页（第一屏：分组入口）
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity"
    sleep 2
    tap_text "方案"
    ss_tap_desc "「$1」的设置"
    [[ "$(has_text 设置)" == yes ]] || die "没进到「$1」的方案设置页"
}
ss_open_group() {   # 方案设置页第一屏上点进一组
    local y
    y="$(row_y_of "$1")" || die "方案设置页找不到分组「$1」"
    adb_q shell input tap $(( GEO_W / 2 )) "$y"; sleep 1.5
}
ss_back() { adb_q shell input keyevent BACK; sleep 1.2; }
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

section "[58] 方案设置页：方案列表每行有齿轮，分组按方案类型列出，返回回到列表"
ss_open_detail 五笔
check "信息头：类型 · 来源 · 版本" "yes" "$(dump_xml | grep -q '码表 · 应用自带 · 版本' && echo yes || echo no)"
check "码表方案有「上屏」组" "yes" "$(row_y_of 上屏 >/dev/null && echo yes || echo no)"
check "码表方案有「整句输入（实验性）」组" "yes" "$(row_y_of 整句输入（实验性） >/dev/null && echo yes || echo no)"
ss_open_group 调频
check "调频组列出全部参数（不折叠）" "yes" "$(has_text 调频策略)"
check "标题栏有「恢复默认」" "yes" "$(has_text 恢复默认)"
check "行内不标「已自定义」" "no" "$(dump_xml | grep -q '已自定义' && echo yes || echo no)"
ss_back
ss_back
check "返回回到方案列表" "yes" "$(has_text 导入方案)"
ss_tap_desc "「清风英文」的设置"
check "英文方案没有整句输入" "no" "$(dump_xml | grep -q 'text="整句输入（实验性）"' && echo yes || echo no)"
ss_open_group 自定义行为
check "英文方案：标点显示方案自带的英文标点" "yes" "$(has_text 英文标点)"
check "手机上不列候选横竖排" "no" "$(dump_xml | grep -q 'text="候选排列"' && echo yes || echo no)"
back_to_pad

section "[59] 方案设置页：五笔改成英文标点，切走再切回后逗号出半角；选回「跟随全局」后恢复"
ss_open_detail 五笔
ss_open_group 自定义行为
ss_tap_row 标点
tap_text 英文标点
sleep 1
check "覆盖文件写了 [punct] mode = english" "yes" "$(ss_ov wubi86 | grep -q '^mode = "english"' && echo yes || echo no)"
check "改了也不在行里加「已自定义」（行高不变）" "no" "$(dump_xml | grep -q '已自定义' && echo yes || echo no)"
back_to_pad
reset_pad
# 标点态是方案代际同步的（同 PC：改的是当前方案时，本代际内用户手动切的标点态胜出），切一次才生效
lang_menu_pick 全拼; sleep 3
lang_menu_pick 五笔; sleep 2
tap_key COMMA
check "五笔下逗号出半角" "," "$(get_pad_text)"
ss_open_detail 五笔
ss_open_group 自定义行为
ss_tap_row 标点
tap_text 跟随全局
sleep 1
check "选回默认值即删掉覆盖" "no" "$(ss_ov wubi86 | grep -q 'punct' && echo yes || echo no)"
back_to_pad
reset_pad
lang_menu_pick 全拼; sleep 3
lang_menu_pick 五笔; sleep 2
tap_key COMMA
check "恢复后逗号出全角" "，" "$(get_pad_text)"

section "[60] 方案设置页：五笔改「全码唯一自动上屏」，打 wynb 立即生效；拨回默认后恢复"
ss_open_detail 五笔
ss_open_group 上屏
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
ss_open_group 上屏
ss_tap_row 全码唯一自动上屏
check "拨回默认值即删掉覆盖" "no" "$(ss_ov wubi86 | grep -q 'auto_commit_at_full' && echo yes || echo no)"
back_to_pad
reset_pad
got="$(ss_type_wubi wynb)"
check "恢复后行为翻回来：wynb 不按空格" "$([[ $ac_on == yes ]] || echo 今)" "$got"
[[ -n "$got" ]] || tap_key SPACE
reset_pad

section "[61] 方案设置页：标题栏恢复默认——分组页只清本组，第一屏清整个方案，扩展词库开关不受影响"
ss_open_detail 五笔
ss_open_group 扩展词库
ss_tap_row 行政区域   # 扩展词库开关：与方案设置写同一个覆盖文件
check "扩展词库开关写进覆盖文件" "yes" "$(ss_ov wubi86 | grep -q 'dictionaries' && echo yes || echo no)"
ss_back
ss_open_group 自定义行为
ss_tap_row 标点
tap_text 英文标点
sleep 1
ss_back
ss_open_group 整句输入（实验性）
ss_tap_row 启用整句输入
ss_back
check "两项都写进了覆盖文件" "yes" "$(ov="$(ss_ov wubi86)"; grep -q 'mode = "english"' <<< "$ov" && grep -q 'sentence_input = true' <<< "$ov" && echo yes || echo no)"
ss_open_group 自定义行为
tap_text 恢复默认
check "分组页恢复先确认，写明本组" "yes" "$(dump_xml | grep -q '「自定义行为」里改过的 1 项' && echo yes || echo no)"
tap_text 恢复
sleep 1.5
ov="$(ss_ov wubi86)"
check "分组恢复：本组的标点清掉" "no" "$(grep -q 'punct' <<< "$ov" && echo yes || echo no)"
check "分组恢复：别组的整句开关仍在" "yes" "$(grep -q 'sentence_input = true' <<< "$ov" && echo yes || echo no)"
tap_text 恢复默认
check "本组已无改动时直接提示、不弹确认" "no" "$(dump_xml | grep -q 'text="恢复"' && echo yes || echo no)"
ss_back
tap_text 恢复默认
check "第一屏恢复先确认，写明整个方案" "yes" "$(dump_xml | grep -q '本方案改过的 1 项设置' && echo yes || echo no)"
tap_text 恢复
sleep 1.5
ov="$(ss_ov wubi86)"
check "全部恢复后整句开关清掉" "no" "$(grep -q 'sentence_input' <<< "$ov" && echo yes || echo no)"
check "全部恢复后扩展词库开关仍在" "yes" "$(grep -q 'dictionaries' <<< "$ov" && echo yes || echo no)"
check "第一屏不再有「全部恢复默认」行" "no" "$(dump_xml | grep -q '全部恢复默认' && echo yes || echo no)"

# 还原五笔覆盖文件（别的用例 / 用户留下的扩展词库开关等）
if [[ -n "$SS_OV_WUBI" ]]; then
    printf '%s\n' "$SS_OV_WUBI" | "$ADB" -s "$SERIAL" shell sh -c "'mkdir -p $SS_OV_DIR && cat > $SS_OV_DIR/wubi86.toml'"
else
    adb_q shell rm -f "$SS_OV_DIR/wubi86.toml"
fi
back_to_pad
