# 内置「用户态插件」预装机制

> 从 v1.5.0 起，APK 里随包分发的一类 feature 插件（首个是手机竖屏布局
> `@xiaopb/dsh-mobile-layout`）不再塞进引擎的 patch 层，而是**装进 profile、
> 交给用户在插件页自己管理**。本文记录为什么这么改、改了哪些地方、影响了什么，
> 以及以后再加一个同类插件要怎么做。
>
> 相关代码：`app/src/main/java/app/dsh/mobile/engine/ProfilePluginProvisioner.kt`
> 　相关脚本：`scripts/pack-mobile-layout.sh`
> 　相关探针：`scripts/probes/_test_builtin_plugins.py`

---

## 1. 背景：内置插件为什么关不掉

引擎的配置是**分层合成**的，优先级从低到高：

```
dsh.profile.bundles（各 bundle 自带 cordis.patch.yml）
  → profile 自己的 cordis.patch.yml（用户层，插件页在这里增删）
  → --patch overlay（android-overlay.yml，App 每次启动重写，最后应用）
  → telemetry 开关
```

`@xiaopb/dsh-mobile-layout` 以前挂在**最外层**的 overlay insert 上：

```yaml
- insert:
    - id: android-mobile-layout
      name: ./android-plugins/mobile-layout/index.js
```

这带来两个结构性后果：

1. **永远开着**。overlay 是最外层，用户在插件页里的任何禁用都会被它盖掉；
2. **插件页看不到它**。`dsh.profile.bundles` 是插件页列表的唯一依据，overlay
   里的 insert 不写这个数组，所以它根本不在「已安装插件」里。

于是 issue #81 撞上了这堵墙：用户不想要底部固定菜单，而 App 侧没有任何
一条通道能把它关掉 —— 连「应急总开关」（停用第三方插件）都覆盖不到它，
因为那个开关清的是 profile bundles。

## 2. 方案：把「装」和「管」交给用户态

引擎原生的「用户态插件」语义只有一条路径 —— **profile bundle**：
npm 包在 `package.json` 里声明

```json
"dsh": { "bundle": { "patch": "./cordis.patch.yml" } }
```

引擎启动时（`dsh-app-boot` 的 `bundlePatchFiles()`）为 `dsh.profile.bundles`
里的每个包读取它自己的 patch 文件挂载。插件页的列表 = 这个数组，开关 = 往这个
数组里增删，卸载 = 走 pnpm 移除依赖。

所以新链路是：

```
CI 打包 tgz ──▶ APK assets/mobile-layout/*.tgz + bundle.json
                        │
                        ▼ 首启（引擎未启动）
            ProfilePluginProvisioner.ensureAll()
                        │  解到 filesDir/plugin-staging/ → sha256 校验
                        │  dsh plugin --profile web add <tgz>   （官方 CLI，离线）
                        │  把 file: 依赖改回版本号
                        │  按开关决定是否写 dsh.profile.bundles
                        ▼
            profiles/web/{package.json, node_modules}
                        │
                        ▼ 之后
            插件页接管：开关 / 升级 / 卸载，与任何第三方插件一样
```

**为什么是「解压到私有目录 + CLI 安装」而不是「直接把插件文件塞进引擎目录」**：
pnpm 需要一条完整的记账（lockfile / store / node_modules 布局），手工铺文件
会让后续任何一次 `pnpm add` 与磁盘状态打架。走 CLI 装一次，之后升级卸载
都走同一条路，App 不需要维护第二套真相。

## 3. 改动点

### 3.1 新增 `ProfilePluginProvisioner`（注册表驱动的预装器）

一个对象管所有「内置但归用户管」的插件，注册表只有一行数据：

```kotlin
private val REGISTRY = listOf(
    BundledProfilePlugin(
        pkg = MOBILE_LAYOUT_PKG,          // "@xiaopb/dsh-mobile-layout"
        assetRoot = "mobile-layout",      // assets/mobile-layout/
        enabledOnFirstRun = { MainActivity.officialMobileUiEnabled(it) },
    ),
)
```

生命周期是这套机制的**核心约定**（用户 2026-10-31 定调）：

| 时机 | 行为 |
|---|---|
| 首次安装 App | 没装就装；装完按 `enabledOnFirstRun` 决定挂不挂 bundles；已装则只记标记、不动状态 |
| 之后每次启动 | **一个字节都不动**（不重装、不重新启用、不覆盖版本） |
| 用户拨设置里的总开关 | 唯一的例外：记一条待办，下次启动落地 |

