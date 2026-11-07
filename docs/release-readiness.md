# 清风输入法安卓版首次正式发版：设置与功能缺口分析（只读）

**结论先说：**
- manifest 写的默认值和 Kotlin 回落值逐项对过，**都一致**，没有因此读错值的 bug。
- 但找到四处"设置写了没人读"或数据陈旧的问题，其中两处会拦发版：
  - 「输入法服务日志级别」这个设置在安卓上没有任何代码读它。
  - Rust 日志写死为 `wind_coordinator=debug`，会把用户自造词原文打进 logcat。
- 另有三个发版前必须处理的清单问题：
  - 测试台 Activity 在正式包里 `exported=true`。
  - `allowBackup=true` 而且没配排除规则，剪贴板历史和个人词库会进系统备份。
  - 没有隐私政策页。

以下"已核实"指看过代码，"未核实"指只是推断。

---

## 1. 默认值审查

### 1.1 manifest 默认值和代码回落值是否一致

`WindInputImeService.kt:479-558` 里每个 `userConfig.getXxx("ui.android.*", 回落值)` 都和 `settings_manifest.toml` 的 `default` 对过：行高 60/44、主题 system、底行 split、数字页 pad9、嵌入编码 false、编码提示 true、振动 true、振动强度 30（`KeyHaptics.kt:36`）、长按 300、按键音 false、气泡 false、各手势开关、候选字号 21、分隔线 true、页码 false、圆形底托 false、粘贴建议 true、自动大写和自动空格 true、`;` 进快捷输入 false、边距 0、单手 off（`:1039`）。**全部一致。**

长按速度是"选项写成字符串、默认值是整数"，`SettingsValues.androidValue` 会按默认值的类型存成整数，所以 `getInt` 能读到，没有问题。

另外三处不一致：

| # | 位置 | 问题 | 影响 |
|---|---|---|---|
| A | `keyboard/KeyboardView.kt:104` | 字段 `spaceLongPressEnabled = true` 的初值和 manifest 默认 `none` 相反 | 实际无害：`onStartInputView` 每次都会重新赋值（`Service:537`）。建议改成 `false`，免得以后有人照这个初值写逻辑 |
| B | `settings_manifest.toml:512-513` 和 `WindInputImeService.kt:112`、`ImeSession.kt:276` | manifest 注释说"键盘自己补空格"，代码注释说"补空格由核心负责（setAutoSpace）" | 只是文档自相矛盾，但正好影响"为什么不放 `schema.english.commit_space`"这个判断，建议核实后统一 |
| C | `app/src/main/assets/data/config.toml` 对比主仓 `data/config.toml` | 安卓这份是手工副本，和主仓 diff 已有大量差异。缺的有 `ui.comment` 段、`grid_*`、`english_merge.*position/max_count`、`input.emoji.block_auto_commit`、`cmdbar.backspace_collapse_group`、`phrase.prefix_match` 等；还留着已废弃的键，如 `pinyin_code_hint`、`ui.candidate.comment_enabled/comment_template_*`、`schema.pinyin.grammar` 的 `weight=0.0 model=""` | 缺的键会回落到 serde 默认值，大多数能用，但出厂值会悄悄跟着 Rust 默认值走，而不是跟着文件走。主仓 `config.rs` 里 `MobileConfig` 的文档也写了"实测已滞后 89 行、无守门测试"。**建议发版前同步一次，并加一个对照测试** |

### 1.2 不适合手机首发的默认值

