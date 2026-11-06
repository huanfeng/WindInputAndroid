# WindInput Android（清风输入法 安卓版）

基于 [WindInput](../WindInput) Rust 核心的 Android 输入法。
架构总览与移植决策见 [docs/architecture.md](docs/architecture.md)。

## 架构速览

- **Rust 核心复用**：拼音/五笔引擎、词库（wdat mmap）、用户词频（redb）、
  配置（TOML 三层合并）、主题调色板解析均复用主仓 crate，经 JNI（UniFFI）进程内直调。
- **Kotlin 原生壳**：`InputMethodService` 对接、软键盘 UI（自定义 View + Canvas）、
  设置 App（Compose + miuix 风格）。
- **配置格式三端统一**：与 Windows/macOS 共用同一份 TOML 语义，为多端同步预留。
- **词库以 yaml 原文发布**（GPL 合规），端上编译 wdat 缓存。

## 工程结构

```
app/                          单模块 APK（IME 服务 + 设置入口）
├── src/main/java/com/windinput/ime/
│   ├── service/              InputMethodService 对接层
│   ├── keyboard/             软键盘 UI（View + Canvas）
│   ├── settings/             设置页（Compose + miuix）
│   └── core/                 Rust 核心 Kotlin 门面（UniFFI 绑定落点）
docs/                         架构文档、上游改造说明、会话层重构记录
```

## 构建

```bash
./gradlew :app:assembleDebug
```

要求：JDK 17~21、Android SDK。`local.properties` 配置 `sdk.dir`。

> **compileSdk 37 的坑**：API 37 起平台包带小版本号（`platforms;android-37.0`），
> 只写 `compileSdk = 37` 时 AGP 找的是 `android-37`，报「Failed to find target with
> hash string」——看着像 SDK 没装全，其实是命名变了。工程已用 `compileSdkMinor = 0`
> 补齐，SDK 侧要装的是 `platforms;android-37.0` 而非 `platforms;android-37`。

Rust 侧（两套脚本等价，按平台选）：

```bash
scripts/build-rust.sh           # Linux / macOS
```
```powershell
pwsh scripts/build-rust.ps1     # Windows
```

绑定层 `rust/wind-android` 在本仓，输入法内核在主仓，两者经主仓的 `wind-mobile`
门面以 path 依赖对接——因此**两仓必须并列存放**：

```
<workspace>/WindInput          主仓（核心 + wind-mobile 门面）
<workspace>/WindInputAndroid   本仓（Kotlin 壳 + wind-android 绑定层）
```

主仓不在隔壁时 cargo 会报 path 依赖找不到，那不是环境问题，是目录摆错了。

## 测试

分几层，**越靠上越该先跑**——模拟器一轮十几分钟，Rust 层同一条按键序列几秒就有结论，
能在下面复现的问题不要拿到模拟器上试错。

| 层 | 跑什么 | 命令 |
| --- | --- | --- |
| Rust 单测 | 按键流 / 方案切换 / 候选回调 / 词库备份（`rust/wind-android/tests/key_flow.rs`） | `cd rust && cargo test -p wind-android` |
| Kotlin 单测 | 布局解析、设置清单、许可声明、备份文案等纯逻辑（JVM） | `./gradlew :app:testDebugUnitTest` |
| 构建 | 交叉编译 + APK | `scripts/build-rust.sh && ./gradlew :app:assembleDebug` |
| 仪器测试 | 要真 Android 运行时的控件行为：自绘键盘 / 候选栏的无障碍动作（读屏双击走 ACTION_CLICK，adb 注入的触摸会绕过 TalkBack，只能在进程里验） | `./gradlew :app:connectedDebugAndroidTest`（需开着模拟器；跑完会卸掉应用，之后的 emu-test 会重装） |
| 端到端 | 模拟器上点键盘、断言输入框（40 余个用例） | `scripts/emu-test.sh` |

一键跑完整条：

```bash
scripts/test-all.sh             # Rust 单测 → 交叉编译 → APK → 起模拟器 → 端到端
scripts/test-all.sh --skip-emu  # 只到构建为止
```

Linux 服务器上先把无头模拟器拉起来：

```bash
scripts/emu-up.sh               # 从快照恢复（默认），等到 boot_completed
scripts/emu-up.sh --cold        # 丢掉快照完整开机，要干净环境时用
```

**无显卡机器上的两处优化**（都在 `emu-up.sh` 里，改动前先读那里的注释）：

- **软件渲染**走 SwiftShader（`-gpu swiftshader_indirect`），完全不碰 GPU。
  新版 emulator 的 `-help-gpu` 已不列这个名字但仍接受，日志里认这行确认生效：
  `emuglConfig_init: vulkan_mode_selected:swiftshader`。
- **开机快照**：冷启动约 40s，之后从快照恢复只要十几秒。快照会**固化硬件配置**，
  所以脚本在 `config.ini` 有改动时自动作废旧快照——否则「改了核数内存却一点没变快」
  没有任何提示。

