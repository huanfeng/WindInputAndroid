# 移动端皮肤：设计

> 2026-11-24 草案（只设计，未动代码）。承接 `docs/key-mapping-skin-plan.md` §3.6 与 2026-11-24 的架构决定：
> **移动端皮肤与 PC 的 `wind-theme` 是两套完全不同的实现，只在思路上相同**——PC 有独立的三平台共用渲染架构，
> 手机端的绘制（Canvas / Bitmap / 缓存 / 深浅色 / 动态取色）完全是另一回事；求值与绘制**全放 Kotlin**，不经 FFI、不碰核心。
> 范围不止几何参数：**背景图片、定位、按键态颜色、字体**都要纳入。
>
> ⚠️ 本文**修订**了 `docs/architecture.md` §3.4「解析仍在 Rust（`wind-theme` 的 palette/resolve），Kotlin 拿到求值完的调色板」
> 与 `android-ime-features.md` §5 里主题「落点 K+R」的旧结论。落地时那两处要同步改成指向本文。
>
> 相关：`docs/key-mapping-skin-plan.md`（键位映射，与本文**数据分离**）、`docs/schema-import-plan.md`（方案包导入，本文包格式借它的守卫思路）、
> 主仓 `WindInput/docs/design/theme-editor-skin.md`、`theme-skin-animation.md`（PC 皮肤，仅作思路参考）。

## 1. 现状盘点（读代码得到的事实）

| 项 | 现状 | 位置 |
|---|---|---|
| 颜色 | `Palette` 15 个语义槽位（`background / surface / key / keySpecial / keyPressed / text / hint / accent / onAccent / accentSoft / accentText / preedit / separator / icon / border`），内置 `light` / `dark` 两套 Kotlin 常量；`Palette.current` 是全局只读 | `keyboard/Palette.kt` |
| 取色时机 | 视图**构造时**取、不监听变化；系统配置变化或设置变更时 Service **整个重建输入视图** | `Palette.kt` 类注释；`WindInputImeService.kt:222-238`、`:548-553` |
| 深浅色选择 | 设置项 `ui.android.theme`（`system / light / dark`）；「跟随系统」读 `Resources.getSystem()` 的 uiMode | `Palette.resolve` |
| 几何 | `KeyboardDimens`：`KEY_GAP_DP 2.7`、`EDGE_PAD_DP 3.4`、`KEY_RADIUS_DP 8`、`KEY_HEIGHT_DP 51`、`PAD_TOP_DP 0` 是**编译期常量**；`rowHeightDp`（竖 60 / 横 44，设置项 `row_height_*`）、`barPreeditDp 19`、`barMainRowDp 38` 是可变量；行间距**不是独立参数**，而是「行高 − 键帽高 51」的推导（`keyGapVPx`） | `keyboard/KeyboardLayout.kt:102-159` |
| 字号 | 键面 20dp（≥2 字 16dp）、角标 11dp、空格 / 小标签 12dp、中英键 17dp / 11dp，全是 `KeyboardView` 里的字面量 | `KeyboardView.kt:273-290`、`:783-817` |
| 绘制 | `KeyboardView.onDraw`：`canvas.drawColor(bgColor)`，逐键 `drawRoundRect` + `drawText`；Paint 都是成员（不在 onDraw 里分配）；按键态颜色由 `highlightKey / isAccent / isSpecial / Shift 状态` 分派 | `KeyboardView.kt:682-738` |
| 背景 | **没有统一的背景层**：根视图、条区、候选栏、键盘、单手条、面板各自刷 `Palette.current.background` 实色 | `InputRootView.kt`（init）、`BarView.kt:166/329/344/520`、`CandidateBarView.kt:66`、`KeyboardView.kt:683`、`OneHandStrip.kt:26`、`PanelScaffold.kt:71/81` |
| 弹层颜色 | 气泡、候选菜单、面板都读 `Palette.current` | `KeyPopupManager.kt`、`CandidateMenuPopup.kt`、`panels/*` |
| 定位 | 底部避让占位 View + `keyboard_bottom_gap`（0–48dp）、`keyboard_side_gap`（0–32dp）、单手模式（`OneHandStrip`） | `InputRootView.setGaps`、`OneHandStrip.kt` |
| 数据目录 | 用户层在 `Android/data/<包名>/files`（`DataDirs.userRoot`）；键盘自己的 `config.toml` / `layouts/` / `icons/` 现在放 `<userRoot>/keyboard/`（`keyboardDir`）；覆盖解析 `resolveDataFile`：user 优先、data 兜底 | `core/DataDirs.kt` |
| 目标 SDK | minSdk 26、targetSdk 36 | `app/build.gradle.kts` |

**结论**：槽位体系已经是语义命名、视图只读 `Palette.current`，所以「换颜色」的地基是好的；缺的是①槽位不够细（键面文字、候选栏、气泡、面板没有独立槽位）、
②几何是常量、③**没有统一背景层**（实色刷在十来个地方，图片背景需要先收口）、④数据来源只有两套 Kotlin 常量。

> 术语：「皮肤」= 视觉外观（颜色、几何、背景、字体）。「键位」（上滑 / 下滑 / 长按、布局）归 `keymap.toml` 与布局文件，**皮肤不碰**。

## 2. 为什么移动端皮肤不用 Rust

### 2.1 理由

