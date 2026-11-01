# 运行时换装：意图日志与恢复

> 适用版本：v1.4.26 起。对应 issue #49（PR #48 审查 P1-2 的跟进）。
> 代码位置：`app/src/main/java/app/dsh/mobile/engine/RuntimeInstaller.kt`。

## 1. 换装到底做了什么

`engine/` 目录里装着整套 Node 运行时（约 580MB，两万多个文件）。更新运行时
不能「先删再解压」—— 那会留下一个「删完就没」的窗口。所以流程是：

1. 把新运行时解压到 `engine.staging`（`engine/` 全程不动）；
2. 校验 staging 完整性（`isRootCompleteAt`：锚点文件 + node 可执行位 + ELF 架构）；
3. **逐个顶层条目**换名：`engine/<name>` → `engine.old/<name>`，`engine.staging/<name>` → `engine/<name>`；
4. 写版本戳 `.runtime.json` / `.runtime-version`；
5. 保留 `engine.old`，直到引擎首次进入 Healthy。

第 3 步是唯一会破坏现有运行时的环节。典型顶层条目是 `bin`、`lib`、`usr`、
`share`、`etc` 这些 —— 一次换装通常只需要换 5–10 次名字，但**任何一次**
之后被杀，磁盘上就是新旧混合态。

## 2. 为什么必须用「意图日志」

v1.4.25 及之前，盘上唯一的进度证据是 `.pending-health`：

- 它在换装**开始之前**被删（旧 `install()` 的 `pendingMarker().delete()`）；
- 在换装**全部完成之后**才写（`pendingMarker().writeText("0")`）。

中间整段没有任何证据。进程在中间被强杀（低内存、用户强杀、OEM 清后台）后，
下次启动 `recoverPendingHealth()` 在「标记不存在」处直接返回，混合态无人处理。

更麻烦的是：混合态**不一定**被完整性探针判为不完整。`isRootCompleteAt()` 只查
「锚点文件在不在 + node 架构对不对」，**新 `bin/` 配旧 `lib/` 完全通得过**。所以
「反正 incomplete 会重装」这条兜底是不可靠的 —— 最坏情况是带着错配的运行时启动。

因此 v1.4.26 引入 `filesDir/.runtime-swap`：一份**换装意图**，在动 `engine/`
之前落盘，恢复判定只看它，不再依赖完整性探针。

## 3. 状态机

```
                      ┌──── 异常 / 启动恢复 ────→ 回滚 ──→ 无日志
                      │
[无日志] ──换装开始──> SWAP ──条目全部换完──> COMMITTED ──收尾完成──> [无日志] + .pending-health
```

日志是一行 JSON（原子写入：`tmp` → `fsync` → `rename`）：

```json
{"phase":"swap","planned":["bin","lib","usr"],"old":["bin","lib"],
 "version":"1.4.26","builtAt":1759...,"bundled":false,
 "sha256":"…","at":1759...}
```

- `planned`：本次要替换的顶层条目（= staging 的顶层条目）；
- `old`：`planned` 中**换装前确实存在于 root** 的子集 —— 只有这些才可能被搬进
  `engine.old`，回滚也只处理这些；
- `version` / `builtAt` / `bundled` / `sha256`：`committed` 相位下用于补写
  `.runtime.json`，与正常安装路径写出的内容保持一致（否则前滚后的元数据会缺
  sha256，诊断时看起来像「来路不明」的运行时）。

### 关键顺序（不要改）

| 顺序 | 动作 | 被杀在这里会怎样 |
|---|---|---|
| 1 | 清 staging / 清旧 backup / 删 `.pending-health` | root 完好，走「完整即复用」 |
| 2 | 解压 + 校验 staging | 同上；staging 由下次启动清理 |
| 3 | **写 `phase=swap`** | root 完好（一个字节都没动） |
| 4 | 逐条换名（rename，失败即中止） | 混合态 → 下次启动回滚 |
| 5 | **写 `phase=committed`** | 下次启动前滚，不白扔新运行时 |
| 6 | 写版本戳、删 staging、删日志、写 `.pending-health` | committed 前滚补齐 |

