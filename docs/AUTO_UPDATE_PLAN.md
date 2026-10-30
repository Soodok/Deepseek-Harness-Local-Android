# App 自动更新（Auto-Update）实施计划

> 目标版本：v1.4.37（versionCode 287 起）
> 状态：**v3 计划**（2026-10-28 定稿，全部待确认项已拍板）
> 实现分支：`feat/app-auto-update`（不在 `main` 上直接改）
> 关联文档：[RELEASE.md](RELEASE.md)（产物命名 / 签名）、[RUNTIME_SWAP.md](RUNTIME_SWAP.md)、[NOTIFICATIONS.md](NOTIFICATIONS.md)、[CI_RUNTIME_PIPELINE.md](CI_RUNTIME_PIPELINE.md)

---

## 0. 本轮决定（对照表）

| # | 你的要求 | 计划落地 | 章节 |
|---|---|---|---|
| D1 | 新增记录每个 APK SHA-256 的 JSON，且**该 JSON 下载不经代理** | CI 产 `update-index.json` + `.sig`；App 取索引**首请求直连**、强制验签；只有 APK 允许走代理（有 sha pin） | §5 |
| D2 | debug 签名每次变，要固定专属密钥，否则无法覆盖更新 | 共享 debug key 走 CI secret（**私钥不入库**），Gradle 三级回退 + 指纹门禁 | §13 |
| D3 | 检查时机：每次打开查一次；常驻 24h 一次；不开不查 | 冷启动（引擎 READY +20s）必查、不受 24h 节流；常驻 24h 定时器；10 分钟最小间隔保护；**不引入 WorkManager** | §6 |
| D4 | 关于页保留旧入口，支持跳转 / 后台下载 | 关于页「检查更新」行改为状态机：后台下载 / 立即安装 / 前往 Release；被忽略的版本也能手动更新 | §12 |
| D5 | 关于页运行时下载也改后台 + 通知进度 | 新增 `RuntimeUpdateService`，把 `AboutActivity` 的线程逻辑搬进前台服务；与 APK 更新共用通知、互斥 | §7.3 |

「其他内容用推荐方案」已采纳：忽略粒度 = **忽略此版本**（versionCode）；非 WiFi 同意后走**后台服务 + 通知栏进度**；检查间隔 24h；关于页旧入口保留。

### 0.1 定稿决定（v3 新增，2026-10-28 22:36）

| # | 决定 | 落地 |
|---|---|---|
| F1 | 索引网络策略：**按推荐** | 首请求**严格直连**（不套任何代理前缀）→ 直连确定失败后才允许经代理重取，**且必须验签通过**；设置项 `update_index_proxy_fallback`（默认 `true`）可关掉降级分支。见 §5.2 |
| F2 | 形态策略：**先查运行时变更** | 运行时**无变更** → 优先选 `noruntime`（省 ~250MB，且不触发首启重解压）；运行时**有变更** → 弹窗让用户选「只更新 App」/「App + 运行时」。见 §4.2 |
| F3 | 实现落在新分支 | `feat/app-auto-update`，`main` 保持干净；合并前走 PR 评审 |

**F2 的含义变化**：原计划默认「保持同形态」（`update_keep_package_mode=true`），现在改为默认「智能形态」（`update_package_mode_strategy=auto`）。理由：full 用户每次更新都白带 250MB 运行时，而运行时版本通常没变；但运行时有变更时不能替用户决定"多花 3 分钟解压"，所以那一步交回用户。

### 0.2 关于索引走代理的说明（原待确认项，已按推荐采纳）

- 国内直连 `github.com` / `objects.githubusercontent.com` 大概率超时 —— 这正是 `gh-proxy.org` 存在的原因。索引若只允许直连，**国内用户的自动更新会直接失效**（只剩关于页手动跳浏览器）。
- 真正防投毒的不是"直连"，而是**签名**：`update-index.json` 用 release key 签名，App 用内置公钥验签（`RuntimeIndexClient` 已在用这套设施）。代理改索引 → 验签失败 → 拒绝。所以「直连+验签」与「代理+验签」安全性等价，只差可用性；而「直连+不验签」挡不住 DNS 劫持，反而更弱。
- 约束保留：**第一次请求永不带代理前缀**（探针断言），降级写日志，可关。

---

## 1. 目标与非目标

### 1.1 目标

| # | 需求 | 落地形态 |
|---|---|---|
| R1 | WiFi 下后台静默下载，下完再弹窗 | 前台服务（DATA_SYNC）下载，状态栏实时进度；完成后弹「立即安装 / 稍后」 |
| R2 | 非 WiFi 只提示，同意才下 | 弹窗「发现 vX（大小），当前非 WiFi，是否下载？」→ 同意仍走后台服务 |
| R3 | 所有提示可忽略 | 弹窗「稍后」/「忽略此版本」；忽略记忆到下一个新版本 |
| R4 | 下载优先走现有代理 | APK 走 `RuntimeDownloadConfig.orderUrls()`：代理优先 → 直连兜底，共享 `.part` 续传 |
| R5 | 按运行环境自动选对包 | ABI / full·noruntime / debug·release / versionCode 四维匹配（§4） |
| R6 | 进度状态栏可见 | 新通道 `app_update`，参考 `PetPackDownloadService` |
| R7 | **哈希清单不可被代理污染** | 签名索引 + 直连优先 + 强制验签 + APK sha256 pin（§5） |
| R8 | **debug 包可覆盖升级** | 固定 debug 密钥入库，CI/本地一致（§13） |
| R9 | **检查时机贴合使用习惯** | 每次打开查一次；常驻 24h 一次；不开不查（§6） |
| R10 | **关于页仍可手动更新** | 旧入口保留，支持后台下载 / 安装 / 跳转（§12） |
| R11 | **运行时下载也后台化** | `RuntimeUpdateService` + 通知进度（§7.3） |

### 1.2 非目标

- 不做差分/增量更新（bsdiff 需额外 CI 产物，收益不抵复杂度）。
- 不做静默安装（无系统签名不可能，`PackageInstaller` 必须用户点确认）。
- **不引入 WorkManager / JobScheduler**：R9 明确「不开 App 不检查」，周期任务反而多余，还多一个依赖。
- 不做多通道切换 UI（内部仍区分 stable / edge / preview 的索引 channel，见 §22）。
- 不改运行时更新的**触发方式**（仍手动），只改**执行方式**（页面内等待 → 后台服务）。

---

## 2. 现状盘点

### 2.1 可复用能力