| 维度 | PC `wind-theme` | 移动端 |
|---|---|---|
| 渲染 | 自有 scene / 光栅管线，三平台（Win / macOS / Linux）共用一份，Rust 求值出盒模型再画 | `android.graphics.Canvas` + `Bitmap` / `RenderNode`，由 Android 的硬件加速管线画；没有「自己的光栅器」可共用 |
| 资源 | 主题包 `.wtheme`，位图经 Rust 解码 | 解码要走 `BitmapFactory` / `ImageDecoder`（带系统的色彩管理、硬件位图、EXIF、HEIF 等）；Rust 解出来的像素还要再拷进 `Bitmap`，两头都不省 |
| 动态值 | 不涉及系统取色 | Monet 动态取色（API 31+ 的 `system_accent1_*`）、系统深浅色、`WindowInsets`、屏幕密度，**数据全在 Java/Kotlin 世界**，FFI 一层纯属多跑 |
| 热更新 | 改主题 → 重算 → 重绘 | 改皮肤 = 重建输入视图（现成机制）；解析一份几 KB 的 TOML 在 Kotlin 里是微秒到毫秒级，不需要 Rust 的性能 |
| 对核心的影响 | — | 走 FFI 就意味着改 `wind-mobile` 门面、过一遍 UniFFI 绑定重生成、**可能碰 PC**；纯 Kotlin 则**对核心零改动** |
| 测试 | Rust 单测 | 皮肤的解析 / 槽位回退 / 对比度计算是纯函数，直接 JVM 单测，不需要 `cargo` 与设备 |

### 2.2 仍然共享的只有一件事：数据格式的**字段命名与语义**

不共享代码、不共享二进制格式，只在命名上保持可互相对照，让以后「PC 主题 → 手机配色」的转换是一张映射表而不是重新设计：

| 手机槽位（`Palette` 现名） | 对应 PC `_base/theme.toml` `[colors]` | 备注 |
|---|---|---|
| `background` | `bg`（候选窗底）/ `toolbar_background` | 手机的 `background` 是键盘底色（键缝），语义更接近 PC 的 `surface` |
| `surface` | `surface` | |
| `text` | `text` | |
| `hint` | `text_hint` | |
| `preedit` | `text_dim` | |
| `accent` / `onAccent` / `accentText` | `accent` / `on_accent` / `accent_text` | 一一对应 |
| `accentSoft` | `selection` | 都是「强调色的淡底」 |
| `border` / `separator` | `border` | PC 只有一个 `border` |
| `key` / `keySpecial` / `keyPressed` / `icon` | 无 | 键盘独有 |

沿用的**思路**（不是代码）：语义命名而非色值命名；`[colors]` 值支持 `{ light, dark }` 双态与 `"${slot}"` 引用（单链、有环就报错回退）；`base` 单链继承；`[meta]`；
未识别的键**忽略并记日志**而不是整份失败（与 PC「归一化层忽略不认识的节点」一致）。

**不沿用**：PC 的视图节点白名单（`[window] [item] [preedit_bar] …`）、`[skin]` 九宫格窗体 + `decor` + 动画。那是候选窗的异形皮肤，手机键盘没有对应物（键盘是网格，不是一块可拉伸的窗体）。

## 3. 皮肤数据模型

### 3.1 总体

一个皮肤 = 一个目录（或 `.wskin` 包，见 §4）：

```
<id>/
  skin.toml          # 描述 + 颜色 + 几何 + 背景 + 字体（首行 version = 1）
  preview.png        # 缩略图（可选，设置页列表用）
  assets/            # 背景图、字体（可选）
```

解析结果是一个**不可变的 `SkinRuntime`**（Kotlin data class）：颜色已全部解成 `Int`、尺寸已全部解成 dp 浮点、位图已解码、`Typeface` 已就绪。
视图只读它，不碰 TOML、不碰文件——与今天读 `Palette.current` 一样简单，构造时取一次（沿用「整个重建」模型）。

### 3.2 颜色槽位

**原则**：现有 15 槽位**一个不改名、不改语义**（出厂外观靠它们逐字节复现，见 §7 阶段 0 的黄金测试）；新增槽位全部**可省略，缺省按回退链从现有槽位派生**，所以旧皮肤与最小皮肤都合法。

| 槽位 | 现有 / 新增 | 缺省回退 | 用在哪 |
|---|---|---|---|
| `background` | 现有 | — | 键盘底色（键缝）、条区、面板顶栏 |
| `surface` | 现有 | — | 列表行、面板内容区 |
| `key` | 现有 | — | 普通键面、气泡底、药丸底 |
| `keySpecial` | 现有 | — | 功能键面（Shift / 退格 / 切换） |
| `keyPressed` | 现有 | — | 按下高亮；Shift 开启态 |
| `text` / `hint` / `accent` / `onAccent` / `accentSoft` / `accentText` / `preedit` / `separator` / `icon` / `border` | 现有 | — | 现有用途不变 |
| `keyText` | **新增** | `text` | 普通键的主字（现在所有键共用 `text`） |
| `keySpecialText` | **新增** | `text` | 功能键的字与图标（浅色键面上的深字、深键面上的浅字可以分开调） |
| `keyHint` | **新增** | `hint` | 键面角标（上滑符号）；背景图上常需要单独加深 |
| `keyAccent` | **新增** | `accent` | 强调键面（回车 / 中英键高亮）；现在直接用 `accent` |
| `keyBorder` | **新增** | 无（不描边） | 键面描边；与 `keyShadow` 一起撑起「立体键」 |
| `keyShadow` | **新增** | 无（不画） | 键面下沿投影色（偏移固定 1dp，见 §3.3） |
| `barBackground` | **新增** | `background` | 条区（编码行 + 候选行）底色；背景图作用域为「仅键盘」时条区单独定色 |
| `candidateText` / `candidateSelected` | **新增** | `text` / `accentText` | 候选字；选中项文字 |
| `candidateComment` | **新增** | `hint` | 候选注释 |
| `popupBackground` / `popupBorder` / `popupText` / `popupSelected` | **新增** | `key` / `border` / `text` / `accentSoft` | 长按气泡、候选长按菜单 |
| `panelBackground` | **新增** | `surface` | 符号 / 表情 / 剪贴板等面板内容区 |
| `scrim` | **新增** | 无 | 背景图上的**遮罩层**颜色（含透明度），保证文字可读，见 §3.4 |

