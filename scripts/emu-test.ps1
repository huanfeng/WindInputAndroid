# 模拟器端到端输入测试：环境准备 → 点击键盘按键 → 断言输入框内容
#
# ── 三个必踩的坑（都已在脚本里处理，改动前先读）────────────────────────
# 1. 模拟器带硬件键盘，InputMethodService.onEvaluateInputViewShown() 默认返回
#    false → 软键盘视图**从不创建**。必须 settings put secure
#    show_ime_with_hard_keyboard 1。
# 2. `am force-stop` 掉当前 IME 时，系统会把默认输入法切回 Gboard。之后所有
#    点击都是 Gboard 在响应（表现为"输入法坏了"）。force-stop 后必须重新 ime set。
# 3. 输入框为空时 uiautomator dump 会把 hint 当作 text 输出，据此断言会误判。
#    脚本按 hint 属性剔除。
#
# 键盘按键是 Canvas 自绘的，uiautomator 看不到按键节点，只能按坐标点击；
# 坐标由布局比例算出（与 KeyboardLayouts / KeyboardDimens 一致），布局改了要同步这里。
#
# 用法：pwsh scripts/emu-test.ps1 [-Serial emulator-5554] [-SkipInstall]
param(
    [string]$Serial = "emulator-5554",
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$adb = "D:\Develop\SDK\AndroidSdk\platform-tools\adb.exe"
$pkg = "com.windinput.ime"
$ime = "$pkg/.service.WindInputImeService"

function Adb { & $adb -s $Serial @args }

# ── 键盘布局：与 KeyboardLayouts.qwerty 保持一致 ────────────────────────
$rows = @(
    @{ keys = @("q","w","e","r","t","y","u","i","o","p"); weights = @(1,1,1,1,1,1,1,1,1,1) },
    @{ keys = @("a","s","d","f","g","h","j","k","l");     weights = @(1,1,1,1,1,1,1,1,1) },
    @{ keys = @("SHIFT","z","x","c","v","b","n","m","BS"); weights = @(1.5,1,1,1,1,1,1,1,1.5) },
    @{ keys = @("SYM","COMMA","SPACE","PERIOD","ENTER");   weights = @(1.5,1,4,1,1.5) }
)

$script:geo = $null

function Init-Geometry {
    $size = ((Adb shell wm size) -replace '.*:\s*','').Trim()
    $w, $h = $size -split 'x'
    $density = [double](((Adb shell wm density) -replace '.*:\s*','').Trim()) / 160.0
    $rowPx = [int][math]::Round(56 * $density)     # KeyboardDimens.rowHeightDp 默认值
    $barPx = [int][math]::Round(44 * $density)     # BarView 高度
    # 底部 insets（手势条/导航栏）：InputRootView 的 bottomSpacer 高度
    $insetPx = [int][math]::Round(48 * $density)
    $kbBottom = [int]$h - $insetPx
    $script:geo = @{
        W = [int]$w; H = [int]$h
        RowPx = $rowPx
        KbBottom = $kbBottom
        KbTop = $kbBottom - 4 * $rowPx
        BarY = $kbBottom - 4 * $rowPx - [int]($barPx / 2)
    }
    Write-Host "几何: ${w}x${h} density=$density 行高=$rowPx 键盘顶=$($script:geo.KbTop) 候选条y=$($script:geo.BarY)" -ForegroundColor DarkGray
}

function Tap-Key([string]$name) {
    $g = $script:geo
    for ($r = 0; $r -lt $rows.Count; $r++) {
        $idx = [array]::IndexOf($rows[$r].keys, $name)
        if ($idx -lt 0) { continue }
        $weights = $rows[$r].weights
        $total = ($weights | Measure-Object -Sum).Sum
        $unit = $g.W / $total
        $x = 0.0
        for ($i = 0; $i -lt $idx; $i++) { $x += $weights[$i] * $unit }
        $cx = [int]($x + $weights[$idx] * $unit / 2)
        $cy = [int]($g.KbBottom - ($rows.Count - $r - 0.5) * $g.RowPx)
        Adb shell input tap $cx $cy | Out-Null
        Start-Sleep -Milliseconds 400
        return
    }
    throw "未知键位: $name"
}

# 点候选条第 n 项（0 起，按等宽粗估；候选窄时够用）
function Tap-Candidate([int]$n) {
    $g = $script:geo
    Adb shell input tap ([int](60 + $n * 120)) $g.BarY | Out-Null
    Start-Sleep -Milliseconds 500
}

function Dump-Xml {
    Adb shell uiautomator dump /sdcard/wt.xml 2>&1 | Out-Null
    return (Adb shell cat /sdcard/wt.xml)
}

# 读输入框内容；输入框为空时 dump 会把 hint 填进 text，需剔除
function Get-PadText {
    $xml = Dump-Xml
    foreach ($node in ($xml -split '<node')) {
        if ($node -notmatch 'content-desc="windinput-testpad"') { continue }
        $text = if ($node -match '\stext="([^"]*)"') { $Matches[1] } else { "" }
        $hint = if ($node -match '\shint="([^"]*)"') { $Matches[1] } else { "" }
        if ($text -eq $hint) { return "" }   # 显示的是 hint = 实际为空
        return $text
    }
    return ""
}

function Get-EditBounds {
    $xml = Dump-Xml
    foreach ($node in ($xml -split '<node')) {
        if ($node -match 'content-desc="windinput-testpad"' -and
            $node -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            return @{
                CX = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
                CY = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
            }
        }
    }
    throw "找不到测试输入框"
}

function Reset-Pad {
    Adb shell am start -n "$pkg/.settings.TestPadActivity" | Out-Null
    Start-Sleep -Seconds 2
    # 清空：全选 + 删除（比重启 Activity 快，且不会掉 IME）
    $b = Get-EditBounds
    Adb shell input tap $b.CX $b.CY | Out-Null
    Start-Sleep -Milliseconds 800
    Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
    for ($i = 0; $i -lt 12; $i++) { Adb shell input keyevent KEYCODE_DEL | Out-Null }
    Start-Sleep -Milliseconds 300
}

$script:failed = 0
function Check([string]$case, [string]$expected, [string]$actual) {
    if ($actual -eq $expected) {
        Write-Host "  PASS  $case => '$actual'" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $case 期望 '$expected' 实得 '$actual'" -ForegroundColor Red
        $script:failed++
    }
}
function CheckMatch([string]$case, [string]$pattern, [string]$actual) {
    if ($actual -match $pattern) {
        Write-Host "  PASS  $case => '$actual'" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $case 期望匹配 /$pattern/ 实得 '$actual'" -ForegroundColor Red
        $script:failed++
    }
}

# ── 环境准备 ──────────────────────────────────────────────────────────
if (-not $SkipInstall) {
    Write-Host "构建并安装..." -ForegroundColor Cyan
    Push-Location $root
    try { & "$root\gradlew.bat" :app:assembleDebug | Out-Null } finally { Pop-Location }
    Adb install -r "$root\app\build\outputs\apk\debug\app-debug.apk" | Out-Null
}
# 坑 1：模拟器有硬件键盘 → 不显示软键盘视图
Adb shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
Adb shell ime enable $ime | Out-Null
# 坑 2：force-stop 会让系统切回 Gboard，必须重新 set（顺序不能颠倒）
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell ime set $ime | Out-Null
Start-Sleep -Seconds 1
$cur = (Adb shell settings get secure default_input_method).Trim()
if ($cur -notlike "$pkg*") { throw "当前输入法不是本应用: $cur" }
Init-Geometry

Write-Host "`n[1] 五笔组合上屏：aa + 空格" -ForegroundColor Yellow
Reset-Pad
Tap-Key "a"; Tap-Key "a"; Tap-Key "SPACE"
CheckMatch "上屏汉字" '^[\u4e00-\u9fa5]$' (Get-PadText)

Write-Host "`n[2] 连续两轮组合（候选每轮都要出）" -ForegroundColor Yellow
Reset-Pad
Tap-Key "a"; Tap-Key "a"; Tap-Key "SPACE"
Tap-Key "a"; Tap-Key "a"; Tap-Key "SPACE"
CheckMatch "两轮各上屏一字" '^[\u4e00-\u9fa5]{2}$' (Get-PadText)

Write-Host "`n[3] 空缓冲功能键" -ForegroundColor Yellow
Reset-Pad
Tap-Key "SPACE"
Check "空缓冲空格出空格" " " (Get-PadText)
Tap-Key "BS"
Check "退格删除" "" (Get-PadText)

Write-Host "`n[4] 中文标点" -ForegroundColor Yellow
Reset-Pad
Tap-Key "COMMA"
CheckMatch "标点上屏" '^[，,]$' (Get-PadText)

Write-Host "`n[5] 候选点选" -ForegroundColor Yellow
Reset-Pad
Tap-Key "a"; Tap-Key "a"
Tap-Candidate 1
CheckMatch "点选第二个候选" '^[\u4e00-\u9fa5]$' (Get-PadText)

Write-Host "`n[6] 组合中退格删码" -ForegroundColor Yellow
Reset-Pad
Tap-Key "a"; Tap-Key "a"; Tap-Key "BS"; Tap-Key "SPACE"
CheckMatch "删一码后上屏" '^[\u4e00-\u9fa5]$' (Get-PadText)

Write-Host "`n================================" -ForegroundColor Cyan
if ($script:failed -eq 0) { Write-Host "全部通过" -ForegroundColor Green; exit 0 }
Write-Host "$($script:failed) 项失败" -ForegroundColor Red
exit 1