| 能力 | 现有实现 | 复用方式 |
|---|---|---|
| 版本检测 | `engine/UpdateChecker.kt`（Releases API，跳过 draft/prerelease） | 扩展为「检查 + 选包」，保留旧 `check()` 给关于页兜底 |
| 代理编排 | `engine/RuntimeDownloadConfig.kt`：`orderUrls()` 代理优先 / 直连兜底 / https 强制 / 防二次包装 | APK 下载原样复用；**索引刻意不用它**（§5.2） |
| 下载器 | `engine/HttpDownloader.kt`：候选回退、`.part`+Range 续传、sha256 强校验、进度回调 | APK 下载直接用它（本次能真正满足 sha pin） |
| 源标注 | `pet/PetPackIndexClient.sources()` → `PackSource(viaProxy)` | 通知标题「经代理 / 直连」 |
| 后台下载 + 通知 | `pet/PetPackDownloadService.kt`：前台服务、wakelock、800ms 节流、取消、BigText、结果通知 | 抽公共 `DownloadNotifier`，pet / APK / 运行时三处共用 |
| APK 安装 | `engine/ExtensionManager.installApkTask()`：`PackageInstaller` session → `commit(IntentSender)` | 抽公共 `ApkInstaller` |
| 运行时形态元数据 | `RuntimeInstaller.readManifest()`：`package` / `abi` / `runtime.version`；已装在 `filesDir/engine/.runtime.json` | 选包输入 |
| 索引签名 | `RuntimeIndexClient.verifySignature()`（内置 PEM + 自身证书双信任锚） | 抽出 `SignedIndex.kt`，两个索引共用 |

### 2.2 现存缺陷（本次必须一起修）

| # | 缺陷 | 后果 |
|---|---|---|
| D1 | `UpdateChecker` 取 asset 用 `name.contains("arm64")` 且取**第一个**匹配 | ① x86_64 设备拿到装不上的包；② tag Release 里 `-debug-noruntime.apk` 往往排最前 → 正式用户被引到 debug 包（签名不同，覆盖安装必失败） |
| D2 | 版本比较用 `versionName` 切段 | 表达不了「同 versionName 不同构建」；应以 `versionCode` 为准 |
| D3 | 只有「检查 + 跳浏览器」 | 无下载与安装闭环 |
| D4 | Release 里 SHA-256 只在说明正文（人眼可读） | 无法机器校验 → `HttpDownloader` 的 pin 契约过不了 |
| D5 | 无网络类型判断（全仓库无 `ConnectivityManager`） | 无法实现「仅 WiFi 静默下载」 |
| D6 | `PackageInstaller` 用 `getLaunchIntentForPackage` 当回调 | 安装失败（签名冲突/空间/用户取消）无回执 |
| D7 | **debug 用 AGP 默认 `~/.android/debug.keystore`**，每台机器/每个 CI runner 各自生成 | debug 包之间签名不同 → 完全无法覆盖更新，edge 渠道形同一次性包 |
| D8 | 运行时换装在 `AboutActivity` 的线程里跑，进度只在页内 | 退出关于页即失去进度感知；引擎被停期间用户不知情 |

---

## 3. 行为矩阵

| 场景 | 网络 | 设置 | 行为 | 可忽略方式 |
|---|---|---|---|---|
| A | 不计费（WiFi / 以太网 / USB 共享） | 自动检查开、仅 WiFi 下载开（默认） | 静默下载（状态栏进度）→ 完成后弹「立即安装 / 稍后」 | 「稍后」关弹窗，安装包保留，通知留入口 |
| B | 计费（蜂窝）/ 漫游 | 同上 | 不下，弹「发现 vX（大小），当前非 WiFi，是否使用流量下载？」 | 「稍后」/「忽略此版本」 |
| C | 任意 | 「仅 WiFi 自动下载」关 | 同 A，弹窗附流量提示 | 同 A |
| D | 无网络 | — | 静默失败，只记日志；下次打开再查 | — |
| E | 任意 | 版本在忽略列表 | 不提示、不下载；**关于页仍可手动更新**（R10） | 设置页/关于页清除 |
| F | 任意 | 存储不足（< 包大小 × 1.3） | 通知「空间不足，需 ~XXX MB」+ 弹窗 | 关弹窗 |
| G | 任意 | 电量 < 15% 且未充电 | 推迟静默下载，改轻提示（P1） | — |
| H | 任意 | **索引不可得**（直连失败且关了降级，或验签失败） | 不下载；关于页显示「无法获取更新信息」+ 前往 Release | — |

硬性规则：

- **检查必须在引擎 READY 之后**（首启可能在解压 580MB 运行时，此时抢网络/IO 会拖慢启动），延迟 20s 发起。
- 同一版本只自动下载一次（`update_auto_downloaded_code == 目标 code` 不再下，除非手动重试）。
- 安装弹窗每版本最多自动弹一次，之后只留通知/关于页入口。
- 任务在跑时检查逻辑直接返回，不重复起服务。

---

## 4. 版本与资产选择（R5）

### 4.1 判定输入（全部来自运行时事实）

| 维度 | 来源 | 备注 |
|---|---|---|
| ABI | `RuntimeInstaller.readManifest().abi` | 构建期写入，比 `Build.SUPPORTED_ABIS` 可信；缺失回退后者 |
| 包形态 | `readManifest().package` = `full` \| `noruntime` | 决定要不要内嵌运行时那份 |
| 签名类型 | `ApplicationInfo.FLAG_DEBUGGABLE` | 贴「实际装上来的是哪个」，比 `BuildConfig.DEBUG` 稳 |
| 已装版本 | `versionName` + `versionCode` | 比较以 versionCode 为准 |
| 已装运行时 | `filesDir/engine/.runtime.json` 的 `version` / `builtAt` | 与远端 `runtimeVersion` 比对 |
| 远端清单 | 签名 `update-index.json`（§5） | 含 name / size / sha256 / abi / package / variant / runtimeVersion |

### 4.2 选择算法（`UpdateAssetPicker`，纯函数、可单测）

```
输入: index.assets[], 运行环境(abi, pkgMode, isDebuggable), installedRuntime, strategy
1. 过滤 assets: variant 与当前构建匹配（release 包只看 release；debug 包只看 debug）
2. 过滤: abi == 设备 abi
3. 校验 latest.versionCode > installed.versionCode，否则视为无更新
4. 形态决策（F2，strategy=auto）：
   a. pkgMode == noruntime：
        → 选 noruntime 资产（若索引无 noruntime，退回 full 并标注「将下载完整运行时」）
   b. pkgMode == full：
        fullAsset = 索引中 package=full 的资产；nrtAsset = package=noruntime 的资产
        runtimeChanged = fullAsset.runtimeBuiltAt > installedRuntime.builtAt
                       （缺 builtAt 时回退 runtimeVersion != installedRuntime.version）
        - runtimeChanged == false 且 nrtAsset 存在 → 选 noruntime（省 ~250MB，不重解压）
        - runtimeChanged == true  且 nrtAsset 存在 → 返回 AskUser(fullAsset, nrtAsset)
        - nrtAsset 不存在 → 选 full（没得选）
5. strategy == keep → 强制 package == 当前 pkgMode（旧行为，设置可切，保留给保守用户）
6. 结果为空 → 无匹配资产，降级为「有新版本，前往 Release」
```

返回值是带意图的密封类，而不是「一个 asset」：

```kotlin
sealed interface PickResult {
    data object None : PickResult
    data object UpToDate : PickResult
    data class Single(val asset: IndexAsset, val mode: ModeNote) : PickResult
    // 运行时变更时的二选一（F2）
    data class AskUser(val appOnly: IndexAsset, val withRuntime: IndexAsset,
                       val installedRuntime: String, val remoteRuntime: String) : PickResult
}
enum class ModeNote { KeptRuntime, AddedRuntime, FullToNoRuntime, NoRuntimeAsset }
```