约 15 现有 + 约 17 新增。**设置页不做逐槽位编辑器**（`architecture.md` §3.7 已定：不跟 fcitx5 的 21 色槽路线）——槽位多是给「皮肤作者 / 工具站」用的，普通用户在设置页只选预设、调几个旋钮（§6）。

颜色值写法（与 PC 同源）：`"#RRGGBB"`、`"#RRGGBBAA"`（**注意 AA 在末尾**，与 `Palette` 里 `0x1A2F6FEB` 的 ARGB 顺序相反，解析器在边界处统一转成 Android 的 ARGB `Int`，单测钉死）、
`{ light = "…", dark = "…" }`、`"${slot}"` 引用。引用成环 / 指向不存在的槽位 → 该槽位回退缺省并记日志。

**动态取色**：API 31+ 提供内置皮肤「系统动态色」，由 `system_accent1_* / system_neutral1_*` 的色阶按固定映射表生成 `Palette`；API < 31 该预设不出现在列表里。它只是 `SkinRuntime` 的一个**来源**，视图层无感。

### 3.3 几何

全部以 **dp** 计，缺省值 = 今天的常量，所以不写 `[geometry]` 的皮肤外观与现在逐像素一致。

| 键 | 缺省 | 现状落点 | 说明 |
|---|---|---|---|
| `key_gap_h` | 5.4 | `KEY_GAP_DP 2.7 × 2` | 两键横向间隙（现在存的是「一半」，皮肤里存整数更直观，加载时除以 2） |
| `row_gap` | **9** | 隐式：`行高 60 − 键帽高 51` | **新增为独立参数**，取代「行高减键帽高」的推导；键帽高 = 行高 − 行间距 |
| `key_height` | 51 | `KEY_HEIGHT_DP` | 见下「约束」 |
| `key_radius` | 8 | `KEY_RADIUS_DP` | 0 = 直角，≥ 键高的一半 = 药丸形 |
| `edge_pad` | 3.4 | `EDGE_PAD_DP` | 键区离左右边缘 |
| `label_size` / `label_size_long` | 20 / 16 | `labelSizePx` | 键面主字（单字 / ≥2 字） |
| `hint_size` | 11 | `hintPaint` | 角标 |
| `hint_pos` | `top-center`（字母键）/ `top-right`（其它） | `onDraw` 的 `12dp` / `13dp` 偏移 | 取枚举：`top-center / top-right / top-left / bottom-right`；偏移量固定，不开放像素级 |
| `key_shadow_dy` | 0 | 无 | 键面下沿投影（0 = 不画）；配合 `keyShadow` 色 |
| `bar_preedit_height` / `bar_main_height` | 19 / 38 | `barPreeditDp` / `barMainRowDp` | 条区两行高度；与现有横屏收矮逻辑并存（横屏取皮肤值的收缩比） |

**约束（由设置页与解析器共同保证，而不是事后报错）**：键帽高 ≥ 40dp（触摸目标）；`key_gap_h ≥ 2`；`row_gap ≥ 2`；`key_radius` 钳到 `[0, key_height/2]`；越界值**钳制并记日志**，不拒绝整份皮肤。

**行高**（`row_height_portrait / landscape`，用户按手型调）**不属于皮肤**：它是设备 / 手型相关的用户偏好，留在 `ui.android.*`（见 §5）。皮肤给的是「键帽长什么样」，不是「键盘多高」。皮肤中的 `key_height + row_gap` 只在用户没动过行高时作为缺省。

> 与键位映射文档的协调：`key-mapping-skin-plan.md` 阶段 3「几何参数化」与本文阶段 2 是**同一件事**，只实施一次（归本文），那边只保留「键帽高度解耦后旧用户行高观感不变」的验收。

### 3.4 背景

背景是本设计里最需要先动结构的部分：现在**没有统一背景层**，实色被刷在 §1 表里列出的十来处。要支持图片 / 渐变，必须先把背景收口到**一个地方**。

**结构决定**：背景只画在 `InputRootView`（根视图）上，**一个 Drawable**；条区、候选栏、键盘、单手条、面板在「皮肤背景非纯色」时一律**透明**。
纯色背景保持现状（各视图仍刷实色，避免 overdraw 与行为变化）——这样默认皮肤的绘制路径完全不变。

作用域 `scope`：
- `window`（缺省）：背景铺满整个输入法窗口（条区 + 键盘 / 面板 + 底部避让占位），图片连续、没有接缝。底部避让区也在背景之下，手势条后面不会露出异色带。
- `keyboard`：只铺键盘区域；条区用 `barBackground` 实色，适合「条区保持干净」的皮肤。

背景类型：

```toml
[background]
scope = "window"            # window | keyboard
mode  = "image"             # color | gradient | image
color = { light = "#E0E3E9", dark = "#12151C" }     # mode=color（缺省走 colors.background）

# mode = "gradient"
# gradient = { angle = 90, stops = [ { at = 0.0, color = "#1E3C72" }, { at = 1.0, color = "#2A5298" } ] }

# mode = "image"
image          = "assets/bg.webp"            # 基础图；下面三项按需覆盖，找不到就逐级回退
image_dark     = "assets/bg_dark.webp"       # 深色
image_landscape      = "assets/bg_land.webp" # 横屏
image_landscape_dark = "assets/bg_land_dark.webp"
fit    = "cover"            # cover（缺省）| contain | stretch | tile
focus  = [0.5, 0.5]         # 裁剪焦点（0..1），cover 裁掉多余部分时保留哪一块
dim    = 0.0                # 0..1，整体压暗（叠黑色），无需改图
blur   = 0                  # 0..25，见下
alpha  = 1.0                # 图片整体不透明度（透出下面的 colors.background）
```

