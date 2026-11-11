# RC2_PREADAPT：npm 还没发布，先把 0.2.2-rc.2 的补丁适配做完

> 目的：本仓 runtime.zip 只能从 **npm** 安装 `@deepseek-ai/dsh`，而 0.2.2-rc.2 目前
> 只出现在桌面版 nightly feed。为了不等 npm 上架才踩雷，直接从桌面安装包里把引擎
> 提取出来，**提前验证全部 Android 补丁锚点**。
>
> 结论先说：rc.2 改了两个 fs-local 锚点，其余全部命中；补丁已改成「新旧双形态」，
> npm 一上架就能直接升。

## 1. 提取路径（可复现）

```
nightly feed: https://download.deepseek.com/dsh-desk/feeds/win-x64/nightly.yml
  版本 0.2.2-rc.2，deepseek-harness-0.2.2-rc.2-win-x64.exe = 299,659,528 B（构建于 2026-11-11 18:26）

7z x <exe> "\$PLUGINSDIR/app-64.7z"        # NSIS 外层
7z e app-64.7z "resources/app.asar"        # 内层，208,137,981 B
npx --yes @electron/asar extract app.asar out
→ out/dsh/node_modules/@deepseek-ai/       # 337 个包，版本号全为 0.2.2-rc.2
```

对照物：本机已装的 rc.1 桌面版（`resources/app.asar` → 331 个包）。

## 2. 锚点文件哈希对比（rc.1 → rc.2）

| 包 | 文件 | rc.1 | rc.2 | 结果 |
|---|---|---|---|---|
| dsh-ptc-runtime-node | lib/index.js | 952ffdd5… | 2eee67bc… | **CHANGED** |
| dsh-fs-local | lib/index.js | d6d3115d… | 610a3ba3… | **CHANGED** |
| dsh-attachment-local | lib/index.js | ff717f87… | ff717f87… | SAME |
| dsh-web-frontend | dist/index.html | d3581179… | d6a23df8… | **CHANGED** |
| dsh-session-persistence-jsonl | lib/index.js | 69efef5f… | 69efef5f… | SAME |
| dsh-session-persistence-jsonl | lib/worker.cjs | c5c8ccb7… | 94cec706… | **CHANGED** |
| dsh-subprocess-local | lib/index.js | 2964d94d… | 152b009a… | **CHANGED** |
| dsh-host-apiproxy | lib/index.js | — | — | MISSING（rc.5 起上游已移除，非本次回归） |
| dsh-agent-preset-registry | lib/index.js | 7365e9c7… | 7365e9c7… | SAME |

**CHANGED=5 / SAME=3 / MISSING=1**。注意：字节变了不等于锚点失效，必须实跑补丁才算。

## 3. 补丁实跑结果（对 rc.2 树逐个执行）

| 补丁脚本 | 结果 |
|---|---|
| `patch-ptc-runtime-android.py` | ✅ 命中（`STARTUP_ENVIRONMENT_NAMES` 仍保留 `LD_LIBRARY_PATH`） |
| `patch-fs-local-android.py` | ❌ **两个锚点全部失效** → 已修（见 §4） |
| `patch-attachment-local.py` | ✅ 命中（sync 容忍注入 / EACCES·EPERM / EINVAL·EROFS / link shim / 无裸 rename 五项校验全过） |
| `patch-agent-preset-fallback.py` | ✅ 命中 |
| `patch-node-pty-android.py` | ✅ 命中（3 处：进程探测器接受 android、终端活动判定接受 android、写入 Android PTY 实现） |
| `patch-webview-polyfill.py` | ✅ 命中（`polyfill v5 injected`） |
| `patch-webview-syntax-floor.py` | ✅ 通过（2 个 chunk → chrome85，1,834,759 → 1,921,929 B） |
| `patch-apiproxy.py` | ⚠️ 目标包不存在（rc.5 起已移除，脚本 WARN 后跳过，属预期） |

## 4. 唯一真回归：fs-local 的两个锚点（已修）

上游在 rc.2 把错误包装统一到了 `fsErrorFromErrno`：

| 位置 | rc.1 | rc.2 |
|---|---|---|
| `probeStats` catch | `throw error;` | `throw fsErrorFromErrno("stat", absolutePath, error);` |
| `listDirectory` 单子项 catch | `throw listingIoError(localDisplayPath(...), error);` | `throw fsErrorFromErrno("list", localDisplayPath(...), error);` |