> 快照恢复的瞬间系统时间跳变，watchdog 常判 SystemUI 无响应弹出 ANR 对话框。它盖在
> 最前面，之后任何 `uiautomator dump` 拿到的都是那个弹窗，端到端里表现为**「找不到
> 测试输入框」**——跟输入法毫无关系。`emu-up.sh`（存快照前）和 `emu-test.sh`
> （每次找不到输入框时）都会认 `aerr_close` 按钮点掉它。

> **模拟器要 `/dev/kvm`**。没有它 QEMU 退化成纯软件 CPU 模拟，开机十几分钟、点击
> 几乎不响应，端到端会全线超时假失败——所以 `emu-up.sh` 开机就硬检查一次，缺了直接报错
> 而不是慢慢跑。跑在 LXC 容器里时宿主机要透传（PVE 8.1+，对**非特权容器**同样有效）：
>
> ```bash
> pct set <CTID> --dev0 /dev/kvm,gid=<容器内 kvm 组的 gid>,mode=0660
> pct reboot <CTID>
> ```
>
> gid 用 `getent group kvm` 在**容器内**查（各发行版不同，Ubuntu 24.04 上是动态分配的），
> 再把自己加进该组：`sudo usermod -aG kvm $USER`，重新登录生效。
>
> ⚠ 别用老教程里的 `lxc.mount.entry` + `lxc.cgroup2.devices.allow` 那套：非特权容器的
> uid 映射会把 bind 进来的设备节点显示成 `nobody:nogroup`，**容器内没有写权限**，
> `/dev/kvm` 看着在、模拟器就是起不来。`emu-up.sh` 的 `-w` 检查专门拦这种半吊子状态。

## CI / 发版

| 工作流 | 触发 | 做什么 |
| --- | --- | --- |
| `ci.yml` | push main / PR | 双架构交叉编译 → UniFFI 绑定漂移检查 → debug APK（仓库 debug 密钥签） |
| `release.yml` | push tag `v*` | 同上，但用 secrets 里的个人密钥签 release 包 + sha256，建**草稿** Release |
| `release.yml` | 手动触发 | 只产 artifact，不发 Release |

发一版：改 `app/build.gradle.kts` 的 `versionCode` / `versionName` → 提交 → `git tag v<versionName>`
→ push tag。tag 与 versionName 不一致会在「确定版本号」一步直接失败。草稿 Release 人工过目后发布。

签名方案（一把密钥签所有包，任何机器构出来的互相能覆盖安装）见 `keystore/README.md`。
CI 上 **不跑** Rust 测试——它要主仓 build_dev/data，缺数据时用例静默跳过，是假绿；见 ci.yml 头注释。

## 当前状态

- [x] 架构设计定稿（docs/architecture.md）
- [x] 主仓前置改造方案（docs/upstream-coordinator-refactor.md，另行执行）
- [x] APK 骨架：IME 服务 + qwerty 键盘占位（英文透传）+ 设置页占位
- [x] 会话接口层（ImeSession/UiEvent，UniFFI 落位形状）+ FakeSession 占位链路
- [x] 数据目录三棵树（data/user/cache）+ assets 版本化拷出
- [x] 键盘布局 TOML 定义 + 解析 + 用户覆盖（user → data → 内置回退）
- [x] EditorInfo 场景感知（密码框直输、数字框切符号层）
- [x] 设置清单驱动的 Compose 设置页（toggle → 用户层 config.toml）
- [x] InputWindow 窗口框架 + 工具栏↔候选↔剪贴板建议 三态候选条
- [x] 文本编辑 / 剪贴板历史 / 表情 三个 P0 面板
- [x] 按键手势体系：长按气泡、按键预览、上滑副字符（TOML 声明）
- [x] 展开式候选面板、回车键动作标签
- [x] Walking skeleton：wind-android cdylib + UniFFI + RustSession（模拟器端到端 17/17）
- [x] 对照 fcitx5-android 补基础功能（docs/android-ime-features.md §12）：组合态与光标/收键盘同步、
      按键音、长按时长可配、语音输入转发、系统输入法选择器、深浅色跟随系统
- [x] 调色板槽位落地（`keyboard/Palette.kt`，内置浅/深两套）——主题系统的 K 侧接口已定
- [x] 真机反馈修整（2026-09-16）：标点键面/输出随中英模式（布局 ASCII 化，`Punct.kt`）、
      空格居中（底栏对称 + 表情键）、编码栏改浮层（条区 70→44dp）、编码态滑退格清编码、
      编辑面板选择改 `setSelection`、快捷面板改图标格子、设置页 +9 项与 select 类型
- [ ] 词库 yaml 进 assets + 端上编译 + 分级加载（骨架已收 wubi86 主/扩展词库）
- [ ] wind-theme 求值结果灌进 Palette（主题键盘域节点）
- [x] 123 层 / 符号页 / 九宫格数字键盘、单手模式（九宫格拼音待做）
- [x] 物理键盘按键直连引擎
- [ ] 浮动候选窗、Direct Boot、键盘边距配置——余下缺口见 §12
