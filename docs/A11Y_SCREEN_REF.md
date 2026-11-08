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