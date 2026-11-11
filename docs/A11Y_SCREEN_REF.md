# 无障碍屏幕表示 v2 —— 编号文档 + 指纹校验

> 状态：设计定稿（2026-11-08）· 分支 `feat/a11y-screen-ref`
> 目的：把「屏幕」从一棵需要模型自己猜的节点树，变成一份**带编号、可引用、可校验**的文档。

---

## 一、为什么改（现状问题）

当前 `dumpScreenCompact()` 输出（`DshAccessibilityService.kt:823`）：

```
1080x2340 42nodes
3 无线和网络 @180,412 c [row]
7 WLAN @900,412 c [switch]
7 蓝牙 @900,486 c [switch]
```

三个结构性缺陷：

| 问题 | 后果 |
|---|---|
| **无稳定标识** | 三处都是「确定」时模型只能靠文本匹配，`matchScore` 取第一个命中 → 点错 |
| **动作无预校验** | 页面在 dump 与 tap 之间变了，坐标仍然照发 → `dispatchGesture` 返回 true 但点在空处 |
| **无区段/层级** | 模型不知道哪个元素在顶部导航、哪个在列表里，滚动决策靠猜 |

dsh-Go（AGPL-3.0，仅作架构对标）用「编号 + 阅读顺序 + 三区 + 缩进」解决了前两条，
第三条靠「动作返回新屏幕 + 诚实判定」。本设计借鉴其**结构思路**，实现全部自写。

---

## 二、许可边界（硬约束）

dsh-android 是 **MIT**，dsh-Go 是 **AGPL-3.0**。

| 内容 | 允许 | 依据 |
|---|---|---|
| 架构模式、屏幕文档格式、判定语义 | ✅ 自己实现 | 思想与格式不受版权保护 |
| 身份证 GB 11643 校验、Luhn | ✅ 自己实现 | 公开国家标准 / ISO-IEC 7812 |
| 保护页包名清单 | ✅ 自己整理 | 客观事实数据 |
| 具体文案措辞 | ⚠️ 全部重写 | 输出格式的边界模糊，不冒险 |
| Kotlin 源码 | ❌ 一行不抄 | AGPL 传染，整仓许可失效 |

**与 `AI_CONTEXT.md:58` 同一条红线：绝不向本仓引入 GPL/AGPL 代码。**

---

## 三、屏幕文档格式（v2）

### 3.1 结构

```
屏幕 s1 · 设置 · 竖屏 · 键盘收起 · 共 8 项
顶部
  [1] 按钮 返回
  [2] 行 无线和网络
      [3] 开关 WLAN 已开
中部
  [4] 行 蓝牙 已关
  [5] 输入框 "搜索设置"
底部
  [7] 标签 关于手机
```

**逐项规则**

| 项 | 规则 |
|---|---|
| 屏幕号 | `s<N>`，每次 dump 自增；**只有最新一份的编号有效** |
| 头部 | `屏幕 <号> · <应用名> · <横/竖屏> · <键盘状态> · 共 N 项` |
| 区段 | 按元素中心 Y 分：顶部 < 25%、中部 25%~80%、底部 > 80%；某区为空则不输出该行 |
| 元素行 | `[id] <角色> <标签> <状态> @x,y <flags>` |
| 缩进 | 每个可滚动祖先 +2 空格；无滚动祖先为 2 空格（区段行是 0） |
| 编号 | 按**阅读顺序**：先 `bounds.top` 后 `bounds.left` 排序后顺序分配 |
| 标签 | 优先级 text > contentDescription > hint > viewId 尾段；超 60 字符截断加 `…` |
| 脱敏 | 命中身份证/银行卡 → `«身份证 ****1234»` / `«银行卡 ****1234»` |
| 敏感按钮 | 标签命中敏感词（发送/支付/删除…）→ 行首 `⚠` |

**角色取值**（沿用现有 role 判定，`DshAccessibilityService.kt:895-909`）

`switch` `edit` `button` `slider` `list` `scroll` `row`；无法判定则不输出角色字段。

**状态字段**

- `switch`：`已开` / `已关`（`node.isChecked`）
- `edit`：`已聚焦`（`node.isFocused`）、`密码`（`node.isPassword`）
- 其他：无

**flags 保留**（兼容既有语义：`c`=可点 `s`=可滚 `e`=可编辑 `v`=选中 `o`=未选中）但移到行尾，
保持机器可解析。

### 3.2 元素指纹

用于动作前预校验。组成：

```
<packageName>|<viewId 尾段>|<role>|<label(未脱敏原文)>|<checked>|<className 简单名>
```

不含 bounds —— 滚动后位置会变，但元素身份不变。bounds 单独存，用于点击坐标。

### 3.3 空白与兜底

- 节点树为空（纯 Canvas/游戏）→ 头部下方输出 `提示：本页无可用节点，可用截图 + 视觉定位。`
- 超过 200 节点 → 截断并追加 `…另有 N 项未列出`
- 字符预算 3000，超出时**保留底部区**（导航），中部截断并注明

---

## 四、动作协议

### 4.1 `POST /tap-ref`

```json
{"screen": "s1", "id": 5, "long": false}
```

**执行顺序（任一失败即返回，不点击）**

1. `screen` 与最新快照号不符 → `409`，返回 `latest`
2. `id` 超出范围 → `404`
3. **重新读树**，按同一算法重建编号表
4. 第 `id` 号的指纹与快照记录不一致 → `409 {"ok":false,"error":"目标已变化"}`
5. 命中保护页（`ProtectedPages`）→ `403`，提示用 handoff
6. 命中密码框 / 敏感按钮且未解除限制 → `403`
7. 执行点击：优先 `performAction(ACTION_CLICK)`；失败找最近可点击祖先；再失败按可见区域中心 `dispatchGesture`
8. 返回**新屏幕文档**（而非 `ok:true`）

```json
{"ok":true,"via":"node","screen":"s2","text":"屏幕 s2 · …","verdict":"界面已变化"}
```

`verdict` 取值：
- `界面已变化`（指纹集合变了）
- `界面没有明显变化`（前后都可读且一致）
- `内容没有变化，可能已经到头了`（动作是 scroll）
- `（动作已提交，但无法验证结果）`（前后任一无可读元素）

### 4.2 `GET /screen?format=doc`

输出 `{"ok":true,"screen":"s1","text":"…","count":8}`。

保留 `format=json` / `xml=1` / 默认紧凑格式不变 —— **旧路径零改动**，
这是控制回归风险的关键决策。


### 4.3 滑动轨迹不是直线（v1.6.31）

直线匀速滑动是最典型的机器特征：真人的手指受手腕结构、握持角度、屏幕摩擦影响，
走不出标准直线，也从来不匀速。检测方只要看「垂直偏移是否恒为 0」就能一眼分出来。

`SwipePath` 叠四层：主弧（`sin(πt)` 单峰）+ 反向小弧（`sin(2πt)`，让轨迹成轻微 S）
+ 低频微抖（正弦 + 随机相位）+ 逐点噪声。所有偏移都乘 `sin(πt)`，**两端严格归零**，
所以起止点永远精确 —— 拖滑块、拉进度条也能安全带弧线。

