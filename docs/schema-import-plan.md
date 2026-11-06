# 手机端导入方案：规划

> 2026-11-06 草案。状态：第 1–3 步已实现（FFI、设置页入口与预览 / 结果 / 删除 / 恢复自带、文件关联）；`windinput://` 与导出未做。
> 格式权威来源：主仓 `docs/architecture/package-format.md`（方案包 v2 / 配置包 / 文本信封）。本文只讲手机端怎么接。

## 1. 现状

- 设置页能做的：启用 / 停用已装方案（`installed_schemas` / `set_schema_enabled`）、词库导出导入（`dict_export` / `dict_import`，`.wdict` 与 Rime/TSV 词表）。
- **没有「导入方案」**：`wind-android` 的 FFI 没暴露方案包相关接口；`AndroidManifest` 没有 `.wpkg` / `.zip` 的文件关联，也没有 `windinput://` 协议。手机上只能用 APK 自带的方案。
- 核心已经有现成能力（主仓 `wind-transfer/src/scheme.rs`，PC 端在用）：
  `preview_import(package, user_dir)`、`import_package(package, user_dir, strategy)`、`delete_package(id, user_dir, system_dirs, keep_ids)`、`read_package_meta`。
  导入先全部读入内存再逐文件 tmp+rename，有穿越守卫、解压总量限额、`format_version` 门禁。

## 2. 目标与不做的事

目标：在手机上装上电脑端 / 工具站 / 文档站分发的方案包，且手机导出的包电脑端也能认（同一格式，不另起一套）。

不做（本期）：
- 方案商店 / 在线下载；
- 主题包、备份包的导入（格式不同，另案）；
- 手机端导出方案包（核心有 `export_package`，等导入稳定后再加，工作量小）。

## 3. 方案

### 3.1 核心与 FFI（Rust，`rust/wind-android`）

新增三个接口，全部复用主仓 `wind-transfer`，手机端不写第二份包逻辑：

| 接口 | 作用 | 返回 |
|---|---|---|
| `scheme_preview(path)` | 只读预览 | 标题 / 说明、将新增文件、冲突文件、引用系统资源、缺失引用、`config_patch` 原文、方案 id 列表；错误带原因 |
| `scheme_import(path, replace)` | 落盘；`replace=false` 即 Merge（已存在跳过） | 导入文件、冲突文件、方案 id 列表 |
| `scheme_delete(id)` | 删用户导入的方案；共享文件保留，系统（APK 自带）文件永不删 | 删除 / 保留清单 |

要点：
- 入参用**应用私有目录里的路径**，不是 `content://`。宿主先把用户选的文件复制到缓存目录（见 3.2），导入完删除。
- 目标目录是核心的用户层 schemas 目录，不动 APK 自带的数据层。
- 导入 / 删除后发 `ReloadConfig`，已装方案列表立即刷新。
- 带 `config_patch.toml` 的配置包：沿用规格的两步走——先 `scheme_import`，再对 `config_patch` 走现有的配置片段预览 / 应用管线（`previewPatch` / `applyPatch`）。手机端是否已暴露这条管线要在开工前核对，没有就先只支持不带 `config_patch` 的方案包，带的直接拒绝并说明。两步之间不是原子的，文件已装而片段失败要如实报。

### 3.2 宿主（Kotlin）

- **入口一：设置页「方案」页**新增「导入方案」按钮，系统文件选择器（`OpenDocument`，类型 `application/zip` 与 `application/octet-stream`，因为 `.wpkg` 没有标准 MIME）。
- **入口二：文件关联**。注册 `.wpkg` / `.zip` 的 `VIEW` 与 `SEND` intent-filter，指向一个轻量的导入 Activity。在文件管理器、微信、浏览器里点包就进预览。
- **入口三（后做）**：`windinput://` 一键导入链接，配合文档站 cookbook。
- 复制与限额：流式复制到 `cacheDir`，复制时就卡大小上限（沿用 `DictBackup` 的做法），超限直接拒绝，不把整包读进内存。
- 预览页（手机上没有 PC 那样的大对话框，做成全屏页或底部面板）：
  1. 标题、说明（来自 `[package]`，限行数可滚动）；
  2. 将新增 N 个文件、冲突 N 个（冲突默认跳过，给「覆盖」开关，默认关，覆盖前二次确认）；
  3. 缺失引用要醒目提示（缺了会装上也用不了）；
  4. 带配置片段时列出将修改的配置项；
  5. 「导入」「取消」。
- 结果页：成功列出新方案名，附「立即启用」；失败给原因，不留半成品（核心是先全读入内存再写盘，校验期零落盘）。
- 已装方案列表里，用户导入的方案可「删除」，APK 自带的不显示删除。需要核心告知哪些是用户方案；`InstalledSchemaInfo` 现在只有 `id / name / enabled / locked`，要加一个 `user` 标记。

### 3.3 手机特有的风险

- **大词库**：首次加载要建索引，手机上慢，且占内存。预览页显示包内词库大小；导入后第一次使用前给进度提示（现有「正在建立词库索引…」可复用），不要卡在输入时。
- **空间**：导入前用包的解压后总量与可用空间比对。
- **同名冲突**：用户方案与 APK 自带方案同 id 时，以哪个为准要明确（规格是用户层覆盖数据层，需要在预览里写明「将覆盖自带方案」并默认拒绝）。
- **安全**：解压守卫、总量限额、`format_version` 门禁都在核心里，不要在 Kotlin 侧另写。预览里的说明文字是分发者提供的任意文本，只当纯文本渲染，不解析链接。
- **PC 影响**：只新增 FFI 与宿主 UI，不改 `wind-transfer`；若核心需补 `user` 标记之类的字段，评估对 PC 的影响，优先走移动端接口。

## 4. 实施顺序

1. **核心 + FFI**：三个接口、`InstalledSchemaInfo.user`；重新生成 UniFFI 绑定（`scripts/build-rust.sh`）。Rust 测试：用工具站产出样例包（含合法包、冲突包、含备份清单的包、穿越路径包、超限包、版本过高包）验证预览与导入。
2. **设置页入口 + 预览 / 结果页**：先只支持文件选择器；带 `config_patch` 视核对结果决定。
3. **文件关联**：导入 Activity 与 intent-filter；e2e 用 `adb shell am start -a android.intent.action.VIEW -d file://…` 驱动。
4. **删除用户方案**。
5. 之后：`windinput://`、手机端导出。

每步独立提交，每个包出版本号 +1。验证只在 `emulator-5554`。

## 5. 已定的决定（2026-11-06）

1. **测试样例**：用社区的 98 五笔，源目录在工作区 `WindInputCodeTable/schemas/wubi98`（`wubi98.schema.toml`、`wubi98_pinyin.schema.toml`、`wubi98/wubi98_cidian.dict.yaml`，约 1 MB）。按方案包 v2 打成 zip（条目名 = schemas 根相对路径，零层级）做真机 / e2e 样例，同时构造冲突、穿越路径、超限、版本过高、含备份清单等负例。
2. **配置片段**：第一期**不支持**带 `config_patch.toml` 的包。导入时忽略该文件（不落盘、不应用），预览页明确写「此包带配置片段，本版本不会应用」，不拒绝整包。
3. **同 id 覆盖 APK 自带方案**：允许，但预览里醒目写明「将覆盖自带方案」，需二次确认。同时必须有清除手段：对被覆盖的自带方案，设置页提供「恢复自带版本」（删除用户层的同名覆盖文件，回落到 APK 自带数据层）；用户自己导入的新方案提供「删除」。两者都走核心的 `scheme_delete`，系统文件永不删。
