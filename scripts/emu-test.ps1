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
    # 第 2 行两侧各有半键占位（_SP_，不可点）
    @{ keys = @("_SP_","a","s","d","f","g","h","j","k","l","_SP_"); weights = @(0.5,1,1,1,1,1,1,1,1,1,0.5) },
    @{ keys = @("SHIFT","z","x","c","v","b","n","m","BS"); weights = @(1.5,1,1,1,1,1,1,1,1.5) },
    # 底栏权重须与 assets/data/layouts/qwerty.toml 同步：123 · 逗号句号合一 · 空格 · 中英 · 回车
    @{ keys = @("SYM","COMMA","SPACE","LANG","ENTER"); weights = @(2,1,4,1,2) }
)

$script:geo = $null

function Init-Geometry {
    $size = ((Adb shell wm size) -replace '.*:\s*','').Trim()
    $w, $h = $size -split 'x'
    $density = [double](((Adb shell wm density) -replace '.*:\s*','').Trim()) / 160.0
    $rowPx = [int][math]::Round(56 * $density)     # KeyboardDimens.rowHeightDp 默认值
    # 条区只有主行（BarView.MAIN_ROW_DP）；编码栏是浮层不占高度，故 codeBarPx = 0
    $codeBarPx = 0
    $mainRowPx = [int][math]::Round(44 * $density)
    # 底部 insets（手势条/导航栏）：InputRootView 的 bottomSpacer 高度
    $insetPx = [int][math]::Round(48 * $density)
    $kbBottom = [int]$h - $insetPx
    $kbTop = $kbBottom - 4 * $rowPx
    $script:geo = @{
        W = [int]$w; H = [int]$h
        Density = $density
        RowPx = $rowPx
        KbBottom = $kbBottom
        KbTop = $kbTop
        # 候选行在条区下半：条区顶 = kbTop-(编码栏+主行)，候选行中心再下移半个主行
        BarY = $kbTop - [int]($mainRowPx / 2)
        # 编码栏中心（组合中才可见）
        CodeBarY = $kbTop - $mainRowPx - [int]($codeBarPx / 2)
        # 面板 header 中心：面板打开时**条区整个让位**，面板从条区位置起画，
        # 其 header 与条区等高（BarView.TOTAL_DP）。写成 kbTop+xx 会点进键盘区。
        PanelHeaderY = $kbTop - $codeBarPx - $mainRowPx + [int](($codeBarPx + $mainRowPx) / 2)
        # 返回图标中心：header 左侧固定 52dp 宽
        PanelBackX = [int](26 * $density)
    }
    Write-Host "几何: ${w}x${h} density=$density 行高=$rowPx 键盘顶=$kbTop 候选y=$($script:geo.BarY) 编码栏y=$($script:geo.CodeBarY)" -ForegroundColor DarkGray
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

# 点第 n 项并把上屏文本读回来（读完清空），供「首屏候选有哪些」这类取样用
function Tap-CandidateText([int]$n) {
    Tap-Candidate $n
    $t = Get-PadText
    Reset-Pad
    Tap-Key "a"
    Start-Sleep -Milliseconds 500
    return $t
}

# IME 窗口高度（px）。uiautomator 看不到 IME 窗口，只能从 window inset 取。
function Get-ImeHeight {
    $line = Adb shell dumpsys window displays 2>$null |
        Select-String 'mType=ime .*mInsetsHint=Insets\{[^}]*bottom=(\d+)' |
        Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value }
    return 0
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

# 轮询重试：`force-stop` 后 Activity 要重建，固定 sleep 在慢设备（真机首启、
# 模拟器负载高时）不够，dump 会拿到还没布局好的层级。
function Get-EditBounds {
    # 真机上最常见的「重试 12 次仍无」不是布局慢，而是**屏幕休眠或通知栏挡着**：
    # Activity 明明是 mFocusedApp，dump 却拿不到节点，日志里只有十几行
    # 「intent has been delivered to currently running top-most instance」，
    # 看起来像 IME 坏了。先唤醒再说，省得又去查引擎。
    #   adb shell dumpsys power | grep mWakefulness    → Dozing 就是它
    #   adb shell dumpsys window | grep mCurrentFocus  → NotificationShade 就是它
    Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Adb shell cmd statusbar collapse | Out-Null
    Adb shell wm dismiss-keyguard | Out-Null

    for ($try = 0; $try -lt 12; $try++) {
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
        Start-Sleep -Milliseconds 700
        Adb shell am start -n "$pkg/.settings.TestPadActivity" | Out-Null
    }
    throw "找不到测试输入框（重试 12 次仍无）"
}

function Reset-Pad {
    Adb shell am start -n "$pkg/.settings.TestPadActivity" | Out-Null
    Start-Sleep -Seconds 2
    # 清空：全选 + 删除（比重启 Activity 快，且不会掉 IME）
    $b = Get-EditBounds
    Adb shell input tap $b.CX $b.CY | Out-Null
    Start-Sleep -Milliseconds 800
    Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
    # 次数要够长：命令短语用例会上屏 36 字符的 UUID，删不干净会污染下一个用例
    #（表现为下一条断言"实得"里带着上一条的残字，极易误判成新 bug）。
    for ($i = 0; $i -lt 40; $i++) { Adb shell input keyevent KEYCODE_DEL | Out-Null }
    Start-Sleep -Milliseconds 300
    if ((Get-PadText) -ne "") { throw "Reset-Pad 未清空输入框，后续断言不可信" }
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
# 坑 2：force-stop 会让系统切回 Gboard。**enable 与 set 都必须排在 force-stop 之后**
# ——`pm clear` 还会把输入法重置成未启用，enable 若排在前面就被一起清掉了，
# 表现为「脚本说当前输入法是 Gboard」。
Adb shell am force-stop $pkg | Out-Null
Start-Sleep -Seconds 1
Adb shell ime enable $ime | Out-Null
Adb shell ime set $ime | Out-Null
# 系统写 secure setting 有延迟，轮询确认而不是睡固定时长
$cur = ""
for ($i = 0; $i -lt 10; $i++) {
    Start-Sleep -Milliseconds 500
    $cur = (Adb shell settings get secure default_input_method).Trim()
    if ($cur -like "$pkg*") { break }
    Adb shell ime set $ime | Out-Null
}
if ($cur -notlike "$pkg*") { throw "当前输入法不是本应用: $cur" }
# 清日志：用例 10 靠 logcat 判性能，读到上一轮的旧告警会误报
Adb logcat -c | Out-Null
Init-Geometry

# 活跃方案快照：跑完要比对（见文件末尾）。
# **方案选择是持久化的**，而面板上任何一个点错的坐标都可能把它改掉——改完当次用例
# 照样 PASS（不过是换了个中文方案，断言只看「是不是汉字」），污染留给**下一次**运行，
# 现象是「昨天好好的今天全挂」。故把「跑完方案没变」单列成一条不变量来守。
function Get-ActiveSchema {
    $line = Adb shell run-as $pkg cat files/rust/config/WindInput/config.toml 2>$null |
        Select-String '^active = ' | Select-Object -First 1
    if ($line) { return $line.ToString().Trim() }
    return ""   # 首次运行尚未落盘，或非 debug 包没有 run-as
}
$script:schemaBefore = Get-ActiveSchema

# ── 建立前置条件：确保处于中文方案 ──────────────────────────────────────
# **方案选择是持久化的**（引擎会把 schema.active 写进用户配置）。上一次运行若在
# 英文用例中途失败，英文方案就留在那儿了，下次启动仍是英文——于是所有中文用例
# 连同系统短语一起失败，看起来像功能坏了。测试必须自己把前置条件建起来。
Write-Host "`n[0] 前置：确保中文方案" -ForegroundColor Yellow
# 打一次中文探针；出汉字返回该字，否则返回空。
function Probe-Chinese {
    Reset-Pad
    Tap-Key "a"; Tap-Key "a"; Tap-Key "SPACE"
    $t = Get-PadText
    if ($t -match '^[一-龥]$') { return $t }
    return ""
}

# ⚠ **核心是异步构造的**（DeferredSession），未就绪时按键交还宿主、字母直接上屏。
# 那个结果（'aa '）与「当前不是中文方案」**完全一样**，此前这里把两者混为一谈：
# 重装后核心还没起来，[0] 就断定"非中文方案"去点中英键，**把输入法切进了英文**，
# 于是 [1]~[11] 的中文用例连环失败——失败现象在别处，根因是这一个判据。
# 先重试等就绪，重试到底仍不出汉字，才认为真是方案/模式不对。
function Wait-Chinese([int]$tries = 6) {
    for ($i = 0; $i -lt $tries; $i++) {
        $t = Probe-Chinese
        if ($t) { return $t }
        Start-Sleep -Seconds 2
    }
    return ""
}

$probe = Wait-Chinese
if (-not $probe) {
    Write-Host "  等不到中文候选，判定为非中文模式，点中英键切回" -ForegroundColor DarkYellow
    Reset-Pad
    Tap-Key "LANG"
    Start-Sleep -Seconds 4
    $probe = Wait-Chinese 3
}
CheckMatch "已处于中文方案" '^[一-龥]$' $probe

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

Write-Host "`n[7] 系统短语（命令直通车）" -ForegroundColor Yellow
Reset-Pad
# uuid 是出厂命令短语（display = {uuid()} 求值结果）。探针不用 date：
# date 的五笔码表候选太多，短语被挤到第二页。
Tap-Key "u"; Tap-Key "u"; Tap-Key "i"; Tap-Key "d"
Tap-Candidate 0
$got = Get-PadText
# 首选未必是短语，只断言「打得出东西」+ 不崩；短语是否在首位属排序策略
CheckMatch "短语码可上屏" '.+' $got

Write-Host "`n[8] 条区总高恒定（编码栏出现不顶高键盘）" -ForegroundColor Yellow
# ⚠ 编码栏本身**无法用 uiautomator 断言**：IME 窗口不进 dump（实测 dump 只有宿主
# Activity 的 9 个节点）。改测它必须守住的那条约束——组合态出现编码栏时，IME 窗口
# 总高不能变，否则每次起手打字键盘都会跳一下。高度取自 dumpsys 的 ime inset。
Reset-Pad
$idleH = Get-ImeHeight
Tap-Key "a"; Tap-Key "a"
$composingH = Get-ImeHeight
if ($idleH -gt 0 -and $idleH -eq $composingH) {
    Write-Host "  PASS  空闲/组合两态窗口同高 ${idleH}px" -ForegroundColor Green
} else {
    Write-Host "  FAIL  窗口高度跳变：空闲 ${idleH}px → 组合 ${composingH}px" -ForegroundColor Red
    $script:failed++
}
Tap-Key "BS"; Tap-Key "BS"

Write-Host "`n[9] 快捷面板往返" -ForegroundColor Yellow
# 同样绕开 dump：验证「打开面板 → 键盘让位 → 返回 → 键盘恢复」这条往返。
#
# ⚠ 不要用「敲原 'a' 键位应无反应」来验证面板已顶上：那个坐标落在面板的方案选择
# 区，实测正好点中「英」格，测试自己把引擎切成了英文模式，污染后面所有用例。
# 面板上任何坐标都可能是功能键，只点自己放的「返回」才安全。
Reset-Pad
# 工具栏只有两格：最左是快捷面板入口，宽 BarView.TOOLBAR_ICON_DP(52dp)，点它的中心。
# ⚠ 这里曾按「6 个等宽图标」算成 W/12；布局改了必须同步这行。
Adb shell input tap ([int](26 * $script:geo.Density)) $script:geo.BarY | Out-Null
Start-Sleep -Milliseconds 800
$panelH = Get-ImeHeight
# ⚠ 坐标取自 $geo.PanelHeaderY/PanelBackX，别自己算：面板打开时条区让位、面板
# 从条区位置起画，header 不在 kbTop 之下而在其**之上**。此处曾写成 kbTop+20dp，
# 点进了键盘区 → 面板没返回 → 后续用例的点击全落在面板上，级联成多个假失败。
Adb shell input tap $script:geo.PanelBackX $script:geo.PanelHeaderY | Out-Null
Start-Sleep -Seconds 2
Tap-Key "a"; Tap-Key "a"; Tap-Key "SPACE"
CheckMatch "返回后键盘可用" '^[一-龥]$' (Get-PadText)
if ($panelH -le 0) {
    Write-Host "  FAIL  面板期间取不到 IME 窗口高度" -ForegroundColor Red
    $script:failed++
}

Write-Host "`n[10] 首键延迟（词库预热）" -ForegroundColor Yellow
# 惰性构建的反查/合并索引若没被后台预热，会同步阻塞用户首键（实测 2.8s）。
# 阈值取 100ms 而非一帧：16ms 级抖动是 GC/调度噪声，卡顿量级才是要守的。
$slow = Adb shell "logcat -d -s RustSession:W" 2>$null |
    Select-String "阻塞主线程 (\d+)µs" |
    ForEach-Object { [int]$_.Matches[0].Groups[1].Value } |
    Where-Object { $_ -gt 100000 }
if ($slow) {
    Write-Host "  FAIL  首键阻塞主线程 $($slow -join ', ')µs" -ForegroundColor Red
    $script:failed++
} else {
    Write-Host "  PASS  无 100ms 级主线程阻塞" -ForegroundColor Green
}

Write-Host "`n[11] 中英切换键" -ForegroundColor Yellow
# 底栏中英键：切英文后字母直出，再点回中文恢复出汉字。
# ⚠ 目前英文走的是**系统英文模式**（无候选）。英文方案带补全尚未打通
#（候选走英文引擎、上屏走中文码表），见主仓 key_flow.rs 里标 ignore 的那条用例。
Reset-Pad
Tap-Key "LANG"
# 切换要走引擎，等待给足
Start-Sleep -Seconds 4
Tap-Key "h"; Tap-Key "i"; Tap-Key "SPACE"
$en = Get-PadText
CheckMatch "英文模式出拉丁字符" '^[A-Za-z ]+$' $en

Tap-Key "LANG"
Start-Sleep -Seconds 3
Reset-Pad
Tap-Key "a"; Tap-Key "a"; Tap-Key "SPACE"
$backToCn = Get-PadText
CheckMatch "切回中文出汉字" '^[一-龥]$' $backToCn
# ⚠ 收尾必须回到中文：方案选择持久化，留在英文会污染**下一次运行**的所有中文用例
if ($backToCn -notmatch '^[一-龥]$') {
    Write-Host "  切回失败，强制再切一次以免污染下次运行" -ForegroundColor DarkYellow
    Reset-Pad; Tap-Key "LANG"; Start-Sleep -Seconds 4
}

Write-Host "`n[12] 快速点击的按下反馈" -ForegroundColor Yellow
# 「点得快就没有按下效果」的回归。`input tap` 的 down/up 间隔近乎为零，是最坏情况：
# 两次 invalidate 落在同一帧内被合并，若不做保底，按下态一帧都不会被绘制。
# KeyboardView 在高亮结束时自检绘制帧数，未绘制即打 W 日志。
Reset-Pad
Adb logcat -c | Out-Null
for ($i = 0; $i -lt 10; $i++) { Adb shell input tap 60 $script:geo.KbTop | Out-Null }
Start-Sleep -Milliseconds 800
$missed = (Adb shell "logcat -d -s WindKeyboard:W" 2>$null | Select-String "一帧未绘制").Count
if ($missed -eq 0) {
    Write-Host "  PASS  10 次极限连点均绘制出按下态" -ForegroundColor Green
} else {
    Write-Host "  FAIL  $missed 次按下高亮未被绘制" -ForegroundColor Red
    $script:failed++
}
Reset-Pad

Write-Host "`n[13] 候选栏滚动后可选中第 10 个以后的候选" -ForegroundColor Yellow
# 此前点选靠**合成页内数字键 1-9**，第 10 个及以后永远点不到，候选栏做了滚动也是空转
# （一帧只有 6~9 条）。现在候选按窗口拉取、按绝对下标选词。
#
# 判据刻意宽松——只断言「上屏了一个汉字，且不是首屏那几个」：具体是哪个字取决于
# 滚动距离与字宽，钉死某个字会变成脆弱用例。要守的是「够得着远处的候选」。
Reset-Pad
Tap-Key "a"                       # 单码 a：五笔重码极多，必然远超一屏
Start-Sleep -Milliseconds 600
$near = @()
for ($i = 0; $i -lt 3; $i++) { $near += (Tap-CandidateText $i) }
# 甩两把把候选栏滚远（起点留出右边距，避开展开钮）
for ($i = 0; $i -lt 2; $i++) {
    Adb shell input swipe ([int]($script:geo.W * 0.75)) $script:geo.BarY 60 $script:geo.BarY 120 | Out-Null
    Start-Sleep -Milliseconds 700
}
Adb shell input tap ([int]($script:geo.W * 0.4)) $script:geo.BarY | Out-Null
Start-Sleep -Milliseconds 700
$far = Get-PadText
CheckMatch "滚动后点选出汉字" '^[一-龥]+$' $far
if ($far -and $near -contains $far) {
    Write-Host "  FAIL  滚动后选到的仍是首屏候选 '$far'（滚动没生效或仍受页内 9 项限制）" -ForegroundColor Red
    $script:failed++
} elseif ($far) {
    Write-Host "  PASS  选到的是首屏之外的候选 => '$far'（首屏为 $($near -join '/')）" -ForegroundColor Green
}

Write-Host "`n[14] 跑完不改变活跃方案" -ForegroundColor Yellow
$schemaAfter = Get-ActiveSchema
if ($script:schemaBefore -eq "") {
    Write-Host "  SKIP  首次运行，无基线可比" -ForegroundColor DarkGray
} else {
    Check "活跃方案未被用例改动" $script:schemaBefore $schemaAfter
}

Write-Host "`n================================" -ForegroundColor Cyan
if ($script:failed -eq 0) { Write-Host "全部通过" -ForegroundColor Green; exit 0 }
Write-Host "$($script:failed) 项失败" -ForegroundColor Red
exit 1
