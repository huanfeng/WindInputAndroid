# 安卓输入法功能调研

> 状态：调研完成（2026-08-13）
> 来源：fcitx5-android 源码探索（`../ref/fcitx5-android`）+ 主流输入法网络调研
> （Gboard、搜狗、百度、讯飞、FlorisBoard、HeliBoard）+ Android 系统 IME API。
> 用途：确定 WindInput Android 的功能范围与优先级，指导框架预留。

## 0. 优先级与落点约定

- **P0** 键盘可用性基线（没有就不算合格输入法）
- **P1** 重要（主流输入法标配，一期内完成）
- **P2** 增值（二期）
- **P3** 远期/待定
- 落点：`K` = Kotlin UI 侧，`R` = Rust 核心（桌面已有能力），`K+R` = 双侧协作

## 1. 按键与手势

| 功能 | 优先级 | 落点 | 参考与说明 |
|---|---|---|---|
| 长按气泡（多字符网格，横移选择） | P0 | K | fcitx5: `PopupKeyboardUi.kt`，长按弹网格、不抬指横移、抬手上屏 |
| 按键预览气泡（点按放大显示） | P0 | K | fcitx5: `PopupEntryUi.kt`；已有设置项占位 `ui.android.key_preview` |
| 空格滑动移光标 | **P0** | K | 用户点名。fcitx5: 横向滑动按 10dp 阈值折算 N 次左右方向键，可带震动；Gboard 同款交互 |
| 退格滑动批量删除 | P1 | K | fcitx5: 左滑扩选、抬手整段删；Gboard: 从退格左滑选多词 |
| 字母键上滑出副字符（数字/符号） | P1 | K | fcitx5: `KeyDef.Behavior.Swipe`，方向可配，阈值 36dp |
| 长按重复（退格连删） | P0 | K | 已实现 ✅ |
| Shift 双击/长按锁定大写 | P1 | K | fcitx5: `Behavior.DoubleTap/LongPress` |
| 空格长按行为可配置 | P2 | K | fcitx5: 无/轮换输入法/切中英/系统选择器 |
| 多指分离触控 | P1 | K | fcitx5: `isMotionEventSplittingEnabled`；快打场景必需 |
| 滑行输入（glide typing） | P3 | K+R | FlorisBoard/Gboard 有；HeliBoard 依赖闭源库。工程量大，中文价值低于英文，远期评估 |

**框架预留建议**：引入 fcitx5 的**声明式按键行为**模式——`KeyDef` 同时可挂
Press / LongPress / Repeat / Swipe / DoubleTap + Popup，新手势的边际成本极低。
我们的布局 TOML 应预留这些字段（`long_press = [...]`、`swipe_up = "..."` 等）。

## 2. 布局与键盘形态

| 功能 | 优先级 | 落点 | 参考与说明 |
|---|---|---|---|
| 26 键 qwerty | P0 | K | 已实现 ✅ |
| 符号/数字层 | P0 | K | 已实现 ✅（后续按 fcitx5 符号面板扩充分类） |
| **九宫格（T9）拼音** | **P1** | **K+R** | fcitx5-android 没有、商业输入法全有，中文用户刚需。引擎侧歧义展开在 Rust 做（词图天然支持），键盘侧新布局 + 分词候选交互 |
| 数字键盘（计算器式，数字/电话框自动切） | P0 | K | fcitx5: `NumberKeyboard.kt`；我们已有符号层自动切换，需专门数字布局 |
| 自定义布局（用户 TOML） | P1 | K | 我们已有三级覆盖机制 ✅，fcitx5 反而没有（其 Planned Feature） |
| 键盘高度/左右边距/底部边距（横竖屏独立） | P1 | K | fcitx5: `twinInt` 横竖屏成对配置，值得抄 |
| 单手模式（缩窄靠边） | P2 | K | fcitx5 没有；Gboard/搜狗/HeliBoard 有 |
| 悬浮键盘 | P2 | K | Gboard 有完整实现；平板场景重要 |
| 分体键盘（平板横屏） | P3 | K | Gboard/HeliBoard 有 |
| 五笔用户的编码提示行 | P1 | K+R | 桌面 show_code_hint 能力的移动端呈现 |

