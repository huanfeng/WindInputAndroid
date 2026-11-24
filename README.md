<p align="center">
  <img src="app/src/main/res/drawable-nodpi/windinput_logo.png" alt="清风输入法" width="128">
</p>

<h1 align="center">清风输入法 安卓版 (WindInput Android)</h1>

<p align="center">
  轻量、快速、可定制的开源中文输入法——手机上的清风
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen" alt="Platform">
  <img src="https://img.shields.io/badge/ABI-arm64--v8a-blue" alt="ABI">
  <img src="https://img.shields.io/badge/license-MPL--2.0-blue" alt="License">
  <a href="https://github.com/huanfeng/WindInputAndroid/releases"><img src="https://img.shields.io/github/v/release/huanfeng/WindInputAndroid?include_prereleases" alt="Release"></a>
</p>

<p align="center">
  <a href="https://windinput.com"><b>官网</b></a> ·
  <a href="https://github.com/huanfeng/WindInputAndroid/releases"><b>下载</b></a> ·
  <a href="docs/development.md"><b>开发指南</b></a> ·
  <a href="https://github.com/huanfeng/WindInput"><b>电脑版</b></a>
</p>

> 当前为**预览版**，功能仍在快速完善，欢迎试用并反馈问题。

## 特性

- **与电脑版同一套核心** — 输入引擎、词库、配置语义复用 [WindInput](https://github.com/huanfeng/WindInput) 的 Rust 核心，两端输入习惯一致
- **多种输入方案** — 全拼、双拼、五笔 86、五笔拼音混输、笔画、英文，方案驱动，可导入方案包
- **按手机习惯设计** — 26 键 / 九宫格数字键盘、长按与上滑副字符、滑动退格、单手模式、横竖屏分别调节键行高
- **智能候选** — 整句解码、联想、辅助码筛选、智能符号，连续横滑候选条与展开面板
- **好用的面板** — 剪贴板历史与粘贴建议、表情与符号、文本编辑、快捷输入
- **设置可视化** — 清单驱动的设置页，修改即时生效；亮暗主题跟随系统
- **隐私友好** — 输入与词库都只在本机；**只在你点「检查更新」时才联网**，诊断信息由你决定是否分享
- **轻量运行** — Rust 核心经 UniFFI 进程内直调，词库 mmap 加载，首屏迅速

## 安装

1. 前往 [Releases](https://github.com/huanfeng/WindInputAndroid/releases) 下载最新的 `WindInputAndroid-<版本>.apk`，按需核对同页的 sha256
2. 安装后打开应用，按引导在系统设置里**启用**清风输入法并**切换**为当前输入法
3. 升级直接覆盖安装，设置与用户词库都会保留

要求：Android 8.0（API 26）及以上，**仅提供 64 位（arm64-v8a）**安装包。
新版本可在应用内「设置 → 关于 → 检查更新」查询（点了才联网），也可留意 [Releases](https://github.com/huanfeng/WindInputAndroid/releases)（官网暂无安卓版下载与文档）。

## 反馈问题

在应用内「设置 → 关于 → 反馈问题」，或到 [Issues](https://github.com/huanfeng/WindInputAndroid/issues) 提交。
输入相关的问题请附上「设置 → 关于 → 导出诊断压缩包」生成的文件（含按键与候选统计、运行日志，不含输入内容）。
问题的处理进度见 [CONTRIBUTING.md](CONTRIBUTING.md) 的「Issue 状态」。使用上的疑问可到 [Discussions](https://github.com/huanfeng/WindInputAndroid/discussions)。

## 仓库范围

本仓库是安卓端的壳与绑定层，输入法内核在主仓：

| 组件 | 技术 | 职责 |
|------|------|------|
| `app/` | Kotlin | 单模块 APK：`InputMethodService` 对接、自绘软键盘（View + Canvas）、设置页（Compose + miuix） |
| `rust/wind-android` | Rust | UniFFI 绑定层，把主仓的 `wind-mobile` 门面编成 `.so` 供 Kotlin 调用 |
| [WindInput](https://github.com/huanfeng/WindInput) | Rust | 核心：输入引擎、词库、配置与主题（与电脑版共用），经 `wind-mobile` 对接 |

两仓**必须并列存放**（本仓以 path 依赖引用主仓的 `wind-mobile`）：

```
<workspace>/WindInput          主仓
<workspace>/WindInputAndroid   本仓
```

## 从源码构建

要求：JDK 17~21、Android SDK（`platforms;android-37.0`）、Rust stable 与 Android 交叉编译目标。

```bash
scripts/build-rust.sh              # 交叉编译 .so 并生成 Kotlin 绑定
./gradlew :app:assembleDebug       # 构建 debug APK
./gradlew :app:testDebugUnitTest   # Kotlin 单测
```

测试分层、模拟器端到端、CI 与发版流程、工程结构与架构说明见 [开发指南](docs/development.md)；
设计文档在 [docs/](docs/)。

## 参与贡献

欢迎贡献代码、报告 Bug 或提出建议！请阅读 [贡献指南](CONTRIBUTING.md)。

> 首次提交 PR 需要签署 [贡献者许可协议 (CLA)](CLA.md)。

## 许可证

- **本仓库自有代码**（Kotlin 壳、`rust/wind-android` 绑定层、文档）采用 [MPL-2.0](LICENSE)。
- **随包分发的核心**（主仓 [WindInput](https://github.com/huanfeng/WindInput)，GPL-3.0-or-later）与
  **词库数据**（白霜拼音 GPL-3.0、rime-stroke LGPL-3.0 等，逐条见
  [notices.toml](app/src/main/assets/licenses/notices.toml) 与应用内「开源许可」页）各自适用其许可证。
- 因此**构建出的 APK 作为整体按 GPL-3.0 分发**：MPL-2.0 §3.3 允许把 MPL 文件并入以 GPL 发布的更大作品，
  本仓文件均不带 Exhibit B（「与次级许可证不兼容」）声明。单独复用本仓的 MPL 文件不受 GPL 约束；
  只要链接了核心，就须遵守 GPL。
- 项目名称与标识的使用约定同主仓 [BRANDING.md](https://github.com/huanfeng/WindInput/blob/main/BRANDING.md)。

## 致谢

安卓版在设计与实现中参考了许多优秀的开源项目，在此一并致谢：

- **[WindInput](https://github.com/huanfeng/WindInput)** — 共用的核心引擎，及其致谢的
  [RIME / librime](https://github.com/rime/librime)、[Fcitx5 / libime](https://github.com/fcitx/libime) 等项目
- **[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)**、
  [FlorisBoard](https://github.com/florisboard/florisboard)、[HeliBoard](https://github.com/Helium314/HeliBoard)、
  [AnySoftKeyboard](https://github.com/AnySoftKeyboard/AnySoftKeyboard)、
  [Trime 同文输入法](https://github.com/osfans/trime) — Android 输入法实现与交互的对照参考
- **[白霜拼音 rime-frost](https://github.com/gaboolic/rime-frost)**、
  [极点五笔 rime-wubi86-jidian](https://github.com/KyleBing/rime-wubi86-jidian)、
  [rime-stroke](https://github.com/rime/rime-stroke) 等 — 词库与码表数据来源，完整声明见应用内「开源许可」页
- **[miuix](https://github.com/miuix-kotlin-multiplatform/miuix)**、[Lucide](https://lucide.dev)、
  [UniFFI](https://github.com/mozilla/uniffi-rs) — 界面组件、图标与 Rust 绑定