第 3 步与第 5 步就是「两阶段提交」的两个提交点。第 4 步里 rename 失败**直接抛**
（不再退化成 `copyRecursively`）：三个目录同在 `filesDir`（同一文件系统），
`rename(2)` 本来就是原子的；退回拷贝只会把原子切换降级成「拷到一半」，
正是恢复窗口被拉长的原因。

## 4. 启动恢复

`ensureInstalledLocked()` 开头按固定顺序跑三件事：

```
recoverInterruptedSwap()   // ① 换装被杀在半路（本文件）
recoverPendingHealth()     // ② 换装成功但引擎从未 Healthy
cleanupInstallResidue()    // ③ 清 staging / zip 缓存残留
```

① 的决策表：

| `phase` | `engine.old` | `isRootComplete()` | 动作 |
|---|---|---|---|
| swap | 空 | 任意 | 回滚（无旧条目可还原，等价于「什么都没发生」） |
| swap | 非空 | 任意 | 回滚 |
| committed | 非空 | true | 前滚：补写版本戳、清 staging、转 `.pending-health` |
| committed | 非空 | false | 回滚 |
| committed | 空 | true | 前滚收尾 |

回滚规则只有两条：

```
for n in planned where n !in old:  engine/n.deleteRecursively()   // 新独有条目
for n in old: if (engine.old/n exists) { engine/n.deleteRecursively(); 搬回 }
```

第二条的 `if exists` 是**幂等性**的来源：`old` 里的条目若备份目录里没有，只可能
表示「还没搬走过」或者「已经还原过」——两种情形都该保持 `engine/` 不动。绝不能
反过来按「备份里没有就当成新条目删掉」，那样重入一次就会把刚还原好的旧运行时删了
（回滚本身也可能在同一处被杀）。

### 回滚之后会发生什么

回滚把磁盘恢复到「旧运行时 + 一致的元数据」，然后交回 `ensureInstalled()`：

- **完整包（full）**：版本戳是旧的、MANIFEST 是新的 → 版本不符 → **本地重装**
  （不下载）。等于自动重试那次被打断的升级。
- **精简包（noruntime）**：按既有策略「已装好就复用」保持旧版本可用。
  用户下次在「关于 → 运行时」点更新再装 —— 而不是在启动遮罩里被动重下 100MB+。

## 5. `.pending-health` 的语义（别改错）

换装提交成功且**存在旧备份**时写 `.pending-health`（内容为尝试次数）：

1. 第 1 次启动：`attempts=1`，运行时完整 → 保留新运行时，再给一次机会；
2. 第 2 次启动仍不完整或仍不 Healthy：`attempts=2` → 回滚 `engine.old`；
3. 期间引擎一旦进入 **Healthy 或 SafeMode** → `markHealthy()` 删掉备份与标记。

⚠️ **SafeMode 也算确认**。这是有意的：`SafeMode` 的含义是「引擎 HTTP 通了、
只是插件被降级」，运行时本身没问题就不该回滚 —— 插件故障归 `ProfileGuardian`
管。改动这里之前请先确认你理解了这条分界。

### 5.1 SafeMode 怎么算「确认」（#49 P2 补充）

上一节说「SafeMode 也算确认」，容易被误读成「SafeMode 就等于运行时没问题」。实际判定链是：

1. `markHealthy()` 由 `EngineSupervisor` 在收到**引擎 HTTP 就绪**信号后调用，不论此时 profile
   处于正常态还是 SafeMode 态 —— 两者都意味着 `<engine>/lib/node_modules/.../bin.js` 已经
   成功 `execve` 并跑完了 host 准备阶段，运行时本体（node 二进制、.so、node_modules 树）
   是可用的；
2. 所以「能进 SafeMode」本身就是对新运行时的**正向证据**：坏的是插件配置，而插件配置
   在 `dsh-home` 里，**不在 `engine/` 里**，回滚运行时不会修好它；
