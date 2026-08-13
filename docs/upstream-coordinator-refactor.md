# 主仓前置改造：让 wind-coordinator 支持 headless/Android 构建

> 交付对象：在主仓 `WindInput` 执行改造的会话。
> 目标读者已知主仓结构；本文档自包含，含现状证据、改造目标、建议方案与验收标准。
> 背景见本仓 [architecture.md](architecture.md)。

## 1. 改造目标（What & Why）

Android 版将以 **进程内 JNI 直调** 的方式复用 `wind-coordinator`（按键优先级链、
候选/加词/临英/标点/模式切换状态机），不走桌面的 IPC + 服务端渲染路径。为此需要：

1. **`wind-coordinator` 能在不链接 `wind-ui` 的情况下编译**（wind-ui 含 Win32
   Layered Window / NSPanel / DirectWrite / CoreText，Android 上无意义且编不过）。
2. **coordinator 及其依赖闭包能通过 `aarch64-linux-android` 目标的 `cargo check`**。
3. **桌面端行为零变化**：默认 feature 下产物与现在逐字节等价（纯重构）。
4. 附带收益：coordinator 摆脱传递性 `windows` 依赖后，**host 上可以直接
   `cargo test -p wind-coordinator`**（现状不能，见其 AGENTS.md「Testing Requirements」）。

**非目标**（后续另行处理，本次不做）：`wind-android` FFI crate、UniFFI 绑定、
`variant.rs` 的 Android 分支、tracing 的 logcat layer。

## 2. 现状事实（改造依据）

### 2.1 依赖拓扑

- `wind-coordinator` 依赖 `wind-ui`（Cargo.toml 直接依赖），另有 `wind-bridge`、
  `wind-ipc` 等；自身 `cfg(windows)` → `windows` crate、`cfg(target_os="macos")`
  → `core-foundation-sys`。
- 对 `wind_ui::` 的引用共 **56 处，分布在 11 个文件**，按性质分三类：

| 类别 | 引用点 | 说明 |
|---|---|---|
| **纯数据类型**（大头） | `manager::UiCommand`（42 变体枚举）、`candidate_window::CandidateItem`、`manager::{CandidateOp, MenuItemSpec, MenuKind, MenuAnchor, MenuPlacement, DiagSections, InputDiagView, WindowDiagView}`、`toolbar::ToolbarState`、`toast::*`、`manager::{HOVER_PAGE_PREV, HOVER_PAGE_NEXT}` | coordinator 只是**构造并发送**这些值（经 `mpsc::Sender<UiCommand>`），不触碰渲染 |
| **UiManager 句柄** | `manager::UiManager`（coordinator.rs，桌面构造路径持有） | 仅桌面服务进程需要 |
| **宿主服务函数** | `popup_menu::{get_clipboard_text, get_clipboard_text_cached, try_set_clipboard_text}`（handle_cmdbar.rs / handle_candidate.rs）、`manager_macos::forwarder_thread`、`input_source_macos::select_self`、`manager::{HostRenderArc, UiCommand::SetHostRender}` | 平台能力调用，Android 上要么由 Kotlin 侧提供（剪贴板），要么不存在（host render） |

- 文件分布：`coordinator.rs`(21) / `handle_menu.rs`(15) / `handle_cmdbar.rs`(5) /
  `handle_candidate.rs`(4) / `handle_addword.rs`(3) / `handle_quick_format.rs`(3) /
  `handle_cmdbar_macos.rs`(1) / `handle_lifecycle.rs`(1) / `handle_mode.rs`(1) /
  `input_diag.rs`(1) / `layout.rs`(1)。

### 2.2 已有的有利条件

- **headless 构造器已存在且形状正确**：`coordinator.rs` 的
  `new_headless_with_ui(config, data_dir) -> (Arc<Coordinator>, Receiver<UiCommand>)`
  ——Android FFI 层要的就是这个：喂键事件、从 Receiver 消费 UiCommand。
  另有 `new_headless` / `new_headless_with_store_override` 等测试用变体。
- UiCommand 的传输通道本来就是 `mpsc::channel`，coordinator 与渲染已经是
  「生产者/消费者」解耦，耦合只剩**类型定义的所在位置**。
- `wind-bridge` 有完整的 `cfg(unix)` 实现（server_unix/push_unix/shared_memory_posix），
  Android（bionic/unix）大概率可编译；`MessageHandler` trait 可继续作为事件入口契约。

## 3. 建议方案

### 3.1 核心动作：类型下沉，新建 `wind-ui-types` crate

> 命名建议 `wind-ui-types`（或 `wind-surface`），零平台依赖、仅数据定义。

1. 把上表「纯数据类型」全部搬入新 crate：`UiCommand`、`CandidateItem`、
   `CandidateOp`、`MenuItemSpec`/`MenuKind`/`MenuAnchor`/`MenuPlacement`、
   `ToolbarState`、toast 类型、`DiagSections`/`InputDiagView`/`WindowDiagView`、
   `HOVER_PAGE_*` 常量。
2. `wind-ui` 依赖 `wind-ui-types` 并 **`pub use` 原路径再导出**，桌面侧
   （service/wind-ui 内部）的既有 import 不必大改。