- 索引是结构化 JSON，**文件名只进日志**、不参与匹配 —— 从根上消灭 D1。
- 纯函数、不依赖 Android 框架 → 可用 `scripts/probes/` 用 Python 复刻断言。
- **`runtimeBuiltAt` 是比较依据**，与 `RuntimeInstaller.ensureInstalled()` 里 `installed.builtAt > manifest.runtime.builtAt` 的既有判据同源，避免两处用不同口径判断"运行时变没变"。

### 4.3 与运行时通道的关系（三条硬约束）

1. **APK 更新不得降级运行时**：`RuntimeInstaller.ensureInstalled()` 的「full 包只在包内版本更新时重解压，否则复用」必须保留，用探针锁死。
2. **选了 full 且包内 runtime 更新时**：安装后首启要落盘 ~580MB、耗时 1–3 分钟 → 这正是 F2 里「有变更给用户选」的意义；用户选「App + 运行时」时弹窗必须预告耗时。
3. **选了 noruntime 时**（运行时无变更）：安装后不重解压，秒起；`ensureInstalled()` 里 `manifest.packageName == "noruntime" -> reuse=true` 已保证这点。
4. **noruntime → noruntime 不动运行时**；运行时有更新仍走关于页手动通道（R11 只是把它改成后台执行）。

---

## 5. 哈希清单：`update-index.json`（R7，本轮重点）

### 5.1 发布物

| 文件 | 内容 | 签名 |
|---|---|---|
| `update-index.json` | 最新版本元数据 + **每个 APK 的 sha256 / size / abi / package / variant / runtimeVersion** | `update-index.json.sig`（SHA256withRSA，release key） |

- 与 `runtime-index.json` 并列，发在同一个 Release；**tag / edge / preview 三种发布都产出**（否则 edge 用户永远拿不到更新信息）。
- 复用现有签名设施：`scripts/sign-runtime-index.py`（同一把 key、同一个 PublicKey 导出流程 `generateIndexPubKey`）；App 侧两个索引共用同一验签实现（抽出 `SignedIndex.kt`）。
- Fork 自动跟随：索引 URL 由 `RepoConfig.RELEASES_LATEST` 拼，`apply-fork-links.py` 已覆盖，无需改脚本。

### 5.2 抓取路径（**索引不经代理**）

```
① 直连：https://github.com/<repo>/releases/latest/download/update-index.json (+ .sig)
   HttpURLConnection，20s 连接 / 20s 读，绝不套任何代理前缀
② 直连失败（超时/重置/非 2xx/DNS）→ 若 update_index_proxy_fallback=true（默认）
   → 经 RuntimeDownloadConfig.proxy 再取一次
③ 无论 ①②：必须先验签（SignedIndex.verify）再解析；schema/versionCode/minAppVersionCode 校验
   任一不过 → 拒绝，本次不更新（绝不「无签名也用」）
```

- **实现约束**：索引请求**不得**调用 `RuntimeDownloadConfig.orderUrls()`，单独一个 `directUrl()` 分支，注释写明原因。
- **探针锁**：`_test_update_index_direct.py` 断言索引请求 URL 列表里**不出现**代理前缀、且 `verifySignature` 在解析/写缓存之前被调用。
- 缓存：`filesDir/update/index.json` + `.sig`，带 `ETag` 条件请求（304 视为未变更）。缓存的索引**仅用于展示**；真正下载前必须重新验签（或校验缓存里的 sig 仍有效）。

### 5.3 APK 下载与校验（允许走代理）

```
候选 = RuntimeDownloadConfig.orderUrls(listOf(assetUrl), proxy)   ← 代理优先、直连兜底
下载 = HttpDownloader.download(urls, dest, expectedSha256 = index.sha256, expectedSize = index.size)
      → sha/size 不匹配时自动删 .part 并换下一个候选（投毒包会被这条挡住）
二次 = getPackageArchiveInfo 读签名证书，必须 == 已装 App 证书
      versionCode == index.versionCode 且 > 当前
```

### 5.4 为什么这次能真用上 sha pin

之前 `HttpDownloader` 的 `require(sha256.isNotBlank())` 挡住我们，是因为哈希只在 Release 说明正文里，机器读不到。现在哈希来自**验签过的索引**，pin 成立，**下载链路不需要任何降级模式**。

---

## 6. 检查时机（R9）

| 时机 | 行为 | 节流 |
|---|---|---|
| 冷启动（进程创建 + 引擎 READY） | +20s 后**必定检查一次**（符合「每次打开应用检查一次」，不受 24h 节流） | 仅 10 分钟最小间隔保护 |
| 应用常驻 | 每 **24h** 一次定时器（协程 `delay` 循环，绑进程生命周期） | — |
| 后台回到前台（`onStart`） | 距上次检查 > 24h 则补一次 | 24h |
| 网络恢复（VALIDATED 或转不计费） | 有 pending 下载 → **续传**；否则不额外查 | — |
| 手动（关于页 / 设置页） | 无视所有节流 | — |
| 不开 App | **不检查**（无 WorkManager、无 AlarmManager） | — |

- **10 分钟最小间隔**：防反复杀进程重开把 Releases API 的 60 次/小时限额打爆；命中时复用上次结果，不显示"检查中"。
- 常驻定时器随进程存活自然结束；进程被杀 → 下次打开按「冷启动」规则查。
- 结果落 `update_last_check_at` / `update_last_result`；关于页与设置页显示「上次检查 22:30 · 已是最新」。

---

## 7. 架构与模块

### 7.1 新增 `app/src/main/java/app/dsh/mobile/update/`

| 文件 | 职责 | 预估行数 |
|---|---|---|
| `AppUpdate.kt` | 模型：`UpdateInfo`、`IndexAsset`、`UpdateState`、`Phase` | 130 |
| `AppUpdatePrefs.kt` | 偏好（复用 `DshApp.PREFS_UI`）：开关、忽略记录、上次检查/结果、pending、已下载 | 150 |
| `NetworkState.kt` | `ConnectivityManager` 封装：`isUnmetered()`、`isWifi()`、`register()`、线路名 | 110 |
| `UpdateAssetPicker.kt` | §4.2 纯函数选包 | 110 |
| `SignedIndex.kt` | 索引抓取（直连优先/降级规则）+ 验签（自 `RuntimeIndexClient` 抽出） | 210 |
| `AppUpdateChecker.kt` | 编排：取索引 → 选包 → `UpdateInfo`；ETag 缓存 | 190 |
| `AppUpdateDownloadService.kt` | 前台服务：下载 → 校验 → 落地 → 通知 → 完成弹窗 | 400 |
| `RuntimeUpdateService.kt` | 前台服务：运行时换装（R11），逻辑搬自 `AboutActivity` | 260 |
| `DownloadNotifier.kt` | 通知公共实现：通道、进度节流、BigText、取消/结果 | 240 |
| `AppUpdateBus.kt` | 状态总线（`StateFlow`），UI 只订阅 | 90 |
| `ApkInstaller.kt` | `PackageInstaller` 封装 + 预检（签名/版本/权限）+ 回执 | 230 |
| `InstallResultReceiver.kt` | 静态 `BroadcastReceiver`：`PENDING_USER_ACTION` / `SUCCESS` / `FAILURE_*` | 100 |
| `UpdateDialogs.kt` | 三种弹窗（非 WiFi 询问 / 下载完成 / 空间不足），主题色 token | 210 |
| `AppUpdateScheduler.kt` | §6 时机编排 + 两个后台任务的**互斥闸门** | 200 |
| `download/DownloadSession.kt` | **下载基座**：前台服务托管 + 通知 + 节流 + 取消 + 收尾（§7.5） | 260 |
| `download/DownloadNotifier.kt` | 通知公共实现（通道 / 进度 / 展开 / 结果 / 取消动作） | 150 |