`listingIoError` 在 rc.2 里**已不存在**，所以旧锚点不只是「字节变了」，是**语义换了实现**。
修法：`scripts/patch-fs-local-android.py` 改成**双形态**——

- 锚点按 `PROBE_OLD_RC2 → PROBE_OLD`（新版优先）、`LIST_OLD_RC2 → LIST_OLD` 顺序匹配；
- 注入代码保持原有 Android 语义（EACCES/EPERM → `null` / 子项降级 `other`），
  非权限错误仍按**当前版本**的方式上抛（rc.2 抛 `fsErrorFromErrno`，rc.1 抛原始 error）；
- 幂等判定同时认两种已打形态。

实测：

```
rc.2 → fs-local: 已打补丁 -> probeStats: tolerate EACCES (rc.2 form), listDirectory: degrade one child to other (rc.2 form)
rc.2 二次执行 → 已是补丁形态（2 项）          # 幂等
rc.1 → fs-local: 已打补丁 -> ... (legacy form)  # 旧版本不回归
```

## 5. 包清单变化（337 vs 331）

新增 13：`dsh-agent-team`、`dsh-client-ui-account-notifications`、`dsh-client-ui-agent-team`、
`dsh-computer-use`、`dsh-cua`、`dsh-cua-bundle`、`dsh-error`、`dsh-mcp-manager`、
`dsh-office-kit`、`dsh-terminal-panel`、`dsh-tool-agent-team`、`dsh-tool-office`、`dsh-util-ripgrep`

移除 7：`dsh-api-account-controller`、`dsh-deepseek-account-platform`、
`dsh-experimental-agent-team`（及其 -profile / -client-ui / -tool 三个子包）、`dsh-office-to-pdf`

### 5.1 新包原生依赖扫描（rc.2 提取树，13 个新包逐个读 package.json）

`gypfile` / `binary` / `scripts.install` 全部为空，依赖里也没有 node-pty / koffi / better-sqlite3 /
napi / canvas / sharp / sqlite3。唯一相关的一条：`dsh-cua-bundle` 的 optionalDependency 是
`@deepseek-ai/dsh-experimental-cua-macos`（macOS 专用，Android 上被 optional 跳过）。

→ **没有新增需要 bionic 十字编译的原生依赖**。（`dsh-util-ripgrep` 见 §5.3，走的是既有 ripgrep 通路。）

### 5.2 被移除的 7 个包在本仓的引用数

`dsh-api-account-controller` / `dsh-deepseek-account-platform` / `dsh-experimental-agent-team`（含
-profile / -client-ui / -tool）/ `dsh-office-to-pdf` —— 在 `app/src`、`scripts`、`.github` 里
**引用数全部为 0**，移除不影响本仓配置。

### 5.3 默认插件配置变了（这条最容易被忽略）

`dsh-base/cordis.patch.yml` 的 rc.1 → rc.2 diff：

| 变化 | 内容 |
|---|---|
| 插件改名 | `dsh-deepseek-account-platform` → **`dsh-deepseek-account`** |
| **新增默认启用** | `agent-team`（`config.maxTasks: 256`）+ `tool-agent-team` |
| 参数变化 | `tool-subagent-control` 增加 `config.interrupt: false` |
| 注释/默认变化 | `tool-web` 的搜索默认改为 DeepSeek Search API（`deepseek-search-api`）+ 匿名本地 fetch |

**含义**：rc.2 起 Agent Teams 在引擎里**默认开启**，Android 上会多加载两个插件并允许最多 256 个任务。
本仓对这两个插件名零引用（配置全来自上游包），所以升级后需要在真机确认启动无异常、
以及 256 个任务的默认上限对手机内存是否合适（**未验证**，等 npm 上架后真机跑）。

### 5.4 ripgrep 通路不受影响

`@vscode/ripgrep/lib/index.js` 在 rc.1 与 rc.2 **字节完全相同**
（sha256 `107cf2aa0eaf6e9f…`），仍按 `@vscode/ripgrep-${process.platform}-${arch}` 解析；
Termux node 的 `process.platform` 是 `android`，正好命中
`collect-termux-runtime.sh` 造出来的 `@vscode/ripgrep-android-<arch>/bin/rg`（bionic 原生 rg）。
`build-runtime.sh:69-72` 的两条断言（平台包必须在、不能混入宿主 Linux rg）继续有效。
新增的 `dsh-util-ripgrep` 只是把这段解析抽成公共 util，走的是同一条路。