速度靠点密度表达：`GestureDescription` 沿路径匀速移动，用 smoothstep 把采样参数挤向
两端 = 起手慢、中间快、收手慢。另外按下后先停 15~70ms 再动，这个「静止起点」在触摸
采样里是可见的，缺了反而假。

两档：

| 模式 | 曲率 | 端点抖动 | 用途 |
| --- | --- | --- | --- |
| `dispatchSwipe` | 2% | 无 | 精确拖拽（滑块、排序），落点必须准 |
| `dispatchSwipeHuman` | 6%（±40% 随机） | ±10px | 滚动、翻页等日常手势 |

⚠️ 绘制层必须画**真实采样点**（`SwipePath.flatten`），不能画成起止两点连线 ——
手势本身带弧线，画直线的话用户看到的和真发生的就是两回事。

⚠️ 折线要一次 `drawPath` 画完。逐段 `drawLine` 时每段的圆头端帽会互相叠加，
alpha 翻倍，整条线看起来是一串珠子（实测踩到）。

---

## 五、安全层

### 5.1 脱敏（`ScreenMask`）

| 类型 | 判据 | 输出 |
|---|---|---|
| 身份证 | 18 位 + GB 11643 校验位正确 | `«身份证 ****<末4位>»` |
| 银行卡 | 15~19 位 + Luhn 校验通过 | `«银行卡 ****<末4位>»` |

排除上下文：前面 8 字符内含「订单/单号/运单/物流/快递/编号/流水/交易号/条码/序列号」→ 不脱敏
（那是运单号，不是卡号）。

**作用域**：v2 文档格式 + 现有紧凑格式都过一遍。理由：紧凑格式是模型主要读屏入口，
不脱敏等于没做。

### 5.2 保护页（`ProtectedPages`）

两类：
- **整包保护**：安装器、权限控制器、OEM 安全中心 → 只读不点
- **设置类按标题**：是设置包且标题命中「无障碍/开发者选项/设备管理/权限/安装未知应用/
  锁屏/密码/指纹/人脸/生物识别/安全/隐私」→ 只读不点

三条边界：
1. 读**允许**（模型需要知道当前在哪）
2. 点/输入**拒绝**，返回「请用 handoff 让用户自己完成」
3. 无「解除」开关 —— 这类页面 agent 永远不该自己操作

---

## 六、回归风险与控制

| 风险 | 控制 |
|---|---|
| 现有 `dumpScreenCompact` 格式被探针断言 | **不改它的默认输出**，只加脱敏；v2 走独立入口 |
| `LocalLoopExecutor.COMPACT_LINE` 正则锁死旧格式 | 不动 loop，`tap-ref` 是独立路径 |
| 节点遍历变慢 | v2 复用同一次 walk 结果，不额外读树 |
| 编号在 dump 与 tap 之间失效 | 这正是设计目的（提前发现而不是点错） |

---

## 七、验收标准

1. `_test_a11y_screen_doc.py`：格式断言 + 编号连续性 + 三区分配 + 缩进
2. `_test_screen_mask.py`：身份证/银行卡真阳性 + 订单号假阳性排除 + 校验位错不脱敏
3. `_test_protected_pages.py`：包名清单 + 标题匹配 + Settings 非保护页放行
4. 三个探针各做**反向验证**（改坏一处必红）
5. 编译通过（arm64 + x86_64）
6. 门禁四项全绿
7. 模拟器实测：dump doc → tap-ref 成功、改页面后 tap-ref 拒绝
---

## 八、实施状态（2026-11-08，v1.6.19）

上面第一~七节是设计定稿。以下是**实际落地的差异**，以代码为准。

### 8.1 已落盘文件

| 文件 | 行数 | 内容 |
|---|---|---|
| `app/src/main/java/app/dsh/mobile/ScreenDoc.kt` | 409 | 编号文档构建 / 渲染 / 指纹 / resolveForTap |
| `app/src/main/java/app/dsh/mobile/ScreenVerdict.kt` | 74 | 诚实判定与四种拒绝文案 |
| `app/src/main/java/app/dsh/mobile/ScreenMask.kt` | 115 | 身份证 / 银行卡脱敏 |
| `app/src/main/java/app/dsh/mobile/ProtectedPages.kt` | 150 | 22 个整包 + 9 个设置包 + 26 个标题词 |
| `app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt` | +26 | `tapNodeDeep()` 三级降级 |
| `app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt` | +123 | `format=doc` 分支 + `POST /tap-ref` |
| `scripts/probes/_test_screen_mask.py` | 108 | 脱敏契约（含反向验证） |
| `scripts/probes/_test_protected_pages.py` | 150 | 保护页契约（含反向验证） |
| `scripts/probes/_test_a11y_screen_doc.py` | 160 | 格式 / 顺序 / 指纹契约（含反向验证） |

### 8.2 与设计稿的四处修正

1. **指纹字段**。设计稿写的是 6 段（含 `packageName`），落地是 5 段：
   `viewId|role|rawLabel|checked|className`。去掉了包名 —— 它在整份文档层面已经
   比过（`Doc.packageName`），逐元素再带一遍是冗余，还会让 `signature()` 的差异
   被包名掩盖。
2. **`/screen?format=doc` 返回结构**。设计稿只列了 5 个字段，实际多返回
   `app` / `package` / `protected` / `poorTree`。理由：调用方要能用 `screen` 号直接
   接 `/tap-ref`，也要知道这页是不是保护页（否则它会反复尝试并被 403 弹回）。
3. **保护页判定在 `tap-ref` 内部再做一次**。设计上 `isProtected` 是构建期算的，
   `tap-ref` 直接读 `Doc.protectedPage`。但 dump 与 tap 之间页面可能已经切到
   权限页 —— 所以 `resolveForTap` 的重建会刷新 `protectedPage`，用的是重建后那份。
4. **`looksSensitive()` 保留但未接入热路径**。它是给调用方决定要不要承担 mask
   开销用的；当前 `ScreenDoc.build()` 对每条标签无条件调 `mask()`。
   mask 内部已有两道短路（<15 字符 / <15 个数字），实测开销可忽略，
   所以没有为了省这点开销牺牲「一定不会漏」。

### 8.3 探针的反向验证记录

每个探针都做了「改坏一处必须变红」的破坏验证，实际记录：

