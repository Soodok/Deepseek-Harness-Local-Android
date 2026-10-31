# DSH Android · 交接文档

> **只写当前有效的事实。** 历史流水已归档：`docs/history/AI_CONTEXT-archive-20261031.md`
> （4070 行，含 2026-10-11 ~ 10-31 每轮交付细节）。本文超过 300 行就该把旧段归档。
>
> 配套规范：[`COLLABORATION.md`](docs/COLLABORATION.md) 合并流程 ·
> [`HYGIENE.md`](docs/HYGIENE.md) 仓库卫生与迭代节奏 ·
> [`git-workflow.md`](docs/git-workflow.md) 推送通道 ·
> [`RELEASE.md`](docs/RELEASE.md) 发版流程

**最后更新**：2026-11-01 01:40 · HEAD `a29da00` · 已发布 v1.4.76 / 327；v1.5.0-beta / 333、v1.5.0-beta.2 / 334、**v1.5.0-beta.4 / 336（引擎 0.2.1-rc.5）均已发为 GitHub 试发行（prerelease）并通过真机验收**

---

## 一、当前状态

| 项 | 值 |
| --- | --- |
| 版本 | 已发布 327 / 1.4.76；333 / `1.5.0-beta`、334 / `1.5.0-beta.2`、**336 / `1.5.0-beta.4`（引擎 0.2.1-rc.5，已发试发行并真机验收）** |
| 远端 | `main` 与 origin/main 一致 `71e03e8`；tag `v1.5.0-beta` 已推送；CI 两条工作流均绿 |
| 分支 | 只有 `main`（2026-10-31 清理，19 → 1） |
| worktree | 只有主仓库（清理 7 → 1） |
| 已知阻塞 | 无 |
| 待办 | mobile-layout 版本线归属（§五）；正式版发 `v1.5.0` 前须先把 `versionName` 改回 `1.5.0`（无后缀）再打 tag |

## 二、构建与装机（照抄可用）

```bash
# 构建（-Ppackage=noruntime 必须加，否则 :app:prepareRuntimeAssets 报缺 PTC patch）
export JAVA_HOME=/d/新建文件夹/jbr && export PATH=$JAVA_HOME/bin:$PATH
/c/Users/Qt/.gradle/wrapper/dists/gradle-9.2.1-bin/2t0n5ozlw9xmuyvbp7dnzaxug/gradle-9.2.1/bin/gradle.bat \
  -p . -Ppackage=noruntime assembleRelease

# 装机
/d/AndroidWatch_ADB_ToolBox/adb/adb.exe -s 192.168.101.21:9000 install -r <apk>
dumpsys package app.dsh.mobile | grep version   # 核对版本
```

- **targetSdk=28 是有意为之**：为 sideload 豁免 W^X 限制。构建时 lint 报的
  `ExpiredTargetSdkVersion` 是提示，不影响构建（见 `app/build.gradle.kts:241-243` 注释）。
- **验证 APK 里有没有新代码**：Python `zipfile` 读 `classes*.dex` 做字节子串匹配；
  版本号在 `AndroidManifest.xml` 里是 **UTF-16LE** 字符串池。
- **UI 取证**：未导出 Activity 只能 `su -c 'am start -n app.dsh.mobile/.SettingsActivity'`
  （shell uid 2000 直启会 SecurityException）。截图坐标按实际 **1216×2640** 换算
  （读图预览 737×1600，坐标 ×1.65）。点击前必须 `dumpsys window | grep mCurrentFocus`
  核对前台，否则会点到微信。

## 三、验证门禁（改动后必须全跑）

```bash
PYTHONIOENCODING=utf-8 python scripts/check-i18n.py
PYTHONIOENCODING=utf-8 python scripts/probes/_test_builtin_plugins.py
PYTHONIOENCODING=utf-8 python scripts/probes/_test_plugin_install_errors.py
PYTHONIOENCODING=utf-8 python scripts/git-hygiene.py
```

`PYTHONIOENCODING=utf-8` 不能省：Windows 控制台默认 GBK，输出 `✓` 会
`UnicodeEncodeError` 崩掉。

当前基线：i18n 全绿（135 XML / 1003 键 / 4 语同步 / Catalog 20 项）·
builtin_plugins 60 过 0 败 1 跳（tgz 契约需 CI 或本地打包）·
plugin_install_errors 42 过 0 败 · git-hygiene 全 ✓。

## 四、关键文件地图

**核心引擎**（`app/src/main/java/app/dsh/mobile/engine/`）