- **回退链**（图片选择）：`(横竖, 明暗)` → `(明暗)` → `(横竖)` → 基础图 → 无图则退化为 `mode=color`。单个文件缺失只回退这一层，不让整份皮肤失败。
- **缩放 / 裁剪**：`cover`（等比填满、按 `focus` 裁）为缺省，因为键盘窗口宽高比因机型而异（竖屏约 2:1，横屏约 6:1），固定拉伸会变形；`contain` 留边（边部露出底色）；`tile` 平铺（纹理类）；`stretch` 仅为完整性保留。
- **遮罩 `scrim`**：图片之上、键之下的一层半透明色（`colors.scrim`），保证键面字符在亮 / 暗图上都可读；用户在设置页的「亮度」旋钮就是改 `dim`，不用改图。
- **键面半透明**：键面色用 `#RRGGBBAA`（如 `key = "#FFFFFFCC"`）即可让图片从键缝与键面透出；**不做键面真实模糊**（IME 窗口无法对「窗口后面的应用」做 behind-blur，对自己的背景做实时模糊代价高、收益小）。
- **模糊 `blur`**：**加载时烘焙进位图**，不是运行时效果：缩小到 1/8 → 双线性放大回来即得到柔焦（所有 API 一致、零运行时开销）。`RenderEffect`（API 31+）能对 View 做实时模糊，但每帧重算且 API 26–30 没有，所以**不采用**；降级策略因此不存在——所有版本走同一条烘焙路径。
- **深浅色各一张 / 横竖屏各一张**：见回退链。**只解码当前需要的那一张**（§6.2），另一张不进内存。

### 3.5 定位

两类东西容易混，要分开：

1. **背景图的定位**（`fit` / `focus`）——属于皮肤，见 §3.4。
2. **键盘本身的位置**——**不属于皮肤，是用户 / 设备偏好**：左右偏移、整体宽度、底部留空、单手位置都和手型、屏幕大小、手机壳相关，换皮肤不该改它，也不该随皮肤包分发。

键盘位置的参数（都在设置里，不在 `skin.toml`）：

| 参数 | 现状 | 说明 |
|---|---|---|
| 底部留空 `keyboard_bottom_gap` | 有（0–48dp） | 不变 |
| 左右留边 `keyboard_side_gap` | 有（0–32dp） | 不变 |
| 单手模式 `one_hand` | 有（`OneHandStrip`） | 不变 |
| **键盘宽度比例 `width_scale`** | 无 | 新增：70%–100%，与 `align` 一起把「单手」从二值开关推广成连续量 |
| **对齐 `align`** | 无（单手只有左 / 右） | 新增：`left / center / right`；单手模式 = `width_scale≈0.78` + `align=left/right` 的预设 |

**浮动键盘**（可拖到屏幕任意位置的小键盘）**本期不做**，只列前置条件，避免现在的设计把路堵死：
需要 IME 窗口只占键盘实际矩形、用 `onComputeInsets` 声明可触摸区域、背景只画在键盘矩形内（而不是整窗），拖动位置要持久化且设备相关。本文把背景画在根视图 + 作用域枚举，**留了 `scope = "keyboard"` 这个口子**，浮动键盘落地时背景自然只跟着键盘矩形走。

### 3.6 字体

```toml
[font]
keys       = "assets/MyFont.ttf"     # 键面主字 / 角标；缺省系统字体
candidates = ""                      # 候选字；缺省沿用「候选字体」设置项
```

- 来源三种：系统字体（缺省）、皮肤包内置（`Typeface.createFromFile`，导入时解出到皮肤目录）、用户手放的文件（同一路径机制）。
- 范围只含键面 / 角标 / 候选字；**字根字体 `ZiGenFont` 不受影响**（它承载专用字形）。
- 中文字体体积大（常见 4–20 MB）：包内单个字体文件上限 **16 MB**（沿用 PC 主题包单图上限量级），导入预览里显示大小。
- 版权：字体随包分发有授权问题，`[meta]` 必须有 `license` 字段，导入预览显示。本项目是 GPL，**不内置任何第三方字体**，内置皮肤只用系统字体。

### 3.7 按键音与触感：**不纳入皮肤**

按键音 / 振动是个人偏好且与硬件相关（马达型号、静音习惯），随皮肤分发会出现「装个皮肤手机突然响了」。继续留在 `ui.android.sound / haptic / haptic_strength`。
`skin.toml` 解析器对 `[feedback]` 之类的节**忽略并记日志**；将来若做「音效包」，是独立的 `sound` 资源类型，不挤进皮肤。

### 3.8 内置预设

- 出厂随 APK 带 **3–5 个预设**，全部是 **TOML 描述（纯颜色 + 几何 + 渐变）、不带位图**，走与用户皮肤**同一个加载器**（吃自己的狗粮）：浅色（= 今天的 `Palette.light`）、深色（= `Palette.dark`）、两三套配色变体、API 31+ 的动态取色。
- `Palette.light / dark` 这两个 Kotlin 常量**保留**，作为加载器失败时的硬回退（皮肤文件损坏 / 解析异常 / 首次启动数据尚未拷出）——输入法任何时候都不能因为皮肤坏了而没有键盘。
- 为什么不带位图预设：见 §6.1（APK 体积）。想要图片皮肤的用户走导入。

## 4. 包格式（`.wskin`）

沿用方案包 v2 / PC 主题包 `.wtheme` 的**思路**：zip + `package.toml` 元信息 + 根条目 + `assets/`；导入先预览、再确认；校验期零落盘。区别是**守卫由 Kotlin 实现**（方案包的守卫在 Rust 核心里，皮肤不经核心）。

```
package.toml    format_version = 1 / kind = "skin" / name / author / version   （不落盘）
skin.toml       皮肤本体（根条目；识别依据）
preview.png     缩略图（可选，不落盘进皮肤目录，仅用于导入预览与列表）
assets/...      背景图、字体
```

- **扩展名** `.wskin`（与 PC 的 `.wtheme` 区分，避免用户把 PC 主题拖进手机）；权威判据是 `package.toml` 的 `kind = "skin"`，与 PC 同样「扩展名只是提示」。`kind = "theme"` 的 PC 包给出明确的「这是电脑版主题，不能直接用」提示（可选：提供颜色转换，见 §9 待决）。
- **版本门禁**：`format_version` 高于当前支持 → 拒绝并提示升级；`skin.toml` 首行 `version` 同理。两个版本号分开：包格式（zip 结构）与皮肤描述（字段）可以各自演进。
- **守卫**（全部在 Kotlin，**数实际读到的字节而不是 zip 头声明的大小**——zip 炸弹的头可以撒谎）：
  - 条目数 ≤ 64；解压后总量 ≤ 32 MiB；单图 ≤ 8 MiB；单字体 ≤ 16 MiB；`skin.toml` ≤ 256 KiB；
  - 路径穿越（`..`、绝对路径、盘符、反斜杠）、重复条目、符号链接 → 整包拒绝；
  - 文件类型白名单：图片 `png / jpg / webp`，字体 `ttf / otf`，其余忽略；**不执行、不含脚本**（皮肤是纯数据）；
  - 位图**像素数**上限（防「小文件大像素」的解压炸弹，如 1×1 万像素的压缩图）：解码前先读尺寸，超限直接拒绝，见 §6.2。
- **导入流程**：复用 `SchemeImportController` 的状态机形态（`Working → Preview → Done / Failed`，互斥锁，ViewModel 持有缓存文件）——文件选择器 / 文件关联 / 分享接收三个入口共用一个预览页：显示缩略图、名称、作者、授权、占用大小、将覆盖的同名皮肤、含字体的警告。**写盘先写临时目录再整体 rename**，失败不留半成品。
- **导出**：把皮肤目录原样打成 `.wskin`（用户 `skin_prefs` 里的调节**不进包**：那是个人偏好）。与分享 / 工具站的衔接走同一个包，不另起格式。
- **工具站**：皮肤的重编辑（改槽位、做拉伸图）在 Web 工具站做，手机端只「获取 + 应用 + 简单旋钮」，与 `architecture.md` §3.7 一致。

## 5. 文件路径与同步友好

**硬约束**：后续要做数据同步 → 移动端文件**不使用 PC 已有的文件名 / 目录名**，同步按目录区分归属。

PC 已占用（用户层 `%APPDATA%\WindInput`）：`config.toml`、`meta.toml`、`state.toml`、`wind_setting.toml`、`themes/`、`schemas/`、`schema_overrides/`、`data/`、`userdata.redb*`。

```
<userRoot>/                         Android/data/<包名>/files（= PC 的 %APPDATA%\WindInput）
  config.toml                       引擎配置（与 PC 同名同语义，由核心读写）  ← 通用
  schemas/ schema_overrides/ …      方案与词库                               ← 通用
  mobile/                           ★ 移动端独有目录（本设计与 keymap 共用）
    keymap.toml                     键位覆盖（见 key-mapping-skin-plan.md）   ← 可同步
    skin_prefs.toml                 皮肤选择与个人调节                          ← 可同步（见下）
    skins/<id>/skin.toml + assets/  用户导入 / 自制的皮肤                       ← 可同步
  keyboard/                         ⚠ 现有目录（键盘 config.toml / layouts / icons），见 §9 待决
```

**`skin_prefs.toml`**（新，移动端独有文件名；`mobile/` 下，**首行 `version = 1`**）：

```toml
version = 1
active = "default"            # 当前皮肤 id；light / dark 可分别指定：
# active_dark = "night"       # 缺省 = 与 active 相同（皮肤自带 light/dark 双态时一个就够）
[adjust."my-skin"]            # 对某个皮肤的个人调节（不改皮肤文件）
dim = 0.15                    # 背景压暗
blur = 4
key_alpha = 0.9               # 键面不透明度倍率
```

### 5.1 同步分类

| 数据 | 可同步？ | 理由 |
|---|---|---|
| `skins/<id>/`（皮肤内容） | ✅ | 与设备无关的内容；图片按设计稿基准尺寸做，运行时按屏幕缩放（§6） |
| `skin_prefs.toml` 的 `active` / `adjust.*` | ✅ | 品味类偏好，不依赖屏幕 |
| `keymap.toml` | ✅ | 逻辑配置 |
| 行高 `row_height_*`、`keyboard_bottom_gap`、`keyboard_side_gap`、`width_scale`、`align`、`one_hand` | ❌ **设备相关** | 取决于屏幕尺寸 / 手型 / 手机壳 / 手势导航高度；同步过去会把平板的行高套到小屏上。放在 `keyboard/config.toml`（现有）并在 `settings_manifest.toml` 里给这些项加 `sync = false`（清单新增一个可选字段，现有代码忽略未知字段，兼容） |
| `ui.android.haptic* / sound` | ❌ | 硬件相关（§3.7） |
| 皮肤里的 `[geometry]` | ✅ | 全是 dp 且被约束钳制（§3.3），跨设备安全；用户手调的是 `adjust.*` 倍率，不是绝对值 |

**冲突规则（给同步实现的建议，不在本期做）**：皮肤目录按 `id` + `version` 整目录同步，较新版本覆盖；`skin_prefs.toml` 按键合并、同键以后修改者为准；`id` 冲突但内容不同的皮肤（两台设备各自做了同名皮肤）保留双份、后者加后缀。

## 6. 性能与内存

### 6.1 先纠正一个约定：「34MB」是 APK 体积，不是进程内存

