
## 一·零·补三、屏幕文档 v2 + 编号动作（v1.6.19，2026-11-08）

> 详细设计见 [`docs/A11Y_SCREEN_REF.md`](docs/A11Y_SCREEN_REF.md)（含反向验证记录）。
> **改动落在 worktree `E:/tmp/wt-a11y`，分支 `feat/a11y-screen-ref`，基于 main `9c6f755a`。
> 主工作区当时有他人未提交改动（PluginCenter.kt），按规矩没碰。**

### 为什么做这个

用户原话：「我们的无障碍操纵系统，而且对系统各种数据的感知力也比较弱，我想要统一进行改良」。
第一版我提的是「加 waitText / 补 Activity 类名 / 补权限弹窗」这类散点功能 —— 被否掉了：

> 「这些都不能显著增加使用体验，你看下其他方案的实现，我特别欣赏 dsh go 的无障碍方案」

于是改为架构级对标 `ccch1mneyyy/dsh-Go`（AGPL-3.0，**只借设计不抄代码**）。它最值钱的
不是功能清单，而是三件我们完全没有的事：

| 洞见 | 我们的旧状态 | 后果 |
|---|---|---|
| 屏幕是**带编号的文档** | 每行「文本 + 坐标」 | 模型要点第三个「确定」时只能靠文本匹配取第一个命中 → 点错 |
| 动作要**指纹预校验** | `tapById` 直接点 | dump 与 tap 之间页面变了，点在旧坐标上，且回 `ok:true` |
| 动作要回**诚实判定** | 一律 `ok:true` | 「我点了」被当成「点成功了」，在没变的界面上继续往下走 |

### 落地的东西

| 文件 | 行数 | 职责 |
|---|---|---|
| `app/src/main/java/app/dsh/mobile/ScreenDoc.kt` | 415 | 编号文档构建 / 渲染 / 指纹 / `resolveForTap` |
| `app/src/main/java/app/dsh/mobile/ScreenVerdict.kt` | 74 | 诚实判定与四种拒绝文案 |
| `app/src/main/java/app/dsh/mobile/ScreenMask.kt` | 115 | 身份证 / 银行卡脱敏（校验位判据） |
| `app/src/main/java/app/dsh/mobile/ProtectedPages.kt` | 172 | 22 个整包 + 9 个设置包 + 26 个标题词 |
| `DshAccessibilityService.kt` | +26 | `tapNodeDeep()` 三级点击降级 |
| `engine/AgentBridge.kt` | +126 | `GET /screen?format=doc` + `POST /tap-ref` |

**旧路径零改动**：`dumpScreenCompact` 的默认输出没碰，`LocalLoopExecutor.COMPACT_LINE`
（`:1264`）锁死的旧格式原样保留。v2 走独立入口 —— 这是控制回归风险的关键决策，
因为那个正则牵动 PICK / 语义匹配 / compactTokens / positionFingerprint 一整片。

### 屏幕文档格式（真机输出）

```
屏幕 s1 · 设置 · 竖屏 · 共 23 项
顶部
    [2] 无障碍 @540,279
    [3] button 返回 @77,143 c
中部
  [1] scroll (无标签) @540,1107 s
    [4] list (无标签) @540,1386 s
      [5] 已下载的应用 @551,653
      [7] DSH 屏幕点击 @369,788 c
      [8] 已开启 @256,857
```

- 编号按 `bounds.top` 后 `bounds.left` 排序（阅读顺序，不是节点遍历顺序）
- 缩进每层滚动容器 +2 空格；`c` = 可点击，`s` = 可滚动，`e` = 可编辑，`v`/`o` = 选中/未选中
- 预算 3000 字符，超了保顶部 + 底部（导航区）截断中部
- 敏感动作（发送/支付/删除/卸载…）行首标 `⚠`

### ★真机实测抓到并修掉的一个安全漏洞

第一版 `titleTexts` 的口径是「在可滚动容器里的文本不算标题」。真机一跑就露馅：

