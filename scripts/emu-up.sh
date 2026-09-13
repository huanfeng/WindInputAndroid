#!/usr/bin/env bash
# 拉起无头 Android 模拟器并等到可用（Linux 服务器专用，Windows 侧直接开 AVD Manager）
#
# ⚠ 前提：**容器/宿主必须有 /dev/kvm**。这台机器是 PVE 上的 LXC 容器，
# 默认不透传 KVM；没有它模拟器会退化成纯软件 CPU 模拟（开机十几分钟、点击无响应），
# 表现为「脚本卡在等 boot」，而不是报错——所以这里开机就硬检查一次。
# 宿主机上（PVE 8.1+ 的设备透传，对**非特权容器**同样有效）：
#   pct set <CTID> --dev0 /dev/kvm,gid=<容器内 kvm 组 gid>,mode=0660 && pct reboot <CTID>
# 再在容器内 `sudo usermod -aG kvm $USER` 并重新登录。
#
# ⚠ 老教程里的 lxc.mount.entry + lxc.cgroup2.devices.allow 对非特权容器**不够**：
# uid 映射会让 bind 进来的设备节点变成 nobody:nogroup，容器内无写权限——
# /dev/kvm 看着在、模拟器就是起不来。下面的 -w 检查专门拦这种半吊子状态。
#
# 无显卡服务器上的两条优化（见下方 args 处注释）：软件渲染走 SwiftShader，
# 以及**开机快照**——冷启动近 50s，之后从快照恢复只要几秒。默认走快照，
# 要干净环境加 --cold。
#
# 用法：scripts/emu-up.sh [-n windinput-test] [--recreate] [--window] [--cold]
set -euo pipefail

AVD_NAME="windinput-test"
IMAGE="system-images;android-36;google_apis;x86_64"
DEVICE="pixel_6"
RECREATE=0
WINDOW=0
COLD=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--name) AVD_NAME="$2"; shift 2 ;;
        --recreate) RECREATE=1; shift ;;
        --window) WINDOW=1; shift ;;   # 有 X/VNC 时才用得上
        --cold) COLD=1; shift ;;       # 丢掉快照重新开机
        -h|--help) sed -n '1,22p' "$0"; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"

if [[ ! -e /dev/kvm ]]; then
    cat >&2 <<'EOF'
错误：/dev/kvm 不存在，模拟器无法硬件加速。
这不是可以「慢一点跑」的情况——纯软件模拟下开机要十几分钟、UI 点击几乎不响应，
端到端测试会全线超时假失败。请先在 PVE 宿主机上给本容器透传 KVM（见本文件头注释）。
EOF
    exit 1
fi
[[ -w /dev/kvm ]] || { echo "错误：/dev/kvm 存在但当前用户无写权限（把用户加进 kvm 组）" >&2; exit 1; }

AVD_DIR="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$AVD_NAME.avd"
snap_dir="$AVD_DIR/snapshots/default_boot"
# ⚠ 判据是 snapshot.pb 而不是目录在不在：模拟器带 -no-snapshot-save 退出时，
# 保存请求被忽略，**目录却已经建好了**。拿 -d 判断会认定「有快照」，于是传参走
# 加载路径，模拟器加载失败后默默退回冷启动——看到的是「明明该几秒却还是等了一分钟」，
# 而日志里那句 "does not have the requested snapshot" 混在几百行 INFO 中间。
has_snapshot() { [[ -s "$snap_dir/snapshot.pb" ]]; }

if (( RECREATE )); then
    "$AVDMANAGER" delete avd -n "$AVD_NAME" >/dev/null 2>&1 || true
fi
# ⚠ 别用 `avdmanager list avd` 判断存在性：它会去 **fetch remote repository**，
# 在代理后面能挂十几分钟，而且一声不吭——现象是「脚本卡住，模拟器日志还是上一轮的」，
# 看起来像模拟器起不来，其实卡在这条联网探测上。目录在不在是本地事实，看目录。
if [[ ! -d "$AVD_DIR" ]]; then
    echo "创建 AVD: $AVD_NAME ($IMAGE)"
    echo no | "$AVDMANAGER" create avd -n "$AVD_NAME" -k "$IMAGE" -d "$DEVICE" --force
fi

# AVD 调优。写成幂等的、每次启动都跑一遍，不只在创建时——否则早先建好的 AVD
# 永远停在旧参数上，"改了脚本却没变快"查起来毫无头绪。
tune_avd() {
    local cfg="$AVD_DIR/config.ini"
    [[ -f "$cfg" ]] || return 0
    local before; before="$(cat "$cfg")"
    sed -i '/^hw\.ramSize/d; /^disk\.dataPartition\.size/d; /^hw\.keyboard/d;
            /^hw\.cpu\.ncore/d; /^vm\.heapSize/d' "$cfg"
    {
        # 默认 2G 内存 / 800M 数据分区对跑词库的输入法偏紧；机器有 256G，给足。
        echo "hw.ramSize=4096"
        echo "disk.dataPartition.size=8G"
        # 硬件键盘必须开：emu-test.sh 靠 `adb shell input text` 之外的键盘事件，
        # 且配合 show_ime_with_hard_keyboard=1 才是被测过的那条路径。
        echo "hw.keyboard=yes"
        # 默认 4 核。软件渲染下 CPU 是唯一的瓶颈，但 Android 模拟器过 8 核收益递减。
        echo "hw.cpu.ncore=8"
        # 默认 228M。词库加载大，堆紧会把时间花在 GC 上（[10] 首键延迟用例盯的就是这个）。
        echo "vm.heapSize=512M"
    } >> "$cfg"

    # ⚠ 快照把**硬件配置一起固化**了：改完 config.ini 却从旧快照恢复，跑的还是旧规格，
    # 「改了核数内存却一点没变快」就是这么来的，而且没有任何提示。配置一变就作废快照。
    if [[ "$before" != "$(cat "$cfg")" ]] && has_snapshot; then
        echo "AVD 配置有变，作废旧快照（本次冷启动重建）"
        rm -rf "$snap_dir"
    fi
}
tune_avd

