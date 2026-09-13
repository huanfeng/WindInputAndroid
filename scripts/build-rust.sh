#!/usr/bin/env bash
# 构建 Rust 核心并接入本工程（Linux/macOS 版，Windows 见 build-rust.ps1）
#
# 前置：rustup target add aarch64-linux-android x86_64-linux-android；cargo install cargo-ndk
# 用法：scripts/build-rust.sh [release|debug]
#
# 编译对象是**本仓的** rust/wind-android（UniFFI 绑定层）。输入法内核在主仓，
# 经 wind-mobile 门面以 path 依赖引入——因此**两仓必须并列存放**：
#   <workspace>/WindInput          主仓（核心 + wind-mobile 门面）
#   <workspace>/WindInputAndroid   本仓（Kotlin 壳 + wind-android 绑定层）
# 主仓不在隔壁时 cargo 会报 path 依赖找不到，那不是环境问题，是目录摆错了。
set -euo pipefail

profile="${1:-release}"
case "$profile" in
    release|debug) ;;
    *) echo "用法: $0 [release|debug]" >&2; exit 2 ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
rust_dir="$root/rust"
jni_libs="$root/app/src/main/jniLibs"
java_dir="$root/app/src/main/java"

# NDK 路径：优先环境变量，否则从 SDK 里挑最高版本。写死版本号会在 NDK 升级后
# 静默失败（cargo-ndk 报的是「找不到 linker」，看不出根因在这里）。
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/sdk}}"
    ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
    [[ -n "$ANDROID_NDK_HOME" ]] || { echo "找不到 NDK：设 ANDROID_NDK_HOME 或装 \$ANDROID_HOME/ndk/*" >&2; exit 1; }
fi
export ANDROID_NDK_HOME
echo "NDK: $ANDROID_NDK_HOME"

cd "$rust_dir"

# 1. 交叉编译 .so（模拟器 x86_64 + 真机 arm64）
ndk_args=(-t x86_64 -t arm64-v8a -o "$jni_libs" build -p wind-android)
[[ "$profile" == "release" ]] && ndk_args+=(--release)
cargo ndk "${ndk_args[@]}"

# redb 声明了 cdylib，cargo-ndk 会一并拷出，APK 不需要
rm -f "$jni_libs"/*/libredb-*.so

# 2. host 构建 + 生成 Kotlin 绑定（从 host .so 读元数据）
cargo build -p wind-android
cargo run -p wind-android --features cli --bin uniffi-bindgen -- \
    generate --library target/debug/libwind_android.so \
    --language kotlin --out-dir "$java_dir"

echo "OK: .so → app/src/main/jniLibs/, bindings → app/src/main/java/uniffi/"
