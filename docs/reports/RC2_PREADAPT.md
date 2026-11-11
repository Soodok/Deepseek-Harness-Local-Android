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

新增包是否需要 Android 侧特殊处理（`dsh-cua`/`dsh-computer-use` 可能带原生依赖）**未验证**——
等 npm 上架后用 `npm install` 的真实闭包再核对一次（桌面 asar 里的布局是 pnpm 的，不能直接当 npm 闭包用）。

## 6. 升级 checklist（npm 上架后照做）

1. 改 5 处版本钉：`scripts/build-runtime.sh:19`、`scripts/collect-termux-runtime.sh:136`、
   `.github/workflows/runtime-build.yml:37,70`、`.github/workflows/upstream-staging.yml:9`
2. 跑 `upstream-staging`（`dsh_version=0.2.2-rc.2`）→ `runtime-build` 自动跟
3. 核对 13 个新包有无原生依赖（`node-pty`/`koffi` 类）
4. 真机回归：`bash scripts/gate-memory.sh release`
5. 升 app 版本号打 tag

> 环境：2026-11-12 01:0x，Windows + Git Bash，7-Zip 26.01，node v24.15.0，
> `@electron/asar` 经 npx 临时安装。所有提取产物在 `/e/tmp/rc2_asar`、`/e/tmp/rc1_asar`，
> 未入库。
