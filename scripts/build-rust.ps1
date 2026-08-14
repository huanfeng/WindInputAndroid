# 构建 Rust 核心并接入本工程（walking skeleton 构建链）
# 前置：rustup target add aarch64-linux-android x86_64-linux-android；cargo install cargo-ndk
# 用法：pwsh scripts/build-rust.ps1 [-Profile release|debug]
param(
    # ⚠ 不能叫 $Profile：那是 PowerShell 内置自动变量（配置文件路径），
    # 用它作参数名会让判断静默跑偏、编译不执行却仍打印成功。
    [ValidateSet("release", "debug")]
    [string]$CargoProfile = "release"
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
    #
    # ⚠ 参数一律拼进**一个数组**再整体 splat，不要写成 `... build -p x @profileArg`。
    # PowerShell 的 `if` 会把单元素数组解包成标量：`$p = if (…) { @("--release") }`
    # 得到的是 String 而非 Array，而 `@字符串` 是**按字符 splat**——实测传给 cargo 的
    # 是 `- - r e l e a s e` 八个参数，报错只有一句 `unexpected argument '-' found`，
    # 从错误信息完全看不出源头在这行。
    $ndkArgs = @(
        "-t", "x86_64", "-t", "arm64-v8a", "-o", $jniLibs,
        "build", "-p", "wind-android"
    )
    if ($CargoProfile -eq "release") { $ndkArgs += "--release" }
    cargo ndk @ndkArgs
    if ($LASTEXITCODE -ne 0) { throw "cargo ndk 失败（exit $LASTEXITCODE）" }
    # redb 声明了 cdylib，cargo-ndk 会一并拷出，APK 不需要
    Remove-Item -Force -ErrorAction SilentlyContinue "$jniLibs\*\libredb-*.so"

    # 2. host 构建 + 生成 Kotlin 绑定（从 host dll 读元数据）
    cargo build -p wind-android
    if ($LASTEXITCODE -ne 0) { throw "host build 失败" }
    # 参数一次写全：backtick 续行遇到 `--` 会被 PowerShell 解析器吃掉一段
    $bindgenArgs = @(
        "run", "-p", "wind-android", "--features", "cli", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", "target/debug/wind_android.dll",
        "--language", "kotlin", "--out-dir", $javaDir
    )
    cargo @bindgenArgs
    if ($LASTEXITCODE -ne 0) { throw "bindgen 失败" }
} finally {
    Pop-Location
}
Write-Host "OK: .so → app/src/main/jniLibs/, bindings → app/src/main/java/uniffi/"
