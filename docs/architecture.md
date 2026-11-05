# WindInput Android 架构设计

> 状态：已确认（2026-08-13）
> 结论来源：对主仓 `WindInput`（Rust 核心 + Windows TSF + macOS IMKit）的架构探索与移植分析。
> 配套文档：[upstream-coordinator-refactor.md](upstream-coordinator-refactor.md)（主仓前置改造）

## 0. 总体决策

**采用「Rust 核心复用 + Kotlin 原生壳」的混合架构**，不用 Kotlin 重写输入逻辑。

- 主仓 19 个 crate 中 13 个为零平台依赖纯逻辑，可直接交叉编译到 Android。
- 拼音引擎（词图 + Viterbi 整句 + 双拼/模糊音/简拼）、wdat mmap 词库、五层复合词典、
  redb 用户词频等核心资产复用，避免双端逻辑漂移。
- 配置格式统一（多端同步的前提）由复用 `wind-config` 直接达成：同一份 TOML、
  同一套三层合并语义、同一个 REGISTRY 字段真源。

与桌面版的本质差异：桌面是「瘦平台层 + IPC + 独立服务进程（含服务端渲染）」，
Android 上 IME 就是 `InputMethodService` 进程本身，因此 **IPC 层与服务端渲染层
整体不复用**，复用边界下移到逻辑层，通过 JNI 进程内直调。

## 1. 分层架构

```
┌─────────────────────────────────────────────────────┐
│ 设置 App（Kotlin + Compose，miuix 风格）              │
│   读写用户层 config.toml / 主题选择 / 词库管理          │
├─────────────────────────────────────────────────────┤
│ IME 层（Kotlin，InputMethodService）                  │
│   生命周期、InputConnection 执行编辑指令、EditorInfo     │
│   场景感知、directInput、句首大写、方案选择记忆         │
├─────────────────────────────────────────────────────┤
│ 键盘 UI（Android 原生渲染：自定义 View + Canvas）       │
│   软键盘布局、按键触摸、候选条、长按气泡、震动/音效       │
├──────────────── JNI（UniFFI 生成绑定）────────────────┤
│ wind-android（**本仓** rust/，cdylib → .so）          │
│   只做类型投影：核心类型 ↔ UniFFI Record/Enum          │
├───────────────── 唯一一条依赖边 ─────────────────────┤
│ wind-mobile（主仓门面，纯 Rust 无绑定框架）             │
│   会话门面：press(PressKey, KeySource) 进 →           │
│   候选/状态/编辑指令出；按键翻译与自动空格在 touch.rs    │
├─────────────────────────────────────────────────────┤
│ 复用的 Rust 核心（主仓 wind_input workspace）          │
│   wind-coordinator（headless）+ wind-engine /        │
│   wind-dict / wind-store / wind-config / ...         │
└─────────────────────────────────────────────────────┘
```

## 2. 模块复用清单

| 主仓模块 | 处置 | 说明 |
|---|---|---|
| `wind-engine`（拼音/码表/混输/英文） | ✅ 直接复用 | 零平台依赖 |
| `wind-dict`（wdat mmap、复合词典） | ✅ 直接复用 | `cfg(windows)` 部分自动排除；memmap2 支持 Android |
| `wind-store`（redb 用户词/词频） | ✅ 直接复用 | redb 纯 Rust |
| `wind-config`（三层配置、REGISTRY SSOT） | ✅ 直接复用 | 目录解析注入 Android 路径 |
| `wind-candidate` / `wind-phrase` / `wind-punct` / `wind-quick-input` / `wind-reverse` / `wind-transform` / `wind-cmdbar` / `wind-transfer` | ✅ 直接复用 | 纯逻辑 |
| `wind-keys`（VK 常量 SSOT、NavKeys） | ✅ 复用定义 | 软键盘按键 → VK 码的映射基准；`key_inject`/`capslock_hook` 不用 |
| `wind-coordinator`（按键优先级链、状态机） | 🔧 改造复用 | headless 构造器已存在；需主仓前置改造摆脱 wind-ui 依赖，见配套文档 |
| `wind-theme`（TOML 主题解析） | 🔧 部分复用 | 调色板/继承/求值复用；视图节点扩展键盘域 |
| `wind-ipc` / `wind-bridge` / `wind-rpc` | ❌ 不用 | 进程内直调，无 IPC（wind-bridge 的 `MessageHandler` trait 语义保留为 FFI API 的形状参考） |
| `wind-ui`（tiny-skia 光栅化 + 桌面窗口） | ❌ 不用 | 渲染全部 Android 原生 |
| `apps/service` | ❌ 不用 | 服务形态换成 InputMethodService 内嵌库 |
| 软键盘 UI / 布局系统 / 设置 App | 🆕 Kotlin 新写 | 移动端特有，工作量大头 |