| 文件 | 职责 |
| --- | --- |
| `PluginCenter.kt` | 插件安装/卸载。`install()` 在 `:215-335`，含 pnpm 自愈链路（`healPnpmWorkspace` / `ensureNpmrcRegistry` / `healSelinuxContext` / `ignoredBuildNames` / `recordPendingBuilds`） |
| `EngineConfig.kt` | 引擎环境变量。`buildEnv` 在 `:1664`，`applySuGate` 在 `:1837`。openssl 隔离在 `:1823-1824`（修 Termux 共存事故） |
| `EngineSupervisor.kt` | 启动序列。`profile-plugins` 步骤在 `spawnEngine` 之前 |
| `ProfilePluginProvisioner.kt` | 用户态插件首启预装。`PREFS = "dsh_profile_plugins"`，装完打标记，之后每次启动零动作 |
| `ExtensionManager.kt` | 扩展安装（deb + SHA256 校验 + prefix 改写） |

**UI**（`app/src/main/java/app/dsh/mobile/`）

| 文件 | 职责 |
| --- | --- |
| `PluginStoreActivity.kt` | 插件市场/已安装。`runInstall` `:726`、`doInstall` `:811`（含 150s 看门狗）、`reload` `:251` |
| `SettingsActivity.kt` | 设置页。语言 `langCodes = listOf("en","de","zh","pt")` |
| `LocaleHelper.kt` | 语言切换，`SUPPORTED` 在 `:19` |

**资源与脚本**

| 路径 | 说明 |
| --- | --- |
| `app/src/main/assets/extensions/catalog.json` + `catalog.zh-rCN.json` | 扩展清单，**两份必须同步**（当前各 20 项） |
| `scripts/check-i18n.py` | i18n 门禁，`LOCALE_DIRS` 覆盖 de/zh-rCN/pt-rBR |
| `scripts/git-hygiene.py` | 推送前卫生检查（8 项） |
| `scripts/pack-mobile-layout.sh` | 打 mobile-layout tgz，`MOBILE_LAYOUT_VERSION` 控制版本 |

## 五、待办与已知问题

1. **mobile-layout 版本线未定**：本地曾有 0.1.99 视觉改版（抽屉描边 + 抬升面），
   npm 上不存在该版本；PR #82 合入后走上游 0.2.11，本地改版丢失。
   需决定：把改版提回上游，还是本地维护一份 tgz。备份在
   `_dsh_branches_backup_20261031.bundle` 的 `feat/mobile-sheet-014`（`6f10642`）。
2. `_runtime_bak/`（3.0G）与 `_bench/`（1.5G）按用户决定保留，需要时再清。
3. `docs/promo/video/remotion/src/v4/data.ts:44-66` 的 `tools` 是 21 项历史快照
   （catalog 已 20 项），`tests/v4-keyframes.test.mjs:35` 断言 `asr-service` 存在。
   该测试无脚本自动跑，暂不动；若将来接入 CI 需同步。

## 五之三、宣传片 V5 品牌露出收紧（未提交，2026-10-31）

- V5 目标：前九幕不出现 `DSH Mobile`、`DeepSeek Harness`、GitHub 地址或尺寸对比中的其他项目名；第十幕片尾保留一次 `DSH Mobile`。改动仅在 `docs/promo/video/remotion/src/v5/`，未改 Android WIP。
- `Studio.tsx` 公共页眉、`Opening.tsx` 开场、`Workspace.tsx` Web UI 标题均改为 `ANDROID AGENT`/`NATIVE RUNTIME`/`LAN WEB UI`；`Companion.tsx` 去掉 GitHub 地址，片尾仅保留品牌名；尺寸幕把数据驱动的竞品名称改为“参考方案 A/B/C…”，数字和比较逻辑未改。
- `src/v5/tests/v5-contracts.test.mjs` 当前 6/6 通过：52 秒/60fps 连续性、事实数字、宠物资源、确定性动画、中立尺寸标签、应用名仅片尾一次；`npm run typecheck` 通过；最新 `layout-audit.mjs` 双画幅各 332 samples、0 failing frames；`git diff --check -- docs/promo/video/remotion/src/v5` 通过。
- 最终文件：`out/dsh-mobile-portrait-v5-brand-safe-v2-final.mp4`（1080×1920，52s，60fps，H.264 + AAC 48kHz stereo）与 `out/dsh-mobile-landscape-v5-brand-safe-v2-final.mp4`（1920×1080，同规格）；旁白来自 `voice/narration.wav`，视频画面由 Remotion 直接渲染后以 FFmpeg 合入。B站审核是否通过未验证；真实录屏占位仍按 `PICKUP.md` 保留。

## 五之二、「链接用户数据卡 50 秒」的根因与修复（v1.5.0-beta.2，`71e03e8`）