`android-ime-features.md` §14.15 的「R8 压缩、只带 arm64 核心库：65MB → 34MB」说的是 **APK 大小**。它对皮肤的约束是：
**不要往 APK 里塞位图**——一张 1080×2400 的 WebP 背景就 100–400 KB，几张预设就是百分之几的体积回退，而 R8 辛苦压下来的是几十 MB。所以内置预设全部纯颜色 / 渐变（§3.8），图片皮肤走用户导入。

**进程内存**是另一个预算，目前没有现成数据；下面给估算与设计，**数字待阶段 3 在设备上实测后回填**（不写没测过的结论）。

### 6.2 位图

- **按需解码、只留一张**：根据当前 `(横竖, 明暗)` 选定的那一张解码，另一张不进内存。旋转 / 深浅色切换会触发输入视图重建（现成机制），重建时换图。
- **像素上限**：解码目标尺寸 = 输入法窗口宽 × 窗口最大高度（键盘 + 条区 + 面板最大值），`cover` 之后再多的像素都是浪费。先读尺寸（`inJustDecodeBounds`），再按 `inSampleSize`（2 的幂）下采样到「不小于目标」的最小倍数；原图像素数 > 64 MP 直接拒绝。
- **格式**：不含透明度的图（jpg、无 alpha 的 png / webp）用 `RGB_565`（体积减半，背景图肉眼无差）；有 alpha 用 `ARGB_8888`。API 26+ 可申请 `Bitmap.Config.HARDWARE`，**位图驻留 GPU 内存、不占 Java 堆**，`drawBitmap` 是纯 GPU 采样；硬件位图不能 `getPixels`，所以**烘焙（模糊 / 压暗 / 裁剪）在软件位图上完成后，最后一步再转硬件位图**。部分机型 / ROM 的硬件位图有兼容性问题，转换失败回退软件位图。
- **估算**：1080×1200 的 `ARGB_8888` ≈ 5 MB，`RGB_565` ≈ 2.5 MB；这是堆（或 GPU）里一张图的量级，最坏情况（平板、横屏）线性放大——所以有上面的像素上限。
- **解码放后台线程**：在设置页「应用」时就解码并缓存成**已处理好的小文件**（`cacheDir/skin/<id>-<变体>.webp` 或原始像素缓存），输入法里加载优先读这份处理好的缓存；缓存指纹 = 皮肤文件 mtime + 调节值哈希（与词库 `cache_fp` 同思路）。冷启动首帧**不能**等一次大图解码：缓存未命中时先以纯色背景起，解码完再 `invalidate()` 一次（图片是「渐显」，不是「阻塞」）。
- **LruCache 容量 1**：只缓存当前背景；旧位图在换图时 `recycle()`（软件位图）/ 释放引用（硬件位图）。

### 6.3 绘制

- **根视图一个 Drawable 画背景**，子视图透明；键盘 `invalidate()` 只重绘键区，背景 Drawable 在硬件加速下已在 `RenderNode` 里，不会因按键高亮而重新光栅化。
- **onDraw 里零分配**（现有原则，保持）：`Paint`、`RectF`、`Path`、渐变 `Shader` 全部成员、构造时建好；颜色 / 尺寸来自 `SkinRuntime` 的 `Int` / `Float` 字段，不在绘制路径里解析。
- **新槽位的分派不能变慢**：`onDraw` 里按键态选色目前是 `when` 分支；新增 `keyText / keySpecialText` 只多两次字段读取，不引入对象。
- **阴影 / 描边**：`keyShadow` 用「同一个圆角矩形下移 1dp 画一遍」实现，不用 `setShadowLayer`（后者在硬件加速下对 `drawRoundRect` 有兼容性问题且更慢）；默认关。
- **渐变**：`LinearGradient` 在尺寸变化（`onSizeChanged`）时重建一次，不在 `onDraw` 里 new。
- **过度绘制**：纯色皮肤保持现状；图片皮肤子视图透明，整窗只画一次背景 + 一次键。

### 6.4 皮肤加载失败的兜底

任何一步失败（TOML 错误、版本过高、图片缺失 / 超限 / 解码异常、字体加载失败）：**该项回退缺省，整份皮肤继续生效**；整份不可用才回到内置 `Palette.light / dark` 并在设置页给一条可读的提示。失败原因进 `UiTrace`（诊断导出），避免「皮肤没生效但不知道为什么」。

## 7. 与既有设置项的关系与迁移

| 既有设置 | 处理 |
|---|---|
| `ui.android.theme`（`system / light / dark`） | **保留**，含义不变：决定取皮肤里的 `light` 还是 `dark` 档。皮肤自带双态时「跟随系统」仍然有效；皮肤只有一档（只写了单值）时两种模式取同一套色 |
| `ui.android.row_height_portrait / landscape`、`keyboard_bottom_gap`、`keyboard_side_gap`、`one_hand` | **保留在 `ui.android.*`**，不进皮肤（§3.3 / §3.5）；标 `sync = false` |
| 候选字体 / 候选大小（`candidate_text_sp` 等） | 保留；皮肤的 `[font].candidates` 为空时沿用 |
| `Palette.current` 全局 | **保留为兼容层**：阶段 0 把它改成「当前 `SkinRuntime` 的颜色视图」，现有所有 `Palette.current.xxx` 调用点一行不改；新代码读 `SkinRuntime`（带几何 / 背景 / 字体） |
| `KeyboardDimens` 常量 | 阶段 2 逐个变成 `SkinRuntime.geometry` 字段；面板与键盘**共用同一组参数**的约束不变（符号页键帽格子必须与键盘对齐，见 `KeyboardLayout.kt:122` 注释） |

**迁移**：新增项都有缺省且缺省 = 旧行为，**不写迁移代码**；`skin_prefs.toml` 不存在 = `active = "default"`（内置浅 / 深预设，即今天的外观）。**不设置任何皮肤的用户，升级前后键盘逐像素一致**——这是阶段 0 的验收标准。