| 破坏 | 结果 |
|---|---|
| ScreenMask 权重表改错（末位 2→3） | 红 |
| ScreenMask 校验字符表改错（…432→…431） | 红 |
| ScreenMask 身份证/卡号处理顺序对调 | 红 |
| ScreenMask 删一个排除词（运单） | 红 |
| ScreenMask 短路条件放宽（15→8） | 红 |
| ProtectedPages 删 MIUI 安全中心 | 红 |
| ProtectedPages 把 `com.android.settings` 加入整包拦 | 红 |
| ProtectedPages 删「无障碍」标题词 | 红 |
| ProtectedPages 滚动容器不再排除 | 红 |
| ProtectedPages 标题区阈值 0.3→0.9 | 红 |
| ProtectedPages 顶部阈值 0.2→0.5 | 红 |
| ProtectedPages 删 limit 提前 break | 红 |
| ScreenDoc 指纹混入 bounds | 红 |
| ScreenDoc 预算 3000→1000 | 红 |
| ScreenDoc 排序改成 left 优先 | 红 |
| ScreenDoc `latest = saved` 注释掉 | 红 |
| ScreenVerdict 判定永远 UNCHANGED | 红 |
| ScreenVerdict 丢键盘优先级 | 红 |
| ScreenVerdict 丢 poorTree 兜底 | 红 |
| ScreenVerdict 丢包名切换判定 | 红 |
| `/tap-ref` 保护页判定禁用（`false &&`） | 红 |

### 8.4 仍然没做的（明确记录，不假装完成）

- **屏幕去重（ScreenDigest）**。dsh-Go 在编号顺序不变时只输出变化行 + 「其余 N 项
  与 s7 相同」。这个优化对 token 有实际收益，但它引入了一个新状态（上一份文档的
  差异基线），失败模式是「模型看到的编号是旧的却没意识到」。v2 先把正确性做扎实，
  去重留到下一版单独做，并配独立探针。
- **handoff 端点**。设计里提到「把手机交给用户完成登录/验证码/支付」，当前只用
  `403 + 请求用户接管` 这一半，没有真正实现「等待用户操作完成再继续」的阻塞式端点。
- **系统权限弹窗的识别**。`ProtectedPages` 拦的是包名与标题，系统级权限弹窗
  （`com.android.permissioncontroller` 的对话框）算在整包保护里，能拦住；但
  「哪个按钮是允许、哪个是拒绝」没有做语义标注 —— 反正也不让点。
---

## 六、向用户提问（v1.6.24）

模型卡在需要用户决定的地方时，此前只有两条路：瞎猜，或整条任务放弃。
`PhoneAsk` 让用户按一下就能回答。

### 6.1 三条约束

1. **答案只能被消费一次**。模型重试或两个调用方同时读，若能都拿到答案，
   它会以为用户答了两遍，进而执行两次动作。
2. **选项是建议不是限制**。`answer` 接受任意文本，选项只让常见情况点一下就好。
3. **超时要说清楚**。用户没答和用户拒绝是两件事——前者可以再问，后者不该追问。

### 6.2 端点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/ask` | `{text, options, freeText, timeoutMs}`；已有待答问题返回 409 |
| GET | `/ask` | 当前待答问题（悬浮窗与模型共用） |
| POST | `/ask/answer` | `{text, fromOption}` |
| POST | `/ask/cancel` | 用户选「不答」 |
| POST | `/ask/result` | 取答案，取走即清空 |

### 6.3 悬浮窗入口

档位 1 起就生效，不埋在详情档里——提问不是「详情」，是「需要你做点事」。
问题正文 + 选项按钮 + 自由输入 + 「不答」。

两处必须记住的实现约束：

- **按钮要用显式触摸处理**。overlay 窗口里 `setOnClickListener` 实测不触发
  （与麦克风按钮同一原因），三个按钮全部改 `onTouch`。
- **输入框要临时切可聚焦**。悬浮窗默认 `FLAG_NOT_FOCUSABLE`，不切的话
  EditText 收不到输入、输入法不弹，而且点击会穿透到下层界面（实测点输入框
  直接跳进了设置里的「通话和短信」）。失焦时恢复并收起输入法。

---

## 七、任务完成报告（v1.6.25）

AI 干完一件事，用户很可能不在看屏幕。`TaskReport` 负责把这个结果送到他面前。

### 7.1 一个关键区分

**「启动成功」不等于「用户回来了」**。OS 接受一次 Activity 启动请求，只说明
请求被收下，不说明界面真回到前台、用户真看见。

所以顺序是：先拉回会话 → 在确认窗口（3 秒，每 100ms 看一次）内反复确认前台
→ 确认不到才发通知。

### 7.2 判定表

| 情形 | 结果 |
| --- | --- |
| 已经在会话里 | `VISIBLE`，不带走也不打扰 |
| 启动被拒 | `NOTIFIED`，不等，直接发通知 |
| 启动被接受但确认窗口内没回来 | `NOTIFIED` |
| 等待期间任务被取代 | `LOST`，不再打扰 |
| 通知也失败 | `LOST`，不谎称已通知 |

### 7.3 端点

`POST /task/report` `{summary, steps, stopped}`，返回
`{shown, opened, launched, notified}`。

### 7.4 前台判定

不能用 `ActivityManager.appTasks`——它只列本进程自己的任务，只要我们还活着
就能找到自己，实测在设置页上直接误判成「已看见」。用无障碍服务的
`foregroundPackage()`。
---

## 八、悬浮窗交互（v1.6.26 / v1.6.27）

### 8.1 为什么改布局

旧面板是一列平铺：状态、动作、会话、按钮全在一条竖线上。加进「停止」按钮与
「提问抽屉」之后，一列里混着「一直存在的东西」和「临时冒出来的东西」，
用户分不清哪块要动手。

改成两层：

```
┌──────────────────────────────┐
│ ● 状态文案        (▶/⏸)  🎤 │  ← 顶栏：常驻
│ 提示行                        │  ← 只在 出错/完成/等回答 时出现
├──────────────────────────────┤
│ 抽屉：问题 + 选项 + 输入       │  ← 只在有提问时拉出来
└──────────────────────────────┘
```

顶栏放**一直成立**的信息（它在干什么、能不能动、要不要说话），
抽屉放**此刻临时**的信息（有个问题等你回）。

### 8.2 五档状态色

| 档位 | 颜色 | 文案 | 什么时候 |
| --- | --- | --- | --- |
| 空闲 | 灰 `#9AA0A6` | 空闲 | 没有动作在跑 |
| 执行中 | 强调色（默认蓝 `#7DD3FC`） | 执行中 | 动作已放行、还在飞 |
| 出错 | 红 `#E5534B` | 出错了 | 上一步返回非 200/409 |
| 完成 | 绿 `#81C995` | 完成了 | 上一步成功 |
| 等你回答 | 黄 `#F5A623` | 等你回答 | 有未消费的提问 |

两条容易踩的规矩：

- **只有「执行中」跟随用户的强调色**。把「出错」也染成用户喜欢的蓝色，
  等于关掉报警。语义色不随主题漂移。
- **只有「出错」和「等你回答」允许自动把面板弹开**（`OverlayState.needsAttention`）。
  「执行中」「完成」只是告知——每点一下就把面板弹出来，等于 AI 干活时一直在你
  眼前晃，还挡着它正在点的界面。`matters()` 保留原义（非空闲即值得显示），
  两者是不同问题，别合并。

### 8.3 叫停开关（绿/红圆钮）

