# 联想自动隐藏遇到触屏操作要暂停（被 emu-test.sh source，依赖它的 check / section / die / restart_pad / wait_chinese /
# reset_pad / tap_key / adb_q / adb_ / PKG / GEO_BARY 等）。
#
# 真机反馈：联想出来 5 秒后自动收起（hide_after_ms），手指正在候选条上滑动、长按看提示卡时
# 也照收不误。现在按住条区、展开面板开着、提示卡 / 菜单弹着时暂停计时，都结束后重新计满 5 秒。
#
# 判据：五笔 q 上屏「我」后，联想里有下一词「的」（[49] 同一判据）。

# ime_nodes 定义在 [42]，--only 只跑本文件时不存在，这里用自己的一份（放在第一个 section 之前）
ah_assoc_shown() {   # 候选条上有联想「的」→ yes / no
    adb_q shell uiautomator dump --windows /sdcard/a11y.xml
    adb_ shell cat /sdcard/a11y.xml \
        | grep -oE 'class="android.widget.Button" package="'"$PKG"'" content-desc="[^"]*"' \
        | sed -E 's/.*content-desc="([^"]*)"/\1/' \
        | grep -qE '^的(，|$)' && echo yes || echo no
}
ah_enter_assoc() {
    reset_pad
    tap_key q; tap_key SPACE
    sleep 0.5
}

section "[92] 联想：手指按在候选条上滑动超过 5 秒不收，松手后重新计时再收"
# 前面的用例文件（快捷输入的设置页）可能把设置页留在测试台同一任务的前台，先重开测试台
restart_pad
reset_pad
[[ -n "$(wait_chinese)" ]] || die "中文方案未就绪"
ah_enter_assoc
check "上屏后出联想" "yes" "$(ah_assoc_shown)"
# 按下后马上横移超过触摸阈值（取消长按），按住 6.5 秒再抬手；往右拖时候选条已在最左，不会滚走
adb_q shell input motionevent DOWN 200 "$GEO_BARY"
adb_q shell input motionevent MOVE 280 "$GEO_BARY"
sleep 6.5
adb_q shell input motionevent UP 280 "$GEO_BARY"
check "按住期间联想没被计时收掉" "yes" "$(ah_assoc_shown)"
check "按住滑动不上屏" "我" "$(get_pad_text)"
sleep 6
check "松手后约 5 秒照常收起" "no" "$(ah_assoc_shown)"

section "[93] 联想：长按候选弹出提示卡，卡开着超过 5 秒联想不收；收起卡片后重新计时再收"
# 提示卡开着时无障碍导出读不到候选条的节点（卡片的遮罩盖在条区上），所以先收卡再看联想在不在
ah_enter_assoc
adb_q shell input swipe 60 "$GEO_BARY" 60 "$GEO_BARY" 900
sleep 6.5
# 点条区右半边：落在卡片遮罩上，只收卡片、不选候选
adb_q shell input tap 700 "$GEO_BARY"
sleep 0.5
check "提示卡开着期间联想没被计时收掉" "yes" "$(ah_assoc_shown)"
check "长按、收卡都不上屏" "我" "$(get_pad_text)"
sleep 6
check "收起卡片后约 5 秒照常收起" "no" "$(ah_assoc_shown)"
reset_pad