## 3. 候选体系

| 功能 | 优先级 | 落点 | 参考与说明 |
|---|---|---|---|
| 横向候选条 | P0 | K | 已有占位 ✅；fcitx5 有三种填充策略 |
| **展开式候选面板**（全键盘区网格） | P0 | K | 中文输入法刚需（重码多）。fcitx5: Grid/Flexbox 两种布局 + Paging3 分页 + 展开按钮状态机 |
| 候选长按操作（删词/置顶/反查） | P1 | K+R | 桌面右键菜单语义的移动端映射（shadow pin/delete 是 Rust 现成能力） |
| 剪贴板建议条（复制后提示粘贴） | P1 | K | fcitx5: 30s 超时 + 敏感内容遮蔽；Gboard 同款 |
| 内联建议 Inline Suggestions（Autofill，Android 11+） | P2 | K | 系统 API：密码管理器等的填充建议展示在候选条，隐私上系统托管 |
| 密码框数字行 | P2 | K | fcitx5: 检测密码框在工具栏位置放数字行 |
| 工具栏/候选区状态机 | P1 | K | fcitx5: Empty/Toolbar/Clipboard/NumberRow/InlineSuggestion 五态切换，架构值得抄 |

## 4. 功能面板（用户点名重点）

| 功能 | 优先级 | 落点 | 参考与说明 |
|---|---|---|---|
| **剪贴板历史面板** | **P0** | K | 用户点名。fcitx5 全套：Room 持久化、瀑布流、置顶、编辑、滑动删除+撤销、一键清空(跳过置顶)、条数上限、敏感遮蔽；Gboard: 自动存 1 小时+置顶常用 |
| **表情面板（多行网格 + 分类 Tab + 最近使用）** | **P0** | K | 用户点名。fcitx5: Emoji 9 分类/颜文字 6 分类/符号 7 分类共用一套 Picker 框架，分类 Tab + 分页 + 最近使用置首 + 肤色变体长按 + 不可渲染过滤 |
| **文本编辑面板（光标移动/选择/剪切复制粘贴）** | **P0** | K | 用户点名"移动光标/特殊键盘"。fcitx5: 方向键、行首行尾、选择模式开关、全选/剪切/复制/粘贴/退格 |
| 快捷短语面板 | P1 | K+R | 桌面 wind-phrase 是 Rust 现成能力，K 侧只做面板 |
| 状态区（模式开关集合：中英/简繁/表情/设置入口） | P1 | K+R | fcitx5: 引擎动态状态项 + 固定入口；对应桌面工具栏语义 |
| Unicode/特殊符号面板（全角、数学、箭头等） | P1 | K | fcitx5 符号面板 7 分类；对应桌面快符模式的数据 |
| 计算器/日期等快捷输入 | P2 | R | 桌面 wind-quick-input 现成，候选条直接出结果 |

**框架预留建议（本调研最重要的架构结论）**：引入 fcitx5 的 **InputWindow
窗口管理器**模式——键盘、剪贴板、表情、文本编辑、状态区都是可互换的
`InputWindow`，共用候选条下方区域，带进出场动画。新增面板边际成本极低。
我们当前 `LinearLayout(候选条+键盘)` 的结构应尽早重构为该模式。

## 5. 外观与主题

