# DSH Android · 交接文档

> **只写当前有效的事实。** 历史流水已归档：`docs/history/AI_CONTEXT-archive-20261031.md`
> （4070 行，含 2026-10-11 ~ 10-31 每轮交付细节）。本文超过 300 行就该把旧段归档。
>
> 配套规范：[`COLLABORATION.md`](docs/COLLABORATION.md) 合并流程 ·
> [`HYGIENE.md`](docs/HYGIENE.md) 仓库卫生与迭代节奏 ·
> [`git-workflow.md`](docs/git-workflow.md) 推送通道 ·
> [`RELEASE.md`](docs/RELEASE.md) 发版流程

**最后更新**：2026-11-02 19:30 · HEAD `5fce97f` · 已发布 v1.4.76 / 327；v1.5.0-beta / 333、beta.2 / 334、beta.4 / 336、**beta.11 / 343（自描述 `dev` 入口 + 启动预检并行化）均已发试发行并通过真机验收**

---

## 一、当前状态

| 项 | 值 |
| --- | --- |
| 版本 | 已发布 327 / 1.4.76；333 / `1.5.0-beta`、334 / `1.5.0-beta.2`、336 / `1.5.0-beta.4`（引擎 0.2.1-rc.5）、**343 / `1.5.0-beta.11`（自描述 `dev` 入口，真机验收）** |
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

## 五之四、平板进设置被强制竖屏（v1.5.x 修复，2026-11-01 未提交）

- **根因**：`d983e9d`（v1.4.64「设置类页面固定竖屏」）让 13 个原生设置类
  Activity 直接调 `OrientationPrefs.forcePortrait()`，无条件
  `requestedOrientation = SCREEN_ORIENTATION_PORTRAIT` —— 无视三态偏好、
  也不看是否大屏。平板从横屏主界面点进设置，整块屏幕被扳成竖屏。
  同一行上方还留 v1.4.18 的旧注释「不再硬锁竖屏」，注释与代码相反。
- **附带 bug**：`SettingsActivity.applyResponsiveLayout()` 读
  `resources.configuration` 而不是 `onConfigurationChanged(newConfig)` 的入参。
  转屏瞬间 resources 还是旧的一份（实测 newCfg 800dp/竖 与 res 1280dp/横 并存），
  于是竖屏里继续渲染横屏双栏。
- **修复**：新增 `OrientationPrefs.applyToSettingsPage()` 作为唯一入口 ——
  用户开关「设置页跟随屏幕方向」（`dsh_ui` 的 `settings_follow_orientation`）
  打开或大屏（sw >= 600dp）时跟随偏好，否则（手机）保持竖屏。
  `forcePortrait()` 退化为内部实现，不再被页面直调。
  外观页新增该开关行（`rowSettingsFollow` / `swSettingsFollow`）。
- **列布局**：断点改为按配置求值 —— 横屏 840dp、竖屏 600dp 双栏
  （`TWO_COLUMN_MIN_WIDTH_LANDSCAPE_DP` / `_PORTRAIT_DP`），
  且转屏路径必须传 `newConfig`。
- **实测（MuMu x86_64 / Android 12）**：
  | 档位 | 偏好 | 开关 | 结果 |
  | --- | --- | --- | --- |
  | 1600x2560@320（sw800，横屏） | auto | 关 | 不转屏，`mOrientation=USER`，双栏 |
  | 同上 | portrait | 关 | 转竖屏（大屏尊重显式偏好），双栏 |
  | 1180x1888@320（sw590） | auto | 开 | 竖屏 590dp 单栏；转 90° 后 944dp 双栏（**同一窗口 token，未重建**） |
  | 1080x1920@540（sw320，手机） | auto | 关 | 锁竖屏，单栏 |
  | 同上 | auto | 开关一开/一关 | 立即跟转横屏 / 立即转回竖屏 |

- **生效时机（2026-11-01 补）**：只调 `onCreate` 时，本页当场生效，但**已存在的
  兄弟页不会更新** —— 从外观页按开关后返回，上一页仍持有它上次算出的
  `requestedOrientation`，会把屏幕扳回去（实测旧代码：开关 ON 后返回设置页，
  `cur` 从 1920x1080 立刻弹回 1080x1920）。现已让 13 个页面在 `onResume`
  里再对齐一次（值未变时 `setRequestedOrientation` 是空操作），并加探针断言。
- 探针已同步：`_test_responsive_native_ui.py` 断言新入口、双断点与
  `applyResponsiveLayout(newConfig)`；`_test_settings_ux.py` 断言新开关行。

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

## 十二、GitHub issue 修复（2026-11-01）

### #86 android-buildtools 的 gradle 开箱即坏（已修，待发版）

**报告人环境**：App 1.4.76 / 引擎 `0.2.1-rc.2` / PADT00 Android 10 (SDK 29) / `android-buildtools` 16.0.0.4-4 / gradle **9.8.2** + 内嵌 JDK **21.0.13**。
**现象**：`gradle --version` → `Error: Could not find or load main class "-Xmx64m"`。

**三个独立断点（全部已修在第 1、2 条，第 3 条非致命已规避）**：

1. **toybox xargs 不剥引号（致命）**。gradle 启动器用
   `printf '%s\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" | xargs -n1 | sed … | eval`
   解析 `DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m" …'`；GNU xargs 会剥引号，`/system/bin/xargs`（toybox 0.8.0）原样输出 → java 收到字面量 `"-Xmx64m"` 当主类名。
   修法：在启动器里注入 `xargs() { command xargs "$@" | sed -e 's/^"//' -e 's/"$//'; }`（GNU 下 sed 匹配不到任何东西 = 空操作）。
2. **内嵌 Termux JDK 的 `user.home` / `java.io.tmpdir` 硬编码到 `/data/data/com.termux/files/**`（致命）**。JVM 无视 `$HOME`；该目录属别的包不可写 → jansi native 加载失败、`gradle help` 死于 `java.io.tmpdir is set to a directory that doesn't exist`。
   修法：在 eval 之前注入 `GRADLE_USER_HOME` + `GRADLE_OPTS="-Duser.home=$HOME -Djava.io.tmpdir=$GRADLE_USER_HOME/tmp -Dorg.gradle.native=false"`，并 `mkdir -p`。`HOME` 为空或为 `/`（adb/serial shell 的默认值）时**跳过**这两个覆盖（否则等于没修）但保留 `native=false`。
3. **捆绑 native-platform 是 glibc 构建（非致命）**。`libnative-platform.so` 依赖 `libc.so.6`/`libstdc++.so.6`，bionic 上不存在；`-Dorg.gradle.native=false` 后 Gradle 优雅降级输出 `There is no native integration with this operating environment.`。

**实现**（`app/src/main/java/app/dsh/mobile/engine/ExtensionManager.kt`）：
- `private fun patchGradleLauncher(finalDir: File)`（`:1400`）：定位 `opt/gradle/bin/gradle`（回退 `bin/gradle` 且非软链）→ `decodeStrictUtf8` → 含 `GRADLE_PATCH_MARK` 即幂等早退 → 两个锚点（`anchorXargs` = `# Stop when "xargs" is not available.` 整段；`anchorOpts` = `eval "set -- $(`）缺任一则 `Log.w` 跳过、不写坏启动器 → replace 后 `writeText` + `setExecutable(true,false)`。
- `fun migrateGradleLaunchers()`：遍历 `activeRoots(ctx)` 逐个 patch —— 安装期补丁只对新装扩展生效，**老用户升级 App 不会重走 `installPhase`**，靠这个在启动时补。
- 调用点：`installPhase` 里 `patchGradleLauncher(finalDir)`（无条件，扩展 id 是 `android-buildtools` 而非 `gradle`，只能靠「目录里有没有启动器」判断）；`EngineSupervisor.kt` 在 `step("hardssh-install")` 之后 `ExtensionManager(ctx).migrateGradleLaunchers()`。
- 常量：`private const val GRADLE_PATCH_MARK = "# dsh-android-patch: gradle-launcher"`（`:1686`）。

**验证（四组对照 + 真机，全部实跑）**：
| 环境 | 未打补丁 | 已打补丁 |
|---|---|---|
| GNU xargs（桌面） | `-Xmx64m` 干净 | 同上 **+** `-Duser.home=/h` `-Djava.io.tmpdir=/h/.gradle/tmp` `-Dorg.gradle.native=false`，无副作用 |
| toybox xargs（真机 192.168.101.21:9000） | `A="-Xmx64m"`（复现 #86） | `A=-Xmx64m` 干净，六个参数全部正确 |