### 7.2 修改既有文件

| 文件 | 改动 |
|---|---|
| `engine/UpdateChecker.kt` | **删除**（收尾轮决定）：新链路完全由 `AppUpdateChecker` 承担，旧类零引用且带着 D1/D2 两个 bug，留着只会被误用 |
| `engine/RuntimeIndexClient.kt` | 验签抽到 `SignedIndex`，自身改为调用（行为不变，探针覆盖） |
| `engine/RuntimeInstaller.kt` | `installFromIndex` 增 `shouldCancel: () -> Boolean = { false }`（下载与解压阶段检查） |
| `engine/ExtensionManager.kt` | `installApkTask` 的装包提交改走 `ApkInstaller.commit()`（含 `KIND_EXTENSION` 回执） |
| `AboutActivity.kt` | 更新行 → 状态机（§12）；运行时行 → 起 `RuntimeUpdateService`，页内只显示「后台进行中/已完成」 |
| `MainActivity.kt` | 订阅 `READY` → `AppUpdateScheduler.onAppReady()`；`onStart` 补查 |
| `SettingsActivity.kt` + `activity_settings.xml` | 「应用更新」分组（§11） |
| `AndroidManifest.xml` | 两个前台服务（`dataSync`）+ `InstallResultReceiver`（`exported=false`） |
| `app/build.gradle.kts` | debug `signingConfig` 三级回退（secret 注入 → 本地 → AGP 默认）；release 加 `-PrequireReleaseSigning` 闸门（§13） |
| `.gitignore` | debug keystore 例外（§13.2，注意目录忽略规则） |
| `res/values*/strings.xml` ×3 | 约 40 条文案（三语，`check-i18n.py` 必过） |
| `docs/NOTIFICATIONS.md`、`docs/RELEASE.md` | 新通道；索引产物；debug 固定签名 |

### 7.3 运行时后台化（R11）

`RuntimeUpdateService`（把 `AboutActivity.installRuntime` 原样搬进服务）：

```
onStartCommand:
  startForeground(「准备中」不确定进度)          ← 5s 内，否则 ANR
  Thread:
    UpdateTasks.acquire("runtime")              ← 与 APK 下载/安装互斥
    supervisor.stop(graceMs = 3500) → false 则失败退出
    RuntimeInstaller.installFromIndex(entry, indexUrl, proxies, onProgress → 通知, shouldCancel)
    supervisor.restart()
    结果通知（成功 / 失败原因 / 已取消）
```

- 通知：与 APK 共用 `DownloadNotifier`，同通道 `app_update`，标题区分「运行时更新」。
- 取消：仅**下载阶段**生效（`shouldCancel` 注入）；解压阶段移除取消动作（半解压的 engine 目录比等 3 分钟更糟）。
- 关于页：点击 → 起服务 → 行内「运行时 vX 正在后台安装，进度见通知栏」；退出页面不影响；完成后行内「已是最新」（订阅 `RuntimeUpdateBus`）。
- 引擎停摆期间用户点终端/会话会失败 → 通知正文写明「引擎暂停中，安装完成后自动重启」。

### 7.4 状态机与互斥

```
Idle → Checking → (UpToDate | Failed | Gated[空间/电量/网络] | Available)
Available --(不计费 或 用户同意)--> Downloading → Verifying → Downloaded
Downloading --(取消)--> Cancelled(.part 保留 7 天)
Downloading --(sha/签名失败)--> Failed(reason) --(重试/网络恢复)--> Downloading
Downloaded --(安装)--> Installing → (Installed | InstallFailed)
```

- `UpdateTasks` 单例闸门：APK 下载 / APK 安装 / 运行时换装三者互斥；第二个请求进来时 UI 提示「另一项更新任务进行中」。
- 任务不依附 Activity（退出、被杀都不影响）—— 这是必须用前台服务的原因，与 pet 一致。

---

### 7.5 下载基座（本次先建，后续收编 pet）

**问题**：三种"后台下几百 MB"的任务各自把同一套外壳写了一遍 —— 前台服务托管、
5 秒内 `startForeground`、wakelock、进度节流、通知渲染、取消、结果通知、
`stopForeground(DETACH)`。取数策略确实不同（pet 分卷 / 运行时下载+解压 / APK 单文件），
但外壳没有任何理由不同；重复的直接后果是"修一个坑只修一处"——线路标注在窄屏被截断，
就只在 pet 里修过。

**做法：抽壳不抽芯**

```kotlin
abstract class DownloadSession : Service() {
    protected abstract fun ui(): DownloadUi          // 通知长什么样
    protected abstract fun runTask(): TaskResult     // 阻塞执行；用 report() 推进度
    protected open fun gate(): UpdateTasks.Task?     // 与其它更新任务互斥
    protected open fun onFinished(result: TaskResult)
    // 基座统一负责：线程、wakelock、节流、取消标志、结果收尾、stopForeground(DETACH)
    protected fun report(pct: Int, text: String, ...)   // 800ms + 100% 必刷
    protected fun reportSource(viaProxy: Boolean?)      // 标题上的「经代理 / 直连」
    protected fun setCancelable(allow: Boolean)         // 解压阶段关掉取消
}
```

**用基座的两种姿势**（都成立，故不强行统一"怎么下"）：
- **基座驱动（APK）**：`runTask()` 里调 `HttpDownloader`，`onProgress → report()`
- **外部驱动（运行时 / pet）**：业务自己的进度回调直接转 `report()`，基座只渲染

**迁移状态**
| 任务 | 现状 | 计划 |
|---|---|---|
| APK 下载 | ✅ 已用基座 | — |
| 运行时换装 | ✅ 已用基座 | — |
| pet 资源包 | ⏳ 仍是 `PetPackDownloadService` 私有实现 | P1：分卷下载器与安装流程保留，只把外壳换成本类 |

**为什么本版不动 pet**：它现在工作正常（分卷、断点、结果通知都验证过），
和自动更新一起改会让"出问题时分不清是谁弄坏的"。基座已具备它需要的全部能力
（BigText、线路标注、取消、节流），替换是纯粹的搬运。

## 8. 下载链路细节

