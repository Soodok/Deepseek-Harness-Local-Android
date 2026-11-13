# 新手引导重设计（v2）· 设计说明与实现映射

- 日期：2026-11-12
- 状态：**设计稿完成，未落 Android**（HTML 原型可交互，32 张截图已出）
- 原型：`docs/ui-preview/2026-11-12-onboarding-redesign.html`
- 截图：`docs/ui-preview/shots/onboarding-redesign/`（手机深/浅各 9 页、平板 9 页、运行环境四态、画廊）
- 截图脚本：`docs/ui-preview/shoot-onboarding.mjs`（CDP，390×844 / 820×900，dsf=2）

## 1. 现状与问题（有截图依据）

| 问题 | 证据 |
| --- | --- |
| 首启第一屏直接是语言选择，没有产品定位说明 | `_tmp_shots/onb_01_start.png`、`onb_02_lang.png` |
| 语言页 6 张卡共用同一个地球图标，副标题重复 | `onb_02_lang.png` |
| 模式与能力开关挤在同一页（`pagePriv`），小屏需滚动才能看全 | `onb_04_done_progress.png` |
| 完成页只有一行运行状态 + 进度条，解压中没有层级与预期 | `onb_05_done_progress.png` |
| 步骤只能「上一步/下一步」，手机端没有一次性总览 | 布局仅 `btnSteps` 抽屉 |

当前实现为 7 步：语言 / 方向 / 外观 / 强调色 / 屏幕 / 权限与能力 / 完成
（`OnboardingActivity.kt:59-67`、`:175-183`；`activity_onboarding.xml` 对应 `pageLanguage` … `pageDone`）。

## 2. 目标与不做什么

**目标**

1. 首启 30 秒内完成，且每一步只做一个决定。
2. 运行环境解压「前置告知 + 后台进行 + 四态可见」，不再让用户对着空白等 80 秒。
3. 平板/横屏有独立步骤轨，手机有总览抽屉，任意页可跳转。
4. 文案与控件一一对应现有 `ob_*` 字符串体系，可直接落地。

**不做**

- 不做独立欢迎动画页/视频；欢迎页就是第 0 页，可一步进入设置。
- 不做账号、网络权限、模型 Key 配置（放网页设置里）。
- 不改 `ThemedActivity` 主题体系与既有 UI token 门禁。

## 3. 信息架构（9 页 = 欢迎 + 8 步）

| # | 页 | key（建议） | 目的 | 主操作 | 可跳过 |
| --- | --- | --- | --- | --- | --- |
| 0 | 欢迎 | `pageWelcome` | 说明这是什么、做完要多久 | 开始设置 · 约 30 秒 | 是（直接下一步） |
| 1 | 语言 | `pageLanguage` | 界面语言（只影响外壳） | 选择即生效 | 是（默认跟随系统） |
| 2 | 方向 | `pageOrientation` | 默认竖屏/横屏/自动 | 选卡片 | 是 |
| 3 | 外观 | `pageTheme` | 浅色/深色/跟随系统 | 选卡片 | 是 |
| 4 | 强调色 | `pageAccent` | 6 色，按钮/选中/进度联动 | 选圆点 | 是 |
| 5 | 屏幕 | `pageScreen` | 页面缩放、设置页布局、沉浸状态栏 | 滑杆/分段/开关 | 是 |
| 6 | 模式 | `pageMode`（新，自 `pagePriv` 拆出） | 普通 / Shizuku / Root | 选卡 + 前置条件提示 | 否（默认普通） |
| 7 | 能力 | `pageCaps`（新，自 `pagePriv` 拆出） | 通知/读屏/剪贴板/麦克风 + 更多折叠 | 开关 + 推荐预设 | 是 |
| 8 | 完成 | `pageDone` | 运行状态 + 选择回顾 + 首次对话 | 开始第一次对话 | — |

步骤计数只算 8 步（欢迎页不计），与现有 `ob_wizard_step_fmt` 的 `index+1 / pages.size` 语义一致；落地时把 `welcome` 从计数中排除。

## 4. 设计令牌

深色（`data-theme=dark`）