if "$ADB" devices | grep -q "emulator-.*device$"; then
    echo "已有模拟器在跑：$("$ADB" devices | grep 'emulator-.*device$')"
    exit 0
fi

# ── 无显卡服务器上的取舍 ───────────────────────────────────────────────
# swiftshader_indirect = 宿主端 SwiftShader 软件渲染，不碰任何 GPU。新版 emulator
# 的 `-help-gpu` 已不列这个名字，但仍被接受——别以为没生效，日志里认这一行：
#   emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swiftshader
# 关掉的都是这台机器上纯属开销的东西：netsim 的 WiFi/UWB 包流、SIM、被动 GPS、
# 网络限速模拟、遥测上报。输入法测试一样不用。
args=(-avd "$AVD_NAME" -gpu swiftshader_indirect -accel on
      -no-audio -no-boot-anim -no-metrics
      -netdelay none -netspeed full -no-sim -no-passive-gps
      -feature -WiFiPacketStream -feature -Uwb)
(( WINDOW )) || args+=(-no-window)

if (( COLD )); then
    args+=(-no-snapshot-load -no-snapshot-save)
    echo "冷启动（--cold）"
elif has_snapshot; then
    echo "从快照恢复（要干净环境用 --cold）"
else
    # 还没有快照：这次必然冷启动，开机后存一份，下次就快了
    args+=(-no-snapshot-load)
    echo "首次冷启动，开机后将保存快照"
fi

# 从快照恢复的瞬间系统时间会跳变，watchdog 常据此判定 SystemUI 无响应，弹出 ANR
# 对话框。它盖在最前面，之后任何 uiautomator dump 拿到的都是这个弹窗——端到端测试里
# 表现为「找不到测试输入框」，跟输入法毫无关系，能查很久。认 aerr_close 这个按钮 id。
dismiss_anr() {
    local xml node
    "$ADB" shell uiautomator dump /sdcard/anr.xml >/dev/null 2>&1 || return 0
    xml="$("$ADB" shell cat /sdcard/anr.xml 2>/dev/null | tr -d '\r')" || return 0
    [[ "$xml" == *'android:id/aerr_close'* ]] || return 0
    while IFS= read -r node; do
        [[ "$node" == *'android:id/aerr_close'* ]] || continue
        if [[ "$node" =~ bounds=\"\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]\" ]]; then
            echo "清掉 ANR 对话框（快照恢复常带）"
            "$ADB" shell input tap $(( (BASH_REMATCH[1] + BASH_REMATCH[3]) / 2 )) \
                                   $(( (BASH_REMATCH[2] + BASH_REMATCH[4]) / 2 )) >/dev/null 2>&1 || true
            sleep 2
            return 0
        fi
    done < <(printf '%s' "$xml" | sed 's/<node/\n<node/g')
}

echo "启动模拟器（headless，日志 → /tmp/emulator-$AVD_NAME.log）..."
nohup "$EMULATOR" "${args[@]}" > "/tmp/emulator-$AVD_NAME.log" 2>&1 &

"$ADB" wait-for-device
# sys.boot_completed 只说内核起来了；package manager 没就绪时 `ime enable` 会静默失败。
# 两个都等，比 sleep 60 快也稳。
# ⚠ 这两条赋值必须自带兜底。`set -e` 下 `x="$(cmd)"` 的退出码就是 cmd 的退出码，
# 开机途中 adb 随时会抖一下（device offline / 服务还没起），一次非零就把整个脚本
# 打断在这里——表现是「脚本几秒就返回了，却没打印就绪」，而模拟器还在后台慢慢开机，
# 下一步 adb install 撞上半启动的系统，错得面目全非。
for _ in $(seq 1 120); do
    boot="$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r[:space:]')" || boot=""
    pm="$("$ADB" shell pm path android 2>/dev/null | tr -d '\r')" || pm=""
    if [[ "$boot" == "1" && -n "$pm" ]]; then
        echo "模拟器就绪：$("$ADB" devices | grep emulator | head -1)"
        # ⚠ 必须排在存快照**之前**：否则弹窗被一起存进快照，之后每次恢复都带着它。
        dismiss_anr
        if (( ! COLD )) && ! has_snapshot; then
            echo "保存开机快照（下次启动跳过冷启动）..."
            "$ADB" emu avd snapshot save default_boot >/dev/null 2>&1 || true
            # 命令返回 0 不代表存下来了——保存被忽略时它照样 OK。认落盘的文件。
            if has_snapshot; then
                echo "快照已存（$(du -sh "$snap_dir" | cut -f1)）"
            else
                echo "快照未落盘，下次仍是冷启动（不影响本次使用）" >&2
            fi
        fi
        exit 0
    fi
    sleep 5
done
echo "等待开机超时，见 /tmp/emulator-$AVD_NAME.log" >&2
exit 1