- **绿底 + 两根白竖条** = 允许操作。点一下 → 叫停。
- **红底 + 白三角** = 已暂停。点一下 → 放行。

这不是装饰：`PhoneTask.setAllowed(false)` 之后，`begin()` 直接返回拒绝文案，
所有手机动作端点回 `409 {"error":"stopped by user"}`。拒绝文案明确告诉模型
「重试同一个动作不会成功」，并给两条出路：结束对话，或 `POST /ask` 问用户。

暂停期间顶栏整体切红：状态点红、文案「已暂停」、提示行「已暂停 AI 操作，所有
动作被拒绝」。因为此时任务状态（执行中/出错/完成）已经不重要了——不会有任何
动作发生，用户最需要确认的是「它现在动不了」和「怎么恢复」。

按钮状态每 2 秒从桥 `GET /task/allow` 回读一次：模型自己调 `POST /task/allow`
改了状态，界面也会跟上。

### 8.4 动作可视化（v1.6.29 扩到四种动作）

AI 动过哪里，用户得看得见。观感照着**安卓录屏 / 开发者选项「显示点按操作反馈」**做：
一颗半透明白色圆点，边缘用径向渐变羽化（就是「稍微模糊」），内亮环 + 外暗环两层描边
——亮底靠外暗环保轮廓，暗底靠内亮环提层次。只用一层必然有一边难看：深色环在暗底上
像抠了个洞，亮色环在亮底上等于没画。

**开关（v1.7.3）**：设置 → 个性化 →「AI 触摸点」。默认开；关掉只是不画，手势照常注入。

### 8.4.1 悬浮窗只在 AI 干活时出现（v1.7.4）

用户原话：「只有 AI 启用无障碍操控的时候，那个无障碍悬浮窗才会出现，AI 结束操纵后约 1 分钟消失」。

改法：**用「动作」而不是「任务」驱动显隐**。

| 时机 | 行为 |
| --- | --- |
| 服务连上（空闲） | **不显示**（原来无条件 `show`，所以永远挂着一颗球） |
| 每个被放行的无障碍动作（`AgentBridge.taskGate`） | `showForWork()`：重置 hidden + 显示 + 重排 60s 定时器 |
| 最后一次动作后 60s（`StatusOverlay.AUTO_HIDE_MS`） | `hide()`，窗口移除 |
| 任务收工（`endBoundary`） | 从这一刻重新排 60s；**撤无障碍服务也延后到 61s**（窗口 token 绑在服务上，先撤服务等于立刻关窗） |

两个不能收的特殊情况（否则会把用户唯一能操作的入口收走）：

- `PhoneTask.isAllowed() == false`（用户按了停止）→ 不收，按钮就在悬浮窗上；
- `Handoff` 在等用户接管 / 提问抽屉正开着（`askShownId != -1L`）→ 不收，15s 后再看。

验证（emulator-5556 / dsh30 / Android 11，`dumpsys window windows | grep -c "app.dsh.mobile}"`）：

| 状态 | 窗口数 |
| --- | --- |
| 服务连上、空闲 | **0** |
| 一次 `/tap` 之后 | 1 |
| 12 步滚动循环进行中 | 2（悬浮窗 + 触摸点窗口） |
| 循环结束后立刻 | 1 |
| 再等 70s | **0** |
| 隐藏后再 `/tap` | 1（能回来） |
闸门放在 `PointMarker` 的四个入口（`show` / `showHold` / `showSwipe` / `showPinch`）而不是
8 个调用点 —— 调用点分散在 `DshAccessibilityService` 与 `StatusOverlay` 里，漏一个就会
「关了还偶尔冒出来」。偏好是 `dsh_ui/a11y_touch_markers`（`DshApp.touchMarkersEnabled`）。

> 顺带修掉一个**静默老 bug**：`PointMarker` 缓存了 `view`/`params`/`wm`，而 `draw()` 只在
> `view == null` 时才 `addView`。服务重连（无障碍开关拨一下、系统回收后重绑）之后，旧 view
> 挂在死掉的窗口 token 上，后续绘制只走 `updateViewLayout` —— **什么都不显示，也没有报错**。
> 现在 `DshAccessibilityService.onServiceConnected()` 调 `PointMarker.reset()` 清缓存。
> 这个 bug 是这次排查「开关关了没反应 / 开了也没反应」时才暴露的：当时 tap 全在 503，
> 服务根本没绑上，掩盖了它。

四种动作四种画法，靠**形状与运动**区分，不靠颜色：

| 动作 | 画什么 | 为什么这么画 |
| --- | --- | --- |
| 点击 | 一颗点，约 1 秒后淡出 | 一个点，落点明确 |
| 长按 | 点从外圈**向内收**，按多久收多久 | 收缩 = 一直按着；扩散会被误读成「点了一下」 |
| 滑动 | 一条淡白轨迹 + 沿路径移动的点 | 滑动只有「从哪到哪」说得清，画点等于没说 |
| 双指缩放 | 以屏幕中心为轴，两点对称外扩/内收 | 缩放是两个动作，画一根指头看不出在缩放 |

几何与时长抽在 `MarkGeometry`（纯 Kotlin，无 Android 依赖），所以插值、越界夹取、
长按收缩、存活时长这些容易算错的地方能在 JVM 上直接断言。绘制在 `PointMarker`。

三条硬约束：

- 窗口类型 `TYPE_ACCESSIBILITY_OVERLAY` + `FLAG_NOT_TOUCHABLE`：只画，不拦触摸。
  否则标记盖在按钮上会把 AI 自己的下一次点击吃掉。
- **每次现取 `WindowManager`**。缓存下来的 manager 在服务重启后 token 失效，
  表现是「刚开始能画，后来怎么都画不出来」。
- **用无障碍服务自身的 Context**，不能用 `applicationContext`——后者加窗直接
  报 `token null is not valid`。

还有一个容易漏的路径：**`ACTION_CLICK` 不派发手势**。节点直点、祖先点击这两条路
根本没有注入手势，屏幕上原本什么都看不到——用户会以为「它没动，界面自己变了」。
所以这两处也要自己画落点（祖先点击画祖先的中心，那才是真实落点）。

`POST /task/point` `{x,y,label}` 也能手动触发，用来标「AI 打算点这里」。

### 8.5 两个必须记住的实现坑

- **窗口尺寸要在 `LayoutParams` 里定死**。根容器 `wrap_content` 遇到子项
  `match_parent` 无法解析，窗口会被撑成整屏宽（实测 1080px 的白色大条）。
  折叠态 `26dp × 26dp`，展开态 `224dp × WRAP_CONTENT`。
- **浮层坐标必须加 `FLAG_LAYOUT_IN_SCREEN`**。没有它，`LayoutParams.y` 是相对
  **状态栏以下**的内容区，`y=800` 的标记会画在 931 处（实测偏 131px，正好一个
  状态栏）。这种偏移只有对着截图量才看得见，肉眼只会觉得「差不多在附近」——
  我第一版就是这么漏过去的。