真机 `HOME=/` 时 → 只补 `-Dorg.gradle.native=false`，不补两个目录（避免指向只读根）；`HOME=/data/local/tmp/h` 时 → `-Duser.home=/data/local/tmp/h` + `-Djava.io.tmpdir=/data/local/tmp/h/.gradle/tmp` 正确。

**探针**：`scripts/probes/_test_gradle_launcher_patch.py` —— **从 Kotlin 源码抽真实补丁正文**（不复制，避免源码改了探针没跟上），套到保留真实结构的 fixture 上，验锚点/落地/`bash -n`/幂等/标记数=2/opts 补丁在 eval 之前；行为侧跑 GNU、HOME 空、HOME=`/`、toybox 四组。补丁正文里不能有中文（`check-i18n.py` 会拦 Kotlin 字符串字面量的 CJK）。

### 其余 open issue 状态

| # | 标题 | 状态 |
|---|---|---|
| 86 | gradle 开箱即坏 | **已修**（本节），待发版 |
| 83 | 小米13安卓16解析安装包失败 | **无法定位** —— 正文为空、0 评论，需向作者追问错误码与设备信息 |
| 76 | directoryPickerController 不可用 | 与 #74 同源（属主/SELinux 污染成 `u0_aXXX` 之外的 uid），仓库已有 `fixHomeOwnership` 五步自愈（v1.4.45 起） |
| 74 | 存储与运行环境损坏 | 同上，五步（remove/ownership/context/selftest/audit）已在 `Privilege.kt` 实现 |
| 66 | rc.2 会话列表异常 | **已由 rc.5 解决**（v1.5.0-beta.4），待回复关闭 |
| 60/59/49/41/26/2 | 见 GitHub | 未逐条分析 |

### 泰语 locale 与引导页补齐（PR #85 合并后）

PR #85 带来 `values-th`（1046 条，与英文完全相同仅 13 条 ⇒ 是真翻译）与 `LocaleHelper.SUPPORTED` 的 `"th"`、设置页语言项，但**引导页语言卡只有 en/de/zh/pt 四个**——泰语用户在首启页选不到自己的语言，与 v1.4.76 漏葡语同型。已在 `app/src/main/res/layout/activity_onboarding.xml` 补 `cardLangTh` 卡片（含 `txtLangTh`、中间 `Space`），`OnboardingActivity.kt:68-71` 的 `langCards` 与 `:270-275` 的 `updateSelectionUi()` 各补一行。

**同时修掉一个反复踩的坑**：泰语译者把语言名当普通文案翻了（`values-th` 里 `lang_zh` = 「ชาวจีน」、`lang_en` = 「ภาษาอังกฤษ」，中文文件里 `lang_th` = 「泰语」，德语 `Englisch`、葡语 `Inglês`）。语言名必须恒为该语言自己的写法，与当前 UI 语言无关。五个 locale 的 5 个 `lang_*` 全部锁定为 endonym：中文 / English / Deutsch / Português / ไทย。

**门禁加固**：`scripts/check-i18n.py` 新增 **B2. Language endonyms** 项，`ENDONYM_LANG_NAMES` 硬锁这五个名字，任何 locale 译走即报 `::error::`。已反证：把 `values-de` 的 `lang_en` 改回 `Englisch` → EXIT=1 且指出文件与期望值。

### 本轮构建与门禁（2026-11-01）

- `versionCode = 337` / `versionName = "1.5.0-beta.5"`（`app/build.gradle.kts:244`/`:250`）。
- `gradle.bat -p . -Ppackage=noruntime assembleRelease` → **BUILD SUCCESSFUL in 1m 8s**；APK 24119958 字节。
- APK 内 dex 字节核验：`gradle-launcher` / `dsh-android-patch` / `migrateGradleLaunchers` / `xargs` / `java.io.tmpdir` / `cardLangTh` / `lang_th` 全部 FOUND；`AndroidManifest.xml`（UTF-16LE）`1.5.0-beta.5` FOUND。
- `check-i18n.py` → All passed（B2 项：5 names locked across 5 locale）。
- 探针：py **32 passed / 1 failed**（`_test_corepack_hardlink.py`，`assets/runtime.zip` 被 gitignore，noruntime 下必然缺，属已知基线）；mjs **7 passed / 1 failed**（`_test_apk_installer.mjs` 历史遗留）。
- `git-hygiene.py` → 通过（1 条未提交提醒）。
- **`scripts/probes/_test_boot_trace.py` 之前被 `.gitignore` 的 `/scripts/probes/*` 通配漏掉、从未入库**（本地 31 项全过），CI 因此看不见它 —— 已补白名单并入库（`fa93949`）。

### 提交记录（本轮）