```
屏幕 s21 · 设置 · 竖屏 · 共 23 项
    [2] 无障碍 @540,279      ← 4 空格缩进 = depth 1
```

`protected=false` —— 系统「无障碍」页没被识别成保护页，`tap-ref` 直接点在标题上，
回的是 `{"ok":true,"through":"tap"}`。也就是说：**agent 可以自己进无障碍设置页点开关**。
这正是安全层要防的那条路径。

根因：Android 设置页的**标题本身就在 RecyclerView 里**（整页一个滚动容器，标题是第一项）。
按容器属性排除，等于把所有设置子页标题全排除 → `isProtected` 永远 false。

修法：判据改成两个正交条件 —— 标题区（heading 语义或落在屏幕上部 20%）+ **缩进层级 ≤ 1**。
修完实测：

```
protected= True  pkg= com.android.settings
→ POST /tap-ref  {"ok":false,"error":"protected page",
   "reason":"这是系统权限 / 安全页面。可以读，但不能自动点击；请把手机交给用户完成。"}
```

这条已写进 `_test_protected_pages.py` 当**回归用例**（`Item("无障碍", 279, depth=1)` 必须算标题），
并做了反向验证（改回旧口径 → 红）。

### 探针与反向验证

三个探针都做了「改坏一处必须变红」，逐条记录在 `docs/A11Y_SCREEN_REF.md` §8.3（21 条，全部变红）。
全量 60 个探针：**59 通过，1 失败** —— `_test_linux_ext_bridge.py`，已在主工作区 `9c6f755a`
复现同样失败（Alpine 索引里查不到 python3/py3-pip 等映射目标），是**既有基线失败**，与本轮无关。

### 真机验证记录（模拟器 Android 14 / x86_64）

| 场景 | 结果 |
|---|---|
| `GET /screen?format=doc` | `{"ok":true,"screen":"s1",...}`，23 项正确分区 |
| `POST /tap-ref` 正常点击 | `{"ok":true,"through":"action","verdict":"界面有变化。","changed":39}` |
| 用过期屏幕号（s1，当前 s3） | `409 {"error":"screen id changed","latest":"s3"}` |
| 编号不存在（s3 + 999） | `404 {"error":"no element [999] on s3"}` |
| 缺参数 | `400 {"error":"screen required"}` |
| 外部换页后再点旧编号 | `409 {"error":"target changed","reason":"[1]「User instructions」…变了，没有执行"}` |
| 系统无障碍页点标题 | `403 {"error":"protected page"}` |
| 点击无效果（同一输入框） | `{"verdict":"界面没有明显变化。","changed":0}` ← 不再是假 `ok:true` |

### 已知未做（明确记录，不假装完成）

- **屏幕去重**（dsh-Go 的 ScreenDigest：编号不变时只发变化行）。对 token 有实际收益，
  但引入「上一份文档」这个新状态，失败模式是「模型看到旧编号却没意识到」。
  先把正确性做扎实，去重留到下一版单独做 + 独立探针。
- **handoff 端点**（把手机交给用户，阻塞等操作完成）。当前只有 `403 + 请用户接管` 这一半。
- **系统权限弹窗的按钮语义**（哪个是允许/拒绝）。反正在保护页里不让点，暂不需要。

### 构建（照抄）

```bash
export JAVA_HOME=/d/新建文件夹/jbr
export PATH=$JAVA_HOME/bin:$PATH
# noruntime（默认，需要设备联网下载 ~130MB 运行时）
gradle -p . -Ppackage=noruntime assembleRelease
# full（内置运行时，模拟器实测用这个，否则首启卡在「Runtime required」）
gradle -p . -Ppackage=full -Pabi=x86_64 \
  -PruntimeZip=E:/tmp/apkrt/assets/runtime.zip \
  -PruntimeVersion=2026.11.05-85a92f6b -PruntimeBuiltAt=1793885396 assembleDebug
```