- **`dumpsys window` 才是点击坐标的真相**。`uiautomator dump` 抓不到
  `TYPE_ACCESSIBILITY_OVERLAY` 窗口，肉眼估的坐标会偏——实测球在
  `frame=[Rect(21,443 - 89,511)]`（中心 55,477），按截图目测点 (45,405)
  直接穿透到下层设置页，表现为「点了没反应还跳走了」。

### 8.6 怎么在真机上驱动桥（不用改源码）

桥 token 每进程随机生成、只经环境变量交给引擎。以前验证时得临时在
`ensureToken()` 里加一行 `Log.i(TAG, "TEMP-TOKEN:" + token)`，验完再删——两次构建。

**不用了**。引擎是 app 的子进程，同 uid，`run-as` 就能读它的环境：

```bash
adb shell "ps -A -o PID,USER,NAME | grep node"        # 找引擎 pid
adb shell "run-as app.dsh.mobile cat /proc/<pid>/environ | tr '\0' '\n' | grep DSH_BRIDGE_TOKEN"
```

前提是 debuggable 包（debug 构建本来就是）。这也说明 token 对能 `run-as` 的人是可见的——
防的是同设备第三方 App，不是 adb。

### 8.7 轨迹的反自动化检测（v1.6.31 / v1.6.32）

用户的原话是「这样子滑的太直线容易被检测」，第二句是「更吓人一点，防止被自动化检测」。
这里记的是**检测方到底看什么**，以及每条对策的代价。

自动化检测通常不看单个特征，而是几个便宜的特征叠起来打分：

| 特征 | 机器值 | 真人值 |
| --- | --- | --- |
| 路径垂直偏移 | 恒为 0（标准直线） | 几个到几十像素的单峰弧 |
| 速度曲线 | 匀速 | 慢-快-慢，途中偶尔犹豫 |
| 时长 | 固定常数（如恒 300ms） | 与距离强相关，且每次不同 |
| 落点 | 控件几何中心 | 中心附近几像素 |
| 按压时长 | 恒定 | 50–135ms 分布 |

后两项 v1.2.x 就有了（`DEFAULT_TAP_JITTER = 4`、按压 55–135ms）。v1.6.31 补了路径，
v1.6.32 补了时长、停顿、速度抖动、收手过冲。

#### 四层叠加（`SwipePath.build`）

```
主弧      arcAmp · sin(π·te)               幅度 = 长度 × curvature
反向小弧  sAmp · sin(2π·te)               让轨迹是轻微 S 而不是单一圆弧
低频微抖  sin(2π·f·te + φ) · wobblePx     f = 1.5~2.5 随机，φ 随机
逐点噪声  ±0.8px                          采样抖动
速度抖动  沿切线 ±speedJitterPx·w         改点间距 = 改速度
```

所有权重都乘 `w = sin(π·te)`，两端严格归零，所以**起止点永远精确**——拖滑块、
拉进度条这类要求落点准确的动作也能带弧线。

#### 时长按距离推，不用常数

`SwipePath.durationFor(distance)`：`(120ms + 0.18×距离) × (0.8~1.2)`，夹在 120–900ms。
300px 约 174ms，1000px 约 300ms，2000px 约 480ms。

调用方传 `durationMs = 0` 表示「按距离自动」。`/gesture`、loop 的 `swipe`/`scroll`、
`scrollToEdge`、`/vision/drag` 已全部改成 0；显式传值的仍然尊重传入值。

#### 中段停顿

插入 3 个重合点 = 手指在途中停了一下。默认概率 0.3（精确滑动 0.2）。
重合点不会产生新坐标，所以不影响落点，只是让速度曲线出现一次真实的凹陷。

#### 收手过冲只给拖拽，不给滚动

`overshootPx > 0` 时在末尾追加 `+100% / 62% / 28% / 8% / 0` 五个点：冲过终点再回弹，
最后一点仍是精确终点。

**滚动不能开**：滚动松手瞬间的速度决定惯性滑动，手指最后往回拉会被识别成反向
fling，列表反而弹回去——既露馅又难用。目前只有 `/vision/drag`（拖拽类）用 2.5%。

#### 一个必须夹住的量

速度抖动是沿切线挪点位，幅度必须小于上一点的间距，否则轨迹会往回走，
系统会把它识别成二次触摸或手抖。smoothstep 在起点附近导数接近 0，步长极小，
所以不能按固定像素给，要 `cap = 上一点间距 × 0.3`。

JVM 断言里专门有一组用 **300px** 的夸张抖动守这道闸：把 `cap` 改成 9999 会红 45 条。
实际用的 3px 抖动太小，夹不夹都看不出来——**测不出差异的参数等于没测**，
所以宁可拿一个夸张值把闸门本身测出来。

#### 验收证据

- JVM：`passed=799 failed=0`（`SwipePath` 相关新增 10 条：距离-时长单调、时长随种子变、
  过冲真的越过终点、过冲后仍落在精确终点、`pauseChance=1` 真的插入停顿点、
  速度抖动任意幅度都不回退）。
- 反向破坏三组全红：时长改常数（红 2 条）、过冲不追加（红 1 条）、去掉步长夹取（红 45 条）。
- 真机（emulator-5556 / Android 16）：2600ms 慢速滑动截图可见明显弧线轨迹；
  `overshoot=0.12` 的轨迹顶端比终点再高约 60px 并带回弹钩，`overshoot` 缺省时顶端正好停在终点；
  `durationMs` 缺省、`human=false`、`durationMs=300` 三种模式在设置长列表上都实测滚动成功。

### 8.8 动作间隔的真人节拍（v1.6.33）

轨迹再像，节奏不像也没用。本地闭环（`/loop`、`/batch`）原本每步间隔整齐得像节拍器：
实测 noop 步骤的间隔几乎恒定，而真人做同一件事是**抖动**的，偶尔还会停下来多看两眼。

`HumanPace` 只做两件事，纯 Kotlin、只返回毫秒数，睡眠交给调用方（JVM 上能断言分布）：

| 量 | 取值 |
| --- | --- |
| 常规思考停顿 | 60–220ms，`u^1.8` 偏斜到短端（中位约 86ms，均值约 117ms） |
| 偶发犹豫 | 5% 概率额外 700–1800ms |
| 每步合计 | 思考 + 犹豫，`stepPauseMs()` |

接入点：`LocalLoopExecutor.run` 的步骤循环里（`runStep` 之前）、`DshAccessibilityService.runBatch`
的每步开头。计划里传 `"humanPace": false` 可以整体关掉。

为什么默认开：用户原话是「不用考虑每步变慢，AI 思考和给出动作本身就慢」——
模型一次往返就是几秒，每步多几百毫秒在总时长里看不见。

**量级是用户后来下调的**：v1.6.33 先按 220–900ms 做，用户看完说「延迟不需要 0.5 秒
这么高，0.1 秒就够了，正常人类操纵也很快」。v1.6.34 改成 60–220ms。
这点很关键 —— **整齐才是破绽，慢不是**：真人连续操作恰恰很快。

#### 实测（emulator-5556 / Android 16，桥走 13084）