| token | 值 | 用途 |
| --- | --- | --- |
| `--bg` / `--bg2` | `#0b131c` / `#0e1a26` | 页面渐变底 |
| `--surface` / `--surface2` | `#121d2a` / `#0d1722` | 卡片 / 次级卡 |
| `--line` | `#223243` | 1dp 描边 |
| `--ink` / `--ink2` | `#e9f0f8` / `#93a6b8` | 主文字 / 次文字 |
| `--accent` | `#4fa8e0`（天蓝） | 主操作、选中、进度 |
| `--ok` / `--warn` / `--danger` | `#34d399` / `#fbbf24` / `#fb7185` | 就绪 / 等待 / 失败·高风险 |
| `--glow` | `rgba(79,168,224,.22)` | 完成页光晕 |

浅色（`data-theme=light`）：`--bg #f3f7fb`、`--bg2 #e7eff8`、`--surface #ffffff`、`--line #dbe5f0`、`--ink #0f172a`、`--ink2 #5a6b80`、`--accent #0284c7`、`--ok #0f9d6b`、`--warn #b45309`、`--danger #c2410c`。

强调色 6 色（深/浅）：天蓝 `#4fa8e0/#0284c7`、靛蓝 `#7f95ff/#4f6ef7`、青绿 `#3fc4b2/#0f9d8a`、紫罗兰 `#a982ff/#7c3aed`、琥珀 `#f5b454/#d97706`、玫红 `#fb7f9b/#e11d48`。对应现有 `Accent` 枚举顺序，落地时以 `colors.xml`/UI token 为准。

排版：标题 26sp/700，正文 13sp/1.6，卡片标题 13.5sp/600，辅助 11.5sp，标签 10sp；卡片圆角 16dp，按钮/胶囊 99dp；页内左右边距 26dp，卡片内 14dp，块间距 14dp；底部操作条高约 78dp。

## 5. 逐页规格

### 5.0 欢迎
- 结构：62dp 白鲸（官方 `ic_launcher_foreground` path）→ `DSH MOBILE` 眉标 → 两行主标题「手机里，住着一个会写代码的助手」→ 副文案「原生引擎 2.3 秒启动。终端、编译环境、手机操控，都在这块屏幕里。」→ 三行能力卡（原生引擎 / 真编译环境 / 手机操控）→ 底部运行环境胶囊 → 主按钮「开始设置 · 约 30 秒」。
- 运行胶囊：`ready` 显示「运行环境已在后台准备 · 已就绪」；`preparing` 显示百分比；`failed/lite` 显示对应提示。
- 顶部只有「全部步骤」胶囊，不显示进度条（第 0 页）。

### 5.1 语言
- 6 行卡：图标改用语言字形（文 / A / D / P / ก / Я），标题=本地名，副标题=英文名；选中行加 accent 描边与右侧勾。
- 底部提示：「随时可在「设置 → 界面语言」切换，不会重装环境。」
- 切换语言会 `recreate()` 重进引导页（`OnboardingActivity.kt:616`），但不会重装环境；语言值经 `LocaleHelper` 持久化。

### 5.2 方向
- 三张等宽卡：竖屏·手机 / 横屏·桌面 / 自动旋转，图形用设备轮廓线稿；下方「预览」卡显示迷你界面 + 自动说明。
- 说明：「自动 = 跟随设备旋转；横屏时主界面切桌面布局，笔记区更宽。」

### 5.3 外观
- 三张卡：浅色（太阳）/ 深色（月亮）/ 跟随系统（分割圆）；下方预览卡用 3 行骨架 + 按钮展示明暗差异。
- 说明：「深色不会改变网页内容；网页端主题在聊天页右上角设置。」

### 5.4 强调色
- 6 个 34dp 圆点，选中加白圈 + 勾；下方案例卡：主按钮「开始对话」、选中胶囊「已选中」、开关、进度条同步变色。
- 说明：「强调色只作用于 App 外壳，不影响网页端。」

### 5.5 屏幕
- 滑杆：页面缩放 60%–130%，默认 90%；示例卡实时缩放（标题 + 两行骨架）。
- 分段：设置页布局「列表 / 卡片」。
- 开关：沉浸式状态栏（说明「内容延伸到状态栏下方，更沉浸」）。