## 3. 关键设计决策

### 3.1 FFI 接缝：`wind-mobile` 门面 + 本仓 `wind-android` 绑定层

> **决策已变更**（原方案：`wind-android` crate 放主仓，「Rust 代码留在主仓」）。
> 变更理由见下方「为什么把绑定层搬进本仓」。

- **主仓** `wind-mobile`：移动端形态的门面，纯 Rust、**不含任何绑定框架**。三层配置
  加载、已装方案扫描、预热闸门、候选窗口、吃键判定转发都在这里——判据是「换成 iOS
  还成不成立」，成立的就留主仓。
- **本仓** `rust/wind-android`（`crate-type = ["cdylib"]`）：只做三件 Android 专属的事
  ——类型投影（核心类型 ↔ UniFFI Record/Enum）、日志接 logcat、产物形态与绑定生成。
  它对主仓**只有一条 path 依赖**（`wind-mobile`），因此两仓必须并列存放。
- 绑定用 **UniFFI** 自动生成 Kotlin（回调接口、错误传递均为生成代码，避免手写 JNI）。
- API 形状参考 `wind_bridge::MessageHandler` trait（key_event、focus_gained/lost 等
  与 `onStartInput`/`onFinishInput`/键事件几乎一一对应）。
- 平台行为由 `InputProfile` 决定（移动端 Profile 与桌面分开，不再靠 `cfg!`）。
- 正向通道：软键和物理键统一经 `MobileCore::press(PressKey, KeySource)` 进核心。按键翻译
  （美式键盘 VK 表）、英文自动补空格、句末标点换位、触摸数字不选候选都在主仓
  `wind-mobile/src/touch.rs`；返回的 `KeyOutcome.ops` 已含补的空格与换位，Kotlin 只按序执行。
  `consumed=false` 时走宿主默认行为。
- 反向通道：核心对移动端输出中立的 `SessionEvent`（`View` / `Hide` / `Status` / `Notice`），
  `wind-mobile` 的泵线程消费后转成 `MobileEventSink` 回调，绑定层再投影到 UniFFI callback
  interface 推给 Kotlin，由原生 UI 渲染。
- Kotlin 保留的宿主能力：`directInput`（密码框等直出，**此时绝不调用 `press`**，核心的英文
  判断含密码框强制英文）、`EditorInfo` 场景、句首自动大写、方案选择记忆。

#### 为什么把绑定层搬进本仓

原方案让 `wind-android` 住在主仓，直接 path 依赖**七个**核心 crate
（coordinator/host/bridge/config/ipc/keys/ui-types）。两个问题：

1. **绑定层焊在核心的内部结构上**。核心一次内部重构就打断安卓构建，而安卓仓在另一条
   CI 上，断了往往过几天才发现。收敛成 `wind-mobile` 一条边后，核心怎么重构由门面吸收。
2. **平台代码散在两个仓**。Kotlin 壳在本仓、FFI 层在主仓，改一个特性要跨仓提交两次，
   历史也对不起来。

搬迁代价（**已知且接受**）：

- 主仓 CI 的 Android 闸门从「wind-android 全包」降级为「wind-mobile 全包」，**不再覆盖
  FFI 绑定层自身**（uniffi 宏 / cdylib / tracing-android 的 cfg 分支）。这一件改由本仓
  `scripts/build-rust.ps1` 真正交叉编译 `.so` 时把关——比 check 更强，但反馈晚一个仓。