用 `{"action":{"type":"noop"}}` 做纯停顿测量（noop 跳过 settle，基线为 0）：

| 步数 | `humanPace=false` | `humanPace=true` | 每步增量 |
| --- | --- | --- | --- |
| 1 | 49ms | 156ms | 107ms |
| 2 | 33ms | 330ms | 148ms |
| 4 | 82ms | 633ms | 138ms |
| 8 | 46ms | 1029ms | 123ms |

（上表为 v1.6.34 下调后的中位数；v1.6.33 的 220–900ms 版本实测是每步 524/558/586/618ms。）

每步 107–148ms，落在「0.1 秒」这一档。`/batch` 三条 `sleep(50)` 由 449ms（1 步）
变成 1698–1951ms（3 步），同样每步约 +440ms → 下调后约 +100ms。

#### 一个测试踩到的坑

闭环 DSL 里 `type` **不在步骤顶层**，而在 `action` 子对象里：
`{"steps":[{"action":{"type":"noop"}}]}`。写成 `{"steps":[{"type":"noop"}]}` 时
`runStep` 会 `optJSONObject("action") ?: JSONObject().put("type","tap")` 兜底成 tap，
报 `tap needs target.text` 且在第 1 步就 escalate —— 看起来像「停顿没生效」，
其实是步骤根本没跑。**测出来不对劲先怀疑自己的请求体**。

### 8.9 联调发现的三个「刹车缺口」（v1.6.34）

用户要求「测试整条链路有没有什么冲突」。跑完整条链路（读屏 → 编号动作 → 闭环 → 批量 →
叫停 → 提问 → 交接 → 保护页）后，轨迹和节拍都没问题，**叫停这条线查出三个真缺口**。

#### 缺口一：空闲时按停止，等于没按

`PhoneTask.stop()` 原来只在 `RUNNING`/`PAUSED` 时踩刹车：

```kotlin
if (state == State.RUNNING || state == State.PAUSED) { ...state = STOPPED... }
else if (state == State.STOPPED) { stoppedUntil = clock() + stopHoldMs }
```

任务刚好空闲（IDLE）时按停止 → 两个分支都不进 → `stoppedUntil` 还是 0，
接口却回 `ok:true` 加停止文案。下一个动作照常执行。实测：
`stop` 回 `{"ok":true,"state":"IDLE"}`，紧接着 `/tap` 回 200 —— 用户以为停住了。

改成任何状态都踩刹车。

#### 缺口二：绿灯「继续」解不开刹车

`setAllowed(true)`（悬浮窗那个绿/红圆钮）原来只翻转 `allowed`，不动叫停窗口；
而 `clearStop()` 写好了却**全工程没有调用点**。于是：按停止 → 30 秒窗口内按绿灯 →
什么都没发生，要白等满 30 秒。

改法：`setAllowed(true)` 在检测到叫停窗口时一并清除；并且**已允许时再按也要有反应**
（原来自 `allowed == value` 直接 `return`）。

同时 `POST /task/resume` 改成如实回报：还在叫停窗口就回 409 + `stopped:true` +
「只有用户在悬浮窗点继续能立刻解除」。原因很直接 —— 叫停是**用户的刹车**，
模型不该能自己解开。

#### 缺口三（最严重）：叫停拦不住已经在跑的循环

桥的入口闸门（`taskGate`）只能挡住**新请求**。一次 `/loop/run` 带 40 步、
正在执行时用户按停止 —— 那 40 步照跑不误，手机继续被点。实测：

```
第 0.8s 叫停 -> 200 {ok:true, state:STOPPED}
闭环结果    ok=True  totalMs=4614  执行步数=40   ← 一步没少
```

改法：`PhoneTask` 加进程内单例 + `interruptReason()`（叫停窗口内返回停止文案，
`allowed=false` 返回禁止文案），`LocalLoopExecutor` 与 `runBatch` 在**步与步之间**自查，
命中就停。改后：

| 场景 | 改前 | 改后 |
| --- | --- | --- |
| 闭环 40 步，0.8s 叫停 | 跑满 40 步 / 4614ms | 停在 3 步 / 1965ms，`stopped:true` |
| 批量 15 步，0.9s 叫停 | — | 停在 4/15，末步 `aborted: 用户已停止…` |
| 关掉「允许 AI」，0.8s | 跑满 40 步 | 停在 6 步 / 829ms |

顺带把桥的 `task()` 改成复用这个单例 —— 两边各建一个 `PhoneTask` 实例，
闭环永远看不到用户踩的刹车。

#### 验证

- JVM：`passed=1828 failed=0`，新增 14 条（空闲也叫停、绿灯解除、关允许不解刹、
  `interruptReason()` 三种取值、暂停后 resume 仍正常）。
- 反向破坏三组全红：还原 `stop()` 旧守卫红 3 条、去掉「继续即解除」红 2 条、
  恢复 `allowed == value` 早退红 2 条。
- 真机冒烟 17 项：`/dev/ping`、紧凑读屏、文档读屏、tap-ref 的 404/409/200 三条路径、
  2 步真实闭环、批量、带过冲滑动、handoff 生命周期、ask 提问作答、停止/恢复闸门全过。

#### 我自己踩的两个测试侧坑

1. `POST` 路由用 `GET` 调会回 `404 unknown route` —— `/task/resume`、`/ask/result` 都中过，
   看起来像「接口不存在」。写测试时把方法显式写出来。
2. `/ask/answer` 的字段是 `text` 不是 `answer`；而且它**不校验 id**，只取当前待答的问题。

### 8.10 关掉权限之后，AI 还能做什么（v1.6.35）

用户提的要求：「假如在操纵过程中关闭了 AI 的操纵权限，AI 仍然有权利发起提问或者直接结束
操作写出摘要，状态变为绿色」。

这条要求看着小，其实是在划**权限的边界**：关掉的是「动手」，不是「说话」。

#### 现在的边界

