# 手机端单个方案设置：规划

> 2026-11-06。第一期：自定义行为、方案覆盖（码表）、辅助码 + 双拼布局、整句输入。
> 2026-11-25 第二期：主仓取消类型级全局配置（`docs/design/scheme-settings-flatten.md`），**全部**方案设置项按方案存，
> 设置页改为「一个方案一页、按组分子页」，见 §6。§1–§5 记录第一期，§6 以后的内容以 §6 为准。
> 状态：已实现，e2e 用例 [38] [58]–[61] [73]（`scripts/emu-cases/schema-settings.sh`）。
> 不做：切入热键、特殊模式（overlay）、PC「自定义行为」里的每页候选数 / 文字排列 / 字体 / 注释模板 / 自定义标点表。
> PC 对照：`wind-setting/src/dialogs/schema_manager.rs`（节与 TAB）、`schema_codetable.rs`（码表覆盖项）；
> 设计：主仓 `docs/design/schema-settings-vertical-tabs.md`、`schema-scoped-behavior.md`、`aux-code-schema-source.md`、
> `docs/redesign/schema-config-layering.md`。

## 1. 数据落点（与 PC 同一份文件、同一套键）

全部写方案覆盖层 `<用户配置目录>/schema_overrides/{id}.toml`，由核心 `read_schema` 深合并到方案文件之上。
键与方案文件段名完全相同，PC 设置页写出的文件手机能读、反之亦然。

| 值 | 来源 |
|---|---|
| 当前生效值 | 合并后方案（`schema_merged`）；码表项取 `effective_codetable`（含全局基线折叠）；辅助码取合并值，缺省回落全局 `schema.pinyin.aux_code.enabled` |
| 恢复后的值（「默认」） | 不含覆盖层的方案（`schema_base`）；码表项取 `followed_codetable` |
| 是否已覆盖 | 覆盖层（`get_schema_override`）里有没有这个键——只能问覆盖层，合并值分不出是方案作者写的还是用户写的（同 PC `codetableOverride`） |

写入规则（与 PC `schema.saveConfig` 的 `json_diff(方案文件, 新值)` 等价）：新值**等于方案文件里的原始值**时删键，否则写键；
「恢复」= 删键；「全部恢复默认」= 删本页管理的全部键。覆盖层里的其它内容（`dictionaries` 扩展词库开关、`key_actions` 等）
读改写时原样保留——**不**用 PC 的 `schema.resetConfig`（它整文件删除，会把扩展词库开关一起清掉）。
覆盖表删空时删文件。写后 `write_schema_override` / `delete_schema_override` 使该方案引擎失效（混输成员由核心扇出），
再 `refresh_schema_derived_config`（同 PC saveConfig），最后宿主发 `ReloadConfig`。

## 2. 分组 × 键 × 类型 × 方案类型显隐

| 分组（详情页卡片） | 键（覆盖层路径） | 类型 | 全拼 | 双拼 | 码表 | 混输 | 英文 |
|---|---|---|---|---|---|---|---|
| 基本 | 方案信息头（名称 / 类型 / 来源 / 版本 / 作者 / 说明） | 只读 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 基本 | `engine.pinyin.shuangpin.layout` | 选择（布局 id，清单由核心扫目录） | | ✓ | | | |
| 基本 | 扩展词库（现有 `schema_dicts` / `set_schema_dict_enabled`） | 开关 | 有则显示 | 同 | 同 | 同 | 同 |
| 自定义行为 | `punct.mode` | 选择 follow / chinese / english | ✓ | ✓ | ✓ | ✓ | ✓ |
| 自定义行为 | `candidate.layout` | 选择 follow / vertical / horizontal | FFI 有，**界面不显示**（见 §5） | | | | |
| 自定义行为 | `phrases.enabled` | 开关 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 方案覆盖 | `engine.codetable.{auto_commit_at_full, clear_on_empty_max, top_code_commit, punct_commit, show_code_hint, single_char, english_merge.enable, frequency.enabled}` | 开关 | | | ✓ | | |
| 方案覆盖 | `engine.codetable.{auto_commit_min_len, short_code_yield_level}` | 整数（界面按清单选项） | | | ✓ | | |
| 方案覆盖 | `engine.codetable.frequency.strategy` | 选择 top / step / position | | | ✓ | | |
| 辅助码 | `engine.aux_code.enabled` | 三态（跟随全局 / 开 / 关） | 声明的辅助码表**可用**时 | 同 | | | |
| 实验性功能 | `engine.codetable.sentence_input` | 开关 | | | ✓ | | |