| 键 | 当前值 | 建议 | 理由 |
|---|---|---|---|
| `schema.active`（出厂兜底，见 `RustSession.kt:74`、`Service:346`）| `wubi86` | 改成 `pinyin`，或保持现状但首次选择卡不能"一关了之" | 首次选择卡（`Service:1274`）弹一次就记下，用户点外面关掉也算弹过（虽然 `dismissOnOutside=false`，但还有收键盘等路径）。之后就一直是五笔，绝大多数手机用户第一句就打不出来。微信首次引导默认勾的是「全键盘拼音」（wx_onboard.png）。**风险高** |
| `input.emoji.enabled` | `false` | 考虑手机上默认开 | 豆包、微信打出词默认带 emoji 候选。但要走 `mobile.*` 覆盖键（按 `MobileConfig` 的规则，只登记真有平台差异的键），首次开启还要建索引。属于产品判断，可以后放 |
| `schema.pinyin.english_merge.enable` | `false` | 考虑开 | 手机拼音键盘上中英混打很常见，豆包会出英文候选。前缀补全已经限制了"打的不是完整拼音才补"，误伤可控。未核实 |
| `input.auto_pair.chinese` / `english`（安卓副本）| `true`（电脑版是 `false`）| 先真机试，倾向关 | 软键盘没有"跳出配对"的键（`jump_out_keys` 是桌面键），打完引号里的内容还得手动越过右引号。`key_flow.rs:1714` 说明是有意为之，但手机上的跳出方式未核实 |
| `ui.android.key_preview` | `false` | 未核实竞品默认，建议对照 | Gboard 默认开。豆包、微信的默认值没有截图证据 |
| `ui.android.row_height_portrait` | `60dp` | 拿 db_26/wx_26 截图量一下 | 4 行共 240dp，加上 44dp 条区，偏高的可能性大，未核实 |
| `ui.android.space_long_press` | `none` | 可保持，或改成 `switch_ime` | Gboard 长按空格是切换输入法；我们已经放在中英键长按里。低优先级 |
| `debug.log_level` | `info` | 见第 3 节 | 这个设置在安卓上没人读 |

其余重点项都合适，不用改：
- 候选字号 21sp
- 全角关、中文标点开
- 模糊音关（豆包也是「已关闭」，见 db_set_main.png）
- 联想 smart + continuous（`mobile.association`）
- 自动学习开、拼写纠错开、纠错提示关
- 振动开 30%、按键音关、长按 300ms
- 主题跟随系统
- 简繁都关
- 单手关
- 数字页 pad9

---

## 2. 电脑版有而安卓缺的设置项

用脚本对比了两份清单：电脑版 `wind-setting/src/assets/settings_manifest.toml` 共 363 项，安卓清单缺 283 项。全部键都在 `wind-config/src/config_schema.rs` 的注册表里，引擎都支持。"核心已支持"指的是协调器共用逻辑，手机上是否真生效逐项未核实。

### 2.1 首发必须加（核心已支持，只差界面）

| 配置键 | 电脑版名称 | 理由 |
|---|---|---|
| `stats.track_english` | 统计英文输入 | 统计页已经有了；`mobile-ux-reference.md:171` 的规划里就列了这一项，只差一行清单 |
| `schema.pinyin.sentence_uses_user_words` | 组词时使用自造词 | 普通开关，影响整句质量 |
| `input.default.state_scope` | 中英状态作用域 | 可选。手机上"每个 App 记住中英状态"很实用（微信打中文、浏览器打英文），未核实安卓侧是否生效 |
| `input.punct.custom_mappings#slash_dun` | `/` 键输出顿号 | 只有在手机中文标点布局里没有 `、` 时才需要，未核实 |
| （缺功能，不是缺键）「恢复默认设置」 | 规划写在 `mobile-ux-reference.md:197` | 设置页没有全局重置。用户把配置改乱了只能清数据，词库也会跟着丢 |

说明：`schema.pinyin.grammar.enabled` 不算缺，安卓有意改用 `mobile.grammar.enabled`。

### 2.2 可以后加

