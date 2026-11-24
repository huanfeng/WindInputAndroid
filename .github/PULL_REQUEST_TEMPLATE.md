<!-- 本仓库是安卓版仓库；电脑版（Windows / macOS / Linux）与核心引擎的改动请到主仓 https://github.com/huanfeng/WindInput 提交 -->

## 变更说明

<!-- 简要描述这个 PR 做了什么 -->

## 变更类型

- [ ] Bug 修复
- [ ] 新功能
- [ ] 重构 / 代码优化
- [ ] 文档更新
- [ ] 构建 / CI 相关
- [ ] 其他（请说明）

## 相关 Issue

<!-- 如有关联的 Issue，请填写，例如：Fixes #123 -->

## 测试情况

- [ ] `./gradlew :app:testDebugUnitTest` 通过
- [ ] `scripts/build-rust.sh && ./gradlew :app:assembleDebug` 通过
- [ ] 已在真机或模拟器上验证（说明机型 / Android 版本）
- [ ] 涉及按键流 / 引擎时，`cargo test -p wind-android` 通过

## 检查清单

- [ ] Rust 代码已 `cargo fmt`（逻辑与格式化分开提交）
- [ ] 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/)
- [ ] 改了核心公开面时，已重新生成并提交 `app/src/main/java/uniffi/`
- [ ] 改动可能影响主仓 / PC 端时，已在上方说明
- [ ] 已阅读 [贡献指南](../CONTRIBUTING.md)
- [ ] 首次贡献已签署 [CLA](../CLA.md)