- 类型判据：核心 `resolve_engine_type`（english / mixed / pinyin / codetable），拼音再看 `engine.pinyin.scheme == "shuangpin"`。
- 码表覆盖项 = PC 可覆盖集合（`SPEC_BEHAVIOR_FIELDS` + `frequency.*` + `english_merge.*`）∩ 手机设置清单「码表」节已有的项；
  标题与选项文字**取设置清单**（`settings_manifest.toml` 的 `schema.codetable.*`），Rust 只给值，不重复一份文案。
  `auto_phrase.*` 核心 `CodeTableSpec` 没有方案级段，不列（同 PC `visible_keys`）。
- 整句输入只给码表方案（PC `sentence_input_seed`：混输配着拼音子引擎，开了不生效）。
- 辅助码：PC 判据是 `files` 非空；手机加一条「声明的来源至少一个已装」（`aux_code_source_options`）。出厂 APK 现已随包
  `stroke` 方案和 `aux_code/` 码表（小鹤/自然码/首右），判据仍保留：用户删了来源时不给开了没反应的开关。

## 3. FFI（`rust/wind-android`，经 `wind-mobile` 门面）

| 接口 | 作用 |
|---|---|
| `schema_settings(id) -> SchemaSettingsInfo` | 信息头 + 按方案类型筛好的设置项：`key / group / kind / value / default_value / overridden / options` |
| `set_schema_setting(id, key, value)` | 写一项（值一律字符串，按 `kind` 解析、校验选项）；等于方案文件原值即删键 |
| `reset_schema_setting(id, key)` | 删一项覆盖 |
| `reset_schema_settings(id)` | 删本页管理的全部键（保留扩展词库开关等） |

写口先过只读模式检查（同 `set_schema_dict_enabled`），与扩展词库开关共用一把锁（两者都读改写同一个覆盖文件）。
写完 `ReloadConfig`。不在 `schema_settings` 列表里的键（类型不符、未知键）拒绝。

主仓改动只在 **`wind-mobile`**（新增 `schema_settings` 模块 + 门面方法），只调 `EngineManager` 现成的
`schema_base / schema_merged / get_schema_override / write_schema_override / delete_schema_override /
effective_codetable / followed_codetable / aux_code_source_options / shuangpin_layouts` 与
`Coordinator::refresh_schema_derived_config`。PC 不依赖 `wind-mobile`，**无 PC 影响**。
（PC 的 `schema.getConfig/saveConfig` 在 `wind-webdata`，那个 crate 刻意不进 Android 依赖闭包，故不经它。）

## 4. 界面

- 「启用的方案」每行：标题（方案名）+ 右侧「设置」图标按钮 + Switch（启用 / 停用，锁定规则不变）。点图标进详情页。
- 路由加第三层 `Route.detail`（方案 id），走现有 `AnimatedContent` + 预测性返回；标题 = 方案名。
- 详情页：信息头卡片 → 基本（双拼布局、扩展词库）→ 自定义行为 → 方案覆盖 → 辅助码 → 实验性功能 → 「全部恢复默认」。
- 「跟随」的表达：**选回默认值即恢复**（删掉这一项的覆盖），不另设勾选框——手机上一行一个控件。
  改过的项摘要写「已自定义 · 默认：值」。辅助码例外，同 PC 三态（跟随全局（值）/ 开 / 关），
  显式选的开 / 关即使与全局相同也写实值。