- 两仓各有 `Cargo.lock` 与 `target/`，同一批依赖会编译两遍。
- 本仓不能单独 clone 后构建，必须与主仓并列存放。

### 3.2 事件模型：软键盘点按经 `press` 合成为 VK 键事件

软键盘每个键位以 `PressKey`（字符/空格/退格/回车）报给核心，核心在 `touch.rs` 译成
`wind-keys` 的 VK 码，喂入 `handle_key_event` 优先级链——协调器的候选翻页、加词、
临时英文、标点、模式切换逻辑全部保留。硬件键盘（平板/蓝牙）经 `onKeyDown` 走同一条路，
仅 `KeySource` 不同（物理键盘的数字照电脑习惯选候选）。

### 3.3 配置：同一格式 + 新增平台域

- 三层合并（代码默认 → 出厂 `data/config.toml` → 用户层）、REGISTRY SSOT、
  守护测试全部不变。
- 通用逻辑域（`[schema]`、`[input]`、`[keys]`）原样生效；桌面 `[ui.*]` 在 Android
  无落点，新增 `[ui.android]`（或 `[keyboard]`）域：布局选择、键高、震动、
  滑动输入开关等。
- 平台门控沿用 macOS 已验证机制：设置清单（settings manifest）的 `platform` 字段
  过滤；Android 设置页建议由同一份清单驱动生成 Compose 页面骨架。
- `compat.toml` 按进程名规则表可直接收录 Android 包名（小写全等匹配，
  `wechat.exe` 与 `com.tencent.mm` 可并存同表）。
- 目录结构映射：`filesDir/data/`（出厂拷贝）与 `filesDir/user/`（用户层），
  复用 `resolve_data_file` 用户覆盖机制（用户目录同名文件整体替代）。
- 未来同步的数据面：用户层 config.toml + `wind-transfer` 的导入导出/备份通道。

### 3.4 主题：调色板共享 + 形态分端

- **共享层**：`[colors]` 调色板（语义色 + `${var}` 递归引用 + light/dark 双态）、
  `base` 单链继承、`[meta]`。主题的「身份」三端同一份。
- **分端层**：视图节点白名单扩展键盘域节点（`keyboard` / `key` / `key.pressed` /
  `popup` / `candidate_bar` 等）；桌面端归一化层忽略不认识的节点即可。
- 解析仍在 Rust（`wind-theme` 的 palette/resolve），Kotlin 拿到求值完的调色板与
  盒模型参数。`Dim`（dp/px/%）paint 期换算的设计天然适配 Android 多密度屏。

### 3.5 词库：发布 yaml 原文，端上编译（协议合规决策）

> ⚠️ 部分词库文件为 GPL 协议，必须以原始形态原封不动分发，
> 因此 **不采用**「构建期预编译 wdat 打进 APK」方案。

- APK assets 内放**原封不动的 yaml 源文件**（连同 LICENSE/来源清单）。
- 首启把 yaml 从 assets 拷到 `filesDir`（mmap 与编译器都要求真实文件路径），
  在设备上执行 yaml → wdat 编译，产物为运行时派生缓存（与桌面
  `%LOCALAPPDATA%\WindInput\cache` 同性质）。
- 复用整条既有链路：`cached.rs`（yaml→wdat 编译）+ `cache_fp.rs`（内容指纹
  SipHash sidecar 判缓存有效，App 升级词库未变则不重编）。
- **分级编译**缓解首启耗时：按 `base_order` 先编基础词库（键盘立即可用），
  ext 等在后台线程续编，经 `DictManager::set_layer_enabled` 层热插拔上线；
  编译放前台服务/WorkManager 防中途被杀；首启页给进度。
- 设置页做「开源许可」页，集中展示各词库协议文本与来源。
- 附带收益：用户自定义词库/第三方词库导入与桌面同路径。

### 3.6 渲染：键盘用 View + Canvas，设置用 Compose

- 键盘本体：自定义 View + Canvas（IME 窗口对 Compose lifecycle owner 支持需手动搭，
  且键盘对首帧延迟/触摸响应极敏感；Canvas 是 Gboard 等主流 IME 的成熟路线）。
