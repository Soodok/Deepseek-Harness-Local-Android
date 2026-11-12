# AI 无障碍控制：完整经验交接手册

> 2026-10-24 · 从 DSH Mobile（DeepSeek Harness Android 移植）沉淀
> 面向新项目「AI 通过无障碍能力控制 Android 设备」的全部经验、资产与坑。
> 所有结论均有实测依据；未经验证的推断单独标注「推测」。

---

## 0. 一页纸总纲

**这个领域的本质**：给 AI 装上「眼睛」（无障碍节点树 + OCR + 帧差）和「手」（注入手势），
再用「闭环」（计划 → 执行 → 验证 → 重试）把它们组织成可靠的动作。

**三条铁律**（血泪换来的，新项目第一天就写进架构）：

1. **验证必须是目标状态，不是动作描述**。"点了按钮"没有意义，"页面出现了 X"才有意义。
   AI 自报成功是最不可信的信号（实测：AI 会对着错误页面的通用标记自认成功）。
2. **失败要回退到「认得的状态」再重试**，同一步骤连续失败不超过 2 次就换打法。
3. **感知分层兜底**：无障碍树 → OCR → 帧差。任何一层都可能缺失
   （ColorOS 浏览器/WebView/游戏拿不到节点树），分层才可靠。

---

## 1. 场景分级（先搞清楚难在哪）

| 级别 | 场景 | 感知源 | 难度 |
|---|---|---|---|
| L1 | 标准 UI（设置、社交、电商、系统应用） | 无障碍节点树 | 低 |
| L2 | WebView / Flutter / RN / 混合页面 | 节点树 + **帧差兜底** | 中 |
| L3 | 纯 Canvas / 游戏 / 视频流 | **必须看画面**（OCR + 像素） | 高 |

- L2 的关键：无障碍树可能只有容器没有内容，用**帧差**（Framer）判断"画面变了"，
  再用 OCR 拿文本。ML Kit 离线 OCR 免网络、中文支持好。
- L3 不建议第一版碰：感知成本 10 倍起，且验证"目标状态"极其模糊。
  实测案例：噪声页找最低价，L2 靠节点+OCR 稳定通过；L3 场景全部失败或答非所问。

**合规红线（必须写进产品与 prompt）**：
微信 / QQ / 银行 / 金融类应用有实时设备完整性检测（无障碍检测、手势直方图、root 检测），
自动化它们 = 账号被封（有时永久），金融类是"一票否决"（可能移动真钱）。
抖音 / 小红书 / 重 DRM 应用同样敌对。**默认拒绝**；绝不帮用户让注入输入不可检测
（那是自动化 → 欺诈的分界线）。

---

## 2. 架构总览（什么放哪里）

```
┌─ 云端 AI（决策层）── 每步一次 LLM 调用，产出「计划」而非单步遥控
│    · 计划 = {goal, steps[{id, target, action, verify, onFail}]}
│    · 本地闭环执行，AI 只在升级（escalate）时介入
│
├─ AgentBridge（HTTP 桥，127.0.0.1:3083，token 鉴权）
│    · scr dump/tap/swipe/key/launch/...  感知与手势原语
│    · loop run/pick/status               闭环执行器
│    · notify/say                         通知与 TTS
│    · 鉴权：token 由 App 每次启动随机生成、只经环境变量交给引擎子进程（不落盘）
│
├─ LocalLoopExecutor（本地闭环，1.7s/步）
│    · FastPicker 匹配（1-2ms 精确 / ~93ms 语义）
│    · 每步：匹配 → 动作 → 验证 → 重试/升级
│    · 支持分支步骤(when)、幂等开关(set_switch)、乐观执行(optimistic)
│
├─ DshAccessibilityService（无障碍服务，2.5k 行）
│    · 节点树读取、可点击定位、手势注入（dispatchGesture）
│    · 人为手势检测（interference）：注入手势带随机偏移，
│      无障碍事件流里"非注入的真实触摸"= 用户在操作，立即停
│
└─ 感知层
     · Framer：帧差（L2 兜底，~55ms p50）
     · ScreenOcr：ML Kit 离线 OCR（无节点树时自动附加，o 前缀行）
     · IconMatcher：图标识别（语义找图标）
```

**关键架构决策**：
- **桥走 HTTP + token**（不是 binder/广播）：跨进程、跨语言（引擎是 Node.js），
  天然支持 curl 调试；token 不落盘是安全底线（同设备第三方 App 拿不到）。