用户反馈「更新之后链接用户数据要花 50 多秒，而且每次都是」。真机实测 `user-data`
这个阶段本身只有 **11–26 ms**（10 次冷启动日志：13/21/14/13/14/26/13/11/14/12 ms），
慢的不是它。真正原因两个缺陷叠加：

### 缺陷 1：插件首装失败后每次都重试，单次最长 120 秒
`app/src/main/java/app/dsh/mobile/engine/ProfilePluginProvisioner.kt` 的 `install()`
在 `PluginCenter.addLocalTarball` 失败时不写 `provisioned` 标记（旧注释就写着「下次启动重试」），
而 `app/src/main/java/app/dsh/mobile/engine/PluginCenter.kt:576` 的
`p.waitFor(120, TimeUnit.SECONDS)` 是硬超时，`addLocalTarball` 外层还有 180 秒 future 等待。
慢网/断网环境下每次冷启动都白等一到两分钟，而且永远好不了。

修复：新增失败退避，状态存在 `dsh_profile_plugins` prefs 里（跨重启生效）：
- `fail-count:<pkg>` 与 `retry-at:<pkg>` 两个键，延迟递增 **10 分钟 → 1 小时 → 6 小时 → 24 小时**
- 成功安装调 `clearFailure` 清零；断网（`NetworkState.isOnline` 为 false）直接跳过
- 靠的是 `ensure()` 里 `backoffRemainingMs(prefs, pkg) > 0` 就 return

**真机验证**：手工写 `fail-count=1` + `retry-at=now+1h` 并清掉 `provisioned` 后冷启动，
`profile-plugins` 只用 **9 ms** 就跳过，prefs 里失败计数保持不动、没有写入 `provisioned`。

### 缺陷 2：遮罩上显示的阶段名永远慢一拍
`BootTrace.step()` 是**事后上报**（耗时操作跑完才追加），而
`app/src/main/java/app/dsh/mobile/MainActivity.kt` 的 `renderSplashTrace` 旧代码用
`snap.steps.lastOrNull()?.key` 取文案 ⇒ 耗时长的阶段会被显示成**它前面那个**阶段。
`profile-plugins` 慢 120 秒时，屏幕上写的是 `user-data` = 「链接用户数据」。

修复：
- `app/src/main/java/app/dsh/mobile/engine/BootTrace.kt` 的 `Snapshot` 新增 `currentStep`，
  新增 `mark(key)` —— **必须在耗时操作之前调用**；`ready()`/`fail()` 收尾时清空
- `MainActivity` 改为 `snap.currentStep ?: snap.steps.lastOrNull()?.key`
- `EngineSupervisor.kt` 在 10 处耗时调用前插入 `mark(...)`：`agent-seed`、`orphan-reap`、
  `bridge`、`log-sweep`、`runtime-install`、`hardssh-install`、`user-data`、
  `profile-plugins`、`spawn`、`health-poll`

**真机验证（截图）**：`health-poll` 进行到 2.8 秒时，遮罩主文案是
「正在启动引擎… 2.8s · 等待健康检查」（= 正在跑的阶段），明细行是
「运行环境已就绪 · 0.0s」（= 最后完成的阶段）。修复前主文案会写成「运行环境已就绪」。

### 本地想复现慢路径必须带 mobile-layout 资产
本机手动 `assembleRelease` 的包**不含** `assets/mobile-layout/`，`ensureAll` 走
`readAsset` 返回 null 直接跳过（1–3 ms），永远复现不出首装慢。CI 才会打包（job
`pack-mobile-layout`）。本地复现：

```bash
bash scripts/pack-mobile-layout.sh E:/tmp/ml-dist       # 产出 E:/tmp/ml-dist/mobile-layout/{bundle.json,*.tgz}
gradle.bat -p . -Ppackage=noruntime -PmobileLayoutTgzDir="E:/tmp/ml-dist/mobile-layout" assembleRelease
```

**注意**：`-PmobileLayoutTgzDir` 必须指到 `mobile-layout` **子目录**，指到父目录会报
`mobile-layout: bundle.json missing in E:	mp\ml-dist`（`app/build.gradle.kts:512-521`
直接 `copyRecursively` 并要求 `bundle.json` 就在该层）。

带资产包首装实测 `profile-plugins 914 ms`（真走了 pnpm 安装），prefs 写入
`provisioned:@xiaopb/dsh-mobile-layout=true` / `version=0.2.11`。

## 六、真机环境档案

| 项 | 值 |
| --- | --- |
| 设备 | PLZ110 / OPPO，Android 16（`BP2A.250605.015`），`192.168.101.21:9000` |
| adb | `/d/AndroidWatch_ADB_ToolBox/adb/adb.exe`（本机唯一一份，不在 PATH） |
| App uid | `u0_a488`（10488） |
| 引擎目录 | `/data/data/app.dsh.mobile/files/engine` |
| dsh-home | `/data/data/app.dsh.mobile/files/dsh-home` |
| profile | `$DSH_HOME/profiles/web` |

**踩过的坑（不要再犯）**：

- **不要用 root 跑 pnpm**（`su -c "pnpm ..."`）。会让 `node_modules` 子树变成
  root 属主，之后 App（u0_a488）永远 EACCES。修复要 `chown -R 10488:10488`。
- **以 app uid 复现必须带完整环境**，否则继承 root 的 `OPENSSL_CONF` 会报
  `fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf)`——那是假错：
  ```
  HOME=$H DSH_HOME=$H LD_LIBRARY_PATH=$E/lib \
  PATH=$E/bin:/system/bin:/system/xbin TMPDIR=$H/tmp \
  OPENSSL_CONF=$E/etc/tls/openssl.cnf SSL_CERT_FILE=$E/etc/tls/cert.pem
  ```
- **`su 10488` 的 SELinux 域仍是 `su`**，不是 `untrusted_app` → netd 拒其 DNS，
  所有 `ECONNREFUSED`/`ENOTFOUND` 都是测试方法缺陷，不能代表 App 环境。
- **`chcon` 是 root 专属**，App 内调不到（`ls -l /system/bin/chcon` 对 app uid 报
  Permission denied）。`healSelinuxContext()` 因此只能在有 root 的实现路径生效。
- **`adb push` 用原生盘符路径**：`adb.exe` 是 Windows 程序，传 `/e/tmp/x.sh` 会
  `cannot stat`，要传 `E:/tmp/x.sh`。
- **拉设备文件用 `exec-out`**，不用 `shell`：`adb shell "cat <file>" > out` 会把
  二进制写坏（zstd 流报 Data corruption）；要 `adb exec-out "su -c 'cat ...'"`。
- **logcat 能抓 App 日志**：`logcat -s PluginCenter:*` 有效，但缓冲会滚，排查前
  先 `logcat -c` 再复现。

## 七、本轮（2026-10-31）做了什么

- **插件安装三层根因全部修完**（v1.4.64 → v1.4.67）：坏 YAML 自愈、带版本号包名
  清理、`recordPendingBuilds` 写入侧、`ERR_PNPM_IGNORED_BUILDS` 自动放行并重试。
- **v1.4.68**：属主污染与 SELinux MCS 漂移自愈（`isModulesDirError` 分支）、
  网络错误文案不再被 28 字符截断。
- **v1.4.69 ~ 75**：镜像自愈（`.npmrc` registry）、设置页「修复文件属主」入口、
  语言四项 + endonym、扩展中心删除 `asr-service`。
- **v1.4.76**：合并 PR #82（用户态插件预装）、葡语补齐、修
  `about_update_available` 占位符 bug、打 tag 发布。
- **仓库清理**：19 分支 → 1、7 worktree → 1、释放约 964M、新增
  `docs/HYGIENE.md`、`git-hygiene.py` 加 3 项检查、本文档从 4070 行重建。
- **设备能力（v1.4.77 ~ 1.4.81，详见 §八）**：`/dev/*` 九个端点、cap/ 五个实现、
  9 个用户开关 + 权限中心 UI、蓝牙报文收发（广播/GATT/RFCOMM/日志）、
  WiFi 底层（iw 扫描 + 链路 + tcpdump）。提交 `9a8c44f`。
- **v1.5.0-beta（333）**：局域网验证码 8 位字母数字 → 6 位数字，
  并修掉退避不递减导致「输错一次锁死 10 分钟」的真 bug。提交 `0abd1b4`。
- **试发行通道 + i18n 回归修复（提交 `2eb11ac`）**：
  - CI 放宽 tag 校验为 SemVer 两形态（`v1.5.0` 正式 / `v1.5.0-beta` 试发行），
    `publish-release` 用 `id: ver` 判定 `prerelease`，channel 仍为 `stable`。
  - 修 v1.5.0 新阶段 `profile-plugins` 缺本地化映射（启动遮罩裸显示英文 key，
    `_test_boot_trace.py` 报出的真 bug）。
  - `_test_security_audit.py` / `_test_runtime_dedupe.py` 断言与新设计对齐。
  - **i18n-check 从此转绿**（此前 `2ebc892`、`0abd1b4` 两轮均红，连带
    `edge` 滚动 prerelease 停在 `9a8c44f`）。
  - tag `v1.5.0-beta` 已推送，走 `publish-release` 发为 GitHub **prerelease**
    （14 个资产，`releases/latest` 仍是 v1.4.76，未污染）。