- 设置 App：全量 Compose + miuix 风格组件库。
- 主题系统对渲染后端中立，后续如迁移 Compose-in-IME（FlorisBoard 已验证可行）不受影响。

### 3.7 配套工具站策略（重编辑功能不在手机端做）

PC 端已有配套工具站（主题编辑器、主题市场、码表编辑）。凡是**手机上操作不便的
重编辑功能**，一律走同类 Web 工具站方案，手机端只做「获取 + 应用」：

- 主题制作/编辑 → 工具站编辑，手机端导入 TOML（文件选择器 / 分享接收 /
  后续 WebDAV 同步）并应用；
- 码表/词库编辑 → 工具站编辑，手机端导入；
- 主题市场 → 工具站浏览下载，手机端应用。

手机设置页因此保持**轻量**：开关/选择/排序类操作 + 导入入口，
不做复杂编辑器（对比 fcitx5-android 的 21 色槽编辑器路线，我们不跟）。

### 3.8 明确排除的功能

语音识别/AI（含自带 key 形态暂不做）/云输入/在线商店/手写识别均不实现，
语音仅保留「转发系统语音输入法」入口。完整范围见
[android-ime-features.md](android-ime-features.md)。

## 4. 需全新编写（Kotlin 侧）

1. **软键盘布局系统**：qwerty/符号/数字层、布局定义文件（建议 TOML，与配置家族
   格式统一）、长按候选、滑动手势、震动音效。
2. **InputMethodService 集成**：生命周期 ↔ focus 语义对齐、InputConnection
   上屏与预编辑、EditorInfo 场景感知（密码框自动英文、数字框换布局）。
3. **候选条 + 展开式候选面板**。
4. **设置 App**（Compose + miuix，建议清单驱动）。
5. 二期：九键 T9 拼音（引擎级歧义展开，**在 Rust 引擎做**、代码共享）、滑动输入、多端同步。

## 5. 风险与前置项

| 风险/前置项 | 应对 |
|---|---|
| coordinator 依赖 wind-ui（56 处引用/11 文件）需上游改造 | 见 [upstream-coordinator-refactor.md](upstream-coordinator-refactor.md)，桌面行为不变的纯重构 |
| workspace 无 cdylib 先例，NDK 交叉编译未验证 | 先做 walking skeleton：只导出 `version()` 打通 cargo-ndk → .so → Kotlin |
| 首启词库编译耗时（yaml 源约 30 MB） | 分级编译 + 进度 UI + 指纹缓存（见 3.5） |
| `.so` 体积 | 不链 wind-ui 后依赖树大幅缩小；LTO + strip |
| JNI 边界线程模型 | 引擎调用固定单一后台线程（与桌面消息循环语义一致），InputConnection 回主线程 |
| 变体判定（dev/release）靠 exe 文件名 | 换 BuildConfig 注入，`variant.rs` 加 Android 分支 |
| tracing 日志落点 | `tracing-android`（logcat layer），保持「INFO 级不含用户输入/候选/词库内容」约束 |

## 6. 落地顺序

1. **主仓前置改造**：coordinator 摆脱 wind-ui（另一会话执行，见配套文档）。
2. **Walking skeleton**：`wind-android` cdylib + UniFFI + cargo-ndk + 空壳
   InputMethodService，打通「点按一个键 → Rust 返回候选 → 上屏一个字」最小闭环。
3. **键盘 UI 系统**：布局、触摸、候选条。
4. **配置落地 + 设置 App**：Android 目录结构 + Compose 设置页。
5. **主题扩展**：键盘域节点设计 + 共享调色板打通。
6. 二期：九键、滑动输入、同步。

## 7. 本仓工程约定

- 包名前缀：`com.windinput.`（applicationId：`com.windinput.ime`）。
- Rust 代码不入本仓，一律在主仓演进；本仓通过路径引用主仓（CI 可改为拉预编译产物）。
- Android 版功能差距表（对照 `SessionEvent` 及其承载的命令）建立后维护在本仓 docs 下，
  模板参考主仓 `wind_macos/AGENTS.md`。
