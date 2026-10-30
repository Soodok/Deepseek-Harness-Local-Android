# 内置 SSH 远程工作区（@xiaopb/dsh-hardssh）

DSH Mobile 默认内置 [@xiaopb/dsh-hardssh](https://github.com/XiaoPb/dsh-hardssh)：
手机直连服务器，把远端目录当作工作区改代码、跑测试、查日志，不需要在手机上另装插件。

> 这是 XiaoPb fork 的版本（v0.2.11 起 npm 包名由 `@tiphareth/dsh-hardssh` 改为
> `@xiaopb/dsh-hardssh`）。旧名只用于让位判断：用户自己装过旧包时，内置层会跳过，
> 避免同一组 hardssh 行被应用两次。

> **源码/发布包都不进本仓库。** 构建时 CI 解析 npm 最新版本，从上游
> [Release](https://github.com/XiaoPb/dsh-hardssh/releases) 取 tgz（与 npm 包同
> integrity），组装成 APK 的 `assets/hardssh/` 内置副本（见
> [pack-hardssh.sh](../scripts/pack-hardssh.sh)、
> [android-build.yml](../.github/workflows/android-build.yml) 的 `build-hardssh` job）。
> 插件不再打进 `runtime.zip`，运行时与插件可以各自更新。

## 入口

| 位置 | 能力 |
|---|---|
| 左侧栏「SSH 工作区」 | 添加服务器（或从 `~/.ssh/config` 导入）、绑定远端目录为工作区 |
| 右侧栏「SSH」 | Web 终端（xterm + WebSocket PTY）、文件上传下载、本地端口转发、当前服务器命令 |
| Agent 工具 | `ssh_list` / `ssh_exec` / `ssh_upload` / `ssh_download` / `ssh_tunnel` / `ssh_cluster` |
| Agent 工具 | `remote_ls` / `remote_search` / `remote_status`（显式远程工作区操作） |

## 绑定工作区后改代码、跑验证

把某台服务器的目录绑定为 SSH 工作区后，**该会话里的原生工具自动跑在远端**：

| 工具 | 行为 |
|---|---|
| `read` / `write` / `edit` | 经 `ctx.fs` 接缝路由到远端工作区 |
| `bash` | 经 `ctx.subprocess` 接缝在远端执行（编译、测试、git 都在服务器上跑） |
| `glob` / `grep` | 搜索桥：宿主机有 ripgrep 就发同一条 argv，否则由远端搜索阶梯代答 |
| `remote_*` | 显式远程运维：列目录、检索、查看绑定状态 |

这正是「手机当终端、算力留在服务器」的用法：APK 只负责界面与调度，代码和构建都在远端。

## 集成原理（为什么开箱可用）

1. **构建期**：CI 用 `npm view` 解析 `@xiaopb/dsh-hardssh` 最新版本 →
   从上游 Release 下载 `xiaopb-dsh-hardssh-<ver>.tgz`（缺失时回退 `npm pack`）→
   用 npm `dist.integrity` 校验 → `scripts/pack-hardssh.sh` 组装出
   `package.json` / `lib/*.js` / `cordis.patch.yml` / 依赖（`ssh2`、`ws`）。
   `ssh2` 的原生加速是可选的，打包时用 `--omit=optional` 排除，Android 上走纯 JS 回退。
2. **打包期**：`assets/hardssh/` 随 APK 分发；启动时由 `HardsshInstaller` 复制到
   `engine/lib/node_modules/@xiaopb/dsh-hardssh`。runtime 重装会清掉 `engine/lib`，
   下次启动自动补装 —— 运行时更新不再丢 SSH。
3. **启动期**（`EngineConfig.ensureBundledSshPlugin`）：
   - 在 `$DSH_HOME/profiles/node_modules/@xiaopb/dsh-hardssh` 建一条指向引擎目录的链接，
     让 dsh 的 Loader 能从 web profile 解析到插件名（不复制文件）；
   - 把插件自带的 `cordis.patch.yml` 作为**第一个 `--patch` 层**传给引擎：
     它会禁用内置的 `fs-sandbox` / `subprocess` 行，插入 `hardssh` / `hardssh-fs` /
     `hardssh-subprocess` 三个接缝实现；Android 适配覆盖层随后应用，保持既有行为；
   - 插件缺失、链接失败或用户已用社区插件中心装过同名插件时自动跳过，
     引擎与其它功能照常启动（不会因为内置能力失败而起不来）。

## 升级插件

- **随 APK**：装新 APK 即可（构建默认取 npm 最新版；需要钉死时设仓库 Variable
  `HARDSSH_VERSION`，如 `0.2.13`）。
- **不装新 APK**：从上游 Release 下载 tgz 后执行标准命令（需要联网，pnpm 会解析
  `ssh2`/`ws`）：

  ```bash
  # 下载：https://github.com/XiaoPb/dsh-hardssh/releases
  dsh plugin --profile web add /sdcard/Download/xiaopb-dsh-hardssh-0.2.13.tgz
  ```

- 本仓库 Release **不发布** hardssh 包；也不提供 zip。

## 代理下载（可选）

GitHub 直连慢时可用前缀式代理：

```bash
https://gh-proxy.org/https://github.com/XiaoPb/dsh-hardssh/releases/download/v0.2.13/xiaopb-dsh-hardssh-0.2.13.tgz
```

下载后应校验 npm `dist.integrity`（`scripts/pack-hardssh.sh` 构建时已强制校验）。

## 构建门禁与启动自愈（设计方案，尚未实现）

### 背景：v1.4.57 真机"插件没起来"的根因

实测日志（Redmi K70 / app.dsh.mobile 1.4.57）：

```text
HardsshInstaller: no bundled hardssh assets (older APK); skip
EngineConfig: bundled ssh plugin missing at .../engine/lib/node_modules/@xiaopb/dsh-hardssh; ssh workspace disabled
```

不是代码回归，而是**设计使然 + 构建输入缺失**：

- `511f933`（2026-10-24）hardssh 首次内置，方式是**注入 runtime.zip**；
- `a518c9c`（2026-10-25，v1.4.20）**主动解耦**：runtime.zip 不再含插件（云端单独更新/重装
  会清 `engine/lib`，插件在里面会被带走），改由 `assets/hardssh/` 随 APK 分发；
  CI 同时加反向门禁——runtime.zip 里再出现 `@xiaopb/dsh-hardssh` 直接失败。
- 于是 APK 是否带插件完全取决于构建输入：`-PhardsshDir`（CI 的 `build-hardssh` job 必传，
  本地默认不传），legacy 兜底目录 `src/main/assets/hardssh` 从未入库。`dist/` 在
  `.gitignore` 内，bundle 是构建期产物，漏跑一次就一直是空。
- 实证：本地最后一个带 `assets/hardssh` 的包是 **v1.4.38**（215 条），之后的 1.4.5x 全为 0 条；
  且缺 bundle 时只有一行 `logger.warn`，构建照常成功——静默降级。

### 目标 / 非目标

目标：

1. 漏打必须**显性失败**，不再"warn 一句照样出包"；
2. 即使漏打，装机联网后能**自愈**，用户不感知；
3. 离线首启仍可用。

非目标：

- 不取消 APK 内置（离线与可控版本仍以它为准）；
- 不做端上依赖解析（不自己拼 `ssh2`/`ws` 的 tarball，交给 pnpm）。

### 三层设计

**L1 - 构建门禁**（`app/build.gradle.kts`，`prepareRuntimeAssets`）

- release 构建（判定沿用 `-PrequireReleaseSigning=true`）缺 bundle 时 `throw GradleException`，
  与 CI 的 `test -f hardssh-dist/hardssh/lib/index.js` 对齐；debug 保持 warn 以免打断迭代。
- 取源顺序追加一条：`-PhardsshDir` → `dist/hardssh` → `src/main/assets/hardssh`。
  这样本地只要 `scripts/pack-hardssh.sh dist` 跑过一次，之后 `assembleRelease` 自动带上，
  不用每次记着传参。
- 把 `hardssh-bundle.json` 的 `version` 写进 `BuildConfig`（或 ApplicationInfo meta），
  供 L3 比较"内置版本 vs registry 版本"。

**L2 - 内置兜底**（现状，不改）

`HardsshInstaller.ensureInstalled()`：`assets/hardssh` → `engine/lib/node_modules`，
版本戳幂等，runtime 重装后下次启动自动补回。

**L3 - 启动自愈**（`EngineSupervisor` 现有 boot step `hardssh-install`）

- **触发条件**（任一）：内置目录缺失/不完整；或"跟随 npm"开关打开且 registry 版本 > 内置版本。
- **通道**：复用插件中心已跑通的链路 `PluginCenter.install(ctx, "@xiaopb/dsh-hardssh@^0.2")`
  → 引擎进程执行 `node bin.js plugin --profile web add <spec>` → pnpm
  （npmmirror 镜像、store 锁自愈、`ignored-builds` 白名单、引擎兼容性预检与拒绝原因解析全部继承，
  不新增第二套安装器）。
- **落点**：`profiles/web/node_modules` + profile dependencies。装完后
  `EngineConfig.ensureBundledSshPlugin()` 的 `profileManagesSshPlugin()` 自动为 true，
  内置 patch 层主动让位，**不需要新增任何加载逻辑**。
- **时机**：后台线程，不阻塞 spawn（该 CLI 最长 120s，冷启动还要拉 pnpm 本体）。
  装完只标记"下次启动生效"，或在 UI 空闲时触发一次引擎重启。
- **幂等与退避**：prefs 记 `hardssh.bootstrap.lastAttemptAt` / `failCount`，
  连续失败按 1h → 6h → 24h 退避，成功后清零。
- **用户意志优先**：用户在插件中心**手动卸载**过就写 `hardssh.bootstrap.userRemoved=true`，
  不再自动补；设置页提供"恢复内置 SSH"清除该标记。
- **版本钉**：常量 `HARDSSH_BOOTSTRAP_RANGE = "^0.2"`，只在主版本内自动跟随修复，
  大版本升级仍需发 APK 确认。

### 风险与对策

| 风险 | 对策 |
| --- | --- |
| 供应链：npm 账号被劫持 → 插件在引擎内拥有 fs/subprocess 权限 | 钉 `^0.2` 不跟 latest；只在主版本内自动升级；设置页保留"仅内置、不联网升级"开关 |
| 首启无网仍无 SSH | 保留 L2 内置；自愈失败只记日志、不弹窗、不阻塞 |
| 覆盖用户选择（卸了又装回来） | `userRemoved` 标记 + "恢复内置 SSH"按钮 |
| 每次启动重复尝试 | 退避 + 失败计数 |
| 内置层与 profile 层重复插入 | 已由 `profileManagesSshPlugin()` 的让位机制覆盖，无需改动 |
| pnpm 冷启动慢 | 只在缺失/落后时触发一次，异步执行 |

### 验收标准

1. 故意移走 `assets/hardssh` 构建：release 构建直接失败并给出修复提示（含 `pack-hardssh.sh` 命令）；
2. 手工删掉插件目录 + 联网冷启：日志出现安装成功，下一次启动落到
   `bundled ssh plugin enabled` 或 `managed by the web profile bundle`；
3. 断网冷启：不阻塞、不报错，无 SSH 但引擎正常；
4. 连续失败 3 次后 24h 内不再尝试；
5. 插件中心手动卸载后冷启：不被自动装回。

### 实施步骤（未开始）

1. `app/build.gradle.kts`：L1 取源顺序 + release fail-fast + 版本写入 BuildConfig；
2. `HardsshInstaller.kt`：拆出 `needsBootstrap()`，新增 `HardsshBootstrap.kt`（自愈调度与退避状态）；
3. `EngineSupervisor.kt`：boot step 接入异步自愈；
4. 设置页：开关 + "恢复内置 SSH"；
5. 探针：`_test_hardssh_bootstrap_*`（退避、userRemoved、版本比较、缺包 fail-fast）。

## 本地目录选择（Android）

Android 没有桌面端的系统文件夹选择框，DSH 内核固定使用 **browse** 后端：
「添加工作区 → 本地工作区」会打开应用内目录浏览器，可以输入绝对路径，或点
「内部存储 / Documents / 应用数据」快捷入口。

> dsh-hardssh 0.2.8 起，本地分支会自动探测内核能力：只有 `browse` 时走应用内
> 浏览器，只有 `native` 时仍弹系统选择框。此前版本会直接调用 native picker，
> 在 Android 上报 `directory picker failed: directoryPicker.pick needs the
> native capability; the composed picker serves "browse"`。

> dsh-hardssh 0.2.10 起，浏览器界面按 DSH 设计语言重绘（路径输入组、带分类图标的
> 快捷入口胶囊、面包屑、目录行线性图标、固定底栏），并在底栏提供**新建文件夹**：
> 在当前列出的目录中命名创建，成功后自动刷新并高亮新行；重名/权限失败会在弹窗内
> 显示宿主错误。实现走内核 directory picker 的 `createDirectory`，内核缺少该能力时
> 按钮置灰。

如果读取 `/storage/emulated/0/...` 报权限错误，到「设置 → 权限中心」授予
**所有文件访问**；或改选「应用数据」目录（始终可读写）。

## SSH 私钥（Android）

### 推荐：在主机表单里导入或生成

1. 左侧栏「SSH 工作区」→ 新增/编辑服务器 → 认证方式选「密钥」；
2. 点 **导入私钥**：从系统文件选择器选 `id_ed25519` / `.pem`，插件会把私钥
   写入应用私有目录并自动填好路径：

   ```text
   /data/user/0/app.dsh.mobile/files/dsh-home/.ssh/<文件名>
   ```

   目录 0700、文件 0600，文件管理器看不到，也不会进共享存储。

3. 或点 **生成新密钥**：生成 Ed25519 密钥对，私钥写入同一目录，界面会显示
   **公钥**。复制整行追加到服务器的 `~/.ssh/authorized_keys`：

   ```bash
   echo 'ssh-ed25519 AAAA... dsh-hardssh' >> ~/.ssh/authorized_keys
   chmod 600 ~/.ssh/authorized_keys
   ```

### 手动放置（可选）

| 位置 | 路径示例 | 说明 |
|---|---|---|
| 私有目录 | `/data/user/0/app.dsh.mobile/files/dsh-home/.ssh/id_ed25519` | 推荐；普通文件管理器不可见，建议用“导入” |
| 公开数据目录 | `/storage/emulated/0/Documents/dshdata/ssh/id_ed25519` | App 会自动创建 `ssh/`；需要「所有文件访问」；卸载重装仍保留 |
| 应用外部目录 | `/storage/emulated/0/Android/data/app.dsh.mobile/files/dshdata/ssh/id_ed25519` | 未授予「所有文件访问」时的回退位置；Android 11+ 文件管理器可能也受限 |
| 不建议 | `/storage/emulated/0/Download/...` | 可能读不到，且共享存储安全性差 |

- 表单里的「私钥路径」填**绝对路径**；
- 放在上述任一默认搜索目录时，新建主机可自动预填/留空使用默认密钥；
- Android **没有 ssh-agent**，留空只对“默认密钥文件”有效，不会走 agent 代理。

## 安全边界

- SSH 主机与凭据存于应用私有目录（`$DSH_HOME/dsh-ssh.json`，0600）；默认不落盘密码，
  `secretStorage: vault` 时才加密保存。
- 插件的 REST 路由（`/api/dsh-ssh/*`、`/api/dsh-hardssh/*`）仅限回环并做同源校验。
- 引擎只监听 `127.0.0.1`；远程访问能力来自**出站** SSH 连接，不会把手机变成公开入口。
- 远端路径收敛在绑定的 `remoteRoot` 内；相对路径禁止 `..`，符号链接逃逸按错误拒绝。

## 排障

- 设置 → 自检：若出现 SSH 相关告警，先确认插件已安装到
  `files/engine/lib/node_modules/@xiaopb/dsh-hardssh/package.json`（APK 内置副本在
  `assets/hardssh/`，启动时由 `HardsshInstaller` 自动补装；日志 tag 为
  `HardsshInstaller`）。
- `engine.log` 里搜 `hardssh`：加载失败会以
  `warning: N entries did not activate` 的形式列出具体行（不会阻断引擎启动）。
- 插件未启用时，左侧栏不会出现「SSH 工作区」入口，右侧栏也没有「SSH」标签。
- 旧 WebView（< Chrome 85）无法运行引擎前端本身；插件端同样受此限制。

## 已知限制

- **SSH 的可选原生加速件没有打进包**：ssh2 有两个可选原生组件 —— `cpu-features`
  （只用于挑选默认 cipher 列表，不影响功能）和 `sshcrypto.node`（AES-GCM /
  ChaCha20 的 C++ 批处理实现）。打包时用 `--omit=optional --ignore-scripts`
  排除了它们，原因有两个：CI 跑在 Linux x86_64 上，直接装进来的是**宿主架构**的
  `.node`，在 arm64 Android 上 dlopen 必失败；而运行时又删掉了 Node 头文件
  （`include/`），开箱状态下无法用 node-gyp 在手机上重编。
- **没有这两个组件时 SSH 仍然可用**：ssh2 会自动回退到 Node 内置的 `crypto`
  （OpenSSL 的 C++ 实现）做 AES-GCM / ChaCha20，纯 JS 的只是 SSH 协议分帧。
  也就是说不是「纯 JS 加解密」，而是少了 ssh2 的 C++ 批处理加速层，吞吐通常
  略低于桌面，但一般不是实际瓶颈。
- **运行基座能装什么**：Extension Center 可以从 Termux 仓库装 clang / make /
  cmake / python 等工具链扩展；但这些工具链解决的是「编译用户自己的程序」。
  要在设备上重建 ssh2 的原生 binding，还缺与 Termux Node 完全匹配的头文件和
  bionic 交叉编译支持，属于上游不保证的路径 —— 需要极致吞吐时，正确做法是在
  CI 里按 Android ABI 交叉编译后随 runtime 发布，而不是在手机上现编。
- 大文件传输建议走 `ssh_upload` / `ssh_download` 的流式 SFTP，或在远端用
  `ssh_exec` 跑 `tar` / `rsync` / `scp`，让压缩与复制发生在服务器侧。
- WebView 版本要求与主界面一致（Chrome 85+；官方建议更高）。
- SSH 连接消耗真实远端资源：大范围搜索、批量命令、`ssh_cluster` 会并发打到服务器上。