**设置页**（只做轻量，不做槽位编辑器）：「键盘 → 外观」= 皮肤列表（缩略图 + 选择）、导入入口、当前皮肤的几个旋钮（背景亮度 `dim`、模糊 `blur`、键面透明度）、「恢复默认」；每个旋钮带实时预览（用一个只读 `KeyboardView` 嵌在 `AndroidView` 里，**需要 `KeyboardView` 接受显式的 `SkinRuntime` 参数而不是只读全局 `Palette.current`**——阶段 0 的重构点）。

## 8. 分阶段

> 每阶段单独提交；出包升版本号；验证只在 `emulator-5554`；先单测，e2e 只用 `--only` 跑受影响用例。**核心零改动**（全部在 Kotlin）。

### 阶段 0：收口与地基（不改行为）
**目标**：引入不可变 `SkinRuntime`；`Palette.current` 变成它的颜色视图；视图改为可接受显式 `SkinRuntime`（设置页预览要用）；背景收口到 `InputRootView` 的单个 Drawable，**纯色时子视图仍刷实色**（行为不变）；`skin.toml` 读取器骨架（空文件 / 版本过高 / 未知字段 / 引用成环全部不崩）。
**验收**：**黄金测试**——默认皮肤的每个槽位与每个几何值，与重构前的 `Palette.light / dark` 与 `KeyboardDimens` 常量逐项相等；模拟器上默认外观与重构前截图一致（`emu --only` 一条截图用例）。
**测试**：`SkinParserTest`（颜色写法含 `#RRGGBBAA` 的字节序、`{light,dark}`、`${}` 引用与成环、缺省回退链、未知字段忽略）；`SkinRuntimeGoldenTest`（上述黄金对照）。
**状态**：Not Started

### 阶段 1：槽位直写 + 内置预设 + 皮肤选择
**目标**：新增槽位（§3.2）接进 `KeyboardView` / `BarView` / `CandidateBarView` / 气泡 / 面板；内置 3–5 个 TOML 预设 + 动态取色（API 31+）；设置页「外观」可选择皮肤；`skin_prefs.toml` 读写。
**验收**：切预设后键盘、条区、气泡、面板同时变色且无残留旧色；深浅色跟随系统仍正确；皮肤文件手改后「重新载入」生效；故意写坏的皮肤回退到内置并有提示。
**测试**：槽位覆盖率测试（遍历 `Palette` 所有槽位确保每个都有调用点，防新增槽位无人使用）；对比度计算纯函数（文字 / 键面合成后 < 3:1 给警告，不阻塞）；预设全部能被解析。
**状态**：Complete（2026-11-24；动态取色预设按决定不做）
- 已落地：16 个新增槽位（`keyText / keySpecialText / keyHint / keyAccent / keyBorder / keyShadow / barBackground / candidateText / candidateSelected / candidateComment / popupBackground / popupBorder / popupText / popupSelected / panelBackground / scrim`），`Palette` 改 data class、新槽位缺省按回退链取现有槽位；除 `scrim`（阶段 3 背景图遮罩，现仅解析）外都已接进键盘、条区、候选、气泡、候选菜单、面板。
- `SkinStore`（发现 / 选择 / 载入，文件戳缓存）、`mobile/skin_prefs.toml`、内置预设 `assets/data/skins/{graphite,mint,sakura,paper}.toml`（石墨 / 薄荷 / 樱粉 / 纸质，与用户皮肤同一个 `SkinParser`）；设置页「外观 → 键盘皮肤」可选择与「重新载入」，坏皮肤回退默认并列出原因；`SkinContrast` 对比度警告（< 3:1，不阻塞）。
- 切换皮肤不用重启：Service 每次弹键盘 `activate()`，`SkinRuntime.active` 换实例即触发输入视图重建。
- 验证：默认皮肤键盘区与阶段 0 逐像素一致（浅 / 深）；模拟器上 mint → sakura 在进程内切换生效。
- ⚠ 阶段 0 的潜伏崩溃：`SkinParser.REF` 的裸 `}` 在 Android ICU 正则里非法（JVM 单测放行），一旦真正调用解析器就崩；已转义。JVM 单测测不到这类平台差异，所以解析器第一次上设备必须真机 / 模拟器过一遍。

### 阶段 2：几何参数化
**目标**：`KeyboardDimens` 常量变 `geometry` 字段；`row_gap` 独立（取代「行高 − 51」）；设置页滑块（键间距、行间距、圆角、字号），每个带范围、实时预览、「恢复默认」。
**验收**：默认几何下与阶段 0 截图一致；调行间距**不改变整体高度**（键帽高随之调整，条区 + 键盘高度恒等，窗口不跳）；横竖屏 / 单手 / 数字页 / 符号页同步生效且符号页键帽格子仍与键盘对齐；越界值被钳制。
**测试**：`KeyboardDimens` 取整规则（符号页与键盘共用同一组参数）；钳制规则；与行高设置的交互（用户动过行高 → 优先用户值）。
**状态**：Complete（2026-11-24）
- 已做：`KeyboardDimens.geometry`（Service 注入）取代编译期常量；`KEY_GAP_DP / EDGE_PAD_DP / KEY_RADIUS_DP / KEY_HEIGHT_DP` 变成读它的取值函数，键盘、符号页、编辑面板共用同一组数；`keyRadiusPx` 钳到键帽半高。用户旋钮（键间距 2–10、行间距 2–16、圆角 0–20、字号 80%–130%）存 `mobile/skin_prefs.toml` 的 `[geometry]`（`SkinPrefsFile`，选皮肤与调几何互不覆盖），设置页「外观 → 键盘几何」带真实尺寸预览、逐项与全部「恢复默认」；弹键盘时几何变了就重建输入视图。
- 行间距规则：**没固定**时沿用旧推导「行高 − 键帽高 51（至少 2dp）」，旧用户调过行高的观感不变；**固定**后间隙 = 它、键帽高 = 行高 − 间隙（不低于 36dp，旧版横屏行高下限 36 所以不卡 40），整体高度不变。
- 与设计的出入：皮肤文件里的 `row_gap` 仍只作缺省行高的组成（不单独触发「固定」）；字号只有倍率旋钮，皮肤 `label_size / hint_size / hint_pos` 与 `bar_*` 高度未参数化；用户值存的是绝对 dp / 倍率（非 `adjust.*`）。
- 设备验证：默认几何下键盘区与阶段 1 逐像素一致（浅 / 深色）；写入非默认 `[geometry]` 后键盘变圆、间距变大、字号放大，键盘顶边位置不变（总高不变）。符号页 / 数字页 / 横屏 / 单手的实机对齐未逐一看（逻辑共用 KeyboardDimens，单测覆盖取值）。