| commit | 内容 |
|---|---|
| `3dd096b` | fix(#86) gradle 启动器补丁（toybox xargs + Termux JDK home） |
| `1ed75a1` | 引导页补泰语卡 + 语言名统一 endonym + B2 门禁 |
| `fa93949` | 补跟踪 boot-trace 探针 |

## 十二、GitHub open issues 处理轮（2026-11-01 下午）

本轮处理 9 条 open issue，标题为结论、详细证据见各 issue 的回复评论。

### 已修复并关闭

| issue | 根因 | commit |
|---|---|---|
| **#86** gradle 开箱即坏 | toybox xargs 不剥引号（Gradle 的 `DEFAULT_JVM_OPTS` 靠 `xargs -n1` 剥）；Termux JDK 硬编码 `user.home`/`java.io.tmpdir` 到 `/data/data/com.termux` | `3dd096b` |
| **#49** swap 恢复窗口 | P1-2 早在 v1.4.26 落地，本轮收掉三项 P2：下载器强制 https、PKCS12 口令报错、SafeMode 语义文档化 | `e708691` |
| **#66** 引擎停在 rc.2 | rc.5 适配后 `dsh plugin list` 实测正常，四个 Android 补丁按 rc.5 重新对齐 | 前序版本 |
| **#74** root→普通模式插件安装双失败 | v1.4.45 已按「锁按当前引擎 uid 自管理 + 整树 chown + 现场取 SELinux context」修好 | `48a9963` |

### 已改进（保持 open 等用户回信息）

**#76 目录选择器不可用** — commit `14d38dd`。关键认知：**探针 ok 与网关 unavailable 能同时成立**，因为探针查根 context 的 `ctx.get()`，网关是先 `resolveReceiverContext()` 再 `.get()`。已把探针升级为问后端 `capability()`（返回非 `browse` 即报 overlay 未生效），且探测挂在 `missingServices` 循环**之外** —— 服务存在却坏掉根本进不了那个循环，这是原实现的盲区。已回复请报告人提供新版 marker。

**#83 小米13 解析安装包失败** — 正文为空、0 评论，除标题零信息。已列 4 项信息请求 + 2 项自查，并打了「需要信息」标签。**倾向判断：下错 ABI 包**（各版本 x86_64 包占 5–10% 下载量）。

### 待办（enhancement，未排期）

- **#59** Python 原生轮子支持（`pydantic-core`/`numpy`/`onnxruntime` 的 `android_24_arm64_v8a` 构建）+ 插件 `pythonDependencies` 声明 + 外部服务生命周期
- **#41** Profile 管理 UI（多 profile 切换/新建；当前 `DSH_PROFILE=web` 写死，第二个 profile 装了无法启动）
- **#60/#26** 长会话卡顿（同根：上游 context 压缩，用户侧可用 `/compact` 或新开会话绕过）
- **#2** 语音唤醒/连续对话

### 本轮踩到的坑（可复用）

1. **探针必须问「能不能用」而不是「在不在」** —— `ctx.get()` 有服务 ≠ 服务可用。#76 就是栽在这里。
2. **`.gitignore` 的 `/scripts/probes/*` 全局限定会静默吞掉新探针**，每加一个都要往白名单（第 103 行起）追加一行，否则 CI 看不见。
3. **Windows 上 Node ESM 不能 import 裸盘符路径** —— `import("E:/x.mjs")` 报 `ERR_UNSUPPORTED_ESM_URL_SCHEME`，必须 `pathToFileURL(...).href`。
4. **`openssl pkcs12` 口令错时只把 `Mac verify error: invalid password?` 写 stderr 并 rc=1**，两个签名脚本原来一律 `2>/dev/null`，用户看到的是后面 sign 阶段对空 PEM 的报错。现在按该子串分流报「口令不匹配」，`-legacy` 回退只在确认非口令问题时才走。
5. **GitHub API 写操作**：`git credential fill` 能取到已存的 token（`printf 'protocol=https
host=github.com

' | git credential fill`），无需额外配置；但 Windows 原生 Python 读不到 Git Bash 的 `/tmp`，token 要落到 `E:/tmp/`。

## 十三、自描述设备能力入口 `dev`（v1.5.0-beta.11 / 343，`6c622ff`，已推送）

### 解决什么问题

原来是**三处重复**：Kotlin 路由一个端点、`app/src/main/assets/gates/` 一个门脚本、`app/src/main/java/app/dsh/mobile/engine/AgentContextSeed.kt` 一段提示词。漏任何一处，该能力对 AI 就等于不存在。实测 29 个 `/dev` 端点里 **10 个根本没有门脚本**（`/dev/calendar` `/dev/contacts` `/dev/nfc` `/dev/record` `/dev/sensors` `/dev/sms` `/dev/clipboard` `/dev/dialog` `/dev/geofence` `/dev/verify`）。

### 现在的结构（单一数据源 + 通用出口）

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt` | 端点目录唯一数据源：id/method/path/能力开关/系统权限/参数/示例/已知限制 |
| `app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt` | 新增 `GET /dev/help`（目录 JSON，带 `q=` 查单条）与 `GET /dev/routes`（真实路由表 + `catalog_problems`） |
| `app/src/main/assets/gates/dev` | 纯渲染器，从 `/dev/help` 拉目录自渲染，不再硬编码能力名 |

`dev` 的全部子命令：

```
dev                             总目录（按组，带 ✓/· 能力开关标记）
dev <能力>                      该能力完整用法（请求/开关/权限/参数/示例/限制）
dev <能力> k=v ...              执行（k=v 自动拼 JSON 或 query）
dev api <METHOD> <PATH> [BODY]  直接调任意端点（通用出口）
dev raw                         真实路由表 + 目录一致性核对
dev help                        用法说明
```

`dev api` **不是特权后门** —— 请求走同一条 `route()` 分发，各端点自己的能力门、系统权限、token 鉴权照样生效。它只是省去「等 App 为某个端点写门脚本」的等待。

### 防漂移

`EndpointCatalog.verifyAgainst(routes)` 双向核对：目录里写的路径必须在路由表里真实存在；路由表里的 `/dev` 端点若没登记也会报（`META_ROUTES = {GET /dev/help, GET /dev/routes}` 除外）。漂移会打 `Log.w(TAG, "endpoint catalog mismatch: ...")` 并出现在 `GET /dev/routes` 的 `catalog_problems` 里。**当前真机已归零（`[]`）**。

### 本轮校正的参数漂移（自描述目录最怕这个）

| 端点 | 曾写错 | 真实 |
| --- | --- | --- |
| `/dev/nfc` | 有 `op=status` | 只有 `read\|enable\|disable\|clear`；状态走 **`GET /dev/nfc`** |
| `/dev/remind` | `at_ms=<毫秒>`、`repeat:"daily"` | 绝对时间是 **`at=<ISO8601 字符串>`**；`repeat` 是对象 `{"kind":"once\|daily\|weekday"}`；title 与 body **都必填** |
| `/dev/bt` | 有 `classic\|send\|capture` | 只有 `status\|enable\|disable\|scan\|advertise\|connect\|services\|read\|write\|rfcomm\|log` |
| `/dev/dialog` | 有 `op` 字段 | 无 op，是 `auto` / `includeDanger` |
| `/dev/verify` | 字段叫 `text` | 字段是 **`expect`**（必填） |
| `/dev/notify` | 目录登记过 | **这个端点根本不存在**，已删（发通知只走 App 内 `NotifyCap.send()`） |
| `/dev/geofence` | — | `add`/`remove` 都要 `id`，缺则 400 |
| `/dev/media` | `seek value=` | `seek position_ms=` |

### 真机验证（OPPO PLZ110 / Android 16 / `192.168.101.21:9000`）

```
catalog_problems = []                                 目录与 86 条路由完全对齐
dev                                                   8 组 35 个端点，带 ✓/· 开关标记
dev nfc                                               正确提示「没有 op=status，查状态用 GET /dev/nfc」
dev nfc op=read                                       {"ok":true,"items":[]}
dev control op=torch enabled=true                     {"ok":true,"enabled":true}
dev control op=brightness value=128                   {"ok":true}
dev api GET /dev/status                               {"ok":true,"battery":{...},"network":{...}}
dev api GET /dev/calls                                真实通话记录
dev api GET /nonexistent                              404 unknown route（通用出口不越权）
```

### 门禁改动

- `scripts/check-i18n.py`：`EndpointCatalog.kt` 加入 `ALLOWED_CJK_FILES`。它的中文是**给模型读的工具文档**（`GET /dev/help` 返回），不是 UI 文案，与 `AgentContextSeed.kt` 同理。
- `scripts/probes/_test_gate_scripts.py`：C 段从「核对 LIST 表」改为「断言 `dev` 依赖 `/dev/help`（自描述）+ 反向核对磁盘上 30 个门脚本都在 `EngineConfig` 部署清单里」。D 段仍核对门脚本调用的字面量端点都在路由表里。

### 结果

- py 探针 **36 passed / 1 failed**（唯一失败 `scripts/probes/_test_corepack_hardlink.py`：`app/src/main/assets/runtime.zip` 被 `.gitignore:15` 忽略，noruntime 构建下必然缺，属既有基线）
- mjs 探针 **7 passed / 1 failed**（`scripts/probes/_test_apk_installer.mjs`，历史遗留）
- `check-i18n.py` → `All i18n checks passed.`
- `check-no-bulk-perms.py` → OK
- `_test_gate_scripts.py` → `checks=34 failed=0`
- APK 24201638 字节，dex 核验 `EndpointCatalog` `/dev/help` `/dev/routes` `catalog_problems` `META_ROUTES` `nfc-status` `record-status` 全 FOUND；manifest 回读 `versionCode=343 versionName=1.5.0-beta.11`

### 给下一个 AI 的约定

**新增端点只需要动两个文件**：`EndpointCatalog.kt` 登记一条 + `AgentBridge.kt` 加一条路由。`dev` 会自动发现它、显示用法、允许调用。如果哪天 `GET /dev/routes` 的 `catalog_problems` 不为空，就是这两个文件不同步了，按提示补。

---

## 十三、自描述设备能力入口 + PR #90 启动并行化（2026-11-02）

### 自描述入口 `dev`（v1.5.0-beta.11 / 343，提交 `6c622ff`）

**问题**：新增一个设备能力原本要改三处（Kotlin 路由 / `assets/gates` 门脚本 LIST / 提示词），
漏任何一处该能力对 AI 就等于不存在。实测 29 个 `/dev` 端点里有 **10 个根本没有门脚本**。

**方案**：单一数据源 + 通用出口。

| 文件 | 作用 |
| --- | --- |
| `app/src/main/java/app/dsh/mobile/engine/EndpointCatalog.kt` | 端点目录唯一数据源：id/method/path/能力开关/系统权限/参数/示例/限制 |
| `GET /dev/help` | 目录吐 JSON（带 `q=` 查单条含参数与限制；不带则只返分组摘要，省 token） |
| `GET /dev/routes` | 真实路由表 + `catalog_problems`（目录与路由双向核对的差异） |
| `app/src/main/assets/gates/dev` | 纯渲染器，从 `/dev/help` 拉目录自渲染；`dev api <METHOD> <PATH> [BODY]` 直调任意端点 |

`dev api` 走同一条 `route()` 分发，**不绕过任何能力门与系统权限**（实测
`dev api GET /nonexistent` → 404）。

**一致性核对**：`verifyAgainst()` 双向比目录与路由表，漂移会 `Log.w` 并出现在
`/dev/routes` 的 `catalog_problems`。`META_ROUTES = {"GET /dev/help", "GET /dev/routes"}`
是白名单（元端点不属于设备能力）。当前真机已归零。

**顺带的参数漂移校正**（自描述目录最怕漂移，全部真机实调确认）：

| 目录原先写的 | 真实代码 |
| --- | --- |
| `/dev/control` torch 用 `on` | 字段是 **`enabled`**（写 `on` 报 `torch requires enabled`） |
| `/dev/nfc` 有 `op=status` | 没有 status，查状态用 `GET /dev/nfc` |
| `/dev/control` 有 ringer/dnd/settings_action | 实际只有 `wifi / bt / volume / brightness / torch / vibrate` |
| `/dev/remind` 有 `at_ms` | 实际 `at=<ISO8601>`，且 `title` 与 `body` 都必填 |
| `/dev/bt` 有 classic/send/capture | 实际没有这三个 op |
| `POST /dev/notify` | **端点不存在**，已删 |
| 缺 `GET /dev/nfc`、`GET /dev/record` | 已补 `nfc-status` / `record-status` |

**门禁配套**：`scripts/check-i18n.py` 把 `EndpointCatalog.kt` 加入 `ALLOWED_CJK_FILES`
（它的中文是给模型读的工具文档，不是 UI 文案）；`scripts/probes/_test_gate_scripts.py`
的 C 段改断言 `dev` 依赖 `/dev/help` 而非 LIST 表，并反向核对磁盘上 30 个门脚本都在
`EngineConfig.kt` 部署清单里。

### PR #90 启动预检并行化（作者 qingjian0，提交 `baa1705`）

把 `agent-seed` / `log-sweep` / `bridge` 三项互不依赖的启动准备改成
`supervisorScope + async` 并行，并调整依赖顺序：
孤儿回收 → 属主修复 → guardian 恢复 → 并行预检 → 运行时安装。

**审查结论（逐条核对源码）**：
- 三项确实互不依赖：`AgentContextSeed` 只写 `dsh-home/AGENTS.md`；
  `LogJanitor.walkLogFiles` 明确只匹配 `.log` 后缀（不会碰 AGENTS.md）；
  `AgentBridge` 只绑 `127.0.0.1:3083` 并起 sessions 轮询。
- 顺序调整有实际收益：旧顺序在 owner-fix **之前**就写 AGENTS.md / 裁日志 /
  跑 guardian 文件修复，Root 残留时可能先失败再自愈。
- 失败不吞：`initResults.firstOrNull { it.isFailure }?.getOrThrow()` 在继续前抛出，
  与改前 fail-fast 一致；`supervisorScope` 让单任务失败不取消其余两个。
- 带回归探针 `scripts/probes/_test_startup_parallelization.py`（14 项断言，14/0）。

**真机实测（OPPO PLZ110 / Android 16）**：
```
parallel init agent-seed: 0ms ok
parallel init bridge: 2ms ok
parallel init log-sweep: 17ms ok
boot step parallel-preflight: 18ms (total 323ms)
```
引擎进程正常拉起（pgrep 有两个 pid），版本回读 `1.5.0-beta.11`。

### 合并纪律（本次踩到并已修正）

为试构建在临时 worktree 里 `git merge main` 后，把那个合并提交一并带进了主仓历史
（`4733bf0`），形成嵌套 merge。已 `git reset --hard` 回到 `78a6821` 后直接
`git merge --no-ff 65616da`（PR 的 head sha）重做，历史现在是干净的两线合一。
**教训：试合并要在 worktree 里做、验完就丢，不要把它 commit 进分支再合。**

### 门禁基线（本轮实跑）
- `scripts/check-i18n.py` → `All i18n checks passed.`
- `scripts/check-no-bulk-perms.py` → `OK`
- py 探针 **37 passed / 1 failed**（唯一失败 `scripts/probes/_test_corepack_hardlink.py`，
  `app/src/main/assets/runtime.zip` 被 `.gitignore:15` 忽略，noruntime 下必然缺，既有基线）
- mjs 探针 7 passed / 1 failed（`scripts/probes/_test_apk_installer.mjs`，历史遗留）
- PR 自带探针 14 passed / 0 failed

### 待宝宝拍板

`dev api` 能直调 86 个端点里的任何一个。能力开关（权限中心 14 个）与系统权限照旧生效，
但**没有「这次操作要不要先问我」那一层**。是否要为不可逆操作（点按/输入/发短信/打电话/
改系统设置）加确认？选项：① 全开不问（当前行为）② 只在 `CONTROL` 开关打开时放行
③ 不可逆操作弹窗确认。

### 环境要点（本轮新增）

- `adb connect 192.168.101.21:9000` 重连后 `emulator-5554` 也会出现，**必须带 `-s 192.168.101.21:9000`**，否则命令打到模拟器。
- GitHub API 直连可用（token 40 字符），无需代理。
- `git worktree add <path> <branch>` 后构建正常；用完 `git worktree remove <path> --force`。

## 十四、虚拟 HID 输入 + 能力地图（v1.5.0-beta.13 / 344，2026-11-02）

### 为什么 `/dev/uhid` 只能走门脚本，不能走桥

`/dev/uhid` 权限位 `crw-rw---- uhid uhid`，SELinux 只放 `u:r:ksu:s0`（KernelSU root 域）。
`AgentBridge` 跑在 App 进程（uid 10488 / `u:r:untrusted_app`），三条路实测都不通：

| 路径 | 结果 |
| --- | --- |
| App 进程直接 open | `EACCES` |
| Shizuku（`u:r:shell:s0`） | SELinux 拒（即便 shell 在 uhid 组里） |
| `su -c`（App 进程内） | 等 KernelSU 授权弹窗，无 UI 时挂死（curl 60s 超时） |

**而引擎本身就是 root**（`ps -A -o PID,UID,NAME` → `node` 是 uid 0 / `u:r:ksu:s0`），
所以改成门脚本执行，不经桥。

### 三个文件

| 文件 | 作用 |
| --- | --- |
| `app/src/main/assets/tools/hid.mjs` | 一次性 uhid 执行器：`probe/create/tap/text/key/mouse/help`；三描述符 `D_KEYBOARD`/`D_CONSUMER`/`D_MOUSE` |
| `app/src/main/assets/gates/hid` | 门脚本，自举到引擎 bash 后 `exec "$NODE" "$TOOLS/hid.mjs" "$@"` |
| `app/src/main/java/app/dsh/mobile/engine/cap/HidCap.kt` | 桥端点 `GET/POST /dev/hid`，降级为一次性转发器 |

`EngineConfig.kt` 三处门脚本清单（部署 / `setExecutable` / 部署后自检）都加了 `"hid"`；
tools 部署段从写死 `lan-web-proxy.mjs` 改成 `listOf("lan-web-proxy.mjs", "hid.mjs").forEach`。

### 四个真机坑（每个都让功能「看起来坏着」）

1. **`bus` 必须是 `0x03`（BUS_USB）**：填 `0` 时 Android 静默忽略设备，`create` 返回成功但没有输入节点。
2. **`create` 后必须等 ≥200ms 再注入**：`device_add` 是异步的，要经 InputReader 才识别。已在 `hid.mjs:237` 加 `if (op !== 'create') sleep(200)`。
3. **屏幕必须 `Awake`**：`mWakefulness=Dozing` 时事件**能到内核**（`getevent` 抓得到 `KEY_VOLUMEUP DOWN/UP`）但系统不派发。门脚本故意不自动唤醒——AI 不该静默点亮用户屏幕。
4. **两个 HID 页的 usage 号不通用**（详见下表）。

### ★ usage 号实测矩阵（每次都先 `cmd media_session volume --stream 3 --set 60` 复位）

| 命令 | 音量 | 结论 |
| --- | --- | --- |
| `hid tap 233 kind=consumer` | 70 | Consumer 音量+ = 0xE9 |
| `hid tap 234 kind=consumer` | 50 | Consumer 音量- = 0xEA |
| `hid tap 226 kind=consumer` | 0 | Consumer 静音 = 0xE2 |
| `hid tap 128 kind=consumer` | 60 | **无效**：Consumer 页没有 0x80 |
| `hid tap 128 kind=keyboard` | 70 | Keyboard 音量+ = 0x80 |
| `hid tap 129 kind=keyboard` | 50 | Keyboard 音量- = 0x81 |
| `hid tap 127 kind=keyboard` | 0 | 0x7F 是 KEY_MUTE（静音），不是音量小 |

为免 AI 记 hex 出错，`hid.mjs` 内置 `ALIASES` 表（自带正确 page）：
Consumer `vol+ vol- mute play pause next prev stop rewind forward bright+ bright-`；
Keyboard `enter esc back space tab up down left right home power`。
`resolveAlias(name, kind)` 在位置参数解析处先过一遍，失败再退化为十进制。

### 桥端点与插件

- `EndpointCatalog.kt` 的 hid `limits` 已重写：旧文里「consumer 0x80 有效」「code=128 作音量+」两条是**实测错误**，已删。
- `AgentBridge.kt` 的 `key()` 错误文案同步补齐 `lock|screenshot|power_dialog`（原先只列 5 个，AI 传 `lock` 只看到 "unknown"）。
- `app/src/main/assets/android-plugins/device-tools.mjs:330` `device_hid` 面向 AI：op 枚举 `tap|text|key|mouse|status|wake`；`runHid()`（`:57`）直接 `spawnSync(process.execPath, [script, ...args])`，不经桥；`op=wake` 走 `/system/bin/input keyevent KEYCODE_WAKEUP`。

### 无障碍三动作补齐（`DshAccessibilityService.kt`）

- `:47` `onServiceConnected` 加 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS`（多窗口读屏，加完 `windowId` 才不只有主窗口）。
- `performGlobalActionByName()`（`:1855`）新增 `lock`→`GLOBAL_ACTION_LOCK_SCREEN`（API 28+）、`screenshot`→`GLOBAL_ACTION_TAKE_SCREENSHOT`（API 28+）、`power_dialog`→`GLOBAL_ACTION_POWER_DIALOG`。
- **不可加**：`GLOBAL_ACTION_SPLIT_SCREEN` / `GLOBAL_ACTION_ACCESS_SHORTCUTS` 在 compileSdk 里是 `@SystemApi`/`@hide`，targetSdk 28 拿不到常量，要用只能反射。

### 真机验收（OPPO PLZ110 / Android 16 / KernelSU 4.1.3，v1.5.0-beta.13 / 344）

门脚本链路（引擎 bash 直接调 `hid`）：

```
hid probe            → {"ok":true,"node":"/dev/uhid","exists":true,"writable":true,"uid":0,...}
hid tap vol+         → {"tapped":233}  vol 60→70
hid tap vol-         → {"tapped":234}  vol 60→50
hid tap mute         → {"tapped":226}  vol 60→0
hid tap next         → {"tapped":181,"kind":"consumer"}
hid text hi          → {"sent":2}
```

桥链路 `POST /key`：

| 命令 | 结果 |
| --- | --- |
| `{"action":"bogus"}` | `unknown action (back|home|recents|notifications|quick_settings|lock|screenshot|power_dialog; lock/screenshot need API 28+)` |
| `{"action":"screenshot"}` | 新文件 `Screenshot_2026-11-02-17-48-33-68.jpg` 落 `/sdcard/Pictures/Screenshots`（Oplus 存 `.jpg` 不存 `.png`，别去 `DCIM/Screenshots` 找） |
| `{"action":"lock"}` | `mWakefulness` Awake → **Dozing** |
| `{"action":"power_dialog"}` | `mCurrentFocus=Window{... u0 ActionsDialog}` |

### 门禁基线（本轮实跑）
- `scripts/check-i18n.py` → `All i18n checks passed.`
- `scripts/check-no-bulk-perms.py` → `OK: no bulk permission request`
- `scripts/probes/_test_hid_tool.py` → 30 checks 全过
- py 探针全量：37 passed / 1 failed（唯一失败 `_test_corepack_hardlink.py`，noruntime 下缺 `runtime.zip`，既有基线）
- `gradle -Ppackage=noruntime assembleRelease` → `BUILD SUCCESSFUL`

### 环境要点（本轮新增）

- **无障碍服务关着时 `POST /key` 报 `accessibility service not enabled`**，不是代码问题。开启：`su -c 'settings put secure enabled_accessibility_services app.dsh.mobile/app.dsh.mobile.DshAccessibilityService; settings put secure accessibility_enabled 1'`。
- 手机息屏后 App 进程会被 Cgroup Freezer 冻结，桥「能连但不回」。必须 `input keyevent KEYCODE_WAKEUP` + `svc power stayon true`（`screen_off_timeout` 1800000）。
- `adb push` 传绝对路径报 `cannot stat`，要么 `cd` 到仓库传相对路径，要么传 `E:/Deepeek harness/...`（不带前导斜杠）。

---

## 十五、对外 MCP server（stdio，36 工具派生自端点目录）（2026-11-02）

### 是什么

`app/src/main/assets/tools/mcp-device.mjs`：一个 stdio MCP server，把桥的 36 个设备端点
暴露成 MCP 工具，外部客户端（Claude Desktop / Cursor / 任意 agent host）拉起即可用。

工具表**在启动时从 `GET /dev/help?detail=1` 派生**，不硬编码 —— `EndpointCatalog.kt` 加一条
端点，重启后工具列表自动多一个。这正是当初做端点目录的目的。

### 协议决策（每条都有代价，别随手改）

| 决策 | 理由 |
| --- | --- |
| 握手答 `2025-11-25` | 客户端 legacy 列表只有 `2025-03-26 / 2025-06-18 / 2025-11-25`，答别的会被判不可用 |
| **不实现 `server/discover`** | 客户端收到 `-32601` 会回退到 `initialize`（协议规定的年代协商路径）。实现了就得同时兑现 2026 era 的 `ttlMs` / `cacheScope` / `resultType`，我们并不做 —— 假装支持比不支持更糟 |
| 只做 stdio，不做 Streamable HTTP | stdio 覆盖「本机客户端拉起来」这一档；HTTP 要常驻监听 + 会话管理，价值在远程/多客户端，等有需求再做 |
| 工具表派生而非硬编码 | 硬编码必然与端点目录漂移（目录存在的理由就是这个） |
| 工具参数是宽松的 `args` 字符串 | 端点目录的 `params` 是中文说明串、不是 JSON Schema；编一套严格 schema 只会拒掉合法调用 |

### 真机验证（OPPO PLZ110 / Android 16，装机后）

```
$ mcp selftest  → 14/14 checks passed
   initialize / tools/list / server-discover(-32601) / unknown-tool 全部按预期
$ mcp list      → 36 个工具（status … diag）
tools/call status     → GET /dev/status → 403 capability disabled（开关未开，符合预期）
tools/call hid-status → GET /dev/hid → 200 {"ok":true,"node":"/dev/uhid",...}
```

`capability disabled` 返回的是**结构化错误**（`isError:true` + `structuredContent`），
不是崩溃 —— 客户端能读懂「去设置里开开关」。

### 踩过的两个真 bug（都已在探针里钉住）

1. **`process.stdin.on("end", () => process.exit(0))` 会掐掉在途调用**。
   `tools/call` 是异步的，stdin EOF 不等于工作结束 —— 用管道喂 JSON-RPC 时结果直接丢失
   （真机复现：客户端只看到 `36 tools ready`，没有任何回复）。修法是 `pending` 集合 +
   `stdinClosed` 标志，最后一个调用结算后才退出。
2. **端点目录里 `POST /dev/sms` 登记了两次**（`to` 与 `number` 两套字段），而
   `PersonalCap.smsCompose`（`cap/PersonalCap.kt:240-241`）只读 `to`。MCP 那边因此出现
   重名工具（客户端会整份拒收），文档那半条也把人引向死字段。已删，并在
   `scripts/probes/_test_mcp_device_server.py` 断言目录里 sms POST 恰好一条、
   且不得出现 `number` 字段。

### 门脚本

`app/src/main/assets/gates/mcp`：`mcp serve|list|selftest|probe`。
它 `exec` 到 `$DSH_HOME/tools/mcp-device.mjs`（tools 在 `dsh-home/tools/`，门脚本在
`engine/bin/`，三级探测路径）。三处门脚本清单（`EngineConfig.kt:1743/1789/1794`）已加 `"mcp"`。

### 给外部客户端接（Claude Desktop 等）

```json
{
  "mcpServers": {
    "dsh-android": {
      "command": "/data/data/app.dsh.mobile/files/engine/bin/mcp",
      "args": ["serve"],
      "env": { "DSH_HOME": "/data/data/app.dsh.mobile/files/dsh-home",
               "DSH_BRIDGE_TOKEN": "<取自引擎进程 environ>" }
    }
  }
}
```

token 每进程启动随机、不落盘（`AgentBridge.kt:128`），所以外部客户端要自己从
`/proc/<引擎pid>/environ` 取一次。这是刻意的：桥暴露读屏/点击/短信，token 落盘就等于放开。

### 门禁基线（本轮实跑）

- `scripts/check-i18n.py` → `All i18n checks passed.`
- `scripts/check-no-bulk-perms.py` → `OK: no bulk permission request`
- `scripts/probes/_test_mcp_device_server.py` → **25/25**（反证：把重复 sms 造回去立刻变红）
- `scripts/probes/_test_hid_tool.py` → 全过
- 全量 py 探针 → 除既有基线 `_test_corepack_hardlink.py`（noruntime 缺 `runtime.zip`）外全过
- `gradle -Ppackage=noruntime assembleRelease` → `BUILD SUCCESSFUL`

### 环境坑（本轮新增）

- 装机后**引擎 token 会轮换**。拿旧 token 调 `mcp list` 会得到
  `catalog HTTP 403: {"ok":false,"error":"unauthorised"}` —— 这不是脚本坏了，是 token 过期。
  重新从 `ps -A -o PID,NAME | grep node` + `/proc/<pid>/environ` 取。
- 设备上跑 node 必须带 `LD_LIBRARY_PATH=$ENGINE/lib`，否则报
  `CANNOT LINK EXECUTABLE ...: library "libcares.so" not found`。

## 十三、PR #93 合并与审查修复（v1.5.0-beta.15）

### 背景
`xiaowu8848` 的 PR #93「个性化更新 —— 状态栏自定义、设置页背景图、引导页重写」
（5 commits、+3612/−682、51 files）待合并。用户要求：**必须进行全量测试和代码审查**。

### 合并前验证（在 `/e/tmp/wt93` 试合并 worktree 中做）
- `git merge --no-ff --no-commit 5482b40` → 9 个冲突文件全自动解决，无人工冲突。
- `gradle -Ppackage=noruntime assembleRelease` → `BUILD SUCCESSFUL in 2m 38s`。
- 全量 31 个 py 探针 PASS=31 FAIL=0；`check-i18n.py` / `check-no-bulk-perms.py` 全过。
- `git-hygiene.py` 报的 2 项（历史分叉、merge 中间态）**是 worktree 试合并的预期状态**，
  不是仓库真问题 —— 正仓库 main 无此问题。

### 审查发现的真缺陷（均已在合并后修复）
1. **贴纸删不掉（P0）** — `ExpandedStatusBarActivity.kt` 的 `setOnTouchListener` 返回 true
   会吃掉整个手势，`setOnLongClickListener` 永远收不到派发；而贴纸没有别的删除入口。
   改为 `ACTION_DOWN` 起 600ms 定时（`LONG_PRESS_MS`），位移超过 `touchSlopSq`
   或手指抬起即 `removeCallbacks`。
2. **闭包捕获列表下标会误删** — `render()` 重建整个列表后，长按/拖动闭包里的 index
   可能已指向别的贴纸。`StickerPref` 新增 `removeByFile` / `moveByFile` 按文件名定位。
3. **卡片改色污染其它页面** — `ThemedActivity.dimCards` / `restoreCardOpacity` 直接对
   共享的 `GradientDrawable` 实例 `setColor`。改为先 `mutate()` 再改并写回 `v.background`。
4. **缓存命中仍解码整张原图** — `ImageFx.cachedBlur` 原先先 `decodeFile` 原图再查缓存；
   改为先算缓存键、命中即返回。
5. **灵动岛图标降采样漏检高度** — `CustomVisualPref.sampleSizeFor` 原本只在 `maxW<=0`
   时才看高，宽高双限（216×216）会过度降采样。

### 审查中判定为「不是问题」的（查过，不改）
- **bitmap 回收**：checker 建议在 `IslandDotRenderer`/`MainActivity` 里 `recycle()` 旧
  `BitmapDrawable` 的位图。**不采纳** —— API 26+ 位图在 native heap 由 GC 管理，手动
  recycle 反而可能让仍在引用的 `BitmapShader`/共享像素子集崩溃；且 `createBitmap(src,…)`
  返回的是共享像素的子集，回收父图会导致子图变成非法位图。
- **`dimCards` alpha 复利**：`originalCardColors.putIfAbsent` 保留的是首次原值，
  重复调用不会叠乘。
- **Onboarding 四项**：`onSaveInstanceState` 六项齐全、Shizuku 监听在 `onDestroy` 有配对
  移除、`onBackPressed` 走 `commit()`、`probeRoot` 在后台线程 —— 均无问题。
- **Manifest**：4 个新 Activity 全部 `android:exported="false"`，无新增权限。
- **i18n**：`values*` 各语种同步新增 51 条。

### 探针与回归
- 新增 `scripts/probes/_test_appearance_pr93.py`（17 项），已在 `.gitignore` 放行。
- **反向验证已做**：把 longPress 改回 `setOnLongClickListener`、去掉 `mutate()`、
  恢复先解码后查缓存、恢复只查宽 —— 四种改法均让探针**立刻变红**，恢复后全绿。
- 全量 py 探针 40 通过 / 1 失败（`_test_corepack_hardlink.py`，
  `-Ppackage=noruntime` 下的已知基线失败，缺 `runtime.zip`）。
- `gradle -Ppackage=noruntime assembleRelease` → `BUILD SUCCESSFUL in 68s`；
  真机 `adb install -r` → `Success`，`versionName=1.5.0-beta.15`。

### 提交
- 合并提交 `9d361c6`（`git merge --no-ff pr93`）
- 修复提交 `56b248c`，已推 `5482b40..56b248c main -> main`
- tag `v1.5.0-beta.15` 已推

### PR #88（未合并）
`Masspedro` 的「添加移动端 MCP 设置控制台目录项」，**作者在 body 里明确标注 Draft 请勿合并**，
且 base 是旧 main（`0b92ab94`）。暂不动。

### 作者在合并后又推了 5 个提交（2026-11-03 复盘，未合并）
PR #93 的 head 从 `db54d06d`（5 commits，已合入）变成 `c256597b`（10 commits）。
新增 5 个提交里有 2 个被作者自己 revert，净留下的是
`feat(appearance): 模糊改四档预设、卡片底色不透明度可调（保守重做）`
（模糊 0~24 连续滑块 → 四档 0/25/50/75，新增 `settingsBgControlAlpha`）。

**决定：不合并，让作者自己修。** 已在 PR 上评论（
`https://github.com/Soodok/Deepseek-Harness-Local-Android/pull/93#issuecomment-6594931569`）。

**① 四档模糊存不进去（真 bug，按代码推演）**
`CustomVisualPref.kt:82-84` 写入端 `coerceIn(0, BG_BLUR_MAX)`（`BG_BLUR_MAX = 24`），
而 `SettingsBgActivity.kt:55-58` 传的是百分比 `0/25/50/75` → 25、50 都被截成 24；
`:69-71` 读出端期望值在 `BG_BLUR_PRESETS`（0/25/50/75）里，24 不在列表 →
走旧值换算 `24*100/24 = 100` → 返回 **75**。故四档只有 0 与 75 生效。
修法：`coerceIn(0, 100)` 或就近吸附到合法档位。

**② 新 head 会把我修掉的 5 处全部带回**（`git show c256597b:...` 逐个核过，
`removeByFile`/`moveByFile`、`postDelayed`/`LONG_PRESS_MS`、`mutate()`、
「命中就直接返回」缓存注释 全部 grep 计数为 0）。其中 `sampleSizeFor`
（`CustomVisualPref.kt:209-219`）现状是
`val tooTall = maxH > 0 && nh < maxH && maxW <= 0` —— 圆点导入传
`(DOT_MAX_PX, DOT_MAX_PX)` 两个上限都非 0，`tooTall` 恒 false，扁长图会一路降到
高度只剩十几像素。

**③ 合并会有真冲突**（不是快进）：`git merge origin/main` 在
`app/src/main/java/app/dsh/mobile/ImageFx.kt` 冲突 —— 我这边的「缓存键先算先查」
与作者那边的 `blurStrengthOf(blurPercent)` 首行撞同一位置。合并时须两个都保留：
先 `val strength = blurStrengthOf(blurPercent)`，再算缓存键并查缓存。

**④ 建议但不算 bug**：`blurStrengthOf(25/50/75)` = 6/12/18，四档实际只用到 0~18，
没吃到 24 那档。可按档位映射（0/8/16/24）而非线性百分比。

## 十四、P0-3 btsnoop 二进制解析（v1.5.0-beta.16）

### 做了什么
把 `bt op=log` 从「只能读文本 verbose 日志」扩成「也能解析 HCI 帧级二进制日志」。

- 新增 `app/src/main/java/app/dsh/mobile/engine/cap/BtsnoopParser.kt`：纯字节解析器，
  不碰 Android API（因此可脱离设备用 Python 复刻逐字节验证）。
- `RadioCap.kt` 的 `btLog` 增加 `format=binary` 分支 → `btLogBinary()`。
- `EndpointCatalog.kt` 补参数说明/示例/限制。
- 新探针 `scripts/probes/_test_btsnoop_parser.py`（44 项）。

### 格式事实（实测确认，别凭记忆改）
- 文件头 16 字节：`>8sII` = magic `btsnoop\0` + version(1) + datalink(**1002** = H4)
- 每条记录：`>IIIIQ` = orig_len@0 | incl_len@4 | flags@8 | drops@12 | **timestamp@16（8 字节）**
- flags bit0 = 方向（0=host→controller，1=controller→host）
- 载荷首字节 = H4 类型：0x01 Command / 0x02 ACL / 0x03 SCO / 0x04 Event
- 真机样本 `btsnoop_hci_20261031_050524.cfa`（24535 字节）= 447 包（cmd 223 / event 224）

### 途中修掉的两个真 bug（均有反向验证）
1. **`Privilege.runSu` 管道死锁**：旧码「先 `waitFor(8s)` 再 `readText()`」。
   子进程写满 64KB 管道缓冲即阻塞，我们阻塞在 waitFor → 双方互等 → 超时返回 null。
   binary 模式要回 349KB base64 **必定失败**（真机报 `cannot read btsnoop (root required)`），
   text 模式只回几行所以一直没暴露。改为：起线程抽流 → `waitFor` → `join(1s)`。
2. **时间戳偏移读错**：旧码从 `off + 12` 读时间戳高位，而 @12 是 drops 字段。
   真机 `ts` 显示 `14892293`（≈0.17 天），正确应为 `63961914704019956`（≈740299 天，即 2026 年）。
   已修两处读取点（`off + 16` / `off + 20`）。

### `resyncTail` 的两个坑（都是探针抓出来的）
- **扫描窗口太窄**：只在 `RECORD_SIZE`(24) 内试探，切口落进长 ACL 载荷（255B+）中间时
  报告下一个记录边界在几十上百字节后，直接错过。实测 cut=1500 必失败。改为扫 `RESYNC_SCAN=4096`。
- **只按「包数最多」评分会选错**：任意偏移都可能碰巧走出自洽链。改为三项校验
  （残余 0 字节 + 每包首字节 ∈ 1..5 + 时间戳不倒退）后按**吃掉字节数最多**取胜。
  合成流 11 个切口 + 真机 6 个切口全部正确。

### 探针设计教训
反证时发现两条契约检查**不会变红**：`"4096" in parser` 命中的是注释里的数字，
`ts_hi_16` 复刻的是 Python 侧逻辑、碰不到 Kotlin 本体。已改为锚定
`private const val RESYNC_SCAN = 4096` 与 `readInt(data, off + 16)` 的**实际出现次数**。
教训：契约检查必须锚定真正被执行的代码形态，注释与复刻都不算。

### 真机验证（OPPO PLZ110 / Android 16 / KernelSU 4.1.3）
| 用例 | 结果 |
|---|---|
| 默认尾段 | `packets_total=5541 trailing=0`，`ts=63961914704019956` |
| `filter=Connection` | `packets_matched=165`（从 5895 中筛出）|
| `hex=1` | `"hex":"040f040c010408"` |
| `full=true` | `datalink=1002`、`packets_total=9862`、`trailing=0` |
| text（回归） | 仍正常返回 `.log` 行 |

### 回归与发布
- 全量 py 探针 **41 通过 / 1 失败**（`_test_corepack_hardlink.py`：
  `-Ppackage=noruntime` 缺 `runtime.zip` 的已知基线失败）
- i18n / no-bulk-perms / git-hygiene 三门禁全过
- 构建 `BUILD SUCCESSFUL in 84s`；真机 `versionName=1.5.0-beta.16`
- 提交 `a2484c5`，推送 `d2dc544..a2484c5 main -> main`，tag `v1.5.0-beta.16` 已推

## 十五、P0-4 按 UID 断网 / 查流量（v1.5.0-beta.17）

### 做了什么
- 新增 `app/src/main/java/app/dsh/mobile/engine/cap/NetCap.kt`，`op=status|list|block|unblock`。
- 端点 `GET/POST /dev/net`，登记进 `EndpointCatalog.kt`，走既有 `dev net` 统一入口。
- 能力开关 `cap.net`（与 wifi/bt 分开授权、默认关闭）；权限中心加 `rowCapNet`/`swCapNet`
  与 `ic_globe`；五语种 `setting_cap_net` / `_sub` / `_need`。
- 探针 `scripts/probes/_test_net_block.py`（47 项，含三处反向验证）。

### 两档能力
| 档 | 做法 | 需要 |
|---|---|---|
| 查询流量 | `NetworkStatsManager.querySummary`（mobile + wifi 两个 type 分别查再合并）| Usage Access（不需 root）|
| 封锁 | `iptables -I OUTPUT 1 -m owner --uid-owner <uid> -j DROP -m comment --comment dsh-netblock` | root |

### 三个真机踩出来的坑（务必保留在注释里）
1. **回读格式与命令行不同**：`iptables -vL` 打印 `owner UID match 10360`，
   不是命令里那种 `uid-owner 10360`。第一版按后者 grep → 规则在链上也读回 null →
   接口报 `rule inserted but could not be read back; state unknown`。
   修法：用 `iptables -S OUTPUT`（还原命令行语法）判存在性，`-vL` 取计数。
2. **不能拿 ping 判成败**：`su <uid> -c ping` 拿不到 ICMP raw socket，
   **有规则没规则都 100% 丢包**。我先当成功判据，后来做「删规则基线对照」发现删了也丢包
   才识破 —— 典型假阳性。改用回读 `pkts` 计数器。
3. **列偏移**：不带 `--line-numbers` 时第 0 列 = pkts、第 1 列 = bytes。

### 两个设计约束
- **必须插链首** `-I OUTPUT 1`：ColorOS 的 OUTPUT 前面挂着 `customize_OUTPUT`、
  `RESTRICTION_SSDP_CHAIN`、`oem_out`、`fw_OUTPUT`、`st_OUTPUT`、`bw_OUTPUT`
  与两条 BPF skfilter（`prog_oplus-netd_skfilter_egress_*`），追加末尾会被提前 ACCEPT。
- **拒绝封自己 / uid 0**：否则 AI 一执行就把自己断网再也调不回来；要封须显式 `allowSelf=true`。

### 真机验证（v1.5.0-beta.17）
| 用例 | 结果 |
|---|---|
| `op=status`（初始）| `blocked_count=0` |
| `op=list` | `uids_total=77`，返回 shell / 网易UU / B站 / QQ / 微信真实用量 |
| `op=block`（抖音）| `uid=10360 verified=true` |
| `op=status` | `blocked_count=1` 且带包名 |
| 幂等复 block | `already_blocked=true` |
| 让 uid 10360 发流量后 | `packets_dropped=5 bytes_dropped=300` |
| `op=unblock` | `was_blocked=true` → status 归零 |
| 封自己 | `refusing to block myself (uid 10488)` |
| `dev net` | 自描述正常渲染（含五条限制）|

### 尚未覆盖
- IPv6（`ip6tables` 未接）。
- 规则**重启不持久**（`iptables-save` 里没有我们的 owner 规则）。

### 回归与发布
- 全量 py 探针 **42 通过 / 1 失败**（`_test_corepack_hardlink.py` 已知基线）
- 门禁全过；反向验证三处（回读写法 / `-I` 改 `-A` / 去掉防自封）均能让探针立刻变红
- 提交 `500a854`，推送 `d9678b0..500a854`，tag `v1.5.0-beta.17` 已推

## 十六、P1/P2 六项能力（v1.5.0-beta.18）

按 `docs/CAPABILITY_MAP.md` 的 P1/P2 清单逐项落地，一次性交付 6 个新端点。

### 端点总表

| 端点 | 能力 | 档 | 文件 |
|---|---|---|---|
| `POST /dev/ble` | 手机当 BLE 外设（GATT Server） | A | `cap/BlePeripheralCap.kt` |
| `POST /dev/bt-hid` | 手机冒充蓝牙键盘 | A（OEM 视情况）| `cap/BtHidCap.kt` |
| `POST /dev/screenwatch` | 屏幕变化检测（不存图/视频） | B | `cap/ScreenWatchCap.kt` |
| `POST /dev/record` `op=audio` | 录其他 App 的声音 | B | `cap/RecordCap.kt` 扩展 |
| `POST /dev/pulse` | 振动摩斯电码 | A | `cap/PulseCap.kt` |
| `POST /dev/scene` | 传感器场景推断 | A | `cap/PulseCap.kt` |
| `POST /dev/doze` | Doze 白名单自助 | C | `cap/PulseCap.kt` |

### ★ 关键实测结论：蓝牙 HID 在这台机器上可用
`docs/CAPABILITY_MAP.md` 原本写「多数 OEM 需 BLUETOOTH_PRIVILEGED，实测成功率待验」。
真机实测（OPPO PLZ110 / Android 16 / KernelSU）：

```
op=probe → {"profile_constant_ok":true, "get_profile_proxy_called":true,
            "proxy_obtained":true, "bluetooth_privileged_granted":false}
op=register → {"ok":true, "registered":true}
```

**未授 `BLUETOOTH_PRIVILEGED` 也能注册成功** —— 这台 OPPO 没封死 HID_DEVICE。
但结论只对这台机器成立，其它 OEM 仍需各自 `op=probe` 验。

### 各能力的设计要点
- **ble**：与 `/dev/bt` 方向相反（那个是手机去连别人，这个是被别人连）。
  广播载荷上限 31 字节，名字走 scan response。客户端写进来的内容要自己
  `op=events` 轮询 —— Android 不会主动通知 AI。
- **bt-hid**：`probe` 把三个维度拆开报（profile 常量 / proxy 能否拿到 / 权限），
  失败原因不含糊。只注册键盘（`SUBCLASS1_KEYBOARD`），`op=mouse` 会被系统拒。
  `type` 仅 US 布局 ASCII，非 ASCII 跳过而不是猜。
- **screenwatch**：MediaProjection → VirtualDisplay → ImageReader（默认 1/8 边长）。
  只返回 `changed_ratio` / `mean_delta` / `hot_blocks`，**不存任何像素**。
  补的是「截图太贵、无障碍节点树拿不到像素」之间那层。
- **record op=audio**：`AudioPlaybackCaptureConfiguration` 匹配 MEDIA/GAME/UNKNOWN。
  输出 16-bit PCM 单声道裸流（故意不引编码器）。**静音不等于失败** ——
  目标 App 可拒绝被捕获、DRM 内容录不到，用实际写入字节数判断。
- **pulse**：点=1 单位、划=3、符号间=1、词间=7，末尾间隔清零。
  短码 ok/fail/wait/done/ask/sos 比单字母好记。
- **scene**：**规则推断不是训练模型**，每个标签都在 `facts` 里附依据的具体数字。
  加速度用三轴模长避免方向变化造成假运动；气压与上一次调用比才有 delta。
- **doze**：`dumpsys deviceidle whitelist +/-<pkg>`，并用
  `isIgnoringBatteryOptimizations` 回报**生效状态**而不只是命令返回值。

### 真机验证（v1.5.0-beta.18）
| 用例 | 结果 |
|---|---|
| `pulse text=ok` | `code="..." segments=6 duration_ms=300` |
| `pulse text=done unitMs=80` | `code="-.." duration_ms=560` |
| `scene windowMs=1000` | `scene=["dark","still"]` + 英文 facts |
| `doze op=add` | `command_ok=true now_ignoring=true raw="Added: app.dsh.mobile"` |
| `ble op=start name=DSH-Test` | `advertising=true`；status 复查 `running=true` |
| `bt-hid op=probe` | `proxy_obtained=true` → 可注册 |
| `bt-hid op=register` | `registered=true` |
| `screenwatch op=start`（无授权）| `{"error":"no_permission"}` |
| `record op=audio`（无授权）| `{"error":"no_permission","hint":"POST op=request first..."}` |

### 途中修掉的两个问题
1. **facts 里有中文** —— `check-i18n.py` 的 CJK-in-Kotlin-string 门禁拦下
   `PulseCap.kt:239`。facts 是给 AI 读的结构化输出，已改英文。
2. **EndpointCatalog 又写进裸换行** —— `params = "...diff` 后直接折行导致
   Kotlin 字符串断裂。用 `chr(92)` 重建 `\n` 并按行号精确替换。
   **这是同一个坑第二次踩**，以后批量改 Kotlin 字符串必须用 chr(92) 构造转义。

### 尚未做
- P1-3 上下文总线 + 主动提醒闸门（最大差异化方向，工作量最大）
- P1-4 「AI 的手」可视化悬浮窗
- P2-2 通话筛选 / P2-3 通知中枢摘要 / P2-5 精确闹钟
- `screenwatch` 与 `audio` 的**授权路径尚未真机跑通**（只验了无授权时的如实报错，
  授权需要在前台 Activity 点系统弹窗，adb 无法代替点击）

### 门禁与发布
- 新探针 `scripts/probes/_test_new_capabilities.py`（62 项）
- 全量 py 探针 **43 通过 / 1 失败**（`_test_corepack_hardlink.py` 已知基线）
- i18n / no-bulk-perms / git-hygiene 全过；构建 `BUILD SUCCESSFUL`
- 提交 `824bf17`，推送 `d46dfef..824bf17`，tag `v1.5.0-beta.18` 已推

## 十七、上下文总线 + 提醒闸门 + 「AI 的手」（v1.5.0-beta.20）

### 三个新端点
| 端点 | 能力 | 文件 |
|---|---|---|
| `GET/POST /dev/context` | 上下文总线（时间/屏幕/电池/网络/前台/通知概况 + should_nudge）| `cap/ContextCap.kt` |
| `POST /dev/nudge` | 主动提醒闸门（默认不打扰）| `cap/ContextCap.kt` |
| `GET /dev/notify-digest` | 通知摘要（按 App 聚合计数）| `cap/ContextCap.kt` |
| `POST /dev/hand` | 「AI 的手」可视化（tap/path/clear/hide/debugbg）| `cap/HandOverlay.kt` |

### 提醒闸门的五道关（默认全拒）
1. 静音时段 23:00–07:00
2. 息屏
3. 锁屏
4. Doze（后台被冻结，提醒会无限延迟）
5. 同 key 30 分钟冷却

`importance >= 8` 可穿过 1–3（`bypass=true`）；`force=true` 全强制。
拒绝时 `reasons` 列出**具体哪条拦的**。

### ★「AI 的手」踩的五个坑（全部真机定位，注释里都写了）
1. **View 必须主线程建** —— `View` 构造内部 `new Handler()`，在桥的
   `AgentBridge-req` 线程里抛 `Can't create handler inside thread ... Looper.prepare()`。
   整段丢 mainHandler + `CountDownLatch` 等结果。
2. **必须借无障碍服务的 WindowManager** —— `TYPE_ACCESSIBILITY_OVERLAY` 需要
   服务的 window token。用 `applicationContext` 会报
   `Unable to add window -- token null is not valid`，
   **这个错误信息极易被误读成「无障碍服务没跑」**。
3. **不要加 `FLAG_LAYOUT_NO_LIMITS` / `FLAG_LAYOUT_IN_SCREEN`** ——
   加上后窗口被重新居中：请求 (600,1320) 实际画到 (1061,303)。
4. **要减窗口原点** —— `getLocationOnScreen` 实测返回 `[0,135]`
   （状态栏 + 挖孔高度），不减的话每个点整体下移 135px。
5. **纯色圆点在深色 UI 上几乎看不见** —— 加白色外描边 + 白色脉冲环。
   排查手段：`op=debugbg on` 铺半透明红，先确认窗口可见，再定位坐标。

### 途中修掉的两个 bug
- **`by_app` 恒为空**：`context()` 里传了 `limit=0`，`.take(0)` 返回空列表。
  改传 8，并在 `notificationDigest` 里 `coerceAtLeast(1)`。
- **字段名不对齐**：`NotifyCap.toJson` 写的是 `posted_at`，我按 `time` 取。

### 新增工具
`scripts/fix-kotlin-string-newlines.py` —— 修 Kotlin 字符串字面量里的裸换行。
**同一个坑已踩三次**（AI_CONTEXT 十四、十六、十七），这次做成脚本：
判据是「一行里未转义双引号个数为奇数」，命中就把下一行拼上来并补 `\n` 转义。

### 真机验证（v1.5.0-beta.20）
| 用例 | 结果 |
|---|---|
| `GET /dev/context` | 00:42 / Awake / 电量 82% / wifi / 前台 app.dsh.mobile / 通知 67 条 6 App |
| `nudge importance=5` | `allow=false, reasons=["quiet hours"]` |
| `nudge importance=9` | `allow=true, bypass=true` |
| `nudge` 同 key | `cooldown active: last alert 294s ago (limit 1800s)` |
| `notify-digest` | `total=66 apps=6`（QQ 36 / DSH 16 / android 8 …）|
| `hand tap x=400 y=900` | 截图实测中心 **(399,899)**，偏移 -1px |
| `hand debugbg on` | 全屏偏红，确认窗口可见 |

### 门禁
`_test_new_capabilities.py` 扩到 79 项；三处反向验证全部变红。
全量 py 探针 43 通过 / 1 失败（`_test_corepack_hardlink.py` 已知基线）；
i18n / bulk-perms / git-hygiene 全过。
提交 `338db7e`，tag `v1.5.0-beta.20`。

### 尚未做
- P2-2 通话筛选（`CallScreeningService`，需用户设为默认来电筛选应用）
- P2-5 精确闹钟（`SCHEDULE_EXACT_ALARM`）
- `screenwatch` / `audio` 的**授权路径尚未真机跑通**（只验了无授权时的如实报错，
  授权需在前台点系统弹窗，adb 无法代替点击）