## 6. 真机启动实测：rc.2 引擎能在 Android 上跑起来

光验补丁不够——**引擎能不能启动**才是升级的门槛。做法：把提取出的 rc.2 闭包（337 包 / 216 MB）
打成 tar 推到 emulator-5556（dsh30 / Android 11 / x86_64），替换
`files/engine/lib/node_modules`（原目录 mv 成 `node_modules.rc1` 做回滚点），打上全部补丁后重启 App。

### 6.1 结果

| 观察项 | 结果 |
|---|---|
| node 进程 | ✅ 起来 2 个（engine + 子进程） |
| 引擎 HTTP | ✅ `dsh web: http://127.0.0.1:3080/?token=…` 正常监听 |
| 插件健康 | ⚠️ `ok=false failed=[session-log-download:@deepseek-ai/dsh-session-log-export]` |
| 其它插件 | ✅ 其余全部加载（含 rc.2 新增的 `agent-team` / `tool-agent-team` 客户端插件出现在 preload 列表里） |

### 6.2 两个启动期发现

**① `node-addon-require-builtin` 必须打纯 JS 兼容层（否则启动即崩）**

手工树第一次启动直接抛：

```
Cannot find module '.../node-addon-require-builtin/build/nodeabi/node-v137-android-x64/require_builtin.node'
Require stack: .../@deepseek-ai/dsh-app-boot/lib/index.js
```

这正是 `collect-termux-runtime.sh:349-389` 那段注释写的坑（上游只预编译 linux-x64-gnu，
而 `dsh-app-boot.internalModules()` 没有 try/catch）。补上脚本里那份纯 JS 兼容层
（`createRequire` + `--expose-internals`）后引擎正常启动 → **该补丁在 rc.2 上仍然必需，且形态未变**。

**② 唯一失败的插件是提取假象，不是 rc.2 缺陷**

```
session-log-download: failed to import: Cannot find package
  '.../dsh-session-log-export/node_modules/fflate/index.js'
```

桌面 app.asar 是 pnpm 布局，`fflate` 是嵌套软链；asar 提取后只剩空壳目录。
npm 的真实安装会把 fflate 装成实体目录，所以这条**不能算 rc.2 的问题**，但提示升级后要核一次插件闭包。

**③ WebView 白屏与 rc.2 无关（既有问题）**

`Uncaught SyntaxError: Unexpected token '='` —— 前端 chunk 里有 `??=`（Chrome 85+）。
对比 rc.1 的 chunk：同样有 24 个 `??=` / 1 个 `||=` / 1 个 `&&=`，**rc.2 没有变差**；
`patch-webview-syntax-floor.py` 的目标本来就是 chrome85，App 的 WebView 门槛也写 `min=85`，
这台 AVD 的 WebView 是 83（`cause=TOO_OLD`），本来就该被拦在门外。

### 6.3 回滚

实测后已把设备恢复成 rc.1（`node_modules.rc1` 换回，删掉 `/data/local/tmp` 的临时包），
重启后 `[health] plugin health: ok=true missing=[] failed=[]` —— 设备回到原状态。

## 7. 升级 checklist（npm 上架后照做）

1. 改 5 处版本钉：`scripts/build-runtime.sh:19`、`scripts/collect-termux-runtime.sh:136`、
   `.github/workflows/runtime-build.yml:37,70`、`.github/workflows/upstream-staging.yml:9`
2. 跑 `upstream-staging`（`dsh_version=0.2.2-rc.2`）→ `runtime-build` 自动跟
3. 核对 13 个新包有无原生依赖（`node-pty`/`koffi` 类）
4. 真机回归：`bash scripts/gate-memory.sh release`
5. 升 app 版本号打 tag

> 环境：2026-11-12 01:0x，Windows + Git Bash，7-Zip 26.01，node v24.15.0，
> `@electron/asar` 经 npx 临时安装。所有提取产物在 `/e/tmp/rc2_asar`、`/e/tmp/rc1_asar`，
> 未入库。