- **码表进阶**：`single_code_input` / `single_code_complete`（精确匹配）、`z_key_repeat` / `z_key_action`、`wildcard*`（通配，五笔手机用户会用）、`frequency.*` 的半衰期和保护前 N 项、`auto_phrase.*` 的长度和次数、`english_merge.*` 的位置和条数。
- **拼音进阶**：`sentence_count` / `sentence_max_count`、`aux_code.*`（辅助码，双拼用户需要）、`completion.*`、`auto_learn.*` 的长度、`frequency.*`、`english_merge.*` 的位置。
- **混输**：`schema.mix.*` 剩下的 14 项（触发长度、否决上屏、前缀匹配……），放进进阶区。
- **英文**：`schema.english.frequency.*`、`auto_learn.*`、`case_follow_input`。
- **临时拼音和临时英文**：`input.temp_pinyin.*`、`input.temp_english.*`。规划里有这一节（`mobile-ux-reference.md:155`），但安卓的触发方式（工具栏「拼」）和桌面的触发键不一样，要先重新设计。
- **快捷输入**：`schema.quick_input.decimal_places` / `history_max`。`schema.mix_modes` 是 StructList，需要专门的编辑器。
- **字符**：`input.rare_phrase`、`input.scope_relax.*`、`input.punct.smart_list`、`input.punct.custom_*`（映射表编辑器）、`input.symbol.*` 的字符集和时限、`input.auto_pair.*_pairs`。
- **Emoji**：`input.emoji.max_hosts`。附加模块（`modules`）已经由 `EmojiModulesSection` 覆盖。
- **命令短语**：`input.phrase.prefix_match` / `min_prefix`、`input.cmdbar.backspace_collapse_group`。
- **网址、邮箱、Unicode**：`input.url.*`、`input.email.*`、`input.unicode.*`。手机上有 EditorInfo 的 URI/EMAIL 类型，价值比桌面低。
- **纠错**：`input.correction.custom`、误读表、`schema.frequency.exclude_blocks`。
- **生僻字和反查**：`input.rare_char.*`、`input.reverse.lookup_disabled_dicts`。
- **主题**：`ui.theme.name` / `ui.theme.style`。要等键盘配色接上核心主题系统才能加，见第 3 节。
- **方案**：`schema.primary_codetable` / `primary_pinyin`（主方案，影响临时拼音和反查码源）。
- **外接键盘**：`keys.toggle_mode_keys`、`keys.commit_on_switch`、`input.punct.follow_mode`、`input.capslock.*`。规划放在「高级 → 外接键盘」，`HardwareKeys` 已经接了。

### 2.3 手机上不需要

| 类别 | 键 | 原因 |
|---|---|---|
| 热键和按键绑定 | `keys.*` 剩下的所有项（功能快捷键、`select_key_groups`、`page_keys`、`highlight_keys`、`select_char_keys`、`overflow.*`、`key_actions` / `session_actions`、`ctrl_space_toggle`、`pin/delete_candidate`、`quick_input_select`）| 软键盘没有组合键；置顶和删词已经由候选长按菜单承担 |
| 物理键行为 | `input.enter_behavior`、`space/punct_on_empty_behavior`、`buffer_symbol_chars`、`numpad_*`、`alt_commit`、`english_case_cycle_key` | 依赖物理键盘或小键盘。`enter_behavior` 以后可能给外接键盘用 |
| 候选窗定位和尺寸 | `ui.candidate.position_mode/offset_*/custom_*/min_window_*/min_rows/first_show_mode/shadow/hide_window/double_click_screenshot/flip_when_above/swap_preedit_when_above/pager_*/page_number_display/grid_*/layout/per_page*/max_chars/comment_max_chars_*/comment_above/index_labels`、`ui.candidate.font_size`、`ui.font.*` | 桌面浮窗专属。安卓用的是 `ui.android.candidate_text_sp` 等自己的键 |
| 编码显示 | `ui.candidate.preedit_display`、`syllable_separator` | 安卓用 `ui.android.inline_preedit` |
| 候选注释体系 | `ui.comment.*`、`ui.comment_dicts`、`auto_comment_dicts`、`input.cmdbar.candidate_prefix` | 安卓用 `ui.android.candidate_comment` 加长按提示卡（`TooltipSectionsSection`）。以后可以评估注释模板 |
| 悬停提示、状态气泡 | `ui.tooltip.*`（`sections` 已由 `TooltipSectionsSection` 覆盖）、`ui.status.*`、`ui.mode_indicator.style` | 没有悬停；手机状态显示在键面和空格上 |
| 桌面工具栏、任务栏图标 | `ui.toolbar.*`、`ui.labels.*`、`ui.langbar.*` | 安卓有自己的 `ui.android.toolbar_pins` |
| 候选窗布局意图 | 所有 `*.candidate_layout`、`schema.mix_modes#candidate_layout` | 手机只有横条和展开面板 |
| 性能和系统 | `schema.keep_all_loaded`、`schema.dict_cache_policy`、`system.dota2_compat*` | 桌面内存策略和游戏兼容 |
| 日志工具 | `local.tsf_log_*`、`local.setting_log_level`、`local.open_dirs`、`local.log_archive` | TSF 和设置程序是桌面组件。安卓需要的是"导出日志"，见第 3 节 |

---

## 3. 主要功能缺口

### 3.1 发版阻塞

