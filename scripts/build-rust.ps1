# 构建 Rust 核心并接入本工程
# 前置：rustup target add aarch64-linux-android x86_64-linux-android；cargo install cargo-ndk
# 用法：pwsh scripts/build-rust.ps1 [-CargoProfile release|debug]
#
# 编译对象是**本仓的** rust/wind-android（UniFFI 绑定层）。输入法内核在主仓，
# 经 wind-mobile 门面以 path 依赖引入——因此**两仓必须并列存放**：
#   <workspace>/WindInput          主仓（核心 + wind-mobile 门面）
#   <workspace>/WindInputAndroid   本仓（Kotlin 壳 + wind-android 绑定层）
# 主仓不在隔壁时 cargo 会报 path 依赖找不到，那不是环境问题，是目录摆错了。
param(
    # ⚠ 不能叫 $Profile：那是 PowerShell 内置自动变量（配置文件路径），
    # 用它作参数名会让判断静默跑偏、编译不执行却仍打印成功。
    [ValidateSet("release", "debug")]
    [string]$CargoProfile = "release"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$rustDir = Join-Path $root "rust"
$jniLibs = Join-Path $root "app\src\main\jniLibs"
$javaDir = Join-Path $root "app\src\main\java"

$env:ANDROID_NDK_HOME = "D:\Develop\SDK\AndroidSdk\ndk\28.2.13676358"

Push-Location $rustDir
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
        "--language", "kotlin", "--out-dir", $javaDir, "--no-format"  # 与 build-rust.sh 同：不随本机 ktlint 格式化
    )
    cargo @bindgenArgs
    if ($LASTEXITCODE -ne 0) { throw "bindgen 失败" }
} finally {
    Pop-Location
}
Write-Host "OK: .so → app/src/main/jniLibs/, bindings → app/src/main/java/uniffi/"