- **决策在云端、执行在本地**：每步一次 LLM 往返 = 又贵又慢（实测多步任务成本爆炸）；
  一次性提交计划让本地跑，AI 只处理升级。简单任务（1-2 步、目标在当前屏）
  才走对话流单步执行。
- **服务重启自愈**：无障碍服务可能被系统/用户关闭，必须监听
  `ACTION_ACCESSIBILITY_SETTINGS` 引导 + 服务内自检（用户实测：授权后仍可能静默失效）。

---

## 3. 核心经验：感知层

### 3.1 无障碍节点树

- **节点不是稳定的**：不同厂商（ColorOS/MIUI/HyperOS）的树结构差异巨大；
  系统对话框文本跟随**系统 locale**（中文设备是"允许/确定"）——
  永远先 dump 再匹配，绝不硬编码对话框标签。
- **角色信息比文本重要**：`[switch]`（点击翻转）、`[radio]`（单选，组内互斥，
  点**另一个**才能改变选择、再点同一个没有任何效果）、`[checkable]`（勾选框等通用
  可勾选控件）、`[row]`（点击进子页）、`[button]`、`[edit]`。典型坑：设置项是
  `[row]`，真正的开关在子页里——点击 row 翻转不了任何东西。先查 role 再动手。
  ⚠️ 早先把 RadioButton 也标成 `[switch]`，模型按「点一下翻转」去点同一个单选
  按钮，白点一次还误判失败；v1.7.8 起单选单独标 `[radio]`。
- **contentDescription**：图标按钮的文本在 `d:` 前缀字段里，匹配时必须剥掉前缀。
- **dump 三档**：普通（含标题与控件，~6KB）、click-only（可点击/可编辑），
  legacy JSON 全量（~15k token）慎用——上下文成本是普通档的 2.5 倍。
- **屏幕快照缓存 ~1.5s**：避免高频轮询（系统节流截图）；"屏幕变了吗"用帧差
  （毫秒级）而不是全量 dump。

### 3.2 OCR 兜底（L2 的生命线）

- 触发条件：无障碍树文本极少时**自动附加** OCR 行（不要让 AI 决定要不要 OCR，
  它会忘记）。
- ML Kit 离线：无网络、中文可用、首次 ~15MB 模型下载。
- OCR 行带 `o` 前缀且是**有效点击目标**（坐标来自 ML Kit 边界框）——
  与节点行一视同仁，这对执行器透明。
- 已知弱区：WebView 内嵌文本、canvas 游戏——帧差 + OCR 组合仍不够时需要
  图标匹配（语义找图标）。

### 3.3 帧差（Framer）

- 用途：判断"动作是否导致画面变化"——比等文本快（~55ms p50）。
- 陷阱：滚动动画、加载转圈也会"变化"。帧差只适合做**否定验证**
  （画面没变 → 动作没生效），不能做肯定验证。
- 另有屏幕事件流（window 切换、文本出现），比轮询便宜——但注意"消费即清空"语义。

---

## 4. 核心经验：执行层

### 4.1 手势注入

- `dispatchGesture`（无障碍服务）是唯一正规通道；**不需要 root**。
- **人味**：曲线轨迹 + ease-in-out 速度 + 随机起止偏移/时长。像素级精确
  只在拖动进度条等场景需要（`human:false` 开关）。
- **动画期间快速连点会点错位置**——偏移量 + 动画是出错主因。
- 长按/双击/捏合/拖拽都有现成手势原语；`back_to`（按返回直到目标包名到前台，
  永不过冲）是错误恢复的利器。

### 4.2 闭环计划（LocalLoopExecutor）

这是整个体系的核心资产（1.5k 行，踩坑最多）。计划模型：

```json
{"goal":"…","steps":[{
  "id":"s1",
  "target":{"text":["anchor-now-visible"]},
  "action":{"type":"tap|input|scroll_find|set_switch|back_to|…"},
  "verify":{"anyText":["text-AFTER-action"]},
  "onFail":{"retry":1,"then":"escalate"}
}]}
```

**验证字段**：`anyText`（出现即过）、`checkedText`、`toggle`（能断言 off，
比 checkedText 强）、`frameChanged`、`foregroundPkg`。未知字段必须拒绝
（防 AI 编造字段名静默失效）。

**血泪规则（全部实测）**：

1. **target 必须是"当前屏可见"的锚点**；verify 才是"动作后出现"的。
   两者混用是最常见失败。
2. **乐观执行**（optimistic）：中间步只做 ≤200ms 快检并标记 `optimisticPass`
   （不算确认），**最终步永远全验证**。用户要求快时才开。