- **publish-edge 整条红修复（提交 `b0bd3e3`）**：`versionName` 带 `-beta` 后产物名
  变成 `dsh-android-main-arm64-v8a-v1.5.0-beta-*-noruntime.apk`，而
  `scripts/write-update-index.py` 的 `parse_apk_name()` 版本段正则只认纯数字
  `-v1.5.0` → 剥不掉 → `cannot parse abi` → 「生成并签名 update-index.json」失败。
  修法：正则接受预发布标识，且**仅在末尾不是已知 ABI 时剥**（`arm64-v8a` 与预发布
  字符集重叠，否则 `-v8a` 会被当版本段吃掉）。新增探针
  `scripts/probes/_test_update_index_asset_names.py`（13 个真实文件名）钉住。
  修后 `b0bd3e3` 的 main run 全绿，`edge` 滚动刷新到该提交。

## 八、设备能力扩展：已完成（v1.4.81 起，v1.5.0-beta 收尾）

### 交付内容

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 设备状态 | `GET /dev/status` · `device-status` · `device_status` 工具 | 电池/屏幕/音频/网络/蓝牙/存储/内存快照 |
| 设备控制 | `POST /dev/control` · `device-control` · `device_control` 工具 | wifi/bt/volume/brightness/torch/vibrate |
| 定时提醒 | `/dev/remind*` · `remind` · `remind` 工具 | once/daily/weekday，AlarmManager + setAlarmClock |
| 剪贴板 | `POST /dev/control`（op=clipboard） | 读写 |
| 蓝牙报文 | `bt-radio` | adverts（BLE 广播）/ scan / connect / gatt-read / gatt-write / rfcomm / log |
| WiFi 底层 | `wifi-radio` | scan（走 root `iw`）/ link（802.11 层）/ capture（tcpdump） |

### 用户开关（9 个，默认全关）

`CapabilityGate` 的键是 `cap.<name>`，权限中心页「AI 设备能力」分组可视化开关：
`status` `sensor` `notify` `notify_read` `clipboard` `control` `wifi` `bt` `sched`。
开关只在「用户打开 + 系统权限到位」两者同时成立时 `can()` 才为真。

### 真机验证证据（PLZ110 / Android 16）

- 三层链路：CLI 门脚本 → HTTP `/dev/*` → 工具插件，全部跑通；`device-ping` 返回 9 项能力布尔。
- 蓝牙扫描：`input keyevent KEYCODE_WAKEUP` 点亮屏幕后 `bt-radio {"op":"scan","ms":8000}` → `count:2`，含厂商 ID 76（Apple）载荷 `12020003`。
- 蓝牙日志：`bt-radio {"op":"log"}` 读出 `bluetooth_*.log` 内容，从中直接定位到「屏灭不扫描」证据 `Cannot start unfiltered scan in screen-off`。
- WiFi 扫描：`iw` 通道 16 条真实 AP（含隐藏 SSID），Android API 通道 0 条。
- WiFi 链路：`iw dev` + `station` 给出信道 44 / 160 MHz / txpower 23 dBm / HE-MCS 2 / rx 561541596 bytes，这些 Android API 拿不到。

### 关键实现约束（踩过的坑，别重犯）

1. **manifest 必须声明 `BLUETOOTH_SCAN/CONNECT/ADVERTISE` + `ACCESS_FINE/COARSE_LOCATION`**（`neverForLocation`），否则 Android 12+ 扫描永远是 0 条。
2. **`/data/misc/bluetooth/logs` 的 SELinux 标签是 `bluetooth_logs_data_file:s0`**，App 域即便 uid=0 也 `listFiles()` 返回 null —— 枚举必须走 `Privilege.runPrivileged` 执行 `ls -t <DIR>/*.log`，不能先 listFiles 再提权。
3. **屏幕熄灭（Dozing）时 Android 不启动 unfiltered BLE 扫描**，任何「扫描没结果」的排查先点亮屏幕。
4. **`iw scan` 输出里有 `BSS Load:` 子字段**，用 `startsWith("BSS ")` 会造出 `bssid="Load:"` 的垃圾条目；必须用 MAC 正则校验（`isBssHeader`）。
5. **Kotlin 块注释可嵌套**，`/* ... /* ... */ ... */` 会提前闭合导致 `Unclosed comment`；注释里别写嵌套的 `/* */`。

## 九、局域网访问验证码（v1.5.0）