### 5.6 模式
- 三行单选：普通（推荐）/ Shizuku / Root（高风险）。Root 行在未检测到 su 时置灰并给原因；选中后仍需二次确认对话框（沿用 `ob_priv_root_warn*`）。
- 前置条件告警条：未检测到 su / Shizuku 未运行，黄色文字 + 图标。
- 说明：「模式随时可改；Root 需要两次确认，并会在关于页显示当前模式。」

### 5.7 能力
- 顶部推荐条：「推荐配置 通知 + 读屏 + 剪贴板 · 默认已勾选」，点击可一键套用。
- 四个主开关（默认开前三个）：通知、读屏与点按、剪贴板、麦克风。
- 「更多能力（6 项，可稍后开启）」折叠：定位、相机、短信（只读、逐条确认）、传感器…。
- 说明：「以后可在「设置 → 权限与能力」逐项修改；敏感能力默认每次询问。」

### 5.8 完成
- 上半：光晕 + 白鲸（呼吸动画）+ 标题四态 + 副标题。
- 运行状态卡四态（见 §6）。
- 选择回顾 chips：语言 / 主题 / 强调色 / 模式 / 缩放。
- 「接下来」三行：配置模型与 API Key（网页设置 → 模型）/ 试试终端（node -v）/ 连接远程主机（可选）。
- 主按钮「开始第一次对话」，次按钮「上一步」；本页隐藏顶部进度与「全部步骤」。

## 6. 运行环境四态（完成页 & 欢迎页胶囊共用）

| 状态 | 触发 | 标题 | 说明 | 控件 |
| --- | --- | --- | --- | --- |
| `preparing` | 完整包首次解压中 | 正在解压运行环境… 63% | 进度会在后台继续，完成后自动就绪 | 进度条 + 百分比 |
| `ready` | `ensureInstalled()` 完成 | 运行环境已就绪 | 引擎启动只需约 2 秒 | 绿点 |
| `failed` | 解压/校验失败 | 运行环境准备未完成 | 进入后可在「维护」里重试，或重新安装 | 红点 |
| `lite` | noruntime 包 | 精简包：尚未安装运行环境 | 点击开始后选择是否下载（约 130 MB） | 黄点 |

实测基线（emulator-5556，x86_64 完整包）：解压约 87 秒，`files/engine` 611 MB，线程 `onboarding-runtime-prefetch`。文案中的「约 30 秒」指设置流程本身，不含首次解压；欢迎页胶囊在 `preparing` 时必须显示真实百分比，避免预期落差。

字符串沿用：`ob_rt_preparing` / `ob_rt_pct` / `ob_rt_ready` / `ob_rt_failed` / `ob_rt_download`。

## 7. 平板与横屏

- 宽度 ≥600dp：左侧 206dp 步骤轨（标题「设置步骤」+ 9 项，当前项 accent 底、已完成项半透明），内容区 `max-width 620dp` 居中，顶部进度条与「全部步骤」隐藏。
- 平板仍保留底部「上一步 / 下一步」，按钮最大宽度随内容区收缩。
- 竖屏手机：顶部进度条（8 步比例）+「全部步骤」胶囊（打开抽屉，可跳转任意页）。

## 8. 文案与本地化

- 复用现有 87 个 `ob_*` 键；新增建议：
  - `ob_welcome_title` / `ob_welcome_lede` / `ob_welcome_feat_engine` / `ob_welcome_feat_toolchain` / `ob_welcome_feat_screen` / `ob_welcome_cta`
  - `ob_wizard_mode_title` / `ob_wizard_mode_desc` / `ob_wizard_caps_title` / `ob_wizard_caps_desc`
  - `ob_caps_recommend_line` / `ob_caps_more` / `ob_caps_more_hint`
  - `ob_done_next_title` / `ob_done_next_model` / `ob_done_next_terminal` / `ob_done_next_remote`
  - `ob_done_summary_fmt`