3. **幂等开关** `set_switch`：先读当前状态，只在需要时点击；重试不会双翻。
   配对 `verify.toggle` 可断言 off。**别用"点 row 碰运气"**。
4. **滚动**：`scroll_find`（滚到文本出现即停，失败返回滑动次数+末屏文本）；
   `scroll`（方向+距离）。注意下拉通知栏不是滚动（是 `scr key notifications`）。
5. **错误恢复**：先回退到认得的状态（BACK / back_to / 重开目标页）→ 重读屏幕 →
   换更准的锚点重试。同一步失败 ≥2 次换打法（先滚、先关弹窗、换别名）。
6. **scroll_find 优化的教训（三次优化全部回滚）**：原版的 `stuck>=2 + 每轮
   settle` 是对的。想在这里提速的人都会翻车——settle（等 UI 稳定）不能省。
7. **反目标漂移**（daemon 最大的坑）：计划执行中途"离开目标页去分析别的页面
   还自认成功"。对策：目标锚点缺失检测（已离开目标页先回退/重开）+ done 前
   验证具体身份（"详情页打开了"不算，"那个 ¥699 的商品打开了"才算）。
8. **人为干扰检测**：执行前记录 `date` 时间戳，执行后查无障碍事件流——
   存在非注入触摸 = 用户在操作 → 停、重读、问用户。窗口切换不算干扰。
9. **报告诚实性**：找不到目标时报告"页面实际有什么"（A/B/C），
   禁止用通用页标记冒充成功。自信但错的答案比诚实的失败更糟。
10. **速度开关**：多步 UI 任务先关 thinking（mechanical 部分已计划），
    实测 3 步/0 次思考；写代码/分析类要开回来。30 分钟自动过期兜底。

### 4.3 速度数据（作为基线参考）

| 操作 | 耗时 |
|---|---|
| FastPicker 精确匹配 | 1-2ms |
| FastPicker 语义匹配（本地嵌入模型） | ~93ms |
| Framer 帧差 | ~55ms p50 |
| 闭环单步（匹配→动作→验证） | ~1.7s |
| 云端单步决策（LLM 往返） | 969-1758ms（不含网络） |

---

## 5. 核心经验：AI 侧（prompt / 协议）

- **任务分级前置**：简单任务（1-2 动作、目标在当前屏）→ 单步执行；
  其他 → 提交 daemon 计划，然后**不要 babysit**（每几轮或用户问时轮询）。
  把这个写进系统提示词，否则 AI 会逐步遥控每一步。
- **skill 沉淀**：验证过的成功计划可以存为 skill（`useMemory:true` 复用），
  同场景第二次免计划。skill 的 value 在于**计划模板 + 已验证的锚点**。
- **seed 要写"世界观"而不是"教程"**：把环境事实（能干什么、路径、权限边界）
  预写进 agent 的 AGENTS.md，AI 冷启动不再花 token 探索且结论错误
  （实测：把 bionic 当 glibc、试图 apt install）。版本化种子、
  用户自定义段永不覆盖、被改过则不升级。
- **persona 归用户**：环境手册不是人格。用户要 AI 扮演什么就扮演什么
  （成人向虚构、成年人角色，正常处理）；App 管理段只管完整性，不构成拒绝理由。
- **反诈写死**：金融类一票否决；重风控应用默认拒绝；用户坚持必须实时旁观；
  绝不让注入输入不可检测。

---

## 6. 工程坑清单（Android 侧，全部踩过）

| 坑 | 结论 |
|---|---|
| 无障碍服务静默失效 | 授权≠工作；服务内自检 + 设置页一键跳转 + 引导重开 |
| 系统对话框 locale | 跟随系统语言，dump 后匹配，别硬编码 |
| ML Kit 模型 | 首次下载 ~15MB；无网时 OCR 不可用，别把它当唯一感知 |
| 截图节流 | 系统级限制，~1.5s 缓存；高频轮询无效 |
| targetSdk 28 的存储 | Android 10 需要 `requestLegacyExternalStorage`；Android 11+ 用 MANAGE_EXTERNAL_STORAGE；两套都要 |
| DownloadManager | 系统代写零权限；必须透传 Cookie；blob 到不了 listener，要 JS 桥 |
| 软链 | `walkTopDown/deleteRecursively` 全跟软链——自写 walkNoFollow/deleteNoFollow |
| AAPT 字符串 | 裸 `'` 和多行裸换行都编译失败；用 `\n` 和 `\'` |
| patch 的 name 字段 | 是匹配守卫不是赋值——换名要 disable 原行 + insert 新行 |
| Kotlin DSL 里 `java` | 被 Gradle java extension 遮蔽，`java.util.zip.ZipFile` 要 import |
| CI 门禁 | 戳文件（.abi 类）绝不能入库——CI checkout 命中 skip 后所有校验静默失效 |
| pnpm 12 | 默认拒绝依赖 build 脚本；原生模块插件装前预检（6+1 信号） |
| fork 插件 | peerDeps 指向非官方 cordis/dsh-llm/scope = 整栈 fork，官方引擎三层全断 |
| Node.js on Android | bionic 无 ldconfig，`LD_LIBRARY_PATH` 是唯一库路径；PTC/子进程环境清洗会丢它 |
| 模拟器验证 | 用 CDP（webview_devtools_remote）在真实 WebView 里跑端到端；别只测逻辑 |