- **6 位纯数字**（`DshApp.LAN_CODE_LEN = 6`），`SecureRandom` 生成，每次开启局域网访问重新生成。
- 旧版本残留的 8 位字母数字码会在 `lanWebPassword()` 里被判定不合规并自动换新。
- **安全前提**：6 位只有 ~20 bit 熵，抗爆破完全依赖 `app/src/main/assets/tools/lan-web-proxy.mjs` 的失败退避：同源连续 5 次失败锁 10 分钟，成功后清空计数。**删掉那段等于把口令裸奔在局域网上。**
- v1.5.0 修的真 bug：`backoffRemainingMs` 原来直接返回「本轮应等多久」而不减已流逝时间，导致 10 分钟内失败过一次后正确验证码也被 429 挡住。改为 `max(0, waitTotal - (now - st.last))`。实测输错一次等 2 秒即可通过。

## 十、「不兼容的 CPU」排查结论（2026-10-31，已定论 · 不补 32 位）

有用户反馈装包提示「与手机 CPU 不兼容」。全链路取证后的结论：**这是 Android 安装器对 ABI 不匹配的标准报错，不是本项目的自有文案**（全仓库搜「不兼容/CPU/abi」只命中运行期的 `error_runtime_arch_mismatch`，没有任何安装期文案）。

### 已查实的事实（避免以后重复排查）

| 事实 | 证据 |
|---|---|
| 发布产物只有两种 ABI | `.github/workflows/runtime-build.yml:227-232` 矩阵只有 `aarch64/arm64-v8a` 与 `x86_64/x86_64` |
| 单次构建只打一个 ABI | `app/build.gradle.kts:14` `targetAbi`（默认 `arm64-v8a`）+ `:253` `abiFilters += listOf(targetAbi)` |
| **x86_64 包每版都有人下载（≈7%）** | v1.4.53：x86_64-release 28 次 vs arm64-release 477 次；v1.4.76：15 vs 152 |
| 从未发布过 `armeabi-v7a` / `x86` 产物 | 6 个 release 共 8 资产 × 全为 arm64-v8a / x86_64 |
| 82 个 issue（含已关闭）无人报过此错 | 唯一相关旧案 #23 是「x86_64 runtime.zip 塞进 arm64 包导致引擎重启」，已修 |

⇒ 报错的两个可能来源：**① 用户在 arm64 手机上装了 `x86_64` 包**（`INSTALL_FAILED_NO_MATCHING_ABIS`，约 7% 下载量支撑这一可能）；**② 用户手机真的是 32 位 arm**（我方无该架构产物）。

### 结论：不补 32 位（用户 2026-10-31 决定）

32 位**技术上可行**，但用户判断「32 位手机跑不起来」故不做。已核实的可行性证据留档，将来若要补可直接照做：

- Termux `binary-arm` 仓库 18 个依赖包**全齐**：`nodejs-lts 24.22.1`、`bash 5.3.20`、`ripgrep 15.2.0`、`libicu 78.3`、`libsqlite 3.54.1`、`zlib 1.3.2`、`libc++ 30`、`libuv 1.53.0`、`brotli 1.2.1`、`libandroid-support 29-1`、`ca-certificates 1:2026.10.20-1` 等，零缺失（仓库共 2884 包）。
- `app/src/main/jniLibs/armeabi-v7a/libtermux.so` 已是合法 ELF（class=1、e_machine=40、8868 字节）；`x86/libtermux.so` 同理（e_machine=3）。
- `app/src/main/cpp/CMakeLists.txt` 只构建纯 C11 的 `dshpty` 与 `dsh_pty_exec`（无 STL、只链 `log`），跨架构无障碍。
- 需要改的点（四处）：`scripts/build-runtime.sh:37-39` 架构映射与 `:66-67`/`:81` 的 `na`、`want` 二元表；`scripts/collect-termux-runtime.sh:30-31` 架构白名单；`.github/workflows/runtime-build.yml:227-232` 矩阵；App 侧 `RuntimeInstaller` 的 ABI 校验。

### 若将来要降低「选错包」概率（成本低的替代方案）

tag 发布页不再上传 `x86_64` 资产（模拟器用户仍从 edge/preview 拿），并把 `scripts/generate-release-notes.sh:236-242` 的安装说明从「`arm64-v8a` 用于真机，`x86_64` 用于模拟器」升级为带警告的显式引导。

## 十一、上游引擎升级到 0.2.1-rc.5（2026-11-01，v1.5.0-beta.4 / 336）

用户要求「趁热乎」把内置引擎从 `0.2.1-rc.2` 抬到上游刚发的 `0.2.1-rc.5`。

### 结论：补丁零改动，只改版本号

这不是拍脑袋的判断，是一层层验出来的：