| 功能 | 现状 | 证据 |
|---|---|---|
| 日志隐私 | **有问题** | `RustSession.kt:68` 写死 `initLogging("info,wind_coordinator=debug")`，正式包也这样。协调器 debug 日志里有用户文本，例如 `wind-coordinator/src/handle_addword.rs:719` 的 `debug!("auto-learn: 用户词库已有 {} -> {}，跳过", code, text)`。输入法把用户输入写进 logcat，上架审核和隐私声明都过不去。正式包应降到 `warn`，或改成跟随设置项 |
| 「输入法服务日志级别」设置项 | **形同虚设** | `debug.log_level` 在 `wind-mobile/src`、`rust/wind-android/src`、Kotlin 里都没有读取方（grep 无结果），但设置页「高级 → 调试」里显示着。要么接到 `initLogging`，要么从清单删掉 |
| 测试台暴露在正式包里 | **有问题** | `AndroidManifest.xml` 里 `TestPadActivity` 是 `exported="true"`，并且会 `Log.i` 输入的文本（`TestPadActivity.kt:263`）。应该挪到 `src/debug/` |
| 备份泄露 | **有问题** | `android:allowBackup="true"`，没配 `dataExtractionRules` / `fullBackupContent`。`filesDir/clipboard.json`（剪贴板历史，`ClipboardStore.kt:34`）、个人词库和统计都会进 Google 云备份或设备迁移。至少要排除 clipboard.json |
| 隐私政策和权限说明 | **没有** | 代码里没有隐私页（grep「隐私」只命中词库数据和许可证文本）。「关于」页只有版本号和开源许可（`SettingsActivity.kt:972`）。启用输入法时系统会警告"可能收集你输入的所有文字"，主流输入法都在首次启用时给出说明并链接隐私政策 |
| 默认方案和首次引导 | **部分有** | 有首次选择卡（`Service:1274`，"选一个输入方案"），但只在输入法里弹一次，是一个列表，没有缩略图。`android-ime-features.md:305-308` 计划在 v0.4 做首启引导。最低要求：出厂兜底改成拼音，或保证不选就不能关 |
| 正式签名和版本号 | **部分有** | 正式包只在 `local.properties` 配了个人密钥时才签（`build.gradle.kts:74`）；`versionName = "0.3.0-dev.76"`。发版时要定正式版本号，并确认签名密钥就是以后一直用的那把（换密钥后用户无法覆盖安装）|

### 3.2 首发建议有

| 功能 | 现状 | 证据和备注 |
|---|---|---|
| 设置页的启用和切换引导 | 已有 | `SettingsActivity.kt:137-215` 的 `ImeSetup`，首页状态卡 `:544` |
| 键盘高度调节 | 已有 | 横竖屏分开的行高、底部和左右边距（manifest `keyboard` 页） |
| 横屏适配 | 部分有 | 横屏有独立行高（`Service:477-481`），`onEvaluateFullscreenMode = false`（`:389`）。没有横屏专用布局，也没有平板分体或悬浮键盘。建议真机过一遍横屏 |
| 主题和皮肤 | 部分有 | 只有内置浅色和深色两套（`Palette.kt:57,79`），没接 `ui.theme.*`（manifest 第 1003 行注释）。首发可以只做深浅色，但要在说明里讲清楚 |
| 按键音和振动 | 已有 | `KeyHaptics`、`ui.android.sound`。按下和长按分别配置没做 |
| 词库备份和恢复 | 部分有 | `DictBackup.kt`：WindDict 格式和电脑版互通。**设置（两份 config.toml）没法导出**，换机只能带词库 |
| 词库同步 | 没有 | WebDAV 是规划（`android-ime-features.md:132`），放后续 |
| 崩溃反馈 | 部分有 | `CoreGuard.kt` 兜住了 Rust panic，首次出错提示一次。没有 Kotlin 侧的 `UncaughtExceptionHandler`，也没有"导出日志 / 反馈"入口（grep 无结果）。没有云端崩溃收集的前提下，至少要做"复制或分享最近日志"和反馈渠道（issue 链接）。`mobile-ux-reference.md:198` 规划过「关于 → 反馈」 |
| 恢复默认设置 | 没有 | 见 2.1 |
| 剪贴板、表情、符号、文本编辑、语音转发、单手、无痕、快捷面板、工具栏定制 | 已有 | `ToolbarItem.kt:19-34`、`panels/*` |
| 候选长按（删词、置顶） | 已有 | `CandidateMenuPopup`，`android-ime-features.md` §14.11 |
| 物理键盘 | 部分有 | 按键已直连引擎；浮动候选窗没做 |