「首次安装」的边界用 `SharedPreferences("dsh_profile_plugins")` 的布尔标记
（卸载 App 即清），**不是** dsh-home 里的文件 —— 后者跨卸载存活，重装 App
会被当成「已预装过」而永远不装。

### 3.2 `PluginCenter.addLocalTarball()`：阻塞式本地 tgz 安装

预装发生在 boot 序列、引擎还没起来，调用方必须拿到结果才能决定是否写标记，
所以是 `Future.get(180s)` 的阻塞版本。但它**仍然复用同一条 `installExecutor`**，
与用户手动安装/卸载串行 —— 并发 pnpm 操作同一个 profile 会损坏
`node_modules` 与 store 状态。

### 3.3 两个真机踩出来的坑（已修）

**坑 1：依赖被记成悬空绝对路径。**
`dsh plugin add <本地 tgz>` 会把依赖写成

```json
"@xiaopb/dsh-mobile-layout": "file:/data/user/0/app.dsh.mobile/files/plugin-staging/xxx-staged.tgz"
```

而暂存文件装完即删。用户之后**安装或更新任何插件**时 pnpm 解析依赖都会失败，
报「找不到那个 tgz」。→ 装完立刻把 `file:` 改回纯版本号（`normalizeDependencySpec`，
仅当当前值确实是 `file:` 时改写，用户自己用 registry 装的记录原样保留）。

**坑 2：CLI 对「依赖已满足」是 no-op，不补挂载项。**
`dsh plugin add <tgz>` 在 `dependencies` 里已有同版本时什么都不做 —— 而「用户
关掉插件」恰好就是这个形态（依赖在、bundles 被摘）。所以首装路径必须自己
补 `dsh.profile.bundles`（`ensureBundleEntry`），否则「默认开启」落空。

### 3.4 `EngineConfig`：删掉旧的资产部署与 overlay 挂载

净删约 155 行：overlay 的 insert 段、部署清单 `"mobile-layout" to listOf(...)`、
版本比对与自动 npm 更新（`MOBILE_LAYOUT_NPM` / `mobileLayoutNeedsAssetsDeploy` /
`readDeployedVersion` / `readBundledVersion` / `scheduleMobileLayoutNpmUpdate` /
`flattenPackageJsonBytes` / `jsonFromUrl` / `extractTarEntries`）。
**版本升级从此不需要 App 侧代码**：插件的更新由上游发版 + 插件页「更新」完成。

同时保留一次性清理：升级后把旧版本铺在引擎目录里的
`android-plugins/mobile-layout/` 删掉，避免残留文件被误当资产。

### 3.5 设置页总开关接线（v1.4.67 的两套竖屏方案）

上游 v1.4.67 把竖屏方案做成二选一（设置 → 外观 →「官方推荐手机 UI」）：
开 = 社区 mobile-layout，关（默认）= 仓库自制的 narrow-* 三件套，靠 overlay
把另一方标 `disabled` 实现互斥。mobile-layout 挪出 overlay 后，那个开关的
「开」这一侧就没有落点了 —— 会变成「三件套关掉、但什么布局都没上」的半残状态。

接线方式：

- `enabledOnFirstRun` 读同一个开关值（首装时决定挂不挂载）；
- 拨开关时 `ProfilePluginProvisioner.requestEnabled()` 记一条待办，由下次启动
  的 `applyPending()` 落地（引擎在跑时改 `package.json` 会被退出时的内存态
  写回吞掉）；
- overlay 侧只剩 narrow-* 三件套那一个占位符（`OO_NARROW_DISABLED_OO`），
  失效的 `OO_OFFICIAL_DISABLED_OO` 已删。

两者不打架的兜底：narrow-sidebar / narrow-settings 自带让位判断 ——
`document.documentElement` 上有 `data-dshm-mode` 就直接 return。所以即使
两个都开着（用户在插件页手动打开，而总开关仍是「关」），mobile-layout 也能
正常接管，不会出现两套样式互相抢。

### 3.6 CI 与构建脚本

- `scripts/pack-mobile-layout.sh`：`npm view` 解析版本 → 优先 GitHub Release
  的 tgz（sha512 校验），回退 npm tarball → **bundle 契约自检**（包内
  `cordis.patch.yml` 存在、`dsh.bundle.patch` 指向它、包内版本 == 解析版本、
  `lib/index.js` + `lib/client.js` 存在）→ 产出 `<tgz>` + `bundle.json{version,tgz,sha256}`