| 验的什么 | 怎么验 | 结果 |
| --- | --- | --- |
| 依赖图有没有变 | 比对两端 `@deepseek-ai/dsh` 的 `dependencies` | **86 项完全一致**，只有 `@deepseek-ai/*` 的版本值整体抬升 |
| 补丁目标文件有没有变 | 9 个补丁目标包 `lib/index.js` 逐个 sha256 | **全部字节相同**（`dsh-subprocess-local 2964d94d6aa7`、`dsh-fs-local 63fbb41d2c33` 等） |
| 锚点还命中吗 | 用 npm 镜像**真实 install** 出 616 包依赖树，再跑全部补丁 | **14 项链路标记全过**（详见下） |
| 补丁幂等吗 | 同一棵树连跑两遍 | 第二遍全部报「already patched / skipped」，零重复写入 |
| zod 布局变了吗 | 数副本 | **51 份：顶层 4.6.7 + 嵌套 50×4.4.3**，与 rc.2 完全一致，`dedupe-zod.py` 无需改 |

### 真实依赖树验证法（可复用，比假布局可靠）

```bash
# 1) 用镜像真实解析依赖树（约 3 分钟，616 包）
mkdir -p /e/tmp/tinytree && cd /e/tmp/tinytree
printf '%s' '{"name":"t","private":true,"dependencies":{"@deepseek-ai/dsh":"0.2.1-rc.5"}}' > package.json
npm install --omit=dev --ignore-scripts --no-audit --no-fund --registry=https://registry.npmmirror.com

# 2) 关键：补丁脚本都按 <root>/lib/node_modules/ 找包，必须把树挪进去
mkdir -p lib && mv node_modules lib/node_modules   # 同卷改名，秒级

# 3) 跑补丁（DSH_PATCH_TARGET 给 webview 补丁用）
export DSH_PATCH_TARGET=E:/tmp/tinytree
python scripts/patch-node-pty-android.py E:/tmp/tinytree
python scripts/patch-fs-local-android.py E:/tmp/tinytree
python scripts/patch-attachment-local.py E:/tmp/tinytree
python scripts/patch-ptc-runtime-android.py E:/tmp/tinytree
python scripts/patch-webview-polyfill.py
```

**踩坑**：`cp -a` 复制 306 个包会跑十几分钟（Windows 小文件极慢），必须用 `mv` 同卷改名；bash 的 `ln -s` 在 Windows 上建的是目录联接，`cmd mklink /D` 又要管理员权限，都别用。

### 14 项链路标记（改完补丁后逐条核）

`android-js-fallback`（node-addon-require-builtin）、`isAndroidUnsyncableLevel` + `COPYFILE_EXCL`（attachment-local）、`rename as link`（session-persistence-jsonl）、`dsh-android`（flock.js）、`dsh_pty_exec` + `0.0.0-android-ptyexec`（node-pty）、`platform === "linux" || platform === "android"`（subprocess runner-launch）、`(this.platform === "linux" || this.platform === "android") && observed.complete === true`（subprocess index）、`legacy-webview polyfill`、`fs-local` 的 `dsh-android`、`LD_LIBRARY_PATH`（ptc-runtime）、`LAUNCHER_FAILURE_EXIT`（sandbox-local import 已摘）、`android-inert`（koffi 打桩）。

### 改动的版本钉死点（7 处，全改完才算升级）

`scripts/build-runtime.sh:19`、`scripts/collect-termux-runtime.sh:114`、`.github/workflows/runtime-build.yml:37` 与 `:70`、`.github/workflows/upstream-staging.yml:9`、`scripts/test_build_runtime_version.py:17`、文档 `README.md:340` / `README_EN.md:36,127` / `README.de.md:34` / `docs/CI_RUNTIME_PIPELINE.md:101,269,274,291`。

**故意没改**：`app/src/main/assets/android-plugins/{chat-perf,narrow-rightbar,narrow-settings,narrow-sidebar}/package.json:22` 的 `"dsh": ">=0.2.1-rc.2"` —— 这是**兼容下限**不是当前版本，`>=` 天然容纳 rc.5，改成 rc.5 反而会拒绝掉仍用 rc.2 的用户。

### 本地验不了的三件事（必须靠 CI）

1. `scripts/collect-termux-runtime.sh` 需要 Linux + apt/dpkg + Termux 仓库，本地 Windows 跑不了。
2. `scripts/test_build_runtime_version.py` 在本机**一直是 8 fail**（`bad interpreter: C:\Program`，MSYS shebang 路径转换问题），与版本号无关，属既有状况。
3. `scripts/probes/_test_corepack_hardlink.py` 依赖 `app/src/main/assets/runtime.zip`（`.gitignore:15` 忽略），noruntime 下必然 fail。