| 功能 | 优先级 | 落点 | 参考与说明 |
|---|---|---|---|
| 深浅色跟随系统 | P0 | K+R | 已实现 ✅（`Palette.kt` 内置浅/深两套，按 uiMode 选）；主题 TOML 的 light/dark 双态变体是桌面现成设计 |
| 主题系统（调色板共享 + 键盘域节点） | P1 | K+R | 见 architecture.md §3.4；主题**制作/编辑走 PC 工具站**（§3.7），手机端只做导入+应用，不做 fcitx5 式 21 色槽编辑器 |
| 背景图片主题（裁剪/亮度） | P2 | K | 制作走工具站；手机端仅应用（本地图片裁剪可保留最简版） |
| Monet 动态取色（Android 12+） | P2 | K | fcitx5: `ThemeMonet.kt`，与我们调色板体系可并存（动态色作为一个特殊主题） |
| 主题导入导出 | P2 | K | fcitx5 本地文件管理；我们天然是 TOML 文件，导入导出免费 |
| 按键音/震动细分（按下/长按分别配置，按键类型区分音效） | P1 | K | 按键音基础版 ✅（系统四种键音按键型分发，音量随系统）；按下/长按细分未做。fcitx5: `InputFeedbacks.kt`；我们已有基础震动开关 ✅ |

## 6. 系统集成

| 功能 | 优先级 | 落点 | 参考与说明 |
|---|---|---|---|
| EditorInfo 场景感知（密码/数字框） | P0 | K | 已实现基础版 ✅；扩展：email/URI 场景换底排键位 |
| 回车键动作标签（发送/搜索/下一项） | P0 | K | imeOptions 驱动回车键显示，主流标配 |
| 全面屏/导航栏适配（edge-to-edge） | P0 | K | fcitx5: 三档处理 + Android 15 强制 e2e 自动降级。**Android 15 (targetSdk 35) 强制 edge-to-edge，必须一开始就做对** |
| 语音输入入口（转发系统 voice IME） | P1 | K | 已实现 ✅（快捷面板，系统里有 voice 子类型的输入法才出入口）；自研识别不做 |
| 输入法切换（语言键轮换 + 长按选择器） | P1 | K | 系统选择器已接 ✅（快捷面板「切换输入法」）；自绘选择对话框待定 |
| 物理键盘支持（隐藏软盘 + 浮动候选窗） | P2 | K+R | fcitx5: 跟随光标的浮动候选窗（onUpdateCursorAnchorInfo）；桌面键事件模型在 Rust 侧免费 |
| Direct Boot（锁屏前可用） | P1 | K | 设备加密存储同步关键设置；输入法作为解锁前组件必须处理 |
| 隐私模式（incognito，密码/隐私场景不学词） | P1 | K+R | FlorisBoard 有；对接 Rust 侧 = 暂停用户词频学习 |
| 手写笔手写（Android 14+ stylus API） | P3 | K | 系统级 API，平板+笔场景；远期 |
| 备份/恢复（设置+用户词打包 zip） | P1 | K+R | fcitx5: `UserDataManager`；桌面 wind-transfer 是现成序列化通道，也是未来多端同步的数据面 |

## 7. 中文输入法特有能力（Rust 核心带来的差异化）

以下桌面能力经 Rust 核心"免费"获得，K 侧只需入口/呈现：

| 能力 | 呈现建议 |
|---|---|
| 五笔/拼音/双拼/混输多方案 | 状态区方案切换 + 设置页方案管理 |
| 以词定字、临时拼音、临时英文 | 键盘模式指示 + 候选条 |
| 简繁转换 | 状态区开关 |
| 快捷加词/造词 | 候选长按菜单 + 加词面板 |
| 反查（拆字/编码/读音） | 候选长按 tooltip |
| 命令直通车 cmdbar | 候选条特殊候选 |
| 用户词频/shadow 置顶删除 | 候选长按菜单 |
| 搜狗 scel 词库导入 | P2；fcitx5 有实现可参考（`data/pinyin/dict/`），wdat 转换在 Rust 做 |

## 8. 小胖输入法（PangIME）专项参考

> 用户指定参考（https://pangime.com/ ）。C 语言核心、Win/Mac/iOS/Android/Linux
> 全平台的个人开发输入法，定位"打造您自己的专属输入法"——灵活性路线与主流
> 商业输入法差异明显，与我们的开源/可定制定位高度同频。

### 8.1 独特功能与对我们的启示