| 能力 | 关掉允许之后 | 为什么 |
| --- | --- | --- |
| 动作（/tap、/gesture、/key、/input、/loop、/batch、/vision/*） | **拒绝**（409） | 这就是用户关掉的东西 |
| 提问（/ask） | **允许** | 用户关掉的是 AI 的手，不是它的嘴；卡住了必须能问 |
| 收尾报告（/task/report） | **允许** | 半路被叫停也要能交代一句 |
| 收尾（/task/finish） | **允许** | 写摘要 + 结束任务 + 状态区回绿 |
| 重新打开允许开关 | **不允许** | 开关是用户的，AI 不能自己解刹车 |

`/task/finish` 是这一版新增的：body `{summary, steps?}`，做三件事 —— 结束任务（回 IDLE）、
投递一份摘要报告、把状态区放回绿色。响应里带 `allowed` 字段，模型能看见开关仍是关的。

#### 为什么状态区要单独处理

`applyState()` 原来是这样：

```kotlin
if (!allowOn) { renderPaused(ctx); return }   // 红点 + 「已暂停」
```

于是 AI 收尾完，界面还挂着红的「已暂停 AI 操作，所有动作被拒绝」—— 用户会以为卡住了。
改成 `if (!allowOn && !finishedGreen)`：`finishedGreen` 只由 `/task/finish` 置位，
下一次有动作进来时清掉。

实测（emulator-5556，悬浮窗 frame `[21,443]-[609,685]`，按 12px 网格取色）：

```
收尾前：状态点 = 灰(IDLE)   按钮 = 绿
收尾后：状态点 = 绿(DONE)   按钮 = 红(仍不允许)
```

按钮仍是红的 —— 这是刻意的：AI 不能因为「我收尾了」就把用户的开关打开。

#### 顺带补上的一个洞

`/batch` 一直**不在** `A11Y_ACTION_PATHS` 里，于是叫停闸门与保护页闸门都绕过它 ——
一次批量动作可以在权限页上点下去。加进集合后实测：

```
普通页批量  200
叫停后批量  409 stopped by user
权限页批量  403 protected page（连测 3 次）
```

#### 验收

- JVM `passed=1831 failed=0`（新增 3 条：finish 回 IDLE、finish 不打开允许开关、
  关着允许时动作仍被拒）。
- 真机：关权限后 `/tap` 409、`/ask` 200、`/task/report` 200、`/task/finish` 200 且
  `{"state":"IDLE","allowed":false}`、收尾后 `/tap` 仍 409。

---

### 8.11 面板底色：去渐变，只留半透明 + 描边（v1.7.1）

用户原话：「这个悬浮窗的悬浮效果好烂，不要搞悬浮渐变效果了，但可以搞点透明效果和边缘描边。」

#### 改了什么

`res/drawable/bg_overlay_panel.xml` 原来是 **layer-list 三层**：半透明底 + 顶部高光渐变 +
内描边。其中第 2 层是 `angle=270`、`#1FFFFFFF → #0AFFFFFF → #00FFFFFF` 的线性渐变，
v1.5.5 加它是想给面板一个「光源方向」。

现在退回**单个 shape**：

```xml
<shape android:shape="rectangle">
    <solid android:color="@color/surface_glass" />
    <stroke android:width="1dp" android:color="@color/stroke_glass" />
    <corners android:radius="16dp" />
</shape>
```

#### 为什么渐变反而难看

面板高度只有几十 dp（顶栏 8dp padding + 一行字 + 8dp），渐变在这么短的距离里**看不出
光源方向**，只剩一条上亮下暗的脏色带；面板内容本身就在同一块区域，渐变和文字抢注意力。
换句话说：这个尺寸下面板不需要「体积感」，需要的是**轮廓**。

#### 透明度与描边的具体数值

| 令牌 | 浅色 | 深色 | 改动理由 |
| --- | --- | --- | --- |
| `surface_glass` | `#E6FFFFFF`(90%) → `#D1FFFFFF`(82%) | `#D9161D29`(85%) → `#C7161D29`(78%) | 90% 基本等于不透明，透不出底下的网页 |
| `stroke_glass` | `#260F172A`(15%) → `#380F172A`(22%) | `#2EFFFFFF`(18%) → `#3DFFFFFF`(24%) | 去掉高光之后轮廓全靠这条边，15% 太淡 |

深色下限守 78%：再低正文对比度会掉到 4.5:1 以下（实测 `surface #161D29` 在 72% 时对
`text_secondary #9CA3AF` 只有 4.1:1）。

#### 顺带改的三处

1. **去掉根布局的 `android:elevation="8dp"`**。悬浮窗是独立 Window，`wrap_content` 的窗口里
   View 的 elevation 画不出完整阴影，只会沿边缘渗一圈灰边 —— 又是一层脏。
2. **抽屉底色 `surface_alt` → 透明 + 1dp 分隔线**（新增 `@id/overlayAskDivider`，
   颜色 `stroke_glass`，随抽屉一起显隐，代码在 `StatusOverlay.renderAsk()`）。
   原来抽屉是不透明实心块，面板变半透明之后它会抵消掉「透出网页」这件事，
   展开态看起来像两个不相干的控件拼在一起。
3. **折叠球 `bg_overlay_ball.xml` 从 `@color/surface` 改用 `surface_glass` + `stroke_glass`**。
   原注释写着「透明」但实际用的是不透明色，展开/收起时底色会突变。

#### 验证（emulator-5556 / dsh30 / Android 11 / 1080x2280）

- 浅色：面板 `mFrame=[22,413][638,534]`，半透明圆角 + 可见描边，无渐变残留。
- 深色（`cmd uimode night yes` + 重启进程让夜间资源生效）：同位置，深底 + 亮描边。
- 抽屉（`POST /ask` 推一条待答问题后展开）：分隔线在顶栏与问题之间正常出现，
  抽屉本身透明，能透出底下的页面。
- JVM `passed=1870 failed=0`；`scripts/check-i18n.py` 全绿。

> 注意：`cmd uimode night yes` **不会**让已创建的悬浮窗换色 —— 视图在 inflate 时就把资源
> 解析好了。要验证夜间配色必须重启进程（`am force-stop` + 重新设置
> `enabled_accessibility_services` 拉起）。

> 另一个与本次无关的观察：悬浮窗 `mAttrs` 的 y=330，实际 `mFrame` 是 y=413，
> 差 83px（= 状态栏高度）。窗口没有 `FLAG_LAYOUT_IN_SCREEN` 时 y 相对内容区。
> 点按要按 `mFrame` 而不是 `mAttrs`，否则点不中（第一次就点到了底下的 Gmail）。

---

### 8.12 截图直接进对话 + 滚动更远 + loop 跑更多步（v1.7.2）

用户原话：「无障碍再优化一下那个滑动效果，滑动距离可以稍微再多增加一点，loop 连续动作
允许更多步。AI 每次截完图得手动去读一遍，能不能直接强制将图片发给 AI 注入对话，
而不是要求 AI 每次自己去读。」

#### 一、截图：从「回 base64 文本」变成「回 image 块」

旧路径的问题不是慢，是**根本看不到画面**：`GET /screenshot` 回的是
`{"ok":true,"format":"png","width":1080,"height":2280,"base64":"..."}`，
一屏 ≈ 200KB base64。当文本读既吃满上下文，模型也拿不到像素，只能 `save=1` 存盘再
`read_image` 读回来 —— 这就是用户说的「每次截完图得手动去读一遍」。

改法：把 `/screenshot` 通过 **MCP client** 暴露成一个原生工具，返回真正的 image 块。
引擎侧的 `@deepseek-ai/dsh-mcp-client` 会把声明为 `{type:"image", mimeType, data}` 的
MCP content 存成 attachment 再投影进对话（源码里 `containsImage` → `prepareImageProjection`），
所以模型是**直接看见**这张图，不需要第二次工具调用。

落地的四件事：

1. `app/src/main/assets/tools/mcp-device.mjs`：`/screenshot` 的响应转成
   `{type:"image", data, mimeType}`；文本块只留一行摘要（不再带 base64）。
   规范要求 **canonical base64**（引擎会重新编码逐字节比对），所以对 data 做了
   `^[A-Za-z0-9+/]+={0,2}$` 校验，不合格就当普通文本，不硬塞。
2. 同文件加 `--only id,id`（或 `DSH_MCP_ONLY`）过滤。整套端点 40+ 个，全注册会让
   每个请求都背上这些工具定义、并让 provider 的缓存前缀失效 —— 只放 `screenshot`。
3. `EndpointCatalog.kt` 补登记 `/screenshot`（id `screenshot`）。**它此前根本不在目录里**，
   而 MCP 工具表是照目录生成的 —— 不登记就查不到、`--only screenshot` 直接匹配 0 条。
4. `EngineConfig.kt` 的 overlay 里插一行 `dsh-mcp-client`：
   ```yaml
   name: ./lib/node_modules/@deepseek-ai/dsh-mcp-client/lib/index.js
   config:
     serverName: device
     transport: stdio
     command: '/data/user/0/<pkg>/files/engine/bin/mcp'   # 部署时算好绝对路径
     args: ['serve', '--only', 'screenshot']
     env: { DSH_BRIDGE_TOKEN: <引擎 env> }
   ```

两个真机踩出来的坑：

- **`name` 不能写裸包名** `@deepseek-ai/dsh-mcp-client`。裸名按 profile 解析，而 profile
  不依赖这个包；行会**静默失败**（`failOnStartupError` 默认 false），工具永远不出现。
  相对路径 `./lib/...` 是相对 overlay 文件所在目录（`files/engine`）解析的，能命中。
- **`command` 不能用 `process.cwd()`**。引擎的工作目录是 `<home>/workspaces`，
  门脚本在 `<engine>/bin` —— 拼出来是个不存在的路径，同样是静默 spawn 失败。
  改成部署时用 `File(engineRoot(ctx), "bin/mcp").absolutePath` 插值进 YAML。

顺带：`scr shot` 的默认行为改成 `--save`（只回路径）。以前默认把 base64 打到标准输出，
一次就把上下文吃穿；要看画面走 MCP 工具，要留证走 `--save`，`--raw` 保留旧行为。

#### 二、滚动：一次走更远，且不会算出屏幕外的坐标

新增 `app/src/main/java/app/dsh/mobile/ScrollGeometry.kt`（纯 Kotlin，可 JVM 断言），
取代 `LocalLoopExecutor` 里写死的算术：

| | 旧 | 新 |
| --- | --- | --- |
| 默认行程 | `0.6h` | `0.72h` |
| 手指起点 | `0.65h` | `0.86h`（下）/ `0.14h`（上） |
| 越界处理 | 无（`distance` 调大 → 终点负坐标 → 手势被裁，实际滚得更少） | `coerceIn(MIN_TRAVEL_PX, maxTravel())` |

`maxTravel = h * (1 - 2*0.14)` 正好等于默认行程，所以默认值就是「在不碰状态栏和
手势导航条的前提下能走的最远距离」。

#### 三、loop：预算默认 300s，但真正先撞墙的是 bash

**先量再改**（emulator-5556，设置页滚动 20 步）：`totalMs=23331` → **1167ms/步**。
按这个数：180s ≈ 154 步，300s ≈ 257 步。

但只看服务端预算是错的。模型是通过 bash 跑 `loop run -` 的，四个预算依次是：

| 层 | 值 | 超时后的表现 |
| --- | --- | --- |
| `bash-local` 默认 `timeoutMs` | **120s**（≈102 步，可传，上限 600s） | 命令转成后台作业，结果从 `job_output` 拿 |
| 闸门 `DSH_HTTP_TIMEOUT` | 跟随 budgetMs（+15s 余量） | 客户端先断开 → 拿不到服务端报告 |
| `/loop/run` `budgetMs` | 默认 300s，夹 10s~600s | 执行器在两步之间收手，返回结构化报告 |
| 引擎工具超时 | 由工具声明 | 工具报 `TOOL_TIMEOUT` |

所以「同步拿到结果」的实际窗口是 bash 的 120s；要更久就在 bash 调用里传 `timeoutMs`。

#### 3.1 到点收手（v1.7.2 补）

以前超时只切断 HTTP 连接，**循环线程还在后台继续点**：调用方看到 504，屏幕却还在动，
下一次调用还得排在这个线程后面（实测：40 步的循环 15s 超时后仍跑满 ~47s）。

现在 `LocalLoopExecutor.run(ctx, svc, planJson, deadlineAt)` 在**步与步之间**（与用户叫停
同一位置，绝不在半截手势上停）检查截止点，到点返回：

```json
{"ok":false,"budgetExceeded":true,"error":"loop budget exhausted before step s17","failedAt":"s17","steps":[...]}
```

HTTP 侧的 `withScreenTimeout` 改成 `budgetMs + 5s` 兜底 —— 让执行器那份带 `failedAt` 的报告
先到，而不是让调用方只看到一个 504。

验证：`budgetMs=15000` + 60 步滚动 → wall **16012ms**、HTTP 200、`budgetExceeded=true`、
`failedAt=s17`、完成 16 步；随后两次 `/screen` 正文逐行相同（循环确实停了）。
默认预算 20 步 → `ok=true`、`totalMs=27608`。

另外 `mcp-device.mjs` 里 `/loop/run`、`/batch` 走 `LONG_CALL_TIMEOUT_MS = 320_000`
（其余端点仍是 60s）：外部 MCP 客户端调这两个端点时，60s 会先于服务端断开。

#### 验证

- JVM：`passed=1888 failed=0`（+18 条 ScrollGeometry 断言）。两组反向破坏都变红后还原：
  默认行程改回 0.6（1 条红）、去掉行程夹取（4 条红）。
- MCP 探针：`node scripts/probes/_test_mcp_image.mjs` → 28 条通过；反向破坏
  「去掉 canonical base64 校验」红 2 条、「不看端点路径」红 1 条。
- 真机（emulator-5556 / dsh30 / Android 11）：引擎拉起后 `ps` 里多出子进程
  `... bin/node .../tools/mcp-device.mjs --only screenshot`，引擎日志
  `mcp-device: 1 tools ready (filtered from 1 ids)`；`tools/list` 只返回 1 个工具；
  `tools/call screenshot` 回 `content: [text, image]`，`image/png`、37723 字节、PNG 魔数、
  base64 规范化往返一致。
- 真机滚动：`POST /loop/run` 单步 `scroll` 返回 `ok:true`（1583ms），屏幕从首屏滚到
  Sound/Security/System 段，手势没有被裁。
- 启动耗时：`boot.log` TOTAL 8898ms（改前同一台机器 9405ms，在噪声范围内）。

> 仍未验证：**图片是否真的出现在模型的对话里**。这一步需要一次真实的模型回合
> （模型调用 `mcp__device__screenshot`），本轮只验到「MCP 返回的 wire 形状符合引擎
> 的准入要求」——引擎侧 `prepareImageProjection` 的输入正是这个形状。