- 新增键须同时在 `values`、`values-de`、`values-zh-rCN`、`values-pt-rBR`、`values-th`、`values-ru` 六套落地，过 `check-i18n`（当前 1652 键 × 6 全绿）。
- 文案规范：按钮用动词短语；风险提示用「后果 + 操作边界」两句；数字与路径不写死，走资源。

## 9. Android 落地映射

| 设计 | 现有实现 | 改动 |
| --- | --- | --- |
| 欢迎页 | 无 | 新增 `pageWelcome` 布局与 `OnboardingActivity` 第 0 页；复用 `RuntimeInstaller` 预取状态显示胶囊 |
| 语言/方向/外观/强调色/屏幕 | `pageLanguage`…`pageScreen` | 按 §5 调整样式与文案；语言图标改字形 |
| 模式页 | `pagePriv`（模式+能力同页） | 拆为 `pageMode`；复用 `capRoot`/`capShizuku` 检测与二次确认 |
| 能力页 | `pagePriv` 下半 | 新增 `pageCaps`；复用 `capabilityContainer`、`btnCapRecommend` |
| 完成页 | `pageDone` + `txtDoneRuntime`/`pbDoneRuntime` | 四态渲染已有预取回调，补回顾 chips 与「接下来」；标题按状态切换 |
| 步骤数 | `steps` 7 项（`:59-67`） | 8 步（不含欢迎）；`pages` 9 项（`:175-183`） |
| 平板 | `sideRail` 已有 | 补 9 项与当前/已完成样式；内容区 620dp 居中 |
| 进度 | `progress.max = pages.size`（`:184`） | 用 `page / 8` 计算，欢迎页隐藏 |

新增控件建议 id：`pageWelcome`、`pageMode`、`pageCaps`、`welcomeRuntimePill`、`capsRecommendRow`、`doneSummary`、`doneNextList`。

## 10. 验收清单

1. 完整包冷启：欢迎页出现 ≤1s，胶囊显示真实解压百分比，87 秒内变为「已就绪」。
2. noruntime 包：胶囊显示「精简包：尚未安装运行环境」，进主界面弹下载确认。
3. 解压失败（断流/坏包）：完成页显示失败态，可进入并在「维护」重试。
4. 9 页均可通过手机抽屉与平板侧轨直接跳转；返回键逐页回退，首页返回直接退出。
5. 语言切换即时生效且不重建 Activity；6 语言无截断（de/ru 最长词）。
6. 深浅两主题下所有文字对比度 ≥4.5:1；强调色 6 色选中态可见。
7. 平板 820×900：内容区居中、无横向溢出；手机 390×844：无纵向溢出（完成页除外可滚动）。
8. `check-i18n`、`check-ui-tokens` 全绿；新增键 6 语言齐全。
9. TalkBack：每个卡片/开关有 contentDescription，焦点顺序=视觉顺序；触控目标 ≥48dp。
10. 完成页「开始第一次对话」直达主界面且不重复弹引导（`Privilege.KEY_ONBOARDED` / `isOnboarded()`，见 `engine/Privilege.kt:37,68` 与 `MainActivity.kt:196`）。

## 11. 风险与待定

- 欢迎页加入后首启步数从 7 变 8，老用户不受影响（`isOnboarded()` 为 true 时 `MainActivity.kt:196` 直接跳过引导）。
- 「约 30 秒」是设置流程估算，首次解压 87 秒；文案需与胶囊状态配合，避免被理解为总时长。
- 能力页「更多能力」的 6 项需与 `CapabilityRegistry` 实际能力对齐后再定文案，当前原型是占位（定位/相机/短信/传感器）。
- 完成页标题四态会改变 `doneTitle` 文本，需同步无障碍朗读。
- 平板侧轨在横屏小高度（如 600dp）可能溢出，落地时改为可滚动。

## 附：截图索引

- 手机深色：`p0-welcome-dark.png` … `p8-done-dark.png`
- 手机浅色：`p0-welcome-light.png` … `p8-done-light.png`
- 平板：`p0-welcome-tablet.png` … `p8-done-tablet.png`
- 运行四态：`state-preparing.png` / `state-ready.png` / `state-failed.png` / `state-lite.png`
- 画廊：`gallery-phone-dark.png`