3. 反过来，如果新运行时连 SafeMode 都进不去（node 起不来、缺 .so、node_modules 树断），
   `markHealthy()` 永远不会被调用，两次启动后按第 2 条回滚 —— 这正是我们想要的。

因此判断边界是 **「引擎进程是否成功跑到 HTTP 就绪」**，而不是「插件是否全部加载成功」：

| 现象 | 是否确认新运行时 | 动作 |
|---|---|---|
| 引擎 Healthy、插件全加载 | ✅ | 删 `engine.old` 与标记 |
| 引擎 SafeMode（插件被降级/隔离） | ✅ | 删 `engine.old` 与标记，交给 `ProfileGuardian` |
| 引擎崩溃/超时/端口不通 | ❌ | `attempts+1`，第 2 次仍失败则回滚 |
| 引擎起来了但 boot 卡在 user-data 等阶段 | ❌ | 同上（`markHealthy` 未被调用） |

排查时看 `boot.log` 最后一行是否到达 `ready` 阶段，以及 `engine.log` 里有没有
`ProfileGuardian: SAFE MODE: quarantined bad config surface to <path>`
（`ProfileGuardian.kt:238` 的原文）——有前者且有后者 = 走了 SafeMode 分支，属确认；
只有后者没有前者 = 进程没跑起来，属待回滚。

## 6. 残留清理

解压阶段是最长的窗口（240MB zip 在低端机上 30–60 秒），也最容易撞上低内存。
被杀之后 `engine/` 往往仍然完整 → 下次启动走「复用」直接返回，而
`staging.deleteRecursively()` 只存在于 `install()` 内部。于是：

- `engine.staging`：约 580MB；
- `cacheDir/runtime.zip`：约 124MB；

会**永久**留在盘上。`cleanupInstallResidue()` 在每次启动（无换装日志时）清理它们，
并写一行日志说明释放了多少。设置页「一键自检」也新增了一项
`install.residue` 用于事后诊断（只报告、不删除）。

## 7. 如何复现「换装中途被杀」

这个缺陷无法用单测覆盖 —— 它只在两条指令之间、进程被 SIGKILL 时出现。
所以留了一个**仅 debuggable 包生效**的开关：

```bash
# 换装前先埋好断点（取值：before_swap / after_entry:N / after_commit）
adb shell run-as app.dsh.debug sh -c 'echo after_entry:1 > files/.debug-swap-abort'

# 然后在 App 里触发一次运行时安装，它会在换完第 1 个顶层条目后自杀
adb logcat -s RuntimeInstaller

# 重启 App，观察恢复
adb shell run-as app.dsh.debug ls -la files/ | grep -E 'runtime-swap|engine\.'
```

期望结果：

| 断点 | 期望日志 | 期望磁盘 |
|---|---|---|
| `before_swap` | `interrupted runtime swap: phase=swap backup=false` | 回滚为空操作，`engine/` 完好 |
| `after_entry:1` | `... phase=swap backup=true` → `rolled back from swap log` | `engine.old` / `engine.staging` / 日志全部消失 |
| `after_commit` | `... phase=committed` → `swap was already committed` | 新运行时可用，`engine.old` 转为 pending-health |

复现完记得删掉断点文件：

```bash
adb shell run-as app.dsh.debug rm -f files/.debug-swap-abort
```

## 8. 已知限制（不做）

- **rename 失败即中止**：极端文件系统异常下不再尝试「拷完凑合」。这是刻意取舍 ——
  原子性优先于容错。
- **连续换装会吞掉上一轮的回滚目标**：`install()` 开头会删掉旧的 `engine.old`。
  若上一轮刚装完还没被 Healthy 确认，就又从「关于」页发起一次更新，回滚目标
  会变成「上一轮刚装的那套」。代价是失去更早的最后一版，需要 `engine.old.prev`
  双备份才能彻底解决，成本高于收益。
- **`engine.old` 与 `engine.staging` 都在 `filesDir`**：同卷才有 rename 的原子性。
  不要把其中任何一个挪到外部存储。