| 项 | 决定 | 理由 |
|---|---|---|
| APK 候选 URL | `RuntimeDownloadConfig.orderUrls(...)` | 与 pet/运行时同一条规则；代理挂了自动回直连 |
| 索引 URL | **直连专用**（§5.2） | R7 |
| 落盘 | `filesDir/update/<versionCode>/<assetName>` + `.part` | 不用 `cacheDir`（存储紧张会被回收，250MB 白下）—— pet 踩过 |
| 续传 | `.part` + `Range`；服务端忽略 Range 时从头写 | 跨候选源共用 `.part` |
| 并发 | 单任务闸门 + 跨服务互斥 | 不会同时下 APK 又换运行时 |
| 超时 | connect 20s / read 60s | 大文件慢速网络 |
| 空间预检 | 需 `size * 1.3` 可用 | 解压/临时文件余量 |
| 唤醒 | `PARTIAL_WAKE_LOCK` 上限 60 分钟，`finally` 释放 | 锁屏 CPU 深睡会把读拉长到几十秒 |
| 清理 | 清非当前版本目录；`.part` 超 7 天清；安装成功清整个目录 | 防几百 MB 越积越多 |

---

## 9. 通知（R6，参考 pet）

| 项 | 取值 |
|---|---|
| 通道 | `app_update`「应用更新」，`IMPORTANCE_LOW`、`setShowBadge(false)`（运行时复用同通道） |
| 通知 ID | `0x9E10`（APK）、`0x9E11`（运行时），避开 pet `0x9E01` 与 engine/agent/lan_web |
| 标题 | `应用更新 · 经代理` / `应用更新 · 直连` / `运行时更新`（线路放标题，pet 实测窄屏正文尾部会被截） |
| 正文 | `正在下载 v1.4.37 · 42%` / `正在安装运行时 · 62%` |
| 进度 | `setProgress(100, pct, false)`；准备阶段 `indeterminate=true` |
| 展开 | `v1.4.37 · 86.4 / 213 MB · 1.2 MB/s · 剩余 1 分 40 秒`；运行时：`引擎暂停中，完成后自动重启` |
| 动作 | 「取消」（运行时仅下载阶段有） |
| 点击 | 主界面（APK）/ 关于页（运行时） |
| 节流 | <800ms 且百分比未变不刷；源切换 / 100% / 失败立即刷 |
| 完成 | `v1.4.37 已下载 · 点击安装`（`setOngoing(false)`、`setAutoCancel(true)`） |
| 失败 | `下载失败：<原因> · 点击重试` |

Android 13+ 无通知权限：服务仍合法，只有弹窗可见；此时「已下载/有更新」信息落到关于页状态行，不让用户错过。

---

## 10. 弹窗与「可忽略」

| 弹窗 | 触发 | 标题 | 正文 | 按钮 |
|---|---|---|---|---|
| **运行时变更二选一**（F2） | 选包结果 = `AskUser` 且有更新 | `发现新版本 v1.4.37` | `本次更新包含新运行时（v2026.11.01 → v2026.12.03）。仅更新应用约 213 MB，首次启动立即可用；连同运行时约 463 MB，首次启动需 1–3 分钟。` | `仅更新应用`（主，选 noruntime）/ `应用 + 运行时`（选 full）/ 副文本 `稍后` |
| 移动网络询问 | 有更新 + 计费网络 | `发现新版本 v1.4.37` | `当前非 WiFi，安装包 213 MB。是否使用流量下载？` | `仍然下载` / `稍后`（副文本：`忽略此版本`） |
| 下载完成 | 校验通过 | `新版本 v1.4.37 已就绪` | `已下载完成，是否立即安装？`（若选的是 full 且含运行时更新，追加：`含运行时更新，首次启动需 1–3 分钟`） | `立即安装` / `稍后` |
| 空间不足 | 预检失败 | `存储空间不足` | `需要 277 MB 可用空间，当前 120 MB` | `知道了` |

- 运行时变更二选一**只在有更新且索引里两种资产都存在时**出现，每版本最多问一次；选过一次即记 `update_mode_choice_code`，后续静默沿用。
- 弹窗在计费网络下会**叠加**：先问形态，再问流量（避免用户选完又被打断两次以上，问形态时正文已带大小，故先形态后流量）。

颜色必须走 `@color/*` token（`AI_CONTEXT.md` 纪律；`_test_theme_menu` 会抓硬编码 hex）。

「可忽略」三层语义：

- **稍后** = 本次不处理，任务保留；下次时机（下次打开 / 24h / 连 WiFi / 手动）可再提示。
- **忽略此版本** = 写 `update_ignored_version_code`，该版本不再自动提示；**关于页仍可手动更新**（R10）；更高版本自动失效。
- **关闭弹窗（返回键/点外部）** = 等价「稍后」，不写忽略记录。

---

## 11. 设置项

| 项 | 键 | 默认 | 说明 |
|---|---|---|---|
| 自动检查更新 | `update_auto_check` | `true` | 关闭后只在关于页/设置页手动检查 |
| 仅在 WiFi 下自动下载 | `update_wifi_only` | `true` | 关掉时移动网络也静默下（首次关闭二次确认） |
| 直连失败时经代理获取更新信息 | `update_index_proxy_fallback` | `true` | 见 §0 偏差；关掉即严格直连 |
| 更新包形态 | `update_package_mode_strategy` | `auto` | **`auto`**：运行时无变更优先 noruntime，有变更弹窗二选一（F2）；**`keep`**：始终与当前形态一致（旧行为） |
| 已忽略的版本 | `update_ignored_version_code` | `0` | 显示版本名 + 「清除」 |
| 立即检查 | — | — | `checkNow(force=true)` |

运行期状态键：`update_last_check_at`、`update_last_result`、`update_pending_version_code`、`update_auto_downloaded_code`、`update_prompted_version_code`、`update_index_fetched_at`、`update_mode_choice_code`（形态二选一已问过）、`update_chosen_package`（`full`/`noruntime`，本次选定形态）。

---

## 12. 关于页改造（R10）

`rowUpdate` 从「点击 → 检查 → 跳浏览器」改为**状态机行**（行内文案 + 主按钮 + 次按钮）：

| 状态 | 行内文案 | 主按钮 | 次按钮 |
|---|---|---|---|
| 未检查 / 已是最新 | `已是最新（1.5.0）· 上次检查 22:30` | `检查更新` | — |
| 有更新（计费网络） | `发现 v1.5.1（213 MB）· 非 WiFi` | `后台下载` | `前往 Release` |
| 有更新（不计费） | `发现 v1.5.1（213 MB）` / `正在下载 42%` | `后台下载` / `查看进度` | `前往 Release` |
| 已下载待安装 | `v1.5.1 已下载，待安装` | `立即安装` | `删除安装包` |
| 被忽略的版本 | `v1.5.1 已忽略，可手动更新` | `后台下载` | `前往 Release` |
| 索引不可得 | `无法获取更新信息：<原因>` | `重试` | `前往 Release` |
| 安装失败 | `安装失败：签名不一致，需卸载重装` | `重试` | `前往 Release` |

- 所有动作都走 §7 的服务与 `ApkInstaller`，**与自动流程同一条代码路径**（避免"手动能装、自动装不上"的分叉 bug）。
- 老行为（跳浏览器）保留为次按钮 —— 这就是「旧入口保留」的落点。
- `rowRuntime` 同构改造：`检查运行时` → `后台安装`（起服务）→ `后台进行中，进度见通知` → `已是最新`。

---