### 发布与真机验收（2026-11-01 01:35 完成）

**发布**：tag `v1.5.0-beta.4` → CI run `41222388867` 全过（`fetch-runtime` 双架构 success、4 个 build-apk success、`publish-release` success）。Release 产物 14 项、`prerelease: true`、`published_at 2026-10-31T17:07:28Z`。`update-index.json` 里 `latest = 336 / 1.5.0-beta.4 / channel stable`（channel 仍写 stable 是**故意的**：release 签名包只认 stable，改名会让试发行用户收不到后续正式版）。

**第一次 tag CI 失败的原因**：那次打 tag 时 main 上的 `runtime-build`（run `41221673826`）还在跑，滚动 runtime 还是 rc.2 产物，`fetch-runtime` 的 `--strict` 严格档校验 sha 不匹配 → 硬失败。等 `runtime-build` 把 rc.5 产物刷上去后重打 tag 即通过。⇒ **打 tag 前必须先确认 main 的 `runtime-build` 已绿**。

**重打 tag 的办法**（github.com:443 不通、只有 api.github.com 通时）：
```bash
TOK=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill 2>/dev/null | sed -n 's/^password=//p')
# 1) 删旧 ref
curl -X DELETE -H "Authorization: Bearer $TOK" \
  "https://api.github.com/repos/Soodok/Deepseek-Harness-Local-Android/git/refs/tags/v1.5.0-beta.4"
# 2) 建 annotated tag 对象（拿到返回的 sha）
curl -X POST -H "Authorization: Bearer $TOK" \
  "https://api.github.com/repos/Soodok/Deepseek-Harness-Local-Android/git/tags" \
  -d "{\"tag\":\"v1.5.0-beta.4\",\"message\":\"...\",\"object\":\"$(git rev-parse HEAD)\",\"type\":\"commit\"}"
# 3) 用上一步返回的 tag 对象 sha 建 ref（直接塞 commit sha 会 422）
curl -X POST -H "Authorization: Bearer $TOK" \
  "https://api.github.com/repos/Soodok/Deepseek-Harness-Local-Android/git/refs" \
  -d '{"ref":"refs/tags/v1.5.0-beta.4","sha":"<上一步的 tag sha>"}'
```

**真机验收（PLZ110 / OPPO Android 16，192.168.101.21:9000）**：
- 下载 full 包 `dsh-android-v1.5.0-beta.4-arm64-v8a-release.apk` 155239295 字节，sha256 `96729af9f25d8e5920a3b006c8996773287f2d531b1ceb8dfd12f34212b6a0b6` —— 与 `update-index.json` 里登记的 sha256 **完全一致**。
- `adb install -r` Success；`dumpsys package` → `versionCode=336 versionName=1.5.0-beta.4`。
- **引擎逐包核验全部 rc.5**：`dsh` / `dsh-plugin-manager` / `dsh-app-boot` / `dsh-subprocess-local` / `dsh-fs-local` / `dsh-ptc-runtime-node` 均 `0.2.1-rc.5`。
- **补丁生效核验**（不是只看版本号）：`node-pty/package.json` = `{"name":"node-pty","version":"0.0.0-android-ptyexec"}` 且 `lib/index.js` 里 `dsh_pty_exec` 命中 7 次；`dsh-subprocess-local/lib/runner-launch-*.js` 里 `platform === "linux" || platform === "android"` 命中 1 次；`dsh-fs-local/lib/index.js` 里 `dsh-android` 命中 2 次；`dsh-ptc-runtime-node/lib/index.js` 里 `LD_LIBRARY_PATH` 命中 5 次；`dsh-web-frontend/dist/index.html` 里 `legacy-webview` 命中 1 次；`engine/bin/dsh_pty_exec` 存在（11496 字节，0755）。
- **实跑验证**（最硬）：在设备上用 rc.5 引擎执行 `dsh --version` → `0.2.1-rc.5`；`dsh plugin --profile web list` → 正常列出 3 个依赖（`@xiaopb/dsh-mobile-layout@0.2.11`、`@xiaopb/dsh-pet-bridge@0.3.3`、`dsh-status-rotator@0.38.0`），说明 **subprocess-local 补丁在 rc.5 下能真正 spawn pnpm 子进程**。
- 引擎进程起来（`node --expose-internals .../dsh/lib/bin.js web --patch ...`），监听 `127.0.0.1:3080`；桥 `127.0.0.1:3083` 返回 `{"ok":false,"error":"unauthorised"}`（无 token 正常）。
- App UI 正常，**升级后旧会话历史完整保留**（截图确认能打开 10 月 31 日的对话）。
