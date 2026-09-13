#!/usr/bin/env bash
# 一键跑完整条测试链（Linux）：Rust 单测 → 交叉编译 → APK → 模拟器 → 端到端
#
# 分层刻意如此：**能在 Rust 层复现的问题不要拿到模拟器上试错**。
# 模拟器一轮端到端要几分钟，而 key_flow.rs 里同一条按键序列几秒就有结论。
# 所以 rust 阶段失败就直接停，不往下走。
#
# 用法：scripts/test-all.sh [--skip-emu] [--skip-rust] [-s <serial>]
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKIP_EMU=0; SKIP_RUST=0; SERIAL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-emu) SKIP_EMU=1; shift ;;
        --skip-rust) SKIP_RUST=1; shift ;;
        -s|--serial) SERIAL="$2"; shift 2 ;;
        -h|--help) sed -n '1,10p' "$0"; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

G=$'\e[32m'; R=$'\e[31m'; C=$'\e[36m'; N=$'\e[0m'
step() { echo; echo "${C}── $* ──${N}"; }
die() { echo "${R}✗ $*${N}" >&2; exit 1; }

if (( ! SKIP_RUST )); then
    step "1/4 Rust 单元测试（host）"
    ( cd "$ROOT/rust" && cargo test -p wind-android ) || die "Rust 测试失败，先在这层修"
fi

step "2/4 Rust 交叉编译 + UniFFI 绑定"
"$ROOT/scripts/build-rust.sh" release || die "交叉编译失败"

step "3/4 构建 Debug APK"
( cd "$ROOT" && ./gradlew :app:assembleDebug ) || die "APK 构建失败"

if (( SKIP_EMU )); then
    echo; echo "${G}✓ 构建链全通（按 --skip-emu 跳过端到端）${N}"
    exit 0
fi

step "4/4 模拟器端到端"
"$ROOT/scripts/emu-up.sh" || die "模拟器起不来"
emu_args=()
[[ -n "$SERIAL" ]] && emu_args+=(-s "$SERIAL")
# ⚠ 不要图快加 --skip-install：那个开关跳过的是「构建 **和** 安装」两件事，
# 设备上装的会是上一轮的旧 APK，测试照跑不误——改了代码却测了旧包，是最坏的假通过。
# 上一步已 assembleDebug，这里 gradle 走 UP-TO-DATE，几秒而已。
"$ROOT/scripts/emu-test.sh" "${emu_args[@]}" || die "端到端测试有失败用例"

echo; echo "${G}✓ 全链通过${N}"
