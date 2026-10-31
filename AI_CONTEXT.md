# DSH Android · 交接文档

> **只写当前有效的事实。** 历史流水已归档：`docs/history/AI_CONTEXT-archive-20261031.md`
> （4070 行，含 2026-10-11 ~ 10-31 每轮交付细节）。本文超过 300 行就该把旧段归档。
>
> 配套规范：[`COLLABORATION.md`](docs/COLLABORATION.md) 合并流程 ·
> [`HYGIENE.md`](docs/HYGIENE.md) 仓库卫生与迭代节奏 ·
> [`git-workflow.md`](docs/git-workflow.md) 推送通道 ·
> [`RELEASE.md`](docs/RELEASE.md) 发版流程

**最后更新**：2026-10-31 · HEAD `ed0d491` · 已发布 v1.4.76 / 327；工作区 v1.4.77 / 328（设备能力未完成，暂停状态见 §八）

---

## 一、当前状态

| 项 | 值 |
| --- | --- |
| 版本 | 已发布 327 / 1.4.76；工作区 `versionCode = 328` / `versionName = "1.4.77"`（`app/build.gradle.kts:244-245`），未完成、未发布 |
| 远端 | `main` 与本地缓存的 origin/main 一致（本轮未 fetch）；HEAD `ed0d491` |
| 分支 | 只有 `main`（2026-10-31 清理，19 → 1） |
| worktree | 只有主仓库（清理 7 → 1） |
| 已知阻塞 | 设备能力 P0 ping 端到端验证未完成；连续两轮以上无进展后暂停，详见 §八 |
| 待办 | 设备能力先见 §八；原有问题见 §五 |

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

## 八、设备能力扩展：未完成，按两轮无进展规则暂停（2026-10-31）

### 工作区与实现范围

- 当前 HEAD `ed0d491`；工作区版本为 328 / 1.4.77，未提交、未打 tag、未推送。暂停时 `git status` 有 29 项（其中 cap/ 按目录显示），包含本轮未修改的 UI 文件；不可整体覆盖或把这些 UI 改动当作设备能力补丁。
- `AgentBridge.kt:353-425` 已改 Map 注册表并加入九个 /dev 端点；原有处理函数仍在桥文件内。diff 为 +75/-63，文件从 2419 行增长到 2431 行，未满足“AgentBridge 只分发、实现全部拆包、行数下降”；旧路由逐条等价尚未通过专门验证。
- 新增 `Route.kt`、`CapabilityGate.kt` 和 cap/ 下五个实现文件；Manifest 增七项权限、通知监听服务、提醒和开机/升级恢复接收器。
- 新增四个门脚本 device-ping/device-status/device-control/remind 与 device-tools.mjs，已接入 EngineConfig 的部署、自检与 overlay；种子只升到 50，尚未补充新能力说明。P2 硬件操作代码也已写入，超出本次优先实现顺序，未验收。

### 已验证证据及边界

- 用户指定 JAVA_HOME/Gradle，带 `-Ppackage=noruntime` 的 compileReleaseKotlin 与 assembleRelease 均退出码 0；最后完整构建输出 `BUILD SUCCESSFUL in 32s`，56 tasks（14 executed / 42 up-to-date）。ExpiredTargetSdkVersion 仍出现，不影响本次成功退出。
- 暂停前重跑四门禁，均退出码 0：i18n `All i18n checks passed.`（135 XML / 1007 keys）；builtin_plugins `通过 60 / 失败 0 / 跳过 1`；plugin_install_errors `通过 42 / 失败 0`；git-hygiene `通过（1 条提醒）`，提醒为 29 项未提交改动，并非八项全绿。
- 这些既有探针并未验证设备能力端点语义；不能据此宣称 P0/P1/P3/P5 已完成。尚未运行独立 checker 审查。
- 真机安装返回 Success；曾回读 328 / 1.4.77 及新增 Manifest 组件。新进程启动后插件健康为 `ok=true missing=[] failed=[]`。这只证明插件激活，不证明模型工具列表已逐项检查或执行成功。
- device-tools 真机加载失败先后定位 required:false 与 repeat 对象缺 additionalProperties，均已修正；最后安装的构建包含这些修正。

### 未完成与已识别问题

- P0 CLI→HTTP→工具插件 ping 尚未拿到成功调用结果；鉴权排查连续超过两轮，按用户要求停止。空 token 仅在 LAN 代理启动命令中观察到，不能证明主引擎环境丢失 token，也不能据此认定 su 为根因。EngineProcess.kt:189-209 已加 token export 补丁，构建与安装通过，但修复效果未验证。
- 所有 cap 开关默认关闭，但全源码未发现 CapabilityGate.setEnabled 的调用点，用户开关 UI、通知监听授权跳转与电池白名单引导未接入；权限真值和失败调用审计仍需补全。
- StatusCap 尚缺 cellular/proxy/foreground.activity 等字段，蓝牙 connected 固定空数组；DND 以铃声模式替代真实勿扰状态。不能称为规格要求的完整真实快照。
- NotifyCap.read 目前检查的是 notify 而非 notify_read；degraded=accessibility_cache 分支实际读取 listener 自建缓存，未接现有无障碍通知缓存。CapabilityGate 的监听授权判定把组件字符串直接与包名比较，尚待纠正。
- 提醒 once 触发后立即删任务，snooze 随后按 id 查表将无记录；ack 为空实现。repeat.at 未使用，以固定 24h 推进，过期任务恢复/并发读写/声音震动自定义/actions/icon 仍未完成验收或实现。
- 未做状态字段、单次传感器、微信标题正文、剪贴板、控制操作的功能真机验证；未做锁屏 20 分钟、重启恢复、升级恢复、稍后 10 分钟验收。不能承诺提醒准时。

### 下一步首要任务

先完成 P0 收敛与专门测试，再推进其余阶段：通过引擎内正常调用路径取得 ping 请求/响应证据；不要继续根据 LAN 代理命令猜测主引擎 token，也不要绕过桥鉴权。补开关 UI 与路由等价/拆包验证后，再修复上述 P1/P3 问题并做独立审查和完整真机验收。