### 3.3 后续

- Direct Boot：没有 `directBootAware`。重启后锁屏界面用不了本输入法，系统会临时换别的，不会崩。
- 自绘主题导入和背景图、Monet 取色
- 悬浮键盘、分体键盘
- Autofill 内联建议（`onCreateInlineSuggestionsRequest` 没实现）
- 状态栏输入法图标
- 键盘内的命令短语面板
- 方案级覆盖
- 滑行输入
- 九宫格拼音已确定不做

---

## 4. 首发前待办清单（按优先级）

1. **正式包日志降级**：`RustSession.kt:68` 的过滤串在正式包里改成 `warn`；或者把 `debug.log_level` 接到 `initLogging`，同时修掉"设置项没人读"的问题。 ✅ 已做（a81f89c）
2. **`TestPadActivity` 移出正式包**：挪到 `src/debug/AndroidManifest.xml`，或改成 `exported=false`。 ✅ 已做（74d7d7f）
3. **备份排除规则**：加 `dataExtractionRules` / `fullBackupContent`，至少排除 `clipboard.json`；个人词库是否允许云备份要做个决定。 ✅ 已做（4fbad42）
4. **隐私政策**：「关于」页加隐私政策（不联网、数据只存在本地）和权限说明。 ✅ 已做（070ec61）
5. **出厂兜底方案改成拼音**：`RustSession.kt:74`、`Service:346`、`assets/data/config.toml` 里的 `schema.active` 一起改。同时确认首次选择卡不会在用户没看到时就记成已弹。 ✅ 已做（adcddc3）
6. **同步安卓 `assets/data/config.toml`**：和主仓 `data/config.toml` 对齐，删掉废弃键，并加对照测试，防止再次滞后。 ✅ 已做（fc7eaf7）
7. **补反馈渠道**：「关于」页加反馈入口（issue 链接）和"分享诊断日志"；Kotlin 侧加未捕获异常记录。 ✅ 已做（f25764d）
8. **恢复默认设置**：做两档，键盘设置和引擎设置分别恢复，词库不动。 ✅ 已做（1cb9499）
9. **真机定三个默认值**：中英文自动配对开还是关，以及软键盘上怎么跳出配对；竖屏行高 60dp 是否偏高；按键气泡默认开还是关。
10. **清单补 `stats.track_english` 和 `schema.pinyin.sentence_uses_user_words`**：都只差一行清单。 ✅ 已做（ed20d96）
11. **统一自动补空格的注释**：确认归键盘还是归核心，改掉 manifest 第 512 行和 `Service:112` 之间的矛盾。 ✅ 已做（a0ac6f2）
12. **`KeyboardView.kt:104` 初值改成 `false`**：和 manifest 保持一致。 ✅ 已做（a0ac6f2）
13. **定正式版本号和签名密钥**：`versionName` 去掉 `-dev`，`versionCode` 照规则 +1，确认 release 用的是长期密钥。
14. **跑一轮横屏和大屏冒烟**：行高、边距、单手、展开候选面板。
15. **决定 Emoji 候选和拼音英文候选手机上是否默认开**：要开就走 `mobile.*` 覆盖键，按 `MobileConfig` 的规则登记。
16. **写一页设置导出和导入的方案**：首发可以不做，但要在文档里说明换机只能带词库。
17. **系统输入法列表显示当前方案**：每个启用的方案注册一个子类型，与当前方案双向同步。 ✅ 已做（ee46659、b51e286）

### 需要拍板的问题
- [x] 出厂兜底方案改成拼音，还是保留五笔加一个更强的首启引导？→ 改拼音，首次卡片仍可选五笔
- [x] 个人词库要不要进 Android 系统备份？→ 设置与个人词库进，剪贴板历史、缓存、日志不进
- [x] 正式包里 `debug.log_level` 是删掉，还是接成真正生效的设置？→ 接成真设置，安卓出厂 warn
- [ ] 中文引号和括号自动配对，手机首发开还是关？
- [x] 隐私政策放在 App 里，还是放官网再链接过去？→ App 内二级页 + 链官网首页（官网还缺单独的隐私政策页）

附：电脑版缺失键全量列表见 `（全量缺失键列表未入库，可按需重新生成）`。