- `.github/workflows/android-build.yml`：新增 job `pack-mobile-layout`
  （12 秒），artifact `mobile-layout-bundle`；`build-apk` 下载它到
  `mobile-layout-dist/`，做两行存在性断言，再用 `-PmobileLayoutTgzDir=` 传给 gradle。
- `app/build.gradle.kts`：资产取源优先级
  `-PmobileLayoutTgzDir` > `local-overrides/mobile-layout/` > `src/main/assets/mobile-layout/`；
  校验 `bundle.json` + 至少一个 `.tgz`，缺失只 `warn` 不阻断（老分支/裁剪构建仍能出包）。

### 3.7 删除的内置散文件

```
app/src/main/assets/android-plugins/mobile-layout/{package.json,index.js,client.js}
```

`lib/client.js` 单文件 5542 行、且与上游插件仓库存在视觉分支漂移 —— 内置副本
本身就是一个维护负担。改为 tgz 之后，**唯一真相回到插件仓库**，App 只管搬运。

## 4. 影响点

### 4.1 用户可见行为

| 场景 | 改前 | 改后 |
|---|---|---|
| 首次安装（总开关默认关） | 竖屏直接是 mobile-layout（底部固定菜单） | narrow-* 轻量修正；mobile-layout **已装但不启用**，插件页里可一键打开 |
| 关掉竖屏布局 | 做不到（只能卸载 App 回退版本） | 插件页关掉，或外观页总开关拨到「关」 |
| 想用 mobile-layout | 默认就是 | 总开关打开，或插件页打开，重启引擎生效 |
| 升级插件 | 等 App 发版（内置副本随 APK） | 插件页「更新」，或等上游发版后由 App 内置新 tgz（仅首装场景） |
| 卸载插件 | 做不到 | 插件页卸载（真删 node_modules） |
| 应急总开关 | 覆盖不到它 | **覆盖到了**（它就是普通第三方插件） |

### 4.2 首次启动成本

首装那一次 boot 多一步 `dsh plugin add`（本地 tgz，实测约 1~3 秒，无网络），
后续启动零成本（读到已预装标记直接返回）。失败只记日志、下次启动重试，
绝不让引擎起不来。

### 4.3 与既有机制的关系

- **应急总开关 / 插件自愈**：`PluginKillSwitch.isRestoring()` 为真时预装器整段跳过
  —— 恢复流程正在重建 `node_modules`，此时插一脚会与它抢同一个 profile。
- **profile 写回时序**：所有对 `package.json` 的写入都发生在 `spawnEngine`
  之前（引擎停止态）。这是硬约束：引擎退出时会把内存态 profile 写回磁盘。
- **版本号**：仍按既有约定跟随上游，本地不 bump。APK 内置的 tgz 版本由 CI 的
  `MOBILE_LAYOUT_VERSION`（默认 `latest`）决定，需要钉死时在仓库 Variables 设。

### 4.4 兼容与回退

- 从旧版本升级上来：`dsh-home` 里的 profile 若已含该插件（旧版是 overlay 挂的，
  不会在 profile 里），预装器会走「已装则只记标记」分支，不动用户状态；
  铺在引擎目录的旧散文件由 `EngineConfig` 一次性清理。
- 回退到旧 APK：旧版本会把 mobile-layout 重新按 overlay 挂上，profile 里多出来的
  这条依赖无害（overlay 优先级更高，样式仍是旧的）。不会出现双份加载。

### 4.5 风险与对策

| 风险 | 对策 |
|---|---|
| tgz 损坏 / 半下载 | `bundle.json` 里的 sha256 校验，不匹配直接放弃本次安装 |
| CLI 行为变化（依赖记账） | 探针锁住「`file:` 必须改回版本号」这一契约 |
| 用户在插件页关掉后 APK 升级又被装回来 | 首装标记 + 标记存在即整段跳过；探针锁住该分支 |
| 设置开关与插件页状态打架 | 只有用户拨总开关才写待办；插件页的手动操作永远优先，不被动过 |
| 首装失败导致「没有布局」 | 失败不写标记 → 下次启动重试；同时窄屏仍有 narrow-* 三件套兜底 |

## 5. 以后新增一个预装插件（照做这 6 步）

以 `<pkg>` = 插件 npm 包名、`<key>` = 资产目录名（建议短横线小写）为例：

**① 上游仓库要满足 bundle 契约**

```jsonc
// package.json
"dsh": { "bundle": { "patch": "./cordis.patch.yml" } }
```

tgz 里必须带 `cordis.patch.yml`、`lib/index.js`、`lib/client.js`，且
**不要**把自己的依赖做成裸 `import('包名')`（必须走宿主注入，否则用户态加载会失败）。