- 与 PC 的细微差别：PC 码表覆盖是「勾选 + 控件」，可以显式钉住一个与跟随值相同的值；手机上选回默认值就是跟随。
- 「全部恢复默认」删的是 FFI 列出的全部键，含界面不显示的 `candidate.layout`（PC 写过的话也一并清掉）。

## 5. 与 PC 的差异与风险

- **候选排列不在手机上显示**：手机候选条没有横 / 竖排之分，核心的布局意图在移动端没有消费者；按 PC 设计稿
  「开了没反应的开关比不给更糟」，FFI 保留（数据与 PC 对等、Rust 测试覆盖），界面不列。
- 标点态：PC 与核心一致——改的若是**当前**方案，切走再切回（代际变化）才生效；这是核心语义（手动切换胜出本代际），手机不改。
- 码表覆盖 PC 有 ~20 项，手机只做清单里已有的 11 项；其余（精确匹配、通配、z 键……）全局页手机也没有，暂不列。
- 方案文件被用户方案包替换后，覆盖层里的旧键仍按新方案文件深合并——与 PC 相同。
- 风险：读改写与 PC 设置页不会并发（手机端只有本进程写）；与扩展词库开关的并发由同一把锁兜住。

## 6. 第二期（2026-11-25）：全部方案设置按方案存

- **FFI**：`scheme_form(id) -> SchemeFormInfo{effective, default_values, builtin, members}`（三张 TOML 表，键为
  `schema.<类型>.<项>`）与 `apply_scheme_form(id, sets, resets)`；主仓 `wind-mobile/src/scheme_form.rs`，
  与 PC `schema.getSettings` / `schema.setSettings` 同口径（`EngineManager::scheme_settings_snapshot` / `scheme_settings_apply`）。
  第一期的 `schema_settings` / `set_schema_setting` 仍给「自定义行为」「辅助码」「整句输入」与信息头用。
- **清单**：`settings_manifest.toml` 的隐藏页 `scheme_form`，每项带 `section`（组名）与 `schemes = [类型…]`；
  组：全拼 / 双拼 = 基本、智能组词、词组补全、英文候选、调频、自动学习、辅助码；码表 = 上屏、匹配、通配与 z 键、调频、
  自动造词、英文候选；混输 = 基本、拼音部分、英文部分；英文 = 候选、调频、自动造词。
- **方案页**：一个列表，每行「方案名　开关 | 齿轮」；开关启用 / 停用，点行或齿轮进该方案设置页。
  切换当前方案交给键盘。方案页下方只剩全局的模糊音。
- **方案设置页**：信息卡 → 本方案预设 → 各组入口（副标题写组的总开关状态或前几项）→ 全部恢复默认；
  代码画的组：九宫格键盘（全拼 / 双拼，「基本」之后）、扩展词库、自定义行为、整句输入（码表）、成员方案（混输）。
  选回方案默认值 = 删覆盖；改过的项写「已自定义 · 默认：x」。路由 `Route("schema", "scheme:<id>", <组名>)`。
- **九宫格**：方案设置「九宫格键盘」组里「启用九宫格键盘布局」，键盘配置记两份显式选择
  `ui.android.t9.enabled_schemas` / `disabled_schemas`（逗号分隔 id，配置文件只存标量）；两份都没有的按类型默认：
  全拼类开（含后来导入的）、双拼类关。键盘侧只在配置**确定关了**时撤掉记着的九宫格布局（核心忙时不问它）。
  读法栏开关（仅全拼）与九宫格标点仍是所有方案共用。
- **不折叠**：全应用去掉「进阶」折叠；全局设置的进阶项放进分组页底部「更多设置」的下一层（`detail = "more:<组名>"`）。
- **笔画**：`role = "aux"` 的方案装了默认不启用（核心 `config_api::set_default_off_schemas`）。
- 已知缺口：「恢复默认设置」只清 `config.toml`，不清 `schema_overrides/`。

