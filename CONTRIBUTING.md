# 贡献指南

感谢你对清风输入法安卓版（WindInput Android）的关注！欢迎 Bug 报告、功能建议和代码提交。

## 签署 CLA（必须）

**所有贡献者在首次提交 Pull Request 前必须签署贡献者许可协议（CLA）**，与主仓
[WindInput](https://github.com/huanfeng/WindInput) 使用同一份协议，在任一仓库签署即覆盖全部清风输入法仓库。

1. 提交 Pull Request
2. CLA Assistant 机器人会在 PR 中发起签署请求
3. 在 PR 评论中回复：`I have read the CLA Document and I hereby sign the CLA`
4. 签署后 CLA 检查自动通过；状态没更新时评论 `recheck`

未签署 CLA 的 PR 无法合并。协议全文见 [CLA.md](CLA.md)。

## 许可证

本仓库自有代码（Kotlin 壳、`rust/wind-android` 绑定层）采用 [MPL-2.0](LICENSE)。
随包分发的核心（主仓，GPL-3.0-or-later）与词库数据各自适用其许可证，
因此**构建出的 APK 整体按 GPL-3.0 分发**。详见 README「许可证」一节。

## Bug 报告与功能建议

本仓库是**安卓版**仓库，只收集安卓版的问题。电脑版（Windows / macOS / Linux）与核心引擎的问题请到
[主仓](https://github.com/huanfeng/WindInput/issues)提交；拿不准时先在这里提，我们会帮忙转到主仓。

issue 的状态由标签表示：`状态: 待反馈`、`状态: 无法重现`（等提交者补充）、`状态: 已确认`（问题确认，待修复）、
`状态: 已计划`、`状态: 暂无计划`。维护者回复后 14 天无人回应会收到提醒，再过 7 天自动关闭；提交者回复即重新打开。

请用 [Issues](../../issues) 的模板提交。输入相关问题请附：

- 机型、Android 版本、输入法版本（设置 → 关于）
- 出问题的应用、复现步骤
- 「设置 → 关于 → 导出诊断压缩包」生成的 zip（含按键与候选统计、运行日志，不含输入内容；发之前请自查）

## 开发环境

- JDK 17~21、Android SDK（`platforms;android-37.0`）
- Rust stable，含 Android 交叉编译目标；`local.properties` 配置 `sdk.dir`
- **主仓必须与本仓并列存放**（`wind-mobile` 以 path 依赖对接）：

```
<workspace>/WindInput          主仓
<workspace>/WindInputAndroid   本仓
```

构建与测试命令见 [README](README.md)。提 PR 前请至少跑通：

```bash
scripts/build-rust.sh && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

涉及按键流 / 引擎行为时，加跑 `cd rust && cargo test -p wind-android`（需要主仓生成的词库数据）。
端到端用例整套约 45 分钟，请优先单测，必要时用 `scripts/emu-test.sh --only` 跑受影响用例。

## 提交规范

使用 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)：`<类型>(<范围>): <描述>`，
类型：`feat` / `fix` / `docs` / `refactor` / `perf` / `test` / `chore` / `style`。

- Rust 代码用 `cargo fmt` 格式化，逻辑修改与格式化分开提交
- 修改核心公开面后，须重新运行 `scripts/build-rust.sh`，并把 `app/src/main/java/uniffi/` 一起提交（CI 会检查绑定漂移）
- 改动影响主仓（PC 端共用的核心）时，请在 PR 中说明

## Pull Request 流程

1. Fork 本仓库，从 `main` 创建分支
2. 完成修改并通过上述检查
3. 按 PR 模板填写
4. 等待 CLA 检查与代码审查