**② 写一个打包脚本**（照抄 `scripts/pack-mobile-layout.sh`，改 3 处常量）

```
PLUGIN_NAME=@xiaopb/dsh-xxx
PLUGIN_SLUG=dsh-xxx
ASSET_KEY=xxx
```

脚本要保持三个行为：`npm view` 解析版本、优先 Release tgz + sha512 校验、
产出 `bundle.json{version,tgz,sha256}`。**契约自检那几行不要删** —— 它拦的是
「上游忘了带 cordis.patch.yml」这类只能靠真机才能发现的错。

**③ CI 加一个 pack job + 接进 build-apk**

```yaml
env:
  XXX_VERSION: latest          # 需要钉死就在仓库 Variables 覆盖

jobs:
  pack-xxx:
    # 与 pack-mobile-layout 同构：setup-node → 跑脚本 → upload-artifact

  build-apk:
    needs: [pack-xxx, ...]
    steps:
      - uses: actions/download-artifact@v4
        with: { name: xxx-bundle, path: xxx-dist }
      - run: test -f xxx-dist/bundle.json
      - run: gradle ... -PxxxTgzDir="$PWD/xxx-dist"
```

**④ gradle 加一个 `-P` 参数**

在 `app/build.gradle.kts` 里照 `mobileLayoutTgzDir` 那段加一份：优先级
`-P` > `local-overrides/<key>/` > `src/main/assets/<key>/`，产出到
`assets/<key>/`，缺失只 warn。

**⑤ 注册表加一行**

```kotlin
BundledProfilePlugin(
    pkg = "PUBLISHER_PKG_NAME",
    assetRoot = "xxx",
    enabledOnFirstRun = { true },     // 或按某个设置开关决定
)
```

App 侧不需要写第二个安装器、第二个版本管理、第二个失败重试 —— 都在这一层。

**⑥ 探针补断言**

`scripts/probes/_test_builtin_plugins.py` 里加：
注册表有条目、资产目录名与约定一致、gradle 参数存在、本地覆盖位存在、
tgz 内 bundle 契约成立。跑一遍 `python scripts/probes/_test_builtin_plugins.py`。

### 本地离线编译测试

不想等 CI 时，把自建 tgz 丢进（该目录已 gitignore，不入库）：

```
local-overrides/<key>/
├── bundle.json
└── <pkg>-<version>.tgz
```

然后 `gradle assembleDebug -Ppackage=noruntime -Pabi=x86_64` 即可，无需任何
`-P` 参数 —— gradle 会自动优先取 `local-overrides/`。也可以显式传
`-PmobileLayoutTgzDir=<绝对路径>` 覆盖。

## 6. 验证记录

| 项 | 方式 | 结果 |
|---|---|---|
| 探针 | `_test_builtin_plugins.py` | 66 / 66 通过（A 类宿主兼容层 4 个插件 + B 类用户态装配链路） |
| CI 打包 | run `41107025069` 的 `pack-mobile-layout` | 12s，解析到 0.2.10，sha256 校验通过，artifact 89 607 B |
| CI 构建 | 同 run 4 条 build-apk | 全绿，日志 `mobile-layout: embedded …` |
| APK 资产形态 | `unzip -l` | 只有 `assets/mobile-layout/{bundle.json, *.tgz}`，`android-plugins/mobile-layout/` 已消失 |
| 首装 | MuMu（Android 12 / x86_64）卸载重装 CI release 包 | `installed 0.2.10 on first run`；依赖记为 `0.2.10`；`plugin-staging/` 已清空 |
| 用户关闭后重启 | 手摘 bundles 项后重启 | `already provisioned …; leaving state untouched`，bundles 未被改回 |
| 重新打开 | 恢复 bundles 项后重启 | 底部 56px 操作条（新建/会话/文件/图片/插件/更多）恢复 |

## 7. 已知边界（不在本次范围）

- 插件自身在 MuMu 上仍报 `Uncaught TypeError: html.removeProperty is not a function`
  （上游已在更高版本修），属插件侧问题。
- `hardssh` **没有**一起搬到用户态：它的 npm tgz 不含 `node_modules`
  （`ssh2 / ws / @xterm/*` 由 `pack-hardssh.sh` 单独 `npm install --omit=optional`
  组装），离线 pnpm 安装不成立。统一到此层的前提是先解决它的依赖打包形态，
  而不是改预装器。
- 插件页的「更新」走 registry，需要网络；APK 内置的 tgz 只在首装时用到，
  不会在用户升级后把版本往回带。
