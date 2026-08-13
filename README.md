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
docs/                         架构文档与上游改造说明
```

## 构建

```bash
./gradlew :app:assembleDebug
```

要求：JDK 17、Android SDK（compileSdk 36）。`local.properties` 配置 `sdk.dir`。

Rust 核心（`wind-android` crate）位于主仓，经 cargo-ndk 编译 `.so` 后接入本仓
（walking skeleton 阶段接通，当前键盘为透传占位）。

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
- [x] Walking skeleton：wind-android cdylib + UniFFI + RustSession（wubi86，待模拟器实测）
- [ ] 词库 yaml 进 assets + 端上编译 + 分级加载（骨架已收 wubi86 主/扩展词库）
- [ ] 主题键盘域节点 + 共享调色板
- [ ] 九宫格布局、单手模式、横竖屏独立尺寸配置