| 功能 | 机制 | 对 WindInput Android 的启示 | 建议 |
|---|---|---|---|
| **双检索** | 结构化词条 `前缀#后缀#编码#参数`（如 `小胖#输入法#sr#1#0`）：**结合已上屏内容**简化后续输入，用于分离重码、生成上下文简码；支持多前缀、可选段 `我[还]有[一]个`、删除前缀实现替换纠错 | 上下文感知的码表词条是引擎级创新，主流输入法没有。五笔重码分离场景价值高；与桌面 top-commit/简码机制正交 | P3，Rust 引擎侧远期评估（需 committed_text 上下文，协调器已有该状态） |
| **快速纠错** | 自动发现并改正输入错误，无需用户干预 | 对应桌面拼音 fuzzy/纠错能力的延伸 | 观察其具体行为后评估 |
| **WebDAV 码表/配置同步** | 官方或**自架服务器**，同步码表与配置 | **直接命中我们的多端同步规划**：WebDAV 自架友好、无云服务依赖、与开源定位一致、客户端实现简单。我们的同步载荷（用户层 config.toml + wind-transfer 导出）天然是文件，WebDAV 是最合理的第一后端 | **P2，采纳为同步方案首选后端** |
| **txt 开放码表 + 内置码表编辑器 + 网络码表** | 码表明文开放、App 内直接编辑、支持远程码表源 | 我们 yaml/TOML 码表同样开放；码表编辑**不在手机端做**，走 PC 配套工具站（见 architecture.md §3.7），手机端只做导入；网络码表 = 远程词库源订阅 | 编辑走工具站；导入 P2；网络码表 P3 |
| **AI/语音 = 用户自带 API key** | 接入 openai/讯飞 API，用户自行配置 key，不自研 | 与"不做自研 AI"决策兼容的折中形态：核心永不依赖云，留给愿意配 key 的用户 | P3 可选插件形态，不进核心 |
| **二次开发接口** | 允许用户定制扩展 | 对应我们的布局/主题/清单 TOML 开放 + 未来 cmdbar 扩展 | 已在路线上 |

### 8.2 与桌面 WindInput 的能力重合

小胖的「以词定字」「命令直通车」「词频调整」「图形化输入统计」桌面 WindInput
均已具备（wind-coordinator / wind-cmdbar / wind-store / stats），经 Rust 核心
在 Android 免费获得——印证第 7 节判断。

## 9. 明确不做 / 远期评估

- **AI 类**（讯飞 AI 键/帮写/润色、AI 剪贴板）：不做，与开源轻量定位冲突
- **云输入/云候选**：不做（隐私定位）
- **自研语音识别/手写识别**：不做；语音走系统 voice IME 转发
- **在线皮肤商店/字体商店/电子宠物**：不做；主题 = 本地 TOML 文件
- **翻译/表情包搜索/GIF**：远期评估
- **滑行输入**：P3，中文场景收益有限

## 10. 对现有框架的三条改造建议（按紧迫度）

1. **InputWindow 窗口管理器**（做剪贴板/表情/文本编辑面板前必须先有）：
   面板区抽象为可互换窗口，候选条常驻其上。建议下一个迭代就做，
   否则每个面板都会长成特例。
2. **KeyDef 行为声明化**：布局 TOML 的 key 条目扩展
   `long_press`/`swipe_up`/`double_tap` 字段，`KeyboardView` 手势识别
   泛化为行为分发（当前退格长按重复是硬编码特例）。
3. **横竖屏成对配置**：键高/边距类设置项引入 fcitx5 的 twinInt 模式
   （竖屏值+横屏值成对），设置清单 item 类型预留 `twin_int`。

## 11. 调研来源