## 13. debug 签名固定（R8）

### 13.1 问题

AGP 默认 debug 签名用 `~/.android/debug.keystore`，**首次构建时自动生成**。于是：每个开发者不同、**每个 CI runner 每次都不同**（runner 是干净 HOME）→ edge/preview 的 debug 包可以做到「每版签名都不一样」，根本无法覆盖安装。

### 13.2 做法（**私钥不入库**）

需要的是「同一把 key」，不是「公开这把 key」。私钥进仓库的唯一后果是：谁都能签一个
"看起来同源"的 debug 包，签名比对这道防线在 debug 渠道上归零。所以和 release 一样，
材料只放两处：CI secrets + 本地磁盘。

**三级回退**（`app/build.gradle.kts`）

| 优先级 | 来源 | 场景 |
|---|---|---|
| 1 | `DSH_DEBUG_STORE_FILE`（CI 解码到 `$RUNNER_TEMP`）/ `-PdshDebugStoreFile` | CI 与自定义 |
| 2 | `debug-signing/dsh-debug.keystore` + `.properties`（gitignore） | 本地开发 |
| 3 | 不覆盖 → AGP 默认 `~/.android/debug.keystore` | 新克隆 / 无 secret 的 fork PR |

- 第 1 级**显式指定却文件不存在时直接失败**：静默回落会让人以为用的是共享 key，
  实际产出 runner 随机 key 的包 —— 正是本机制要消灭的坑。
- 第 3 级**不是失败**：构建照常成功，只打一行警告。产物只与本机构建互相可覆盖，
  用于日常开发足够；要覆盖历史 edge 包就从团队密码库取一份 `debug-signing/`。
- 替换掉原先"密钥入库 + 口令明文 + 缺失即失败"的方案（那套能让任何人伪造 debug 包，
  且让每个新克隆的开发者都被迫先造一把 key 才能编译）。

**与 release 的分界**：release 缺私钥 = 正式包构建失败 —— CI 传
`-PrequireReleaseSigning=true`，缺材料时 `packageRelease` 直接抛异常，宁可失败也不产出
`app-release-unsigned.apk` 让发布链路误传。debug 缺私钥只是退化，不阻断任何人。

**`.gitignore` 只放行公开指纹**

```
debug-signing/*
```

- 整个目录都不入库，**包括**证书指纹：期望值只走 CI 变量 `DEBUG_CERT_SHA256`。
  仓库里留一份指纹 = 两个真值来源，换 key 时必有一个是错的。
- ⚠️ **不要**写任何 `!debug-signing/...` 否定规则：解禁单个文件时极易顺手解禁目录，
  而目录里躺着 `dsh-debug.properties`（口令）与 `dsh-debug.keystore.base64`（私钥本体）。
  更不要写 `!keystore/` —— 那会连带 release 私钥一起放行，一次 `git add -A` 就推到公开仓库。

**CI 门禁**（`.github/workflows/android-build.yml`）

| 情况 | 行为 |
|---|---|
| 没配 `DEBUG_KEYSTORE_BASE64`（fork PR） | 警告 + 跳过，明确标注"该产物不可分发" |
| 配了 secret 但没配 `DEBUG_CERT_SHA256` 变量 | **失败**（否则"校验通过"其实没校验） |
| 都配了但指纹不一致 | 失败（signingConfig 失效 / secret 被换 key） |

**口令与历史清理**

- 口令**随机生成**（28 位，`scripts/make-debug-keystore.sh`），与 keystore 分开存放；
  Gradle 侧没有默认口令 —— 找到 keystore 却读不到口令会直接失败并给指引。
  理由：keystore 泄漏 + 口令公开 = 私钥泄漏，而"误提交 / 发给别人排查 / 备份盘同步+"
  都是常见泄漏途径。
- 密钥曾误入三个**本地**提交（分支未 push）：`git filter-branch` 只重写该范围 → 删
  `refs/original` + 过期本分支 reflog → 直接删除两个松散对象。**没有**跑全库
  `gc --prune=now`（那会连同仓库里其它 70 个无关的不可达提交一起销毁）。
- 因为"进过历史"就等于"可能已流出"，**已轮换一把新 key**，旧 key（`36:F9:91…`）作废。
  成本为零：现存 edge/preview debug 包本来就是各 runner 随机 key 签的，换不换都得
  卸载重装一次。

### 13.3 代价与迁移

- **断层一次**：现在装着旧 edge/preview debug 包的用户，其包是随机 debug key 签的 → 新共享 debug key 的包**无法覆盖**，必须卸载重装（`filesDir` 数据一并丢失）。
- 处理：Release 说明顶部明确「debug 包签名已固定，edge/preview 用户需卸载重装一次（此后可正常覆盖升级）」；**release 用户完全不受影响**。
- 签名一致性只在「同一把 key」内部成立：CI 用 secret 里的 key，本地开发若没放
  `debug-signing/` 就用本机 key —— 两者互不覆盖是**预期行为**，不是 bug。
- 与自动更新的关系：选包按 `FLAG_DEBUGGABLE` 分流（§4），release 用户永远不会被推 debug 包，反之亦然。

---

## 14. 安装与进程/引擎协同

1. **安装前必须停引擎**：覆盖安装会杀进程，node/llama/局域网页子进程会变孤儿（`OrphanReaper` 兜底，但显式停更干净）。`supervisor.stop(3500)` 返回 false 则放弃并提示。
2. **权限**：`canRequestPackageInstalls()` 为 false → 引导 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`（带包名）。
3. **回执**：`commit()` 的 `IntentSender` 指向 `InstallResultReceiver`（`PendingIntent.getBroadcast`）：
   - `STATUS_PENDING_USER_ACTION` → 取出 `Intent` 并 `startActivity`（**漏这步就是"点了没反应"**）；
   - `STATUS_SUCCESS` → 清 `filesDir/update/` 与相关偏好；
   - `STATUS_FAILURE_*` → 保留 APK，提示 + 「重试 / 删除」。
4. **签名冲突**（换 key / 别人 fork 的包）：`INSTALL_FAILED_UPDATE_INCOMPATIBLE` → 文案「当前安装包与系统内已安装版本签名不一致，无法覆盖安装，需卸载重装」+ `前往 Release`。**不做任何绕过。**
5. 安装成功后系统不会自动拉起 App；下次打开即新版，启动时对比并清理残留。

---

## 15. 文件布局

```
filesDir/update/
  index.json / index.json.sig     # 校验过的索引缓存（仅展示用）
  <versionCode>/
    dsh-android-v1.4.37-arm64-v8a-release.apk
    ....apk.part                  # 可续传，7 天过期
    meta.json                     # {versionCode, versionName, sha256, source, at}