3. `wind-coordinator` 的 import 全部切到 `wind_ui_types::`，Cargo.toml 移除对
   `wind-ui` 的无条件依赖。
4. **`SetHostRender(HostRenderArc)` 变体的处理**（唯一带平台资源的变体），三选一，
   由实施者按代码实情定：
   - a) `HostRenderArc` 定义为 `Arc<dyn HostRenderSink>`，trait 下沉到 types crate
     （方法签名若不含平台类型即可）；
   - b) 该变体加 `#[cfg(feature = "host-render")]`，types crate 开 feature，
     桌面默认开、Android 关；
   - c) `SetHostRender` 不进 `UiCommand`，改为桌面构造路径的独立初始化调用
     （它本来就只在启动期发一次的话，语义上更干净）。

### 3.2 宿主服务：抽 trait 注入

coordinator 直接调用的平台函数收敛为一个小 trait（建议放 types crate 或
coordinator 内部）：

```rust
pub trait HostServices: Send + Sync {
    fn clipboard_text(&self) -> Option<String>;
    fn clipboard_text_cached(&self) -> Option<String>;
    fn set_clipboard_text(&self, text: &str) -> bool;
    // 视代码实情增删：全屏检测 is_foreground_fullscreen、
    // macOS input source 切换等
}
```

- 桌面实现放 `wind-ui`（现函数原样搬入 impl），经构造器注入。
- headless 构造器给 no-op 默认实现（现 `new_headless` 丢弃 UI 通道的做法同款思路）。
- Android 后续由 JNI 回调 Kotlin `ClipboardManager` 实现。
- `manager_macos::forwarder_thread` / `input_source_macos::select_self` 这类
  已在 `cfg(target_os="macos")` 内的调用，维持 cfg 即可，无需进 trait。

### 3.3 Feature 门控收尾

- `wind-coordinator` 增加 feature：`desktop-ui = ["dep:wind-ui"]`，进 default。
  `--no-default-features` 即 headless/Android 路径：无 `UiManager`、无 host render、
  宿主服务为 no-op（或由调用方注入）。
- 桌面构造路径（持有 `UiManager` 的 `new`）整体置于 `#[cfg(feature = "desktop-ui")]`。
- 检查 headless 路径下的 `PushServer`（`new_headless*` 里无条件构造）：确认它在
  无客户端时惰性/无副作用；若会起监听线程，一并 feature 门控或换成 trait 注入。
  （Android 不需要 push 通道，UiCommand Receiver 已覆盖。）
- `webdata.rs`（RPC 数据）、`watchdog.rs`、`layout.rs`、`input_diag.rs` 若仅服务
  桌面，顺手评估是否进 `desktop-ui`；拿不准就先留在公共部分，能编译就不阻塞。

### 3.4 依赖闭包核对（Android target）

coordinator 摆脱 wind-ui 后的依赖闭包应为：`wind-ui-types`、`wind-bridge`、
`wind-ipc`、`wind-config`、`wind-store`、`wind-dict`、`wind-engine`、
`wind-candidate`、`wind-transform`、`wind-theme`、`wind-cmdbar`、`wind-phrase`、
`wind-keys`、`wind-quick-input`、`wind-reverse`、`wind-punct` + `fontdb`/`chrono`
等纯 Rust 外部依赖。需逐一确认 `aarch64-linux-android` 下 `cargo check` 通过
（`wind-dict` 的 `cfg(windows)`、`wind-keys` 的注入/钩子部分预期自动排除；
`fontdb` 支持 Android；发现新的平台泄漏就地 cfg 处理）。

## 4. 验收标准

按顺序全部满足：

1. `cargo check -p wind-coordinator --no-default-features` 在 **host（Windows）**
   通过，且依赖树中无 `wind-ui`（`cargo tree -p wind-coordinator
   --no-default-features -i wind-ui` 为空）。
2. `rustup target add aarch64-linux-android` 后，
   `cargo check -p wind-coordinator --no-default-features --target aarch64-linux-android`
   通过（仅 check，无需 NDK 链接器）。
3. 默认 feature 下桌面全量构建、全部既有测试通过；`apps/service` 无改动或仅
   import 路径机械替换。
4. `new_headless_with_ui` 在 `--no-default-features` 下可用（这是 Android FFI
   的入口，不能被门到 desktop-ui 里）。
5. （加分项）为 headless 路径补一个冒烟测试：构造 → 喂一串键事件 → 断言
   Receiver 收到含候选的 UiCommand。此测试应能在 host 直接 `cargo test` 跑。

## 5. 风险提示

- `handle_menu.rs` 的 15 处引用全是 `MenuItemSpec` 等数据类型构造，搬类型即解，
  **不要**试图把菜单逻辑本身 feature 掉——Android 也可能复用菜单数据（长按弹出）。
- 改动面集中在 import 路径与类型搬家，逻辑零改动；提交建议拆为：
  ① 新建 types crate + wind-ui 再导出（桌面无感）；② coordinator 切 import；
  ③ HostServices trait 抽取；④ feature 门控 + Android target 修补。每步桌面可构建。
- 遵守主仓约束：VK 用 `keymap::VK_*`；`cargo fmt` 与逻辑改动分开提交；
  日志 INFO 级不含用户输入。