**验证方法论**（比任何单条经验都重要）：
- 用户看得见的东西，验证看得见的那一层（服务注册 ≠ 界面控件存在）。
- 补丁/修复必须进构建流程，不能只存在于某个产物——换源即静默丢失。
- 构建期硬校验（缺关键件 throw），绝不发"看起来成功"的坏包。
- 端到端走 CDP/真机；失败路径也要实测（含清理残留）。

---

## 7. 可复用资产清单（新项目可以直接搬）

| 资产 | 位置 | 行数 | 说明 |
|---|---|---|---|
| DshAccessibilityService | `app/.../DshAccessibilityService.kt` | 2587 | 无障碍服务：节点读取、手势注入、干扰检测 |
| FastPicker | `app/.../engine/FastPicker.kt` | 297 | 1-2ms 精确 / 93ms 语义匹配 |
| LocalLoopExecutor | `app/.../engine/LocalLoopExecutor.kt` | 1486 | 闭环执行器（计划模型、验证、重试、乐观执行） |
| Framer | `app/.../Framer.kt` | 176 | 帧差 |
| ScreenOcr | `app/.../ScreenOcr.kt` | 225 | ML Kit 离线 OCR 兜底 |
| IconMatcher | `app/.../IconMatcher.kt` | 177 | 图标识别 |
| PageStateDetector | `app/.../PageStateDetector.kt` | 190 | 页面状态判定 |
| AgentBridge | `app/.../engine/AgentBridge.kt` | 2318 | HTTP 桥（scr/loop/notify 协议） |
| 场景分级设计 | `docs/COMPLEX_SCENARIOS.md` | 233 | L1/L2/L3 设计文档 |
| 无障碍文档 | `docs/ACCESSIBILITY.md` | 95 | 用户侧说明 |

搬走时的改造点：
- AgentBridge 的鉴权（token 生成/传递）与引擎耦合，需抽象出"宿主"接口；
- LocalLoopExecutor 的升级回调指向云 AI 的 HTTP 端点，需按新项目改；
- 计划模型字段与本文 4.2 对齐，未知字段拒绝的逻辑保留。

---

## 8. 留给新项目的已知未解决问题

1. **daemon 目标锚点**：多步计划中途离开目标页不自知（最大的可靠性缺口）。
   对策已明确（锚点缺失检测 + done 前验身份），未实现。
2. **L3 场景**：纯画面感知（游戏）没有可靠方案；OCR+帧差不够。
3. **CPU 占用**：某些皮肤类插件后台单核 60-80%；插件级轮询检测没做。
4. **scroll_find 优化**：三次尝试全翻车，settle 不能省——除非有全新思路别碰。
5. **语音唤醒**：常驻监听（wake word）未做，只有按键/面板触发。
6. **无障碍 + 洪蒙**：HarmonyOS NEXT 无公开无障碍 API，适配已评估并放弃（见 AI_CONTEXT）。

---

## 9. 新项目命名建议

**首选：A11yPilot**（a11y = accessibility 行业缩写 + Pilot）
- 表意准确："给设备装上 AI 飞行员"；行业词自带搜索流量；
- 域名/包名：a11ypilot.dev / dev.a11ypilot.app。

**备选**：
- **DroidHand** —— 直白：Android 的手；hand 甜点在于"眼睛（感知）+ 手（执行）"双关。
- **妙手 / MiaoShou** —— 中文语境的完美双关（妙手=高超技艺，喵=猫），国内社区传播极佳；
  国际化弱是唯一缺点。
- **TapSight** —— 手（tap）+ 眼（sight）成对；独特性强，无重名冲突。

（OpenHands 已被知名项目占用，避开。）