- 本地：`../ref/fcitx5-android` 源码探索（功能与实现类清单见本文各表）
- [Gboard 隐藏技巧（HowToGeek）](https://www.howtogeek.com/gboard-has-tons-of-hidden-tricks-you-probably-arent-using/)
- [Gboard 快捷操作（Computerworld）](https://www.computerworld.com/article/1614593/gboard-shortcuts-android.html)
- [开源 Gboard 替代品实测（MakeUseOf）](https://www.makeuseof.com/best-open-source-gboard-alternatives-tested/)
- [FlorisBoard 功能介绍（LinuxLinks）](https://www.linuxlinks.com/florisboard-modern-keyboard/)
- [HeliBoard（F-Droid）](https://f-droid.org/en/packages/helium314.keyboard/)
- [搜狗 vs 百度输入法对比（少数派）](https://sspai.com/post/29229)
- [讯飞输入法功能（小米应用商店）](https://m.app.mi.com/detail/294)
- [Android Stylus 手写 API](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields)
- [IME 集成 Autofill 内联建议](https://developer.android.com/identity/autofill/ime-autofill)
- [小胖输入法官网](https://pangime.com/)、[双检索机制](https://pangime.com/shuangjiansuo.html)、[功能介绍（ababtools）](https://ababtools.com/?post=6535)

## 12. 对照 fcitx5-android 的现状核对（2026-09-14）

fcitx5-android 是架构最像本项目的参照（Kotlin 壳 + 原生引擎推送候选）。逐项对过它的
`FcitxInputMethodService` 覆写点、`input/` 各组件和 `AppPrefs` 设置项之后，基础功能层面
的差集如下。**只列基础功能**，方案/词库这类由 Rust 核心带来的能力不在此比。

### 本轮补上的

| 缺口 | 做法 | 守它的用例 |
|---|---|---|
| 收键盘 / 点编辑框后编码残留 | `onFinishInputView`、`onViewClicked`、`onUpdateEditorToolType` 三处作废组合（核心 ESC 语义） | emu-test [15] [16] |
| 按键音 | 系统 `AudioManager` 四种键音按键型分发；`ui.android.sound` | — |
| 长按判定时长 | `ui.android.long_press_delay`，退格连删起始延迟与之共用 | — |
| 语音输入 | 找已启用输入法里第一个 `voice` 子类型转发；没有就不摆入口 | — |
| 切换输入法 | 系统选择器（`showInputMethodPicker`） | — |
| 深浅色 | `Palette.kt` 内置两套，`onCreateInputView` 按 uiMode 选；全部视图颜色只从槽位取 | 手工：`cmd uimode night yes` 截图 |

### 仍缺的（按建议顺序）

| 缺口 | fcitx5 对应 | 说明 |
|---|---|---|
| 物理键盘：按键直连引擎、浮动候选窗 | `onKeyDown/onKeyUp` → `forwardKeyEvent`；`onUpdateCursorAnchorInfo` 定位浮窗 | 平板 + 蓝牙键盘场景。桌面键事件模型在 Rust 侧现成，K 侧要做的是 KeyEvent → VK 映射与浮窗 |
| 键盘左右 / 底部边距（横竖屏成对） | `keyboardSidePadding` / `keyboardBottomPadding` 的 twinInt | 大屏单手够不着两侧时的标配 |
| Direct Boot | `directBootAware` + 设备加密存储 | 锁屏解锁前的密码框用不了本输入法，系统会临时换别的 |
| 空格长按行为可配 | `spaceKeyLongPressBehavior` | 现为无动作 |
| 内联建议（Autofill） | `onCreateInlineSuggestionsRequest` | 密码管理器填充条 |
| 候选长按（删词 / 置顶） | 无（fcitx5 走引擎菜单） | Rust 侧 shadow pin/delete 现成，K 侧缺手势 + 菜单 |
| 状态栏输入法图标 | `showStatusIcon` | 小事，但主流都有 |
| 备份 / 恢复 | `UserDataManager` | 桌面 wind-transfer 是现成通道 |

fcitx5 有而**刻意不做**的：主题编辑器（21 色槽）——制作走 PC 工具站；插件系统——本项目
引擎单一；表情肤色变体、颜文字分类——二期按需。