```

---

## 16. 安全

| 面 | 措施 |
|---|---|
| 索引来源 | **首请求直连**（不套代理）+ 强制验签（内置 PEM / 自身证书） |
| 索引重放 | `versionCode > 当前` + `minAppVersionCode`；旧索引只会导致"不更新"，无害 |
| APK 传输 | 允许代理；`enforceHttps` 强制 https（用户自建 http 代理为显式例外） |
| APK 完整性 | **sha256 pin（来自验签索引）+ size**，`HttpDownloader` 内置强校验 |
| APK 来源 | 二次校验签名证书 == 已装 App 证书 |
| 防降级 | versionCode 单向递增 + 索引 `minAppVersionCode` |
| 校验失败 | 一律删文件、不进安装器；**无"跳过校验"开关** |
| 隐私 | 检查只发一个带 `If-None-Match` 的 GET；不上报设备信息 |
| 凭据 | 下载不带任何 token |
| debug key | 公开是有意为之，仅测试渠道（§13.3） |

---

## 17. 异常与自愈

| 异常 | 处理 |
|---|---|
| 直连索引超时 | 按 §5.2 降级（默认开）→ 仍验签；都失败 → 本次不更新，关于页可重试 |
| 验签失败 | 拒绝 + 日志记 host 与原因（便于排查代理/劫持）；不降级 |
| API 限额 | 读 `X-RateLimit-Reset`，本会话不再重试；索引走静态文件不消耗 API 限额 |
| 代理全部失败 | 直连兜底；都失败 → 「下载失败」+ 保留 `.part`，网络恢复续传 |
| 下载中进程被杀 | 前台服务降低概率；`.part` 保留，下次检测 pending 续传 |
| 服务启动超时 | 5s 内 `startForeground`（先挂「准备中」） |
| 磁盘写满 | 预检拦截 + 写失败捕获 → 清 `.part`，提示 |
| 用户取消系统安装框 | `STATUS_FAILURE_ABORTED` → 不二次骚扰，只留通知/关于页入口 |
| 已下载长期不装 | 7 天清理，允许重新下载 |
| 运行时换装中途被杀 | 现有 `recoverInterruptedSwap()` 已覆盖，后台化不改变该保证 |

---

## 18. 文件级改动清单

**新增（Kotlin）**：`update/` 目录 14 个文件（§7.1）

**新增（脚本 / CI / 密钥）**：

| 文件 | 用途 |
|---|---|
| `scripts/write-update-index.py` | 汇总 `apk/` 资产 → `update-index.json`（sha256/size/abi/package/variant/runtimeVersion/versionCode） |
| `scripts/make-debug-keystore.sh` | 一次性生成 debug 密钥（产物入库） |
| （无） | debug 签名材料**全部不入库**：私钥/口令/指纹分别走 `DEBUG_KEYSTORE_BASE64`、`DEBUG_STORE_PASSWORD`、`DEBUG_CERT_SHA256`（见 §13.2） |
| `.github/workflows/android-build.yml` | ① build-apk 额外上传 `meta-<abi>-<package>.json`（versionCode/versionName/runtimeVersion）；② publish-* 汇总生成并签名 `update-index.json`；③ debug APK 指纹校验 |
| `scripts/probes/_test_update_asset_picker.py` | 选包矩阵（含 F2 形态决策）断言 |
| `scripts/probes/_test_update_index_direct.py` | 索引首请求不走代理 + 必须验签 |
| `scripts/probes/_test_update_prefs.py` | 忽略 / 节流 / 状态键 |
| `scripts/probes/_test_debug_signing.py` | debug 签名固定（防回落默认 keystore） |
| `scripts/probes/_test_update_no_runtime_downgrade.py` | full 包不降级运行时（防回归） |

> ⚠️ **`.gitignore` 还有一处坑**：`/scripts/probes/*` 是整目录忽略 + 逐个 `!` 白名单的（第 70–87 行）。上面 5 个探针必须各加一行 `!/scripts/probes/_test_xxx.py`，否则文件写好了却**不会进版本库**，CI 上"探针全绿"其实是没跑到。

**修改**：§7.2 表 + `docs/NOTIFICATIONS.md`、`docs/RELEASE.md`、`AI_CONTEXT.md`（发版记录）。

### 18.1 本轮实现状态（2026-10-28 深夜）

已完成：CI（`write-update-index.py` + 三个发布渠道签名上传 + debug 指纹门禁）、
共享 debug 签名（私钥走 CI secret + 三级回退 + 探针）、`update/` 全部 12 个类、
`download/` 基座、关于页状态机（**主按钮 + 次按钮**）、设置页「应用更新」分组、
主界面接入、三语文案、**5 个探针**、编译通过。

> 签名策略在实现后调整过一次：原方案把 debug 私钥 + 明文口令提交进 `debug-signing/`；
> 改为**仓库只留公开指纹**，私钥走 `DEBUG_KEYSTORE_BASE64` secret、本地可选回退（§13.2）。
> 并额外做了两件事：① 用 `filter-branch` 把密钥从三个本地提交的历史中清除（分支未 push）；
> ② 轮换新 key + 随机口令（旧 key `36:F9:91…` 作废，新指纹只填 CI 变量
> `DEBUG_CERT_SHA256`，仓库不留副本）。

**收尾轮补掉的缺口**（对照第一轮自评）

| # | 缺口 | 处理 |
|---|---|---|
| 1 | 设置页 4 个开关没有 UI | 新增 `settings_section_update`：自动检查 / 仅 WiFi / 索引代理降级 / 形态策略 + 已忽略版本（可清除）+ 立即检查；关「仅 WiFi」二次确认 |
| 2 | `engine/UpdateChecker.kt` 成死代码（D1/D2 bug 跟着留着） | **删除**（零引用，关于页已走 `AppUpdateChecker`） |
| 3 | `ExtensionManager.installApkTask` 第三套装包实现 | 装包提交收归 `ApkInstaller.commit()`，回执改 `InstallResultReceiver`（`KIND_EXTENSION` 不动更新状态） |
| 4 | 清理只做"安装成功清空" | `AppUpdatePrefs.cleanupStale()`：下载前清非当前版本目录 + `.part` 超 7 天 |
| 5 | 关于页是"一行一动作" | 主按钮（检查/后台下载/查看进度/立即安装/重试）+ 次按钮（前往 Release / 删除安装包），长按保留 |
| 6 | 版本号仍是 1.4.36 / 286 | 升到 **1.5.0 / 287** |
| 7 | 缺 `_test_update_prefs.py`、`_test_update_no_runtime_downgrade.py` | 已补（忽略语义、节流常量、设置项消费、full 不降级运行时） |
| 8 | `NOTIFICATIONS.md` 无 `app_update` 通道；`AI_CONTEXT.md` 无发版记录 | 已补 |

**待人工完成（一次性，代码里做不了）**：按 `docs/RELEASE.md` §3.1 上传
`DEBUG_KEYSTORE_BASE64` / `DEBUG_STORE_PASSWORD` / `DEBUG_KEY_ALIAS` 三个 secret
与 `DEBUG_CERT_SHA256` 变量；否则 CI 会走"无 secret"分支（警告 + 跳过指纹校验），
打出的 debug 包无法覆盖安装历史 edge/preview 包。

**仍未做**

1. **pet 未迁到基座**（见 §7.5）。
2. **ETag 只做了单源缓存**：debug 构建会依次试 edge/preview/stable 三个索引，
   缓存与 ETag 按 URL 分文件存放，但没有做"跨源 304 复用"的优化。
3. **真机验收未做**（§20 的 A1–A16）。
4. **`values-pt-rBR` 的翻译质量**：本轮把缺失的 213 条（含更新文案 91 条）按该文件既有
   约定补成了**英文原文**（文件头就写着"未翻译条目保留英文"），因此键已齐、无回退缺口，
   但 pt-BR 用户看到的更新界面仍是英文 —— 要真正本地化需要一次专门的翻译轮（不属于 P0）。

---

## 19. 分阶段实施

### P0（本轮，v1.4.37）—— 覆盖 R1–R11

| 顺序 | 工作 | 依赖 |
|---|---|---|
| 1 | CI：`write-update-index.py` + 三个 publish 流程产出签名索引；build-apk 输出 meta | 无 |
| 2 | `SignedIndex.kt`（直连/降级/验签，抽自 `RuntimeIndexClient`）+ 探针 | 1 |
| 3 | `UpdateAssetPicker` + 探针 | 1 |
| 4 | debug 固定密钥（Gradle + gitignore + 变量注入 + 探针 + RELEASE.md） | 无（与 1–3 并行） |
| 5 | `AppUpdatePrefs` / `NetworkState` / `AppUpdateBus` / `DownloadNotifier` | 2 |
| 6 | `AppUpdateDownloadService`（下载 + 校验 + 通知 + 取消） | 5 |
| 7 | `ApkInstaller` + `InstallResultReceiver`（并收编 `ExtensionManager`） | 6 |
| 8 | `UpdateDialogs` + 忽略语义 | 6 |
| 9 | `AppUpdateScheduler`（§6）+ `MainActivity` 接入 | 6 |
| 10 | `RuntimeUpdateService` + 关于页运行时行改造（R11） | 5、7 |
| 11 | 关于页更新行状态机（R10）+ 设置页分组 | 6、7 |
| 12 | i18n 三语 + 全量探针 + 编译 + 真机验收 | 全部 |

### P1（下一版）

- 灰度（索引 `rollout`，按安装 id 取模，出问题 `rollout=0` 停机）。
- 弹窗内展示更新说明（前 500 字）。
- 电量 < 15% 推迟；国产 ROM 自启动引导。
- 下载「暂停 / 继续」按钮（跨网络切换更可控）。

### P2（可选）

- 多通道（stable / preview）切换 UI。
- 差分更新评估（包体涨到 300MB+ 再考虑）。

---

## 20. 验收标准

| # | 场景 | 期望 |
|---|---|---|
| A1 | WiFi + 有新版 | 打开 App 后 60s 内出现下载通知；进度平滑；完成弹「立即安装」 |
| A2 | 立即安装 → 系统框确认 | 装成功；重开版本号提升；`filesDir/update/` 清空 |
| A3 | 移动网络 + 有新版 | 无静默下载；弹询问框；选「稍后」后无下载动作 |
| A4 | A3 后连上 WiFi | 自动开始下载 |
| A5 | 忽略此版本 | 不再自动提示；**关于页仍能手动下载/安装**；更高版本恢复提示 |
| A6 | 代理设为 `gh-proxy.org` | 通知显示「经代理」；改成无效地址 → 回落直连并续传成功 |
| A7 | 索引直连被阻断 | 按设置降级经代理取索引且**验签通过**；关闭降级时提示"无法获取更新信息" |
| A8 | 代理投毒（假索引） | 验签失败 → 拒绝、不下载，日志可见 |
| A9 | 代理投毒（假 APK、真索引） | sha256 不匹配 → 删 `.part` → 回落直连重下 |
| A10 | 下载中杀进程 → 重开 | 从 `.part` 续传，最终 sha 通过 |
| A11 | x86_64 模拟器 / arm64 真机 | 各拿到对应 ABI 包（直取索引，不靠文件名猜） |
| A12 | CI edge debug 包连装两版 | **第二版可覆盖升级**（签名固定）→ 证明 D7 修好 |
| A13 | 存储只剩 100MB | 预检拦截，弹「空间不足」 |
| A14 | 运行时更新（关于页） | 起前台服务；退出页面不影响；通知显示百分比；完成后引擎自动重启、行内「已是最新」 |
| A15 | APK 下载中触发运行时更新 | 互斥闸门挡住，提示「另一项更新任务进行中」 |
| A16 | 篡改 APK 后手动安装 | 签名校验失败 → 删除 → 通知「校验失败」 |

---

## 21. 待确认项：**已全部关闭**（2026-10-28 22:36）

| # | 原问题 | 结论 |
|---|---|---|
| 1 | 索引直连失败后是否允许「经代理重取（强制验签）」 | **允许，按推荐**（F1）。首请求严格直连 → 失败降级 → 必须验签；设置项可关。 |
| 2 | 形态纠偏默认值 | **改为智能形态**（F2）：运行时无变更 → noruntime；有变更 → 弹窗二选一。设置项 `update_package_mode_strategy` 可切回 `keep`。 |

实现分支：`feat/app-auto-update`（F3）。`main` 不动。

---

## 22. 附录：`update-index.json` 结构

```json
{
  "schema": 1,
  "generatedAt": "2026-11-02T10:00:00Z",
  "latest": {
    "versionCode": 287,
    "versionName": "1.5.0",
    "tag": "v1.4.37",
    "channel": "stable",
    "publishedAt": "2026-11-02T09:40:00Z",
    "minAppVersionCode": 260,
    "rollout": 100,
    "notesUrl": "https://github.com/<owner>/Deepseek-Harness-Local-Android/releases/tag/v1.4.37",
    "notes": "前 500 字纯文本"
  },
  "assets": [
    {
      "abi": "arm64-v8a", "package": "full", "variant": "release",
      "name": "dsh-android-v1.4.37-arm64-v8a-release.apk",
      "size": 223456789,
      "sha256": "3f2a…",
      "runtimeVersion": "2026.10.28-eb83a614",
      "runtimeBuiltAt": 1761667200000
    },
    {
      "abi": "arm64-v8a", "package": "noruntime", "variant": "release",
      "name": "dsh-android-v1.4.37-arm64-v8a-release-noruntime.apk",
      "size": 61234567,
      "sha256": "9c11…",
      "runtimeVersion": null,
      "runtimeBuiltAt": 0
    }
  ]
}
```

- `assets` 用数组而非嵌套 map：CI 生成简单，App 按 `(variant, abi, package)` 三元组取，缺格即"无匹配资产"（少一层空判断）。
- `variant` ∈ `release`（可自更新）/ `debug`（仅 edge/preview）。
- **`runtimeBuiltAt`（F2 依赖）**：整数毫秒时间戳，取自构建期 `runtime/MANIFEST.json` 的 `runtime.builtAt`；`noruntime` 资产为 `0`。选包用它和本机 `.runtime.json` 的 `builtAt` 比大小判断"运行时有没有变更"——与 `RuntimeInstaller.ensureInstalled()` 内的复用判据同一个口径。缺失时回退比较 `runtimeVersion` 字符串相等。
- `channel` ∈ `stable` / `edge` / `preview`；App 只在「当前构建与索引 channel 匹配」时使用（防止 edge 用户被 stable 索引覆盖）。
- 签名为 detached `SHA256withRSA`，与 `runtime-index.json.sig` 同一把 key、同一套验签代码。