### 阶段 3：背景与定位
**目标**：背景三种模式（纯色 / 渐变 / 图片）；`scope`；`fit / focus / dim / blur / alpha / scrim`；深浅色、横竖屏回退链；位图管线（尺寸上限、下采样、`RGB_565`、烘焙模糊、硬件位图、处理后缓存、渐显）；键盘 `width_scale` + `align`；`[font]`。
**验收**：图片背景铺满且按键可读；旋转、深浅色切换换图正确、无闪白；**内存与首帧时间在设备上实测并回填本文 §6.2**；`scope=keyboard` 时条区独立；解码失败回退纯色；超限图被拒绝。
**测试**：下采样倍数 / 目标尺寸 / 回退链选择 / 缓存指纹——全是纯函数，JVM 单测；位图管线用 instrumented 或 emu 一条用例看真实解码；以及一条「大图 + 低内存」的压力用例。
**状态**：Not Started

### 阶段 4：包导入导出与分享
**目标**：`.wskin` 的预览 / 导入 / 删除 / 导出；文件关联（`.wskin`）、分享接收；守卫（§4）；导入预览页；皮肤导出。
**验收**：导入合法包可预览、应用、删除；穿越路径 / 超限 / zip 炸弹 / 版本过高 / 缺 `skin.toml` / `kind` 不对 全部被如实拒绝且**不落盘**；同 id 覆盖有二次确认；导出的包可被另一台设备导入。
**测试**：守卫负例全套（合成小包，参照 `rust/wind-android/tests/scheme_import.rs` 的负例清单与 `SchemeImportFilesTest`）；状态机（参照 `SchemeImportStateTest`）；e2e 用 `am start -a VIEW` 驱动一条。
**状态**：Not Started

### 阶段 5（远期，不排期）
按键贴图（`[key.shape]` 位图 + 九宫格切片，用 `drawBitmap(src, dst)` 九次，零分配）；动画；浮动键盘；PC 主题颜色转换（§9）。

## 9. 风险与待决问题

**风险**
- **可读性**：图片背景 + 半透明键是最容易做出「看不清字」的皮肤。对策：`scrim` / `dim` 旋钮、导入预览与设置页对比度警告；缺省作者提示。**不阻塞**（用户有权做丑皮肤），但警告要可见。
- **OEM 兼容**：硬件位图、窗口透明、`RGB_565` 在个别 ROM 上表现不一，与 B8（底部空白）同类——所以失败要走 §6.4 回退并写进诊断导出，而不是静默。
- **首帧延迟**：键盘对首帧极敏感（现有 15ms 首屏的成果）；图片解码绝不能在主线程阻塞首帧，§6.2 的「缓存 + 渐显」是硬要求。
- **与键位映射的耦合**：两者共享 `mobile/` 目录与「版本号 + 容错读取」规则，但**数据互不引用**；`KeyboardView` 同时被两个特性改动，建议**阶段 0（本文）先于键位映射阶段 1**，减少冲突面。
- **字体授权**：随包分发字体有版权风险；强制 `license` 字段 + 导入预览显示，内置皮肤不带字体。
- **`skin.toml` 命名**：避免叫 `theme.toml`（PC 同名文件语义不同，同步工具按文件名识别时会混）。`key-mapping-skin-plan.md` 里「`theme.toml` 15 槽位」的旧写法要改为 `skin.toml`。

**已定（2026-11-24）**
1. **目录**：选 B，已落地。`<userRoot>/keyboard/` 整体迁到 `<userRoot>/mobile/`，键盘的 `config.toml` 改名 `keyboard.toml`（`MobileDirMigration`，不覆盖已有文件、幂等、带单测）。同步按目录区分归属，不再有与 PC 同名的移动端文件。
2. **内置预设**：用 TOML 资源，经与用户皮肤同一个加载器——与以后 iOS 端复用。**图片等位图资源一律独立成文件**（放在皮肤目录的 `assets/` 下，`skin.toml` 只写相对路径引用），不内嵌进 TOML（不用 base64）。
3. **PC 主题颜色导入**：不做。主题编辑器后续会直接提供专门的移动版皮肤输出。
4. **`.wskin` 文件关联**：待阶段 4 再定（预览前按 `package.toml` 的 `kind` 分流）。
5. **皮肤可改字号等**：允许（`label_size`、`hint_size`、`hint_pos` 等），钳制范围在阶段 2 定。
6. **动态取色预设**：待定，不阻塞阶段 1。
7. **同步**：走 WebDAV / S3 同步配置；PC 与移动端的部分输入数据会合并。本文只需保证**结构与目录不与 PC 冲突**，不预设通道、不设计同步协议。设备相关的值（行高、留空、单手、振动）标 `sync = false`，仍按此约定。
