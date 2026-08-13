# 构建 Rust 核心并接入本工程（walking skeleton 构建链）
# 前置：rustup target add aarch64-linux-android x86_64-linux-android；cargo install cargo-ndk
# 用法：pwsh scripts/build-rust.ps1 [-Profile release|debug]
param(
    [string]$Profile = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$windInput = Join-Path (Split-Path $root -Parent) "WindInput\wind_input"
$jniLibs = Join-Path $root "app\src\main\jniLibs"
$javaDir = Join-Path $root "app\src\main\java"

$env:ANDROID_NDK_HOME = "D:\Develop\SDK\AndroidSdk\ndk\28.2.13676358"

Push-Location $windInput
try {
    # 1. 交叉编译 .so（模拟器 x86_64 + 真机 arm64）
    $profileArg = if ($Profile -eq "release") { @("--release") } else { @() }
    cargo ndk -t x86_64 -t arm64-v8a -o $jniLibs build -p wind-android @profileArg

    # 2. host 构建 + 生成 Kotlin 绑定（从 host dll 读元数据）
    cargo build -p wind-android
    cargo run -p wind-android --features cli --bin uniffi-bindgen -- `
        generate --library target/debug/wind_android.dll --language kotlin --out-dir $javaDir
} finally {
    Pop-Location
}
Write-Host "OK: .so → app/src/main/jniLibs/, bindings → app/src/main/java/uniffi/"
