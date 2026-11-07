# 发版前加固的 e2e 用例（被 emu-test.sh source，依赖它的 check / check_match / section /
# tap_text / dump_xml / reset_pad / restart_pad / wait_chinese / write_kb_cfg /
# with_comment_off / ks_open / tap_ime / adb_ / adb_q 等）：
# 隐私政策页、分享诊断信息、恢复键盘设置、系统输入法子类型随方案变化。

# has_text / back_to_pad 定义在别的用例分组里，--only 只跑本文件时那些分组被滤掉、函数不存在，
# 这里用自己的一份（放在第一个 section 之前，快照总会保留）
rp_has_text() {   # 当前界面有没有这段文字（等界面稳定，最多约 4 秒）
    local try
    for ((try = 0; try < 6; try++)); do
        dump_xml | grep -q " text=\"$1\"" && { echo yes; return; }
        sleep 0.7
    done
    echo no
}
rp_back_to_pad() {   # 从设置回测试台（设置在前台时普通的 am start 拉不回来）
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.TestPadActivity"
    sleep 1
}
rp_settings() {   # 新开一个设置页并直达某一页（NEW_TASK | CLEAR_TASK，路由只在创建时读）
    adb_q shell am start -f 0x10008000 -n "$PKG/.settings.SettingsActivity" -e page "$1"
    sleep 2.5
}
rp_center() {   # 前台应用里文字为 $1 的节点中心 → "x y"
    local node
    node="$(dump_xml | sed 's/<node/\n<node/g' | grep -F " text=\"$1\"" | head -1)"
    [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]] || return 1
    echo "$(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 ))"
}
# 子类型 hashCode = SchemaSubtypes.subtypeId = Java 的 "wind-schema:<id>".hashCode()，dumpsys 里按十六进制打印
rp_sub_hash() {
    python3 -c 'import sys
h = 0
for c in "wind-schema:" + sys.argv[1]: h = (31 * h + ord(c)) & 0xffffffff
print("%x" % h)' "$1"
}
rp_cur_sub() { adb_ shell dumpsys input_method | grep -m1 'mCurrentSubtype=' | sed 's/.*@//'; }

section "[76] 关于：隐私政策页可打开，写明不联网与数据删除方式"
rp_settings about
tap_text 隐私政策
check "隐私政策页：不联网" "yes" "$(rp_has_text 不联网)"
check "隐私政策页：启用时的系统提示" "yes" "$(rp_has_text 启用时的系统提示)"
rp_back_to_pad

section "[77] 关于：分享诊断信息弹出系统分享面板"
rp_settings about
tap_text 分享诊断信息
# 先在后台读日志、拼文本，再弹面板：慢的时候要好几秒，轮询而不是睡固定时长
rp_chooser() {
    # 先整段读进变量再匹配：脚本开着 pipefail，`dumpsys | grep -m1 | grep -q` 里 grep -m1 提前关管道，
    # 上游吃 SIGPIPE，整条管道就算失败——面板明明出来了也判 no
    local i focus
    for ((i = 0; i < 10; i++)); do
        focus="$(adb_ shell dumpsys window | grep mCurrentFocus | head -1)" || true
        [[ "$focus" =~ [Cc]hooser|intentresolver|[Rr]esolver ]] && { echo yes; return; }
        sleep 1
    done
    echo "$focus" >&2
    echo no
}
check "弹出了系统分享面板" "yes" "$(rp_chooser)"
adb_q shell input keyevent BACK
sleep 1
rp_back_to_pad

section "[78] 高级：恢复键盘设置清掉 ui.android.*（点两下确认），词库与输入设置不动"
# 基线配置 + 一项改过的键盘设置；恢复后 [ui.android] 里一项都不剩
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG" | grep -vE '^[[:space:]]*haptic[[:space:]]*=' |
    awk '{ print } /^[[:space:]]*\[ui\.android\][[:space:]]*(#.*)?$/ { print "haptic = false" }')"
check "前置：写进了 haptic = false" "yes" \
    "$(adb_ shell run-as "$PKG" cat "$KB_CFG" | grep -q '^haptic = false' && echo yes || echo no)"
core_before="$(read_core_cfg)"
rp_settings advanced
read -r rx ry <<< "$(rp_center 恢复键盘设置)" || true
if [[ -n "${ry:-}" ]]; then
    # 两下之间要隔 0.6 秒以上（挡误触双击），又不能超过 5 秒（自动复位）：按坐标点，不在中间再 dump
    adb_q shell input tap "$rx" "$ry"; sleep 1.2
    adb_q shell input tap "$rx" "$ry"; sleep 2
fi
kb_after="$(adb_ shell run-as "$PKG" cat "$KB_CFG")"
check "恢复后 haptic / candidate_comment 都清掉了" "no no" \
    "$(grep -q '^haptic' <<< "$kb_after" && echo yes || echo no) $(grep -q '^candidate_comment' <<< "$kb_after" && echo yes || echo no)"
check "核心用户层（输入设置、方案）不动" "$core_before" "$(read_core_cfg)"
# 写回整套用例的基线配置
write_kb_cfg "$(with_comment_off "$SUITE_KB_ORIG")"
restart_pad
wait_chinese >/dev/null
reset_pad

section "[79] 系统输入法子类型：标签是方案名，当前子类型随方案切换"
ims="$(adb_ shell dumpsys input_method)"
check "注册了方案子类型（全拼 / 五笔）" "yes yes" \
    "$(grep -q 'mSubtypeNameOverride=全拼 ' <<< "$ims" && echo yes || echo no) $(grep -q 'mSubtypeNameOverride=五笔 ' <<< "$ims" && echo yes || echo no)"
check "当前子类型 = 五笔" "$(rp_sub_hash wubi86)" "$(rp_cur_sub)"
ks_open
tap_ime 全键盘拼音
sleep 3
check "切到全拼后当前子类型 = 全拼" "$(rp_sub_hash pinyin)" "$(rp_cur_sub)"
reset_pad
ks_open
tap_ime 五笔键盘
sleep 3
check "切回五笔后当前子类型 = 五笔" "$(rp_sub_hash wubi86)" "$(rp_cur_sub)"
check_match "切回五笔：aa 出汉字" "$CN" "$(wait_chinese)"
reset_pad
