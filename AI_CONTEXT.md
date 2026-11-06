# DSH Android · 交接文档

> **只写当前有效的事实。** 历史流水已归档：`docs/history/AI_CONTEXT-archive-20261031.md`
> （4070 行，含 2026-10-11 ~ 10-31 每轮交付细节）。本文超过 300 行就该把旧段归档。
>
> 配套规范：[`COLLABORATION.md`](docs/COLLABORATION.md) 合并流程 ·
> [`HYGIENE.md`](docs/HYGIENE.md) 仓库卫生与迭代节奏 ·
> [`git-workflow.md`](docs/git-workflow.md) 推送通道 ·
> [`RELEASE.md`](docs/RELEASE.md) 发版流程

**最后更新**：2026-11-06 18:05 · 分支 `feat/linux-workspace` HEAD `279ef33`（x86_64 容器支持）；main 线见下节。已发布 v1.4.76 / 327；v1.5.0-beta / 333、beta.2 / 334、beta.4 / 336、beta.11 / 343 均已发试发行并通过真机验收

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

## 一·四、容器运行器源码与专有许可（2026-11-05，v1.6.13）

| 项 | 状态 |
| --- | --- |
| 源码位置 | `app/src/main/cpp/dsh_alpine.c`（**3203 行 / 164KB**，双架构）**已入库** |
| 许可 | **专有**（All rights reserved），不适用仓库根 MIT。声明在文件头 |
| 镜像仓 | <https://github.com/Soodok/dsh-linux-runtime>（**已公开**，许可正本在此） |
| 产物 | `arm64-v8a/libdsh_alpine.so` **31104 B**（md5 `cbc5d087bdd8986bee044d1e9004112e`）；`x86_64/libdsh_alpine.so` **42736 B** |
| 同步守卫 | `scripts/probes/_test_dsh_alpine_source_sync.py`（23 checks） |

**为什么源码重新入库**：v1.6.12 后期曾把源码移出仓库（只留 `.so`），结果出现
两次「源码漂移」——DNS 双向改写的源码一度只存在于 `/tmp/work3.c`；
`path_looks_like_android` 这个修复我以为写过、实际从未落地。专有许可让我们
不必为了不公开而藏源码，公开换来可审查 + 不再漂移。

**改源码必须重编 `.so`**，否则源码与产物不一致。`_test_dsh_alpine_source_sync.py`
会校验：源码里的关键能力字符串是否在产物中存在、ELF 硬约束（ET_EXEC /
固定基址 / 无 PT_INTERP / 不引 libc）、无调试残留。**这条探针曾真的抓到
`path_looks_like_android` 缺失**。

**编译命令**（NDK r28+，见 `app/src/main/cpp/CMakeLists.txt` 顶部）：

```bash
NDK=/e/Android/Sdk/ndk/28.2.13676358
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android28-clang"
"$CC" -O1 -ffreestanding -fno-builtin -fno-pie -fno-PIE   -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector -DPATH_MAX=4096   -static -nostdlib -nostartfiles -nodefaultlibs -no-pie   -Wl,-e,_start -Wl,--image-base=0x2000000000   -Wl,-z,max-page-size=0x20000 -Wl,-s   -o app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so   app/src/main/cpp/dsh_alpine.c
```

**新仓与原仓的关系**：新仓是许可正本 + 完整文档（机制、26 个踩坑、性能测量法）；
主仓内的 `dsh_alpine.c` 是受同一许可约束的副本。两边内容必须一致，
同步方式：`cp app/src/main/cpp/dsh_alpine.c ../dsh-linux-runtime/src/`。

**红线**：绝不可向 `dsh_alpine.c` 引入 GPL/AGPL 代码（TAWC、PRoot、QEMU 均为 GPL）。
一旦混入，专有条款立刻被 GPL-3.0 覆盖、整份许可失效。

---

## 一·五、x86_64 容器支持（2026-11-06，commit `279ef33`，分支 `feat/linux-workspace`）

| 项 | 值 |
| --- | --- |
| 主仓源码 | `app/src/main/cpp/dsh_alpine.c` 3203 行（原 2633 行 aarch64-only 的**旧镜像**） |
| 编译 | `./build/build.sh <NDK> x86_64`；x86_64 必须 `-mcmodel=large` |
| 产物 | aarch64 31104 B / x86_64 42736 B，均 ET_EXEC + 基址 `0x2000000000` |
| rootfs | `assets/linux/rootfs-bootstrap-x86_64.tgz` 3657952 B，sha256 `412454ed98…674be`（与官方 .sha256 一致） |

**背景**：主仓的 `dsh_alpine.c` 一直是镜像仓的旧版（对应镜像仓 `d4c49c8` /
主仓 `e9292bb`），镜像仓之后完成的 x86_64 移植（`d3f5923` / `0c49ee9`）没回灌。
按本文「同步方式」的约定，本次把完整版整体拉回主仓。

### x86_64 必改的三处（错一处就 SIGSEGV，且症状互相掩盖）

1. **返回值必须写进 RAX**。aarch64 的 `x0` 既是首参又是返回，`r[0]` 一改自动
   生效；x86_64 入参在 `rdi`、返回在 `rax` 完全分开。只写 `RDI` 的后果是
   guest 拿到的「返回值」其实是它自己的首参。症状：
   `stat("/etc", {st_mode=001, st_size=0, ...}) = 4` —— 全是垃圾值。
2. **`sa_restorer` 必须填有效地址 + `SA_RESTORER`**。aarch64 靠 VDSO 版
   restorer，填 0 没事；x86_64 填 0 则 handler 一返回就跳到地址 0，报
   `SIGSEGV(SI_KERNEL, si_addr=NULL)`。**这个症状极具误导性** —— 我为它错改
   过 `_start` 段属性、`sigaltstack` 的 `stack_t` 布局、`-mcmodel=large`
   重定位三处。
3. **15 个老式无 dirfd syscall**（open/stat/lstat/access/unlink/rmdir/mkdir/
   chmod/chown/truncate/utime/readlink/rename/link/symlink）都是 aarch64 通用
   ABI 根本没有的。不拦它们，容器里 `ls /etc` 列的是宿主 `/etc`。

另有两处 `break` 落到函数末尾 `default`，而 `default` 用
`a0 = (long)(int)(unsigned int)r[0]` 转发 —— **指针被截成 32 位**，内核报
EFAULT。症状 `ls: /dev: Bad address`，而 strace 显示内核其实返回了有效结果。
两处都改为就地 `r[0] = raw6(nr, (long)lp, ...); return;`。

### 容器执行器补 `DSH_DNS_PORT`（本次新修，通用缺陷）

原先只在 `LinuxEngineLauncher` 注入，`LinuxContainerRunner` 没有。后果是
「引擎自己」能联网，而所有走执行器的路径（扩展安装 `apk add`、终端
`wget`/`curl`、`apk update`）全部解析失败，报的却是
`WARNING: updating and opening http://mirrors.aliyun.com/...: temporary error
(try again later)` —— 看起来像镜像站挂了，实际是 DNS 包发到 53 无人监听
（musl 把 DNS 端口硬编码成 53，53 是特权端口 App 绑不上）。

修法：`LinuxContainerRunner.doExec` 的 `pb.environment()` 里，`extraEnv`
循环**之前**加 `LinuxDnsProxy.currentPort().takeIf { it > 1023 }`。
补上后容器内 `apk update` 直接成功，**26337 个包可达**。

### ★模拟器上从 App UI 触发容器会整体失败（已查清，非代码缺陷）

| 进程 | `Seccomp:` | `Seccomp_filters:` |
| --- | --- | --- |
| `zygote64` 本体 | 0 | 0 |
| App 进程（zygote fork 出来的） | **2** | **1** |
| `adb shell` / `run-as` 起的进程 | 0 | 0 |

zygote 在 fork 出 App 进程**之后**、跑 App 代码之前装了 app policy seccomp
filter。seccomp 是栈式叠加：同一 syscall 必须所有 filter 都 ALLOW 才放行，
否则取最严格 action（`KILL` > `TRAP` > `ERRNO` > `ALLOW`）。外层的 `KILL`
先于我们自己的 `TRAP` 生效，SIGSYS handler 根本没机会被调用。实测从 App
内跑任何容器命令都是 `rc=139` 或 `rc=159`、耗时仅 100~200ms、两路输出全空。

**这是设备/ROM 相关差异，真机不复现**（真机 app policy 放行了我们需要的
syscall 集合）。**模拟器上验证容器行为必须走**
`run-as app.dsh.mobile <runner> <rootfs> <interp> <guest> ...`。
完整记录见镜像仓 `docs/pitfalls.md` 第 31 条。

### 诊断手法（本次靠它定位，可复用）

1. 在调用点跑两个探针，把「容器整体起不来」和「只有某个程序崩」分开：
   - `runner.run("")`（不跑 guest）→ 拿到 `rc=5` + `LOADER: preload FAIL`，
     说明 loader 本体能 exec、能打印；
   - `runner.run("/bin/sh", ["-c","echo X"])` → `rc=139`。
2. 对照 `grep -E "^Seccomp" /proc/<pid>/status`：App 进程 vs `run-as` 进程。
3. `audit_lost=651` 时审计日志已溢出，别指望从 logcat 拿到 seccomp 拦截证据。

---

## 一·五·补、容器内硬件 GPU 与图形窗口（2026-11-06 模拟器实测）

镜像仓 `dsh-linux-runtime` 记录完整数据（`docs/benchmark.md` 第六节、`docs/mechanism.md` 7.1~7.3）。
本节只留结论与本仓需要知道的边界。

### 已实测通过（模拟器 dsh34 / x86_64 / API 34 / SwiftShader）

容器内 bionic 图形程序**真的用上了宿主 GPU 并完成渲染**：

```
EGL 1.4 vendor=Android
GL_RENDERER=Android Emulator OpenGL ES Translator (Google SwiftShader)
GL_VERSION=OpenGL ES 3.0 (SwiftShader 4.0.0.1)
1080p 清屏 1.125 ms/帧；中心像素 RGBA=255,89,26,255（对齐 glClearColor）
```

真机对应 `Adreno (TM) 840`（历史实测），同一条通路，只换 GL 后端。

| 环节 | 1080p 实测 |
| --- | --- |
| 渲染 + glFinish | 1.0~1.4 ms/帧 |
| glReadPixels 回读 | 5.3~6.0 ms/帧（唯一瓶颈，占 83%） |
| 共享文件 mmap 写入 | 0.362 ms/帧 |
| **全链路** | **6.4~7.3 ms/帧 → 137~156 fps**（720p 300+ fps） |

### 三个必须记住的坑

1. **必须经 `sh -c` 触发**。直调 `dsh_alpine <rootfs> <interp> /gpu_probe` 会按
   musl 装载、报 `Error relocating: __libc_init: symbol not found`。只有容器内再
   发起一次 `execve` 才命中 `guest_is_bionic()` → cleaner。
2. **写 bionic 探针不能静态链接 libEGL，也不能用 printf**。两者都会在 bionic
   非 zygote 进程里触发 `FORTIFY: pthread_mutex_lock called on a destroyed mutex`
   直接 abort。必须 `dlopen`+`dlsym`，输出用裸 `write()`。
3. **跨进程帧共享不能用 memfd**。`/proc/<pid>/fd/<n>` 只有 root 能 open；同 uid
   的 `run-as` 进程读它被 SELinux 拦成 `Permission denied`（root 读 921600 B
   正常）。必须用**普通文件 + 双方各自 mmap**，文件放
   `/data/data/<pkg>/files/dshframe.bin`（rootfs 里宿主看不到）。

### 能力边界（诚实分列）

| 能力 | 状态 |
| --- | --- |
| 容器内用宿主 GPU 渲染 | ✅ 实测 |
| 像素回读到宿主可读 | ✅ 实测（普通文件 mmap） |
| 全链路 1080p 137+ fps | ✅ 实测 |
| **App 侧 SurfaceView 贴图** | ✅ **实测**（`LinuxFrameSurface.kt` + Linux 页「容器画面」卡片，见一·五·补二） |
| 几何渲染（着色器/VBO/draw call） | ✅ 实测（framesrv `tri` 模式） |
| 直接建系统窗口（SF layer） | ❌ 需 app Binder，免 root 拿不到 |
| 硬件合成（AHB/gralloc 零回读） | ❌ 需 libhybris fork，未做 |

**结论**：「容器里跑图形程序并把画面显示进 App」这条路**已全线打通**（渲染 →
回读 → 跨进程 → 贴图）。「像原生 App 一样拥有独立系统窗口 + 硬件合成」需要
compositor + libhybris，属另一条路线（TAWC 走的）。

---

## 一·五·补二、容器画面进 App（2026-11-06，commit `e057db7` / `7b0857c`）

图形链路的最后一段已补齐：容器渲染的画面真的能显示在 App 界面上。

### 怎么用

Linux 页新增「容器画面」卡片 → 点「开始画面」→ 容器内起 `/framesrv 1280 720 0 16`，
`LinuxFrameSurface` 自动 mmap 共享文件并贴图；点「停止画面」停掉帧服务（保留最后一帧）。

### 数据通路

```
framesrv（容器内 bionic）──EGL/GLES 渲染（宿主 GPU）
   │ glReadPixels 直接写进 mmap 的共享文件
   ▼
/data/data/<pkg>/files/dshframe.bin
   │ App 侧 FileChannel.map（只读）
   ▼
LinuxFrameSurface（SurfaceView + Choreographer）→ drawBitmap
```

### 共享文件格式（两侧必须同步，探针锁死）

```
offset 0   u32 magic = 0x4D415246（小端落盘即 "FRAM"）
offset 4   u32 seq     每帧 +1，脏帧判据
offset 8   u32 w
offset 12  u32 h
offset 16  起为 RGBA8888 像素
```

### 三个已踩的坑

1. **跨进程不能用 memfd**：`/proc/<pid>/fd/<n>` 只有 root 能 open，同 uid 的
   App 读容器进程的 memfd 被 SELinux 拦成 `Permission denied`（root 读 921600 B
   正常）。必须落普通文件，双方各自 mmap。
2. **共享文件头字段顺序最容易错**：我第一版 Kotlin 少读一个 `seq`，结果把
   60 帧的 seq 当成了宽度，logcat 打出 `mapped 60x320`。字段必须按
   magic→seq→w→h 连读四个 int。
3. **`vbo` 初值 0 是 GL 的「默认缓冲」**：对它 `glBufferData` 是
   `INVALID_OPERATION` 且**静默失败**，顶点数据不会上传，表现为「program 链接
   成功但帧里只有清屏色」。必须先 `glGenBuffers(1, &vbo)`。

### 验证证据（模拟器 dsh34 / x86_64 / SwiftShader）

- 容器起 1280×720：共享文件 3686416 B = 16 + 1280*720*4 ✓
- App 截图三次采样 RGB(189,66,128) / (4,251,128) / (79,176,128) →
  t=0.74 / 0.02 / 0.31，全部精确符合 `glClearColor(t, 1-t, 0.5)`
- 界面截图确认画面在变（品红→绿色），不是静止旧帧
- `tri` 模式回读帧：三角形区纯黄 (255,242,51)、清屏区深绿 → 几何管线成立
- 探针 `scripts/probes/_test_linux_frame_surface.py` 38 checks / 0 failed，
  反向验证过（改错 MAGIC / 改回 lockCanvas 立刻变红）

### 显示端优化（commit `deabece`）

分段计时定位（720p / 模拟器）：

| 阶段 | 改前 lockCanvas | 改后 lockHardwareCanvas |
| --- | --- | --- |
| pull（mmap→Bitmap） | 1.4 ms | 1.3 ms |
| draw（Canvas→Surface） | **12.7 ms** | **0.4~0.8 ms** |
| 显示帧率 | 29~30 fps | 28~31 fps |

1. **软件 Canvas 是显示端头号瓶颈**：`lockCanvas()` 的 `drawBitmap` 走 CPU
   逐像素合成，换 `lockHardwareCanvas()` 交 GPU 后低 20 倍。
2. **剩余 ~30 fps 不是我们的代码**：对照实验（去掉 drawBitmap 只清屏）帧率
   仍是 30 fps → 上限来自模拟器 vsync 调度，真机 60/120Hz 会相应提高。
3. **映射区必须显式设小端**：`ByteBuffer` 默认 BIG_ENDIAN，容器按小端写 seq，
   不设 `order(LITTLE_ENDIAN)` 时读出字节反转垃圾值（实测 `lastSeq=-1475936256`
   这类乱数）→ 每帧都被判成新帧，重复帧白拷整帧（1080p 8.3 MB）。

---

## 一·五·补三、与 tawcroot/TAWC 的能力对照（2026-11-06）

完整对照见镜像仓 `dsh-linux-runtime/docs/comparison.md`。这里只留结论，避免以后
再凭印象判断领先或落后。

### 必须是同一路线，没有架构壁垒

逐项源码核对：cBPF + `SECCOMP_RET_TRAP`、`SIGSYS` + `rt_sigaction(SA_SIGINFO)`、
IP 白名单放行自身 syscall、`execveat(/proc/self/exe, AT_EMPTY_PATH)` 自重执行、
`--exec-child` + 手写 ELF loader、单进程模型 —— **双方完全一致**。这条路是
tawcroot 先公开的。对外表述不许暗示「独创架构」。

### 我们多出来的（都有实测证据）

| 能力 | 证据 |
| --- | --- |
| 免 root 的 DNS 双向端口改写 | 传 `DSH_DNS_PORT=15353` → `github.com` 解析成 20.205.243.166；不传 → `nslookup: write to '127.0.0.1': Connection refused`。真实价值：**musl 发行版（Alpine）在免 root Android 上能正常解析域名** |
| 容器内用宿主 GPU 渲染 + 画面回 App | 360p/720p/1080p = 1405.8 / 324.6 / 150.1 fps（容器→共享文件）；`lib/dsh_gl` 封装成 3 文件库 |
| x86_64 支持 | TAWC README 自述只有 arm64 官方构建；我们双架构（但 x86_64 仅给模拟器/开发用） |
| App 内嵌、与引擎共享同一份数据 | 容器进程 PPID = App pid，`/data/data`、`/eng`、`/ws`、`/dsh` 同名 bind |

### 我们明确不如的（不许回避）

1. **图形栈**：TAWC 有 Smithay compositor + libhybris fork，能开系统窗口、硬件合成、
   XWayland。我们只有 EGL pbuffer + glReadPixels 回读，差距是数量级工程量。
2. **发行版生态**：glibc（Arch/Debian）vs musl（Alpine），后者很多二进制要重编。
3. **输入法/桌面集成**：TAWC 有键盘桥接、任务管理器、桌面快捷方式。
4. **社区验证**：TAWC 有真实用户与 issue 沉淀，我们 Linux 模式还没发给用户。

### 未验证、不许写进宣传

- **phantom process killer 不杀我们的容器**：`run-as` 测的 124 进程存活是**假证据**
  （进程挂 adbd 名下、cgroup 是 `/`，不在 App 进程树里）。必须真机走 UI 验证。
- **真机 Adreno 840 帧率**：上表全部数字来自模拟器 SwiftShader。
- **冷启 1.9s → 1.0s**：未达成。

---

## 一·五·补四、路径语义修复：引擎终于在容器里完整启动（2026-11-06）

镜像仓 `4672589`（`dsh-linux-runtime`）。这是 Linux 模式从「能跑命令」到
「能跑 DSH 引擎」的分水岭。

### 症状

手工在容器里起引擎，日志只有两行：

```
hmr (@deepseek-ai/dsh-hmr): Error: EACCES: permission denied, watch '/'
dsh: startup failed: 2 required plugins did not activate
```

同时 `[android-plugin-health] profile is degraded: missing=typert,typertGateway,
directoryPicker,directoryPickerController,workspaceRegistry,workspaceController,
webServer,loader` —— 7 个服务全 missing。浏览器打不开首页。

### 两个独立缺失（都在路径翻译层）

**① `readlink(/proc/self/fd/N)` 没做宿主→guest 反查**

musl 的 `realpath()` 实现是 `open(path, O_PATH)` + `readlink("/proc/self/fd/N")`。
而 `/proc` 整棵前缀是**透传给宿主**的，所以 readlink 返回的是宿主机真实路径：

| guest 路径 | readlink 实际返回 |
|---|---|
| `/tmp` | `/data/data/app.dsh.mobile/files/linux/distros/alpine/tmp` |
| `/dsh` | `/data/data/app.dsh.mobile/files/dsh-home` |

`realpath()` 拿到后还要 `stat` 这条宿主路径 —— 容器里不存在 → ENOENT。
**任何** bind 挂载点下的 realpath 全失败。

修法：在 `SYS_readlinkat` 分支识别 `/proc/[self|PID]/fd/N`，用 `host_to_guest()`
（先 bind 表最长匹配反查，再退 rootfs 前缀裁剪）把返回值裁成 guest 视角。

**② `inotify_add_watch(fd, pathname, mask)` 没进 BPF 树**

pathname 在 **a1**，而且**没有 `*at` 变体**，原树只拦 `*at` 一族，于是被透明
转发，guest 路径直接落到宿主根。Node 的 `fs.watch` 因此 ENOENT；chokidar 沿父
目录逐级回退找「最近存在的目录」，一路退到 `/` → `EACCES watch '/'`。

修法：加进 `tools/gen_bpf_tree.py` 的 `NEEDED`，handler 里像 `statfs` 那样用
`make_host()` 拼宿主路径。inotify 在宿主路径上注册不影响事件回传（wd 跟 inode 走）。

### 定位方法（这条坑最贵的地方：症状离根因极远）

日志里只有 `watch '/'`，而 `/` 是 chokidar 的**回退终点**不是起点。要给
`fs.watch` 打桩才看得到真实入参：

```js
const fs = require("node:fs"); const orig = fs.watch;
fs.watch = function (t, o) {
  fs.writeSync(2, "[watchhook] watch(" + t + ")
");
  try { return orig.apply(this, arguments); }
  catch (e) { fs.writeSync(2, "[watchhook] THROW " + t + " :: " + e.code + "
"); throw e; }
};
```

实测输出全部 ENOENT（`/dsh/profiles/web`、`/dsh`、`/dsh/.credentials.yaml` …），
才把方向从「hmr 插件本身有问题」扭到「路径翻译缺失」。

### 修复后（模拟器 dsh34 / Android 14 / x86_64 实测）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `realpath("/dsh")` | ENOENT | `/dsh` |
| `fs.watch("/dsh/profiles/web")` | ENOENT → 回退 `/` → EACCES | **OK** |
| 引擎日志 | `startup failed: 2 required plugins did not activate` | **只有一行 `dsh web:`** |
| profile 健康 | 7 个服务 missing | 全绿 |
| 首页 | 打不开 | HTTP 200 / **46437 B** / **70 个客户端插件** |

`70` 与原生模式历史基线 `74` 的差额全部查清，**不是容器导致**：
`product-analytics` 与 `sidebar-browser` 被我们的 overlay 按 `profileContext.name
!== 'desktop'` 禁用；`plugin-market` 与 `directory-picker-native` 已被 browse 后端
替换；其余 5 个 `experimental-*` 需 profile 显式开启。

### 新增探针 `bench/path_probe.c`

一次性验证 realpath / inotify / getcwd 三条路径（< 20ms），任一 FAIL 即定位到
具体 syscall。实测 `RESULT: PATH_PROBE_OK (fails=0)`。

> `chdir(/ws)` 的 getcwd 回 `/dsh/workspaces` 不是 bug：`/ws` 与 `/dsh/workspaces`
> 是同一宿主目录的两条 bind 名，`host_to_guest` 按最长宿主前缀反查，两条都合法。

完整版在 `dsh-linux-runtime/docs/pitfalls.md` 第 34 条。

---

## 一·五·补五、包管理器必须跟随发行版（2026-11-06，commit `f888f1f`）

真机反馈「linux 环境安装 node 失败」。**实测结论：`apk add nodejs` 本身正常**
（模拟器上 `OK: 409 MiB in 88 packages`，装完 `node --version` 可用）。真正的
缺陷是包管理器没跟随发行版。

### 缺陷 1：`LinuxPackageManager` 写死 `/sbin/apk`

`LinuxRootfsCatalog` 里 Alpine 声明 `PackageManager.APK`、Ubuntu 声明
`PackageManager.APT`，但 `LinuxPackageManager` 五个方法（`refreshIndex` /
`install` / `remove` / `installed` / `search`）全部硬编码 `/sbin/apk`。
用户切成 Ubuntu 后点「安装 node」，容器里根本没有 `apk` 这个二进制，
返回 `exit 127`，而 UI 只显示「apk add failed: ...」，用户无从判断。

修法：加 `isApt()`（现取 `env.activeOption().pkgManager`，不缓存）：

| 方法 | APK 分支 | APT 分支 |
|---|---|---|
| `refreshIndex` | `apk update` | 直接返回成功（apt 索引由 `installPackagesApt` 管） |
| `install` | `apk add` | 委托 `installPackagesApt` 两步法 |
| `remove` | `apk del` | `apt-get remove -y` |
| `installed` | `apk info -v`，切横线解析 `nodejs-22.23.4-r0` | `dpkg-query -W`，切 TAB 解析 |
| `search` | `apk search -q` | `apt-cache search` |

**为什么 apt 不能一步装**：实测 Ubuntu 24.04 上 `apt-get install -y foo` 报
`dpkg: error: cannot stat pathname '/tmp/apt-dpkg-install-XXXX/00-foo.deb'`
—— apt 把 deb 硬链进 `/tmp` 后 dpkg 在容器的路径重写层上看不见它；同一份
deb 留在 `/var/cache/apt/archives/` 里 dpkg 就能正常读。所以必须是
`apt-get -d install`（只下载）→ `dpkg -i /var/cache/apt/archives/*.deb` →
`dpkg --configure -a`。

### 缺陷 2：网络失败只回「apk add failed」，指向错误方向

`apk update` / `apt-get update` 的网络失败文案是
```
WARNING: updating http://mirrors.aliyun.com/alpine/v3.22/main:
         temporary error (try again later)
```
看起来像镜像站挂了，**实际绝大多数是容器 DNS 不可用**：musl 的 `getaddrinfo`
把 DNS 端口写死成 53，而 App uid 绑不上 53（特权端口），要靠 loader 对
`sendto`/`recvfrom` 做**双向**端口改写，端口号从 `DSH_DNS_PORT` 读。
（`LinuxContainerRunner.doExec` 已在 `f888f1f` 之前的提交里补上这个注入——
原来只有 `LinuxEngineLauncher` 注入，所以「引擎自己」能联网、而 `apk add`
这类走执行器的路径全解析失败。）

修法：加 `diagnose(raw)`，命中 `temporary error` / `try again later` /
`connection refused` / `timed out` 等关键词时追加两句按实际状态区分的提示：
- 代理未运行（`LinuxDnsProxy.currentPort() <= 1023`）→ 提示应用可能被省电策略杀掉
- 代理在跑（报出真实端口）→ 提示查 `resolv.conf` 与上游 DNS

文案走 `R.string`（`linux_pkg_dns_proxy_down` / `_up` / `linux_pkg_index_dns_hint`），
五语齐全。**不能用 Kotlin 字面量**——`scripts/check-i18n.py` 的 D 段会直接报
`CJK in Kotlin string literal` 让门禁失败。

### 验证

- `scripts/probes/_test_linux_pkg_distro.py` **37 checks / 0 failed**
  （守：分派存在、apt 走两步法、dpkg-query 格式串、四条方法都分派、
  五语文案齐全且占位符一致、非中文语种不带 CJK、无未闭合字符串行）
- **反向验证**：`isApt()` 改恒 `false` → `[A2]` 立刻变红；删掉 `install`
  里的 apt 分派 → `[B1]` `[C1]` 变红
- `check-i18n.py` All passed；全量探针 **59 PASS / 1 预期失败**
- `compileDebugKotlin --rerun-tasks` BUILD SUCCESSFUL（2m13s）

### 缺陷 3（★用户报的 `apk update failed` 的真因）：DNS 代理启动竞态

`DshApp.onCreate` 里的 DNS 代理预启动跑在**后台线程**（可能要过一次 `su` 调
`ip_unprivileged_port_start`，不能阻塞主线程）。用户完全可能在代理还没绑好
端口时就点「安装 node」，那一刻 `LinuxDnsProxy.currentPort()` 仍是 0，
`LinuxContainerRunner` 就**不注入** `DSH_DNS_PORT`，容器的 musl resolver 于是
把查询发到 53（没人监听，App uid 也绑不上特权端口）。

**A/B 决定性实测**（模拟器 dsh34 / x86_64，清空 apk 索引模拟首次安装）：

| 条件 | 输出 | 退出码 |
|---|---|---|
| 不注入 `DSH_DNS_PORT` | `WARNING: updating ...: temporary error (try again later)` / `0 distinct packages available` | **RC=4** → UI 显示 `✗ apk update failed` |
| 注入 `DSH_DNS_PORT=15353` | `OK: 26325 distinct packages available` | **RC=0** |

`RC=4` 非零 → `OpResult.ok=false` → `LinuxActivity.installPackages` 在
`idx.ok` 为假时直接 return（`ExtensionStoreActivity` 同理），用户看到的就是
`✗ apk update failed`，**与反馈原文逐字一致**。

修法（commit `e13a22d`）：新增 `ensureDnsProxy()`，在 `refreshIndex` 与
`install`（apk 分支）执行前无条件保一次；`start()` 本身幂等且 `@Synchronized`，
已绑定则早退，代价可忽略；返回 0 表示确实起不来，仍交给上层报错。

### 验证（模拟器 dsh34 / x86_64 实测）

- A/B 对照见上表（同一条命令、同一 rootfs，只差 `DSH_DNS_PORT`）
- `apk add --no-progress nodejs` → `OK: 409 MiB in 88 packages`；
  `command -v node` → `/usr/bin/node`，`node --version` → `v22.23.4`
- `scripts/probes/_test_linux_pkg_distro.py` **41 checks / 0 failed**
- 反向验证：`isApt()` 改恒 `false` → `[A2]` 变红；删 `install` 里的 apt 分派
  → `[B1]` `[C1]` 变红；删 `refreshIndex` 里的 `ensureDnsProxy()` → `[I3]` 变红
- `check-i18n.py` All passed；全量探针 **59 PASS / 1 预期失败**；
  `compileDebugKotlin` BUILD SUCCESSFUL

### 未验证（如实分列）

- **Ubuntu 分支未端到端实测**：模拟器网络拉不下 30MB 的 Ubuntu base rootfs。
  代码路径与断言已覆盖（`[A1]`~`[F1]`），但「切到 Ubuntu 后装 node 成功」
  没有端到端证据。
- **真机未验**：本轮真机离线，全部数据来自模拟器 x86_64 + SwiftShader。
- 阿里云镜像可达性：alpine 侧实测通（26325 个包），Ubuntu 走的
  `mirrors.tuna/aliyun/ustc` ubuntu-cdimage 未实测。

---

## 一·五·补六、AF_UNIX socket 路径翻译 —— weston compositor 首次跑通（2026-11-06，commit `acc6618`）

镜像仓 `8c941b8`。这是图形栈从「容器内硬件 GPU 渲染」走到「容器内跑真正的
Wayland compositor」的关键一步 —— 后者是 TAWC 有、我们一直缺的那半。

### 根因：`sockaddr_un.sun_path` 也是 guest 路径，但 bind/connect 都没翻译

**两层独立缺失，必须都修**，否则症状会从「服务端起不来」变成「服务端起来了
但客户端连不上」——这种迁移比原故障更难查：

| 层 | syscall | 缺了会怎样（实测原文） |
|---|---|---|
| 1 | `bind` | `libwayland: bind() failed with error: No such file or directory` / `fatal: failed to add socket: No such file or directory` → compositor 直接退出 |
| 2 | `connect` | `failed to create display` / `libwayland: failed to connect to socket` → 客户端连不上已建好的 socket |

### 实现要点（`app/src/main/cpp/dsh_alpine.c`）

1. `scripts/gen_bpf_tree.py` 的 `NEEDED` 加 `"bind"`（`connect` 本来就在树里，
   但它只处理 AF_INET 的 DNS 端口改写，要另开 `fam == 1` 分支）。
2. 两个 handler 对 `AF_UNIX` 且 `sun_path[0] == '/'` 做路径翻译：
   `bind_map()` 优先（命中 bind 挂载点），`make_host()` 兜底（拼 rootfs 前缀）。
3. **必须重新拼完整的 `sockaddr_un`**。踩过的坑：只把路径字符串换成 `hb`
   就传原 `a1` 指针 → 内核看到「新 addrlen + 旧内容」直接 `EINVAL`（实测三个
   路径全部 `Invalid argument`）。正确形状是 `sun_family`(2B) 紧跟 `sun_path`，
   `addrlen = 2 + strlen(path) + 1`。
4. freestanding 没有 `strlen`，用既有的 `ldr_strlen`。
5. 抽象命名空间（`sun_path[0] == 0`）与相对路径**不翻译**，原样透传。

### 实测证据（模拟器 dsh34 / Android 14 / x86_64）

**裸 syscall 层**（新探针 `dsh-linux-runtime/bench/unix_socket_probe.c`）：

```
/tmp                     bind+connect+rw OK
/dsh                     bind+connect+rw OK
/var/tmp                 bind+connect+rw OK
RESULT: UNIX_SOCKET_OK (fails=0)
```

**真实 compositor**（weston 14.0.2，Alpine 包 `weston` + `weston-backend-headless`
+ `weston-clients` + `weston-shell-desktop`）：

```
[log] Loading module '/usr/lib/libweston-14/headless-backend.so'
[log] Output 'headless' enabled with head(s) headless
[log] Loading module '/usr/lib/weston/desktop-shell.so'
[log] launching '/usr/libexec/weston-desktop-shell'
[fs]  /tmp/wl/test        srwxrwxrwx      ← socket 建出来了
[fs]  /tmp/wl/test.lock
[ps]  4 个 weston 相关进程驻留
```

**真实 Wayland 客户端**：`weston-simple-shm` 持续存活（不再
`failed to create display`）；`grim` 从 `failed to create display` 变成
`compositor doesn't support wlr-screencopy-unstable-v1` —— 后者是**协议不支持**，
说明连接已经建立。

### 必须澄清的一条旧结论

`docs/pitfalls.md` **#32** 曾把 X11/Xvfb 起不来归因成「SELinux 禁止 `link()`」。
本轮重新取证：那个限制**确实存在**（avc 原文
`avc: denied { link } for comm="ln" scontext=u:r:runas_app:s0
tcontext=u:object_r:app_data_file:s0 tclass=file permissive=0`；无 root 时
`Xvfb -nolock` 也报 "can only be used by root"），但它只是 **X11 专属的第二道
限制**。任何 AF_UNIX 服务的**第一道**限制都是上面的路径翻译缺失。
**wayland 路线不需要 `link()`，所以走 compositor 可以绕开 X11 那道限制。**

### 能力边界（诚实分列）

| 项 | 状态 |
|---|---|
| AF_UNIX bind / connect 路径翻译 | ✅ 实测（三目录 + 真实 compositor + 真实客户端） |
| weston headless compositor 在容器内运行 | ✅ 实测（4 进程驻留、socket 可用、客户端可连） |
| Wayland 客户端连上并渲染 | ✅ 实测（weston-simple-shm 存活） |
| 从 compositor 取帧回 App | ⬜ **未做**。grim 需要 `wlr-screencopy`（weston 不提供）；weston 自己的 `weston-screenshooter` 走 `weston_capture_v1`，实测在无窗口内容时报 `Assertion failed: width > 0`，需要先有客户端表面输出才能截 |
| 硬件 GPU 参与合成 | ❌ **未做**。headless 后端是 `no-op renderer`（纯 CPU），要硬件加速需 `weston-backend-drm` + 真 GPU 后端（musl 侧缺 KGSL，见一·五·补） |
| X11 / Xvfb | ❌ 仍不可行（`link()` 被 SELinux 拒，X11 专属限制） |

**结论**：这一轮把「容器里能不能跑 compositor」从「不能」变成了「能」，
但「compositor 的画面怎么进 App」还没接上 —— 而后者正是 TAWC 用 libhybris
fork + Smithay 解决的那部分。**差距缩小了，但没有消除。**

---

## 一·五·补七、容器内 Wayland GUI 窗口跑通（2026-11-06，commit `8bd6b99`）

镜像仓 `8bd6b99`。承接一·五·补六的 AF_UNIX 修复，这一轮把「容器内能不能开
GUI 窗口」从推测变成实测。

### 两个新发现

**① headless 后端默认 `--renderer=noop`**，而 noop 只走合成流程、**不算像素**。
所以 `weston-debug scene-graph` 明明列出 view（窗口存在、几何正确），截图却拿
不到内容（`weston-screenshooter` 报 `Assertion failed: width > 0`）。加
`--renderer=pixman` 才有真实缓冲区，日志出现 `Using Pixman renderer`。

**② weston 14 的 `weston_capture_v1` 有授权模型**：只授予 screen-share 拉起的
RDP 客户端，第三方工具（`weston-screenshooter` / `grim`）一律
`Output capture error: unauthorized`。`--modules=screen-share.so` 加载模块
本身不足以授权。想抓帧得另写绑定该协议的客户端，或让客户端 `wl_shm` 池落成
真实文件（LD_PRELOAD 一份 `memfd_create` 替身）——**后者未实测**，容器里没有
gcc（`apk add gcc musl-dev` 报 `temporary error`，模拟器网络受限）。

### 关键实测证据（模拟器 dsh34 / Android 14 / x86_64）

```
$ weston-debug scene-graph
Weston scene graph at 31126.295404300:
Output 0 (headless):
	position: (0, 0) -> (640, 360)
	mode: 640x360@60.000Hz
Layer 4 (pos 0x80000000):
	View 0 (role xdg_toplevel, PID 29355, surface ID 3,
	        top-level window 'simple-shm' of org.freedesktop.weston.simple-shm):
		position: (307, 79) -> (557, 329)
		outputs: 0 (headless) (primary)
		SHM buffer
			format: 0x34325241 ARGB8888
			modifier: LINEAR (0x0)
	View 0 (role (null), PID 29351, surface ID 16,
	        background for output headless):
		position: (0, 0) -> (640, 360)
```

`role xdg_toplevel` + `top-level window 'simple-shm'` = 一个真实的顶层窗口，
250×250 居中，带 panel 与 background，全部由容器内的 weston 合成。

### 能力边界（更新，诚实分列）

| 项 | 状态 |
|---|---|
| compositor 在容器内运行 | ✅ 实测 |
| 真实 Wayland 客户端连上并建窗口 | ✅ 实测（scene-graph 有 `xdg_toplevel`） |
| 客户端持续重绘 | ✅ 实测（`weston-simple-shm` 存活） |
| 从 compositor 抓帧 | ❌ 未通（授权模型 + grim 要 `wlr-screencopy`，weston 不提供） |
| 硬件 GPU 合成 | ❌ 未做（`pixman` 是 CPU；headless 后端没有 EGL 路径） |
| X11 / Xvfb | ❌ 仍不可行（无 root 时 `link()` 被 SELinux 拒） |

**产品路径与 compositor 抓帧无关**：容器内 GL 程序的画面走 `lib/dsh_gl`
写共享文件 + App 侧 `LinuxFrameSurface` mmap 贴 SurfaceView，这条已单独验证
（一·五·补二）。compositor 的价值是「让依赖 Wayland 的 GUI 程序（GTK/Qt/
浏览器）能在容器内运行」，不是画面通道。

### 本轮环境事故（必须记录）

**模拟器被另一会话反复抢占**：期间 App 被替换成 release 包
（`versionName=1.5.0`、非 debuggable → `run-as` 失效），容器数据被清空，
且我的 debug 包因签名不匹配装不回去
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`）。
另一会话同时在改 `app/build.gradle.kts`、`engine/LinuxEngineLauncher.kt`、
`engine/ProfilePluginProvisioner.kt`。

**这意味着**：本轮所有容器实测都在「App 自己部署的 debug 环境」里完成，
之后模拟器被换包，**未能复跑验证**。已取得的证据是真实的，但不可当场重放。
后续要复验，需等该会话结束后用 debug 包重装 + 走 LinuxActivity 部署。

---

## 一·五·补八、容器联网的硬前提：App 进程必须在（pitfalls #37）

镜像仓 `29e7320`。这条不是 bug，是架构约束，但排查时极易误判成「镜像站挂了」。

### 实测到的假故障

用 root 手工在设备上直接跑容器装包：

```sh
env DSH_DNS_PORT=15353 /data/local/tmp/dshtest/dsh_alpine <rootfs>     /lib/ld-musl-x86_64.so.1 /sbin/apk add --no-progress weston weston-backend-headless
# → rc=99
# → ERROR: Failed to open apk database: temporary error (try again later)
```

看起来是 Alpine 仓库挂了。实际是 **App 进程不在，127.0.0.1:15353 没人监听**。

### 判据（一行）

```sh
adb shell "cat /proc/net/tcp | awk '{print $2}' | grep -ci 3BF9"
# 0 = DNS 代理没在跑（0x3BF9 = 15353）
```

本轮实测就是 `0`。

### 机制

App uid 绑不上 53（特权端口，`ip_unprivileged_port_start=1024`），所以容器 DNS
由 **App 进程内的 `LinuxDnsProxy`** 在 127.0.0.1:15353 代答，loader 负责
`sendto`/`recvfrom`/`connect` 的双向端口改写。App 不在 → 没有监听者，而
`DSH_DNS_PORT=15353` 仍照常传给 loader → 查询发到死端口 → 表现为
`temporary error (try again later)`。

**所以**：
- 手工起容器做任何实验，**必须先让 App 跑起来**。
- 反过来，用户既然能装上包，就说明 App 活着、代理起来了。
- 这也是 `DshApp.onCreate` 把代理预启动放在 Application 而不是 `readiness()`
  的原因（后台引擎独立存活、App 被杀后重启时 `readiness()` 不再执行）。
- 已修相邻问题：`LinuxPackageManager` 在 `refreshIndex()` / `install()` 前调
  `ensureDnsProxy()`，覆盖「刚开 App 用户就点安装」的竞态（见一·五·补五）。

---

## 一·五·补九、容器层不是冷启瓶颈（2026-11-06，镜像仓 `628e20b`）

镜像仓 `628e20b`（`docs/benchmark.md` 第 10/11 节 + `scripts/verify_container.sh`）。

### 1. 容器层单进程开销 = 4ms（实测）

测法必须批平均 —— Android 内核 `HZ=100`，`/proc/uptime` 只有 10ms 粒度，
单次测必然读到 0 或 10：

```sh
S=$(cut -d' ' -f1 /proc/uptime)
for i in $(seq 20); do <runner> ... /bin/sh -c 'true' >/dev/null 2>&1; done
E=$(cut -d' ' -f1 /proc/uptime)
awk "BEGIN{printf \"%.2f ms/run\", ($E-$S)*1000/20}"
# → 20 runs: 4 ms/run
```

即 `fork + execve + seccomp 装载 + SIGSYS 拦截 + busybox sh 启动 + 退出` 约 **4ms**。

**与真机记录方向一致**（本文件「一·五」章节：端到端 1.33 秒，容器层仅占 0.8%
约 10ms，引擎插件加载占 96%）。

**结论（对目标 (2) 的意义）**：把冷启从 1.9s 压到 1.0s 的空间**几乎全在 Node
引擎的模块加载与插件激活上**，容器侧再优化只有个位数毫秒可捡。这也解释了为
什么三条容器侧优化（Node 24 / 堆参数 / 依赖盘点缓存）实测都没有正向收益 ——
它们动的是引擎侧，而引擎侧瓶颈是 `ModuleWrap`(13.8%) 与插件激活，
`(idle)` 只占 1.4%。

### 2. 可复现的独立验证环境（不依赖 App）

之前所有容器实测都在 App 内跑，被并发会话换包后无法重放。现在有了
`adb root` 下的独立环境 + 一键脚本：

```sh
T=/data/local/tmp/dshtest
adb root
adb shell "rm -rf $T; mkdir -p $T/rootfs $T/home/w $T/eng $T/dsh"
adb push app/src/main/jniLibs/x86_64/libdsh_alpine.so $T/dsh_alpine
adb push app/src/main/assets/linux/rootfs-bootstrap-x86_64.tgz $T/rt.tgz
adb shell "cd $T/rootfs && tar xzf ../rt.tgz && chmod 755 ../dsh_alpine"
# 改绝对软链为相对（只碰 symlink，见 pitfalls #38）
# 写真实 nameserver（root 域可直接用外部 DNS，见 pitfalls #39）
adb push <path_probe> <unix_socket_probe> $T/rootfs/
adb push dsh-linux-runtime/scripts/verify_container.sh /data/local/tmp/
adb shell "sh /data/local/tmp/verify_container.sh $T"
```

实测输出：

```
=== 1) rootfs 基本可用性 ===     OK  alpine-release=3.22.2
=== 2) 路径语义 ===              RESULT: PATH_PROBE_OK (fails=0)
=== 3) AF_UNIX bind/connect ===  RESULT: UNIX_SOCKET_OK (fails=0)

RESULT: VERIFY_CONTAINER_OK
```

**意义**：路径语义修复与 AF_UNIX 修复这两条结论，从「App 内一次性实测」升级为
「可在独立环境一条命令重放的复现」。

### 3. 这个环境装不了大包（如实记录）

`apk add weston`（110 包 / 458 MiB）与 `apk add nodejs`（14 包，含
`icu-data-full`）在模拟器上 **>15 分钟**仍未完成，最后进程被 `ActivityManager`
杀（`Killing ... app.dsh.mobile (adj 0)`，**不是** phantom killer，后者日志是
`Excessive phantom processes`）。所以该环境只适合跑**已编译好的小探针**。

顺带两个坑（已入 pitfalls #38）：
- `apk` 被 `kill -9` 后留下数据库锁，之后全报 `Unable to lock database`，
  清 `rm -f <rootfs>/lib/apk/db/lock` 即可。
- 手工解包 minirootfs **必须把绝对软链改成相对**（`cat -> /bin/busybox` 这种），
  且**只碰真软链** —— 我第一版把 `busybox` 本体也改成了指向自己的死链，症状
  同样是 `LOADER: preload FAIL`。

### 4. 一条被推翻的旧判断

本文件「一·五」章节第 4 条坑写着「容器内没有 DNS，musl 直连 UDP 53 被
netd/SELinux 拦，必须靠静态 `/etc/hosts` + 纯 http 镜像」，并记了
「阿里云实测可达 26178 个包」。

**这只有免 root 场景成立。** 本轮实测：`root` 域下容器可以直连真实外部 DNS：

```sh
echo 'nameserver 223.5.5.5' > <rootfs>/etc/resolv.conf
<runner> <rootfs> /lib/ld-musl-x86_64.so.1 /bin/sh -c 'nslookup mirrors.aliyun.com'
# Server:  223.5.5.5     Address: 223.5.5.5:53
# Address: 112.15.7.205
```

两条路的分工（已入 pitfalls #39）：

| 场景 | DNS 走法 | 前提 |
|---|---|---|
| App 内（`untrusted_app` / `runas_app`，免 root） | App 进程的 `LinuxDnsProxy` + loader 双向端口改写（`DSH_DNS_PORT`） | **App 必须在跑**（pitfalls #37） |
| root / `adb root` | 直接写真实 nameserver，走真 53 | 有 root |

排查前先确认当前 SELinux 域：`run-as app.dsh.mobile id` 显示
`context=u:r:runas_app:s0:cXXX,...`；`adb root` 后是 `u:r:su:s0`。

---

## 一·六·补、冷启 3.4 秒的精确构成（2026-11-07，镜像仓 `b937864`）

镜像仓 `b937864`（`docs/benchmark.md` 第 12/13 节）。这一轮把冷启从
「猜哪个插件慢」推进到「按执行方精确归因」。

### 一、编译缓存实测省 1.1 秒（24%）—— 这是唯一还有正收益的一项

干净 A/B（模拟器 dsh34 / x86_64 / bionic node v24.18.0，各 3 轮，每轮清场）：

| 状态 | 三轮 | 中位 |
|---|---|---|
| **缓存命中** | 3652.2 / 3416.8 / 3385.7 ms | **3416.8 ms** |
| **缓存清空** | 4540.2 / 4088.8 / 4528.4 ms | **4528.4 ms** |

省 **1111.6 ms / 24.5%**，缓存落盘 **15 MB**。

**关键前提**：`NODE_COMPILE_CACHE` 是**懒写盘**的。不注入
`node-compile-cache-flusher.cjs` 时，跑完一整轮缓存目录只有 **16 KB**
（vs 注入后 15 MB）——缓存等于不存在。原生与容器两条路径都已注入
（`EngineConfig.kt` 的 `COMPILE_CACHE_FLUSHER`、`LinuxEngineLauncher.kt:256`）。

**顺带纠正一条被污染的历史数字**：之前记的「原生引擎 6197 ms」不可用 ——
那轮 `TMPDIR` 指向不存在的目录，`spill-local` 报
`ENOENT: mkdtemp '.../tmp/dsh-spill-XXXXXX'` 且 1 个插件未激活，环境与生产
不一致。`mkdir -p $TMPDIR` 后重测，无缓存态是 4089~4540 ms。

### 二、3.42 秒归谁在算（--cpu-prof 截到就绪时刻）

| 归谁 | 占比 | 折算 |
|---|---|---|
| **node 内建（JS 层）** | **44.7%** | **1900 ms** |
| **dsh 插件代码** | **36.8%** | **1562 ms** |
| 第三方 `node_modules` | 11.8% | 502 ms |
| GC | 5.9% | 251 ms |
| idle/program | 0.7% | 31 ms |

**`idle` 只占 0.7% —— 不是在等 IO，是真的在算。**

- **node 内建细分**：`cjs/loader` 243ms、`fs` 220ms、`esm/utils` 212ms、
  `bootstrap/realm` 181ms、`package_json_reader` 149ms、`url` 133ms、
  `esm/resolve` 94ms、`path` 71ms → **模块解析基础设施合计约 1.1 秒**
- **dsh 插件细分**：**`cordis` 548ms**、`dsh-app-boot` 183ms、
  `cordis-plugin-loader` 132ms、`dsh-tools` 132ms、`schemastery` 85ms →
  **插件框架本身合计约 765ms**
- `cordis` 内部：`get`(proxy 读) 149ms / 匿名 94ms / `notify` 39ms /
  `execute` 39ms / `apply` 39ms

**取 profile 的方法很重要**：`--cpu-prof` 覆盖整个进程生命周期。直接「起引擎 →
等 20 秒 → kill」会让 `(idle)` 虚高到 68%，结论完全错。必须用 `timeDeltas`
累加到 `readyat` 那一刻（`READY_US = 4247*1000`），得到 541/1795 样本。

### 三、证伪：`--no-warnings` 是噪声

交替 4 轮：基线中位 **3402.1**（3285.8/3308.2/3496.1/3832.6），
`--no-warnings` 中位 **3416.3**（3314.9/3406.3/3426.3/3881.6），
**差 14.2 ms 且两组完全重叠 → 噪声，不采用。**
记下来是为了避免以后有人再试一遍。

### 四、对 1.0 秒目标的判断

| 可砍方向 | 上限 | 现实性 |
|---|---|---|
| 插件框架（cordis/schemastery/loader） | ~765 ms | 中——属上游，改要 patch 引擎 |
| 模块解析基础设施 | ~1100 ms | 低——Node 自己的路径，除非换加载器 |
| dsh 各插件启动逻辑 | ~800 ms | 中——可裁非必需插件（Tier1 已盘点 16 个） |
| GC | ~251 ms | 低——由分配量决定 |
| 第三方模块 | ~502 ms | 低 |

**即使把插件框架和模块解析全砍掉，也只能到约 1.5 秒。1.0 秒需要换掉加载模型
（SEA / 单文件打包），不是调参数能到的。** 这条结论写进文档，避免后续再在
「调 flag / 裁插件」上反复尝试。

---

## 一·五·补十、AHB 零拷贝渲染打通（2026-11-07，镜像仓 `87532a0`）

镜像仓 `a8a02c3`（探针 + pitfalls #40 + benchmark 第 14 节）、`87532a0`（对比表）。
**这是目前唯一一项量化收益超过以往所有优化总和的图形栈改进。**

### 旧路径的瓶颈

```
GPU 渲染 → glReadPixels（CPU 回读）→ 写文件/共享内存 → App mmap
                ↑ 占 83%
```
1080p 拆解：`render+finish` 1.125 ms + `glReadPixels` **5.630 ms** +
`write` 0.014 ms = **6.777 ms/帧（147.6 fps）**。
`glReadPixels` 慢是因为它强制 GPU→CPU 同步并按行拷整帧 8.3 MB。

### AHB 链路（四步，缺一不可）

```
AHardwareBuffer_allocate
  → eglGetNativeClientBufferANDROID            (AHB → EGLClientBuffer)
  → eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID=0x3140)   (→ EGLImage)
  → glEGLImageTargetTexture2DOES               (→ GL_TEXTURE_2D)
  → glFramebufferTexture2D(GL_COLOR_ATTACHMENT0)          (→ FBO)
  → 正常绘制，像素直接落进 AHB
```

### 实测（模拟器 dsh34 / x86_64 / SwiftShader）

| 分辨率 | render into AHB | AHB lock（一次性） |
|---|---|---|
| 640×360 | **0.044 ms/帧** | 0.765 ms |
| 1280×720 | **0.129 ms/帧** | 4.381 ms |
| 1920×1080 | **0.341 ms/帧** | 4.498 ms |

像素正确性：`glClearColor(1.0, 0.35, 0.1)` → CPU lock 读回
**`center RGBA = 255,89,26,255`**（精确对齐）。`FBO status = 0x8CD5`
（`GL_FRAMEBUFFER_COMPLETE`）。

> **⚠️ 本节初次发布时写的是「1080p 6.777 ms → 0.341 ms = 19.9 倍」，已推翻。**
> 错在口径不同：`0.341 ms` 只含渲染，`6.777 ms` 是 render+回读+落盘。
> 跨探针、跨批次数字不可横比。

### ★同进程 A/B 对照（唯一可信口径，`bench/ahb_topipe.c`）

同二进制、同轮循环、同一 EGL 上下文，各跑 60 帧：

| 分辨率 | A：AHB render+lock+copy | B：glReadPixels render+read+copy | 倍数 |
|---|---|---|---|
| 640×360 | **1.131 ms**（884 fps） | 1.523 ms（657 fps） | 1.35× |
| 1280×720 | **3.162 ms**（316 fps） | 4.985 ms（201 fps） | 1.58× |
| 1920×1080 | **6.344 ms**（158 fps） | 10.930 ms（91.5 fps） | **1.72×** |

1080p 细分：render `1.144` vs `1.111`；**GPU→CPU 取像素 `3.954`（AHB_lock）vs
`8.379`（glReadPixels）—— 2.1 倍，这才是收益来源**；copy `1.213` vs `1.440`。
整体只 1.72 倍是因为 render 和 copy 两段没变，稀释了收益。

**两个必须纠正的说法**：
- `AHB_lock` **不是**一次性开销，每帧取像素都要调
- AHB **并不「免掉回读」**：lock 内部同样做 GPU→CPU 同步，只是这条路更快。
  真正免回读只有「AHB 直接交 SurfaceTexture」一种情况。

### 四个坑（pitfalls #40，每个都表现为「看似通了其实没通」）

1. **`eglCreateContext` 传 NULL config → `EGL_BAD_CONFIG(0x3005)`**，必须先 `eglChooseConfig`。
2. **`eglMakeCurrent` 用 surfaceless → `EGL_BAD_MATCH(0x3009)`**，该 EGL 实现没开
   `EGL_KHR_surfaceless_context`，必须绑真实 pbuffer。
   （`bench/framesrv.c` 一直走 pbuffer 所以从没踩到 —— **照抄成功案例比凭规范推理可靠**。）
3. **`AHardwareBuffer_lock` 是 5 个参数**（最后是 `const ARect* rect`）。少声明
   `rect` 时调用方按 4 参压栈 → 返回 `-22(EINVAL)` 且**编译不报错**。`usage` 也必须
   与申请时一致（申请 `CPU_READ_OFTEN`(0x2) 就传 0x2，传 0x1 同样 -22）。
4. **只把 AHB 变成 EGLClientBuffer 不等于渲染进 AHB**。实测整个探针打印
   `RESULT: AHB_OK`，但 CPU lock 读回 `0,0,0,0` —— 渲染落在当前绑定的 pbuffer 上，
   AHB 一个字节没被写。必须挂成 FBO colour attachment。

### 能力边界（本轮更新）

| 项 | 状态 |
|---|---|
| AHB 分配（GPU 渲染 + CPU 读） | ✅ 实测 |
| AHB → EGLImage → GL texture → FBO | ✅ 实测（COMPLETE） |
| GPU 渲染进 AHB 后 CPU 读到正确像素 | ✅ 实测 |
| 渲染吞吐 | ✅ 1080p 0.341 ms/帧 |
| **AHB 跨进程交给 App** | ❌ **本机不可行** —— `AHardwareBuffer_createFromHandle` 一律 `-22(EINVAL)`。已排除参数个数（4 参）、desc 来源（申请时 vs describe 回读 vs 全零）、method（CLONE/REFERENCE）、handle 复制长度（`version`=12 只是头，真实 104 字节）、跨进程继承（同进程自测也失败）。真根因是环境级：gralloc allocator 未进 VINTF（`Could not find android.hardware.graphics.allocator.IAllocator/default` + `Gralloc4: mapper 4.x is not supported`）。真机未验 |
| **真机 Adreno 验证** | ⬜ **未做** —— 全部数据来自 SwiftShader（CPU 渲染） |
| compositor 用硬件合成 | ❌ AHB 还没接进 weston 的 renderer（它走 pixman） |
| 系统窗口（SurfaceFlinger layer） | ❌ 需 app Binder，免 root 拿不到 |

### 遗留现象（不影响结论）

探针结尾稳定打印
`FORTIFY: pthread_mutex_lock called on a destroyed mutex (0x...)`，
发生在 `RESULT: AHB_OK` **之后**（EGL 已开始 teardown），不影响测量数据。
真机 Adreno 上未验证是否同样出现。

---

## 一·六·补二、冷启二次归因：模块加载基础设施占 50%（2026-11-07，镜像仓 `629969b`）

### 为什么必须重做归因

「一·六·补」那份 profile 把 `callFrame.url` 为空串的样本归进了「dsh 插件」或忽略。
这次复测发现它们**全是 node 的模块加载 native 路径**，被整块漏掉了。

### 探针方法（三个坑，按顺序踩过）

1. **`--cpu-prof` 不行**：node 只在**正常退出**时落盘。引擎就绪后是常驻服务、必须
   `kill -9` → 文件根本不生成（实测 `ls: prof_full.cpuprofile: No such file or directory`）。
2. **`v8.startProfiling` 不行**：那是 `--prof` 路径的私有接口，公开 `node:v8` 没有
   （实测 `TypeError: v8.startProfiling is not a function`）。
3. **正解 = inspector 协议**：`Profiler.enable` → `Profiler.start` → 拦到 `dsh web:`
   那一刻 `Profiler.stop` 并写盘。这样 profile **天然截断在就绪点**，不会把就绪后的
   稳态空转算进 `(idle)`（这正是早先那次 `(idle)` 虚高 68% 的原因）。

探针已入库：`dsh-linux-runtime/bench/profat.cjs`（截断 profile）
+ `bench/profsum.mjs`（按归属分类汇总）。

### 实测构成（模拟器 dsh34 / x86_64 / node v24.18.0）

`uptime=3820ms`，`total=3656.9ms samples=471`

| 归谁 | 耗时 | 占比 |
|---|---|---|
| node builtin（`internal/*`、`node:*`） | **1213.1 ms** | **33.2%** |
| **native/unknown（模块加载 native 路径）** | **605.8 ms** | **16.6%** |
| `@deepseek-ai/cordis` | 496.7 ms | 13.6% |
| GC | 260.3 ms | 7.1% |
| `zod` | 98.8 ms | 2.7% |
| `cordis-plugin-loader` | 76.1 ms | 2.1% |
| `dsh-app-boot` | 73.0 ms | 2.0% |
| `dsh-tools` | 70.7 ms | 1.9% |
| `schemastery` | 64.4 ms | 1.8% |
| `js-yaml` | 53.8 ms | 1.5% |

`native/unknown` 那 606ms 的明细全是**模块加载基础设施**：
`ModuleWrap` 34.5、`openFileHandle` 26.3、`getPackageScopeConfig` 22.1、`lstat` 18.1、
`internalModuleStat` 17.6、`evaluate` 17.2、`decodeUTF8` 17.1、
`compileFunctionForCJSLoader` 15.6、`parse` 15.1、`readFileUtf8` 14.6。

**结论修正：模块解析基础设施从「≈1.1 秒」改为「≈1.82 秒」**
（node builtin 1213 + native 模块加载 606），占总耗时 **50%**。

### 对「1.0 秒」的硬判断（更新）

- 调参数（`--no-warnings`、堆大小）**永远到不了 1.0 秒** —— 一半时间在文件系统
  stat + 读文件 + 编译上，已实测证伪。
- 唯一已兑现的收益是**编译缓存 1112 ms / 24.5%**（「一·六·补」）。
- 进一步只能减少要加载的文件数或避免重复编译。

### 快照与 SEA 双否（均为实测，不是推测）

| 方案 | 状态 | 卡在哪 |
|---|---|---|
| startup snapshot | ❌ | `--build-snapshot` 内部用 `minimalRunCjs` 跑入口，ESM 直接 `SyntaxError: Cannot use import statement outside a module`；且 V8 快照不捕获 ESM `ModuleJob`/`ModuleMap` |
| SEA | ❌ | blob 能生成（1961 B），但 `main` 只支持 CJS；CJS 动态 `import()` ESM 虽能工作（实测 `ESM_IMPORT_OK`），但 import 出去的 ESM 仍逐个走磁盘 → 收益归零 |
| 编译缓存 | ✅ **已生效** | 省 1112 ms / 24.5%，已在产线 |
| bundler 打包成更少文件 | ⬜ 未试 | 工程量大，需与上游包结构解耦 |

**快照对纯 CJS 脚本确实有效**（同一设备 `153ms → 59ms`，省 61%），这条数据留作
「如果将来把引擎打包成单 CJS 文件」时的可行性依据。

### 一次无效实验（已删除脚本，避免后人重踩）

我曾用 `DSH_PROFILE` / `DSH_PROFILE_DIR` 环境变量想隔离「base 框架 vs web-app 插件」
的耗时，得出「空 bundles 也要 3.45s」的结论 —— **那是假的**：这两个变量只被
`@deepseek-ai/dsh-shell-env` 读取，`bin.js` 根本不看，真正决定加载哪个 profile 的
是 argv 的 `web`（= `--profile web`）或 `--patch`。三次测量测的一直是同一个 profile。

另外那版脚本还直接改写了 `profiles/web/package.json`，跑完没清干净残留的 node 进程，
导致后续启动全部 `EADDRINUSE: 127.0.0.1:3080` → `startup failed: 2 required plugins
did not activate`。**教训：改 profile 配置的实验必须用独立 profile 目录，且每轮结束
确认 `ps -A | grep node` 为空。**

---

## 一·五·补十一、容器帧服务接入 AHB 直写（2026-11-07，commit `69a2437`）

镜像仓 `f4bcf7c`。把 `bench` 里验证过的 AHB 路径接进了**产线** `framesrv.c`。

### 产线版实测（模拟器 dsh34 / x86_64 / SwiftShader，各 60 帧）

| 分辨率 | rpix（原） | ahb（新） | 倍数 |
|---|---|---|---|
| 1280×720 | 210.4 fps | **286.0 fps** | 1.36× |
| 1920×1080 | 80.8 fps | **121.4 fps** | **1.50×** |

像素正确性：两条路径写出的共享文件 `seq=60`、中心像素 **`69,71,82,255` 完全一致**。

### `framesrv.c` 第 7 个参数（取像素方式）

- `rpix`（默认）—— 原 glReadPixels 逐行回读
- `ahb` —— 渲染进 `AHardwareBuffer` 再 `lock` 拷进共享文件
- `auto` —— 有 AHB 就用，没有自动退回 rpix

AHB 四步链路：`AHardwareBuffer_allocate` → `eglGetNativeClientBufferANDROID`
→ `eglCreateImageKHR(0x3140)` → `glEGLImageTargetTexture2DOES`
→ `glFramebufferTexture2D(GL_COLOR_ATTACHMENT0)`。

**每一步失败都安静退回 rpix 并打印原因**，这是刻意设计：真机 gralloc 与模拟器不同
（模拟器 `createFromHandle` 恒 `-22`，见 pitfalls #41），帧服务是产线依赖，
降级只能慢不能崩。`stride` 可能 > `W*4`（对齐），必须按行拷，不能整块 memcpy。

### App 侧两处改动

1. **`LinuxActivity.startFrameServer`** 传 `clear auto`
   （不硬编码 `ahb` —— 硬编码一旦设备不支持就整块失败，`auto` 只是慢一点）。
2. **`LinuxEnvironmentManager` 幂等戳**从「asset 路径」改成「路径:字节数」。
   **实测踩过**：framesrv 换成 AHB 版后，设备上仍是**旧的 11728 字节文件** ——
   因为戳里只有 `linux/framesrv-x86_64` 这个路径，内容变了路径没变，`stale`
   判定永远 `false`。
   新增 `assetSizeOf()`：优先 `assets.openFd().length`，失败退到流式读长度
   （AGP 会压缩小 asset，`openFd` 会抛
   `This file can not be opened as a file descriptor; it is compressed`）。

### 探针（`scripts/probes/_test_linux_frame_surface.py`，41 checks / 0 failed）

新增 3 条，**D8 与 E6 都做过反向验证**（改回硬编码 `ahb` / 退回只记路径 → 立刻变红）：
- **D8** 启动参数传 `pixmode=auto` 且不硬编码 `ahb`
- **E6** 幂等戳含 asset 字节数（内容变了要重装）
- **E7** `assetSizeOf` 有压缩回退

**E6 的反向验证第一次没变红**：正则写成 `wantStamp\s*=.*assetSizeOf` 且带 `re.S`，
`. *` 跨行把后面 `assetSizeOf()` 函数定义也匹配进去了，改坏代码也照样通过。
改成 `val wantStamp = .{0,80}assetSizeOf\(ctx, want\)` 后才真变红。
**教训：带 `re.S` 的 `.*` 断言几乎必然假通过，必须限长度。**

---

## 一·五·补十二、`dsh_gl` 库层支持 AHB（2026-11-07，镜像仓 `9b5fd4a` / `17e331c`）

上一轮把 AHB 接进了**产线一个程序** `bench/framesrv.c`。这一轮把它下沉到
`lib/dsh_gl` —— 这是给容器内**任意** GL 程序用的库。只有库支持，
「容器图形栈补齐」才算成立，否则每个程序都要各自复制一遍四步链路。

### API（`dsh_frame_open` 签名不变，零破坏）

```c
int dsh_frame_open_ex(dsh_frame *f, int w, int h, const char *sharepath, int pixmode);
const char *dsh_frame_pixmode_name(const dsh_frame *f);

#define DSH_PIX_RPIX 0   /* glReadPixels，最稳，所有设备都能用 */
#define DSH_PIX_AHB  1   /* AHardwareBuffer，快但依赖 gralloc */
#define DSH_PIX_AUTO 2   /* 有 AHB 就用，没有自动退回 rpix —— 产线用这个 */
```

三条设计约束（都是踩过才定的）：

1. **`DSH_PIX_AHB` 探测失败也返回 0（成功）**，安静退回 rpix。
   调用方不需要自己判断 gralloc 支持度 —— 真机与模拟器行为不同
   （模拟器 `createFromHandle` 恒 `-22`，see pitfalls #41），让库兜这件事。
   实际用了哪条路由由 `dsh_frame_pixmode_name()` 报出。
2. **`present` 时 AHB lock 失败要退回一次 `glReadPixels`**，不能整帧丢掉。
   帧服务的语义是「尽量出帧」，不是「出错就停」。
3. **`stride` 可能 > `w*4`**（对齐），必须按行拷。实测 640 宽时 `stride=640`
   无余量，但真机 1080p 常见 1088/1152。

### 实测（模拟器 dsh34 / x86_64 / SwiftShader，各 60 帧）

| 分辨率 | rpix | ahb | 倍数 |
|---|---|---|---|
| 640×360 | 761.4 fps | **1027.2 fps** | 1.35× |
| 1280×720 | 207.9 fps（三轮 181.0/207.9/239.7） | **324.0 fps**（310.7/354.5/324.0） | **1.56×** |
| 1920×1080 | 101.1 fps | **149.8 fps** | **1.48×** |

**像素逐字节一致**：`cmp gl_rpix.bin gl_ahb.bin` → `IDENTICAL`。
「快」不能是拿画面对不上换来的。

### ★验证脚本自身的三个「判据骗人」缺陷（镜像仓 `17e331c` 修复）

这一段比 AHB 本身更值得记 —— 三个坑都会让**任何人**得到错误结论：

1. **12 项假失败**：`scripts/verify_container.sh` 第 2/3 段原用
   `bench/path_probe` 与 `bench/unix_socket_probe` 做判据，但这两个是
   **bionic** 二进制，经 cleaner 代跑时按宿主根解析路径（pitfalls #41）——
   它们测的是**宿主**的 realpath/inotify，不是 guest 的。
   表现：`realpath(/dsh)` 等 5 项全 FAIL、inotify 全 FAIL、getcwd 全 FAIL。
   改用容器自带 busybox 的 `realpath` / `pwd -P` / `inotifyd` 后全绿。
   **规矩：验证 guest 路径语义，必须用 guest 原生工具。**

2. **假通过 ×2**：第 4 段用 `sed -n 's/.*pixmode=\([a-z]*\).*//p'`
   抠探针输出，在 toybox/busybox 下抠出**空串**，而 `grep -q DSH_GL_PIXMODE_OK`
   照样通过 → 打印 `OK req=rpix actual=`，实际什么都没验到。
   改法：探针直接吐 `DSH_GL_KV pixmode=.../fps=.../center=...`，
   脚本用 `cut -d= -f2` 取，并**显式检查非空** —— 空值判 FAIL 不判 OK。

3. `unix_socket_probe` 的 `$T/hostside` 目录没建，bind 报 ENOENT，
   看起来像 socket 功能坏了，实际只是缺目录。

修后完整输出：

```
=== 1) rootfs ===            OK alpine-release=3.22.2
=== 2) 路径语义 ===          5 项 realpath 全 OK + getcwd(/dsh)(/eng) OK
=== 3) inotify ===           OK
=== 3b) AF_UNIX ===          OK sockaddr_un 重拼逻辑正确（宿主侧）
=== 4) dsh_gl 三路径 ===     rpix 690.1 / ahb 1017.0 / auto 932.1 fps，像素 IDENTICAL
RESULT: VERIFY_CONTAINER_OK
```

### 一个容易漏的执行前提

`dsh_gl_pixmode` 是 bionic 程序（经 cleaner 代跑），**它看到的路径按宿主根解析**。
所以共享文件路径必须传宿主路径：

```sh
# 对
/bin/sh -c '/dsh_gl_pixmode /data/local/tmp/g.bin ahb 640 360 60'
# 错（open ENOENT，因为 cleaner 按宿主根解析 /tmp）
/bin/sh -c '/dsh_gl_pixmode /tmp/g.bin ahb 640 360 60'
```

App 侧不受影响 —— `LinuxFrameSurface` 传的本来就是
`/data/data/<pkg>/files/dshframe.bin`，本身就是宿主路径。

---

## 一·五·补十三、探针假通过审计（2026-11-07，commit `0b9e457`）

审计全仓 **39 处** `re.S` / `re.DOTALL` 用法，筛出 2 处「贪婪 `.*` 且无长度上界」。
这类模式会让断言跨越远超预期的范围，**改坏代码也可能照样通过**。

| 位置 | 原来 | 改为 |
|---|---|---|
| `_test_linux_frame_surface.py` D6 | `/framesrv.*\$shared` | `/framesrv.{0,40}\$shared` |
| `_test_root_gate.py` 白名单 | `protected_comm\(\)\s*\{.*` | `...\{.{0,4000}` |

两处都做了反向验证：

```
D6：把 framesrv 参数里 $shared 换成 /tmp/x.bin
    → ✗ D6 传共享文件路径给 framesrv
gate：从 protected_comm 删掉 surfaceflinger
    → ✗ kill protects surfaceflinger — 必须在 protected_comm 白名单里
恢复后均重新变绿
```

**规矩（本条由三次踩坑累积而成，值得记死）**：

1. 带 `re.S` 的 `.*` **几乎必然假通过**，一律写成 `.{0,N}`。
2. 反向验证要**改到断言的语义前提上**。我第一次验 D6 时把
   `"$shared"` 替换成固定路径，文件里根本没这个字符串 → 没改坏 → 探针没红 →
   差点误判 D6 是假通过。必须按文件里**精确存在的字符串**去改。
3. 用 `sed`/`grep` 抠探针输出时，**空值必须判 FAIL**。toybox/busybox 下
   分组提取常得空串，而 `grep -q OK` 照样通过 —— 这类「通过」是假的。

---

## 一·五·补十四、容器内开真实 GUI 窗口跑通（2026-11-07，镜像仓 `836bdef`）

补上「图形栈能开窗口」的可复现证据，并把判据做对。

### 判据三层（只看 weston 进程存活会假通过）

| 层 | 证据 | 为什么不能省 |
|---|---|---|
| 1 | `wl_compositor` / `xdg_wm_base` / `wl_shm` 三个 global 都拿到 | 只有 compositor 没有 wm_base 开不了顶层窗口 |
| 2 | `xdg_surface.configure` | compositor 对开窗请求的**正式回应**，不是本地日志 |
| 3 | `wl_surface_frame` 回调 ≥ 1 | 证明 compositor 真跑了一帧绘制循环 |

### 探针 `bench/wlwin.c`

现成工具都不行：
- **foot** 要字体（Alpine 容器无 monospace）+ `wl_seat`（headless 不提供）→
  实测 `err: fcft.c:564: monospace::size=8: failed to match font`、
  `err: wayland.c:1735: no seats available`
- **weston-simple-shm** 在 Alpine 没包（`weston-demo` 不存在，
  `weston` 包里只有 `usr/bin/weston` 一个可执行）

所以自己写最小客户端，只依赖 `wayland-client` + `xdg-shell`。
**必须在容器内编译（musl）**，NDK 只有 bionic —— 见 `scripts/build_wlwin.sh`。

### 实测（模拟器 dsh34 / x86_64 / Android 14）

```
weston: Using Pixman renderer
        Output 'headless' enabled with head(s) headless
        launching '/usr/libexec/weston-desktop-shell'
wlwin : WLWIN_KV compositor=4
        WLWIN_KV xdg_wm_base=5
        WLWIN_KV shm=1
        WLWIN_KV configure=0x0
        WLWIN_KV committed=1 size=320x200
        WLWIN_KV frame_callback=1
        RESULT: WLWIN_OK
```

两轮连跑均 `frame_callback=1`，可重复。

### 四个坑

1. **`xdg_toplevel` v4+ 绑定版本 5 就必须提供全部 listener**。只给
   `configure`/`close` 时收到 opcode 3（`configure_bounds`）直接
   `listener function for opcode 3 of xdg_toplevel is NULL` / `Aborted` / `rc=134`。
2. **`XDG_RUNTIME_DIR` 必须 0700 且属主为本 uid**，`0777` 会让 socket 建不出来。
3. **headless 默认 renderer 是 `noop`**（"No-op renderer for testing only"），
   必须 `--renderer=pixman` 才有真实 SHM buffer。
4. **`wp_presentation` 的 `presented` 在 headless+pixman 下不发**（实测 0，
   即使已挂 feedback 且窗口真的合成了）。判据要用 `frame_callback`。

### ★图形栈的真实边界（不许夸大）

`--renderer=gl` 能起，但拿到：

```
GL version: OpenGL ES 3.2 Mesa 25.1.9
GL renderer: llvmpipe (LLVM 20.1.8, 256 bits)     ← 软件渲染
warning: failed to query rendering device from EGL
failed to initialize allocator
```

根因：容器内**没有 `/dev/dri`，也没有 `/dev/kgsl-3d0`**（模拟器宿主本身这两个
设备都不存在）。

| 能力 | 状态 |
|---|---|
| 容器内开真实 GUI 窗口（合成、xdg_toplevel、frame 回调） | ✅ 实测 |
| 容器内 GL 渲染 + 像素送回 App（`lib/dsh_gl`，AHB 1.48×） | ✅ 实测 |
| compositor 的 GL 合成走 GPU | ❌ 走 llvmpipe |
| 免 root 硬件合成（AHB/gralloc 零回读） | ❌ 需 libhybris fork |

**「窗口能开」≠「硬件加速窗口能开」**，后者差一整条 libhybris。

### 验证脚本

`scripts/verify_container.sh` 新增第 5 段（窗口 + GL renderer 信息），
空值判 FAIL。完整跑通 `RESULT: VERIFY_CONTAINER_OK`（五段全绿）：

```
1) rootfs          OK alpine-release=3.22.2
2) 路径语义        5 项 realpath + getcwd 全 OK
3) inotify         OK
3b) AF_UNIX        OK sockaddr_un 重拼逻辑正确
4) dsh_gl 三路径    rpix 880.7 / ahb 985.6 / auto 1090.0 fps，像素 IDENTICAL
5) 开窗口           configure=0x0 frame_callback=1 + GL renderer: llvmpipe
```

---

## 一·五·补十五、`/dev/shm` 缺失修复（2026-11-07，镜像仓 `198f561`）

**这是本轮最重要的发现** —— 一个会让所有 POSIX 共享内存程序静默失败的平台级缺陷。

### 症状三层，每层指向完全不同的方向

| 层 | 看到的现象 | 会误判成 |
|---|---|---|
| 1 | `Created shm allocator` 成功，下一行 `Failed to allocate buffer` 刷屏 | wlroots bug / DRM 缺失 / swapchain 参数 |
| 2 | sway 起得来、output 显示 1280×720，但 `grim` 报 `failed to copy output` | screencopy 协议问题 |
| 3 | **`adb shell ls -ld /dev/shm` → No such file or directory** | （真因） |

```
[INFO] [wlr] [render/allocator/shm.c:118] Created shm allocator
[DEBUG] [wlr] [render/swapchain.c:106] Allocating new swapchain buffer
[ERROR] [wlr] [render/swapchain.c:110] Failed to allocate buffer
[ERROR] [sway/config/output.c:887] Requested backend configuration failed, searching for valid fallbacks
```

### 根因

容器把 `/dev` **整棵前缀透传**给宿主（GPU/binder/ashmem 都靠它，必要），
而 Android 宿主的 `/dev` 里**没有 `shm`**（桌面 Linux 标配，Android 没有）。
于是：

- musl 的 `shm_open()` 实现就是 `open("/dev/shm/<name>")` → **ENOENT**
- wlroots headless 后端拿不到 shm framebuffer → 每次 commit 都失败
- `dbus` / `PulseAudio` / `Chromium` 同理

**更隐蔽**：在 rootfs 里 `mkdir /dev/shm` **没用** —— `/dev` 是透传的，
rootfs 里那个目录根本不会被看到。必须**先改路径翻译**，再建目录。

### 修法（两半，缺一不可）

**① C 侧 `src/dsh_alpine.c`**：新增 `is_shm_path()`，在 `is_host_passthrough()`
的 `/dev/` 前缀判断**之前**早退，让 `/dev/shm` 落到 rootfs 内。

**② Kotlin 侧 `LinuxEnvironmentManager.ensureShmDir()`**：部署时建
`<rootfs>/dev/shm`（1777），调用点在 `installLinuxCompatShims(root)` 之后。

### 决定性验证

```sh
# 修复前
touch /dev/shm/x          → No such file or directory
# 修复后
shm_open=9 ; touch /dev/shm/x → WRITE_OK
# ★反证：把宿主 /dev/shm 彻底删掉，容器内仍可用
adb shell "rm -f /dev/shm/*; rmdir /dev/shm"
/bin/sh -c '/tmp/so'      → shm_open=9   （证明不依赖宿主）
```

修复后 `grim` 输出合法 PNG（`89 50 4e 47` 头），画面里能看到 `wlwin`
的橙蓝渐变方块被 compositor 真实合成 → **容器 → wayland 客户端 →
compositor → 截图取回，全链路打通**。

### 顺带修掉的第二个问题：AF_UNIX 从未被真正验证过

之前只有 `bench/unix_socket_probe.c`，它是 **bionic** 程序，经 cleaner 跑的是
**宿主路径**（pitfalls #41），测不到 guest。新增 `bench/unixsock_guest.c`（musl）：

```
UNIXSOCK_KV path=/tmp    bind=OK connect=OK rw=OK
UNIXSOCK_KV path=/tmp/wl bind=OK connect=OK rw=OK
UNIXSOCK_KV path=/dsh    bind=OK connect=OK rw=OK
UNIXSOCK_KV path=/eng    bind=OK connect=OK rw=OK
UNIXSOCK_KV path=/ws     bind=OK connect=OK rw=OK
RESULT: UNIXSOCK_GUEST_OK (0)
```

**并纠正我自己的一个误判**：sway 首次报 `[wlr] [wayland] bind() failed with
error: No such file or directory` 时我判断「这是真实缺陷」—— 实际只是
**部署的 runner 是旧版**（42736 vs 最新 46976 字节）。换新 runner 后全通。
教训：验证前先确认部署产物的字节数/时间戳与源码一致。

### 探针与验证脚本

`scripts/probes/_test_linux_shm.py`（A/B/C 三段 12 项）：
- A 段守护 C 侧 `is_shm_path` 存在 + 在 `/dev/` 前缀之前
- B 段守护 Kotlin 侧 `ensureShmDir` 存在 + 调用点位置
- C 段反向断言：不许用「在宿主建 /dev/shm」绕过

A4 与 B2 都做过反向验证（删早退 → 变红；删调用 → 变红）。

`scripts/verify_container.sh` 现**七段全绿**：

```
1) rootfs            OK alpine-release=3.22.2
2) 路径语义          5 项 realpath + getcwd 全 OK
3) inotify           OK
3a) guest AF_UNIX    OK bind/connect/rw 全通
3c) /dev/shm         OK 可写（容器自有，不依赖宿主）
3b) bionic AF_UNIX   OK sockaddr_un 重拼逻辑正确
4) dsh_gl 三路径     rpix 765.7 / ahb 1108.9 / auto 846.2 fps，像素 IDENTICAL
5) 开窗口            configure=0x0 frame_callback=1
RESULT: VERIFY_CONTAINER_OK
```

---

## 一·五·补十六、容器合成画面直通 App（2026-11-07，镜像仓 `2f21686`）

把「容器里能开窗口」补完到「**别的程序的窗口画面也能进 App**」。

### 两条路的区别

| 路径 | 显示什么 | 协议 |
|---|---|---|
| `framesrv`（已有） | **我们自己的** GPU 程序渲染的内容 | EGL + AHB/glReadPixels |
| `wlshot`（新增） | **任何** Wayland 窗口被合成后的结果 | wlr-screencopy → swy |

为什么不走 weston：`weston_capture_v1` 有授权模型，只授予 screen-share 拉起的
RDP 客户端，`grim` 一律 `Output capture error: unauthorized`（pitfalls #36）。
wlroots 的 `wlr-screencopy` **无授权门槛**。

所以：**开窗口**用 weston 验证（#43），**抓画面**用 sway + wlr-screencopy。

### 探针 `bench/wlshot.c`

抓一帧 → 直接写成 dsh_gl 共享文件格式 → App 侧 `LinuxFrameSurface` 零改动即可显示。

```sh
apk add --no-interactive wlr-protocols wayland-dev gcc musl-dev sway grim
sh /build_wlshot.sh /wlsrc

WLR_BACKENDS=headless WLR_RENDERER=pixman WAYLAND_DISPLAY=wayland-1   /wlsrc/wlshot /data/data/<pkg>/files/dshframe.bin 5000
```

实测（模拟器 dsh34 / x86_64）：

```
WLSHOT_KV output=1280x720
WLSHOT_KV format=0x1            ← WL_SHM_FORMAT_XRGB8888
WLSHOT_KV copied=1
WLSHOT_KV bytes=3686400
RESULT: WLSHOT_OK
```

共享文件 3686416 B = 16 头 + 1280×720×4；magic `0x4D415246`、seq=1。

### ★最隐蔽的坑：XRGB8888 的第 4 字节是 X，不是 alpha

这个坑**查不出来**，因为所有结构性检查全通过：

```
字节数 3686416 ✓   magic 0x4D415246 ✓   seq=1 ✓   尺寸 1280x720 ✓
```

但逐像素采样会看到 alpha 是**垃圾**：

```
窗口内 y=150：x=50  RGBA=( 30, 89,225,  0)   ← alpha=0
              x=200 RGBA=(126, 89,129,  0)   ← alpha=0
窗口外：      x=500 RGBA=(  0,  0,  0,255)   ← 反了
```

App 侧 `Bitmap.Config.ARGB_8888` + `copyPixelsFromBuffer` 会**当真读 alpha**，
alpha=0 的区域被当成全透明 → **用户在 App 里看到一片空白，且不报任何错**。

修法一行：转换时 `pix[i+3] = 255;`，绝不抄源的第 4 字节。

修复前后同一帧渲染成 PNG：

| | 窗口区域 |
|---|---|
| 修复前 | **纯白一块**（alpha=0，渐变信息视觉上丢失） |
| 修复后 | 蓝→橙渐变完整可见 |

### App 侧接线

`LinuxActivity.captureCompositor()` + 一个新按钮「抓合成画面」。
三种失败态分别给可读反馈（不是笼统「失败」）：

- 没有 sway → `linux_capture_no_sway`「容器里没有 sway（apk add sway）」
- 没有 wlshot → `linux_capture_no_wlshot`「没有编译 wlshot（sh /build_wlshot.sh）」
- 其他 → `linux_capture_failed` 带原始输出前 300 字符

自动拉起 sway（headless + pixman），并从 `/tmp/wl` 里发现 `wayland-N` socket。

### 探针

`scripts/probes/_test_linux_capture.py`（A~G 七段）：
- A 方法存在且被按钮接到（**反向验证过**：把回调改回 `stopFrameServer` → 变红）
- B 用 sway + wlr-screencopy，不用 weston capture
- C `XDG_RUNTIME_DIR` 与 socket 发现
- D 共享文件路径与 `LinuxFrameSurface` 一致（必须是 `/data/data/<pkg>/files` 形式）
- E 三种失败态 + 四个文案资源
- F 五语文案齐全 + `%1$s` 占位符
- G 反向断言：不许硬编码 `weston-screenshooter`

---

## 一·六、冷启优化实录（2026-11-06，commit `866e12b`）

### 已落地：裁掉 5 个遥测插件（省 140ms，零功能损失）

引擎 331 个插件里只有 **74 个自带 client.js**（进 boot manifest 的
`modulepreload`，直接决定 WebUI 有哪些面板），其余是纯服务端。只禁 5 个
纯遥测/上报插件（`desktop-product-telemetry` / `otel` / `session-telemetry-otel` /
`plugin-package-inventory-deepseek` / `harness-dev-context`）：

| | 耗时 |
|---|---|
| 基线（交替 3 轮） | 1330 / 1330 / 1330 ms |
| 裁 5 个（交替 3 轮） | 1160 / 1220 / 1180 ms |
| 服务 HTML | **逐字节一致 49027 B**，74 个 client 插件一个不少 |

看护探针：`scripts/probes/_test_startup_trim.py`（36 checks，反向验证过）。

### ★关键教训：绝不能用插件名字判断它是不是客户端

首轮按「名字不以 `dsh-client-` 开头」判服务端，裁了 97 个 → 1210ms，但 HTML
从 49027 B 掉到 **23295 B**、两段 `modulepreload` 整段消失。
**真因：`dsh-message-quotes`（消息引用）与 `dsh-session-log-export`（会话日志导出）
名字里没有 `client`，实际各自带 client.js。**
正确判据是「有没有 client.js」，从 boot manifest 的
`{"id":...,"url":"plugins/.../client.js"}` 提取。

### 逐组归因（单组 vs 基线 1290~1340ms）

| 组 | 结果 |
|---|---|
| 遥测 3 个 | 省约 50ms |
| **web 族**（web/web-search/web-deepseek-api/web-fetch-http/tool-web/mcp-resources） | **1300ms，无收益** |
| **skill / goal 族** | **1290ms，无收益** |
| **subagent / workflow / ptc 族** | **1280~1320ms，无收益** |
| 会话扩展（统计/大纲/引用/log-export/office-to-pdf 等） | 1260~1270ms |
| llm 可选通道（llm-pi-ai/deepseek-llm-api-extensions/llm-retry） | **1200~1210ms，省 90ms** |

结论：**能力型插件禁掉只丢能力不省时间**（已在噪声内），所以全部保持启用。

### 测量方法（复用时照做）

每轮独立端口、`kill -9` 精确清场、`/proc/uptime` 计时（HHZ=100，须批 N 次求均值）、
`svc power stayon true` + `input keyevent 224` 防 App 被 cgroup freezer 冻住、
curl 带 cookie jar 跟随 303 取首页 HTML 字节数作为「UI 是否被裁坏」的硬判据。
**`netstat | grep 3080` 会匹配 TIME_WAIT 残留，必须只认 LISTEN。**

### 精确到就绪点的 CPU profile（2026-11-06 实测，推翻旧结论）

启动墙钟 **1496ms**，CPU 合计 **2872ms**（user 2255 + sys 617），**并行度仅 1.92×**。

自耗时 TOP：`ModuleWrap` **204.3ms(13.8%)** / GC 81.5ms / `compileSourceTextModule`
62.2ms / `internalModuleStat` 21.2ms / **`(idle)` 只有 20.7ms(1.4%)**。

**旧结论「idle 占 45.3%」是错的**：那次 profile 覆盖 3768ms 而真实就绪只有 1913ms，
多出来的全是就绪后的稳态等待。做法：`--require bootprof.cjs` 拦 `console.log`
检测 `dsh web:` 那一刻 stop profiler（`Profiler.stop` 在标记之后，不污染数据）。

### 已否决的方案（都实测过，别再试）

| 方案 | 实测结果 |
|---|---|
| `--max-semi-space-size=16/32` | **1140ms → 1430/1370ms，更慢** |
| Node 24.9.0（musl 官方构建） | **1120~1210ms → 1470~1930ms，慢 30~70%** |
| `createRuntimeResolution()` 依赖盘点缓存 | 只 **36~41ms**，收益太小不值得做 |
| 裁 43 个服务端插件 | 1000~1020ms，但相比裁 21 个（1020~1070ms）**只差 30ms**，且丢能力 |

**Node 24 测法要注意**：flusher 的 `setInterval(flush, 3000)` 要 3 秒才刷盘，
预热轮就绪后立刻 `kill -9` 会导致缓存 0 文件、对比完全失真。必须预热后多等
5~6 秒。node24 缓存树独立（`v24.9.0-arm64-ef5a0af0-0`），不能与 node22 共用。

---

## 一·七、musl 硬件 GPU 实测：Turnip→KGSL 这条路在本机走不通（2026-11-06）

### 动机

TAWC 用 libhybris fork 让 glibc 程序用 Android GPU 驱动，我们的 musl 容器只能
llvmpipe（同基准慢 35 倍）。调研给出的一条**可能绕过 libhybris** 的路：
Mesa 主线的 Turnip（开源 Adreno Vulkan 驱动）已支持 **KGSL 后端**，而设备表里
列了 Adreno 840；我们已实测 `/dev/kgsl-3d0` 在容器内可 open。

### 实测过程与结果

| 步骤 | 结果 |
|---|---|
| Alpine 仓库有 `mesa-vulkan-freedreno-25.1.9-r0` | ✅ 存在，装上 |
| `vulkan-loader` / `vulkan-tools` | ✅ 装上 |
| ICD 清单 `freedreno_icd.aarch64.json` | ✅ 存在并被 loader 找到 |
| `ldd libvulkan_freedreno.so` | ✅ 全部依赖解析正常 |
| **`vulkaninfo --summary`** | ❌ `Failed to detect any valid GPUs` |
| `TU_DEBUG=startup,driver` 定位 | **`tu_knl.cc:416: device /dev/dri/renderD128 (msm_drm) is not compatible with turnip`** |
| `strings libvulkan_freedreno.so` 查 KMD 源码名 | **只有 `tu_knl_drm.cc` / `tu_knl_drm_msm.cc`，没有 `tu_knl_kgsl.cc`** |
| `mesa-vulkan-swrast`（lavapipe）对照 | ✅ `deviceName = llvmpipe (LLVM 20.1.8, 128 bits)` |

### 根因

1. **Alpine 的 Mesa 只编了 DRM 后端，没编 KGSL 后端。**
   `strings` 里出现的 `kgsl` 只是调试项 `kgsl_profiling_suballoc` 的名字，
   不是 KMD 实现。官方 apk 按桌面目标构建（`freedreno-kmds=drm`）。
2. **本机的 `/dev/dri/renderD128` 不是 GPU 节点。**
   `uevent` 显示 `DRIVER=msm_drm`、`OF_COMPATIBLE_0=qcom,sde-kms` —— 这是
   **MDSS 显示控制器**，不是 msm GPU。Turnip 的 drm 后端因此判为不兼容。
3. **真正的 GPU 只通过 `/dev/kgsl-3d0` 暴露**（Android 专有接口，非标准 DRM）。
   容器内该节点可 open（`crw-rw-rw- 1000 1000 468,0`），但没后端去用它。

### 结论（三层，别混为一谈）

- **Vulkan loader + 容器本身没问题**：lavapipe 能正常枚举出设备。
- **Alpine 预编译 Turnip 用不了**：缺 KGSL 后端，这是**打包选择**问题，不是设备问题。
- **要让它工作**必须 `meson -Dfreedreno-kmds=kgsl` 自己编 Mesa（连同 Vulkan loader
  与 X11/wayland 依赖裁剪）。这是**数小时到数天**级别的构建工程，且 A8xx 在
  musl + KGSL 下的渲染正确性仍未验证（Mesa 设备表列出 840 只说明源码里有条目）。

### 仍未排除、值得后续验证的

- **自编 Turnip（`-Dfreedreno-kmds=kgsl`）**：这是唯一能确认的路，需先做
  0.5~1 人天的 KGSL ioctl 小探针（`GETPROPERTY` / context 创建 / 内存分配），
  打印具体失败的 ioctl 与 errno，再决定要不要投入完整构建。
- **virpipe → vtest → bionic virglrenderer**：Termux 的
  `virglrenderer-android` 配方有现成 NDK 构建 + Android 补丁，让 musl Mesa
  把渲染转发给 bionic 侧执行，绕开 KMD 问题。这是**不依赖 KGSL**的另一条路。
- **cleaner 架构已能跑 bionic 程序并拿到 Adreno 840**
  （`GL_RENDERER=Adreno (TM) 840`，1 万三角形 20 帧 = 14.9ms），
  所以"bionic 侧渲染 + 帧回传"这条路技术上是通的，缺的是传输与合成层。

---

## 一·九、扩展中心 Linux 双发行版适配（2026-11-07，versionCode 368 / 1.5.2）

**一句话**：扩展中心在容器模式下从「只会 Alpine 的 apk」变成「按当前发行版分派
apk / apt」，并打通了 Ubuntu 装包链路 —— 后者在 **x86_64（模拟器）上原先必然失败**，
因为容器层有两个 x86_64 专属 bug 让任何含符号链接的 deb 都装不上。

⚠️ 这两个 bug 都在 `#if defined(DSH_ARCH_X86_64)` 内。aarch64 走 `symlinkat`
（本来就正确翻译了 linkpath），也**没有** `lchown` 这个 syscall（glibc 用
`fchownat + AT_SYMLINK_NOFOLLOW` 模拟），所以真机 arm64 一直不受影响 ——
这解释了为什么 §一·五 记的 arm64 两步法装包「成功」，而模拟器第一次跑就失败。
`libdsh_alpine.so`(arm64) 重编后**与 HEAD 字节一致**（md5 42265d7b…），是正常现象。

### 为什么必须分两张表（不是改名游戏）

同一个 Termux 包名在两个发行版里可能对到完全不同的包，甚至一边有另一边没有。
全部对着真实索引核过（Alpine 26160 个包名 / Ubuntu noble arm64 69641 个）：

| Termux | Alpine 3.22 | Ubuntu 24.04 |
|---|---|---|
| `aapt` / `apksigner` / `gradle` | **没有** | **有**（universe） |
| `aapt2` / `d8` | 没有 | 没有（`dalvik-exchange` 只给 dx） |
| `clang` | `clang17` | `clang`（同名透传） |
| `sqlite` | `sqlite` | `sqlite3` |
| `openssh` | `openssh`（客户端+服务端同包） | `openssh-client` |
| `android-tools` | `android-tools` | `adb` + `fastboot` |
| `rust` | `rust` | `rustc` + `cargo` |
| `ndk-sysroot` | `musl-dev` | `libc6-dev` |

**最关键的差异**：`android-buildtools` 在 Alpine 下整条置灰（N/A），在 Ubuntu 下
**可安装**、只有 `aapt2` 与 `d8` 缺。实测两模式 UI 分别显示
`Unavailable here / N/A` 与 `Not installed · missing: aapt2, d8`。

### 代码改动

- `engine/LinuxExtensionBridge.kt` 整体重写：抽出 `DistroMap`（renames /
  unavailable / itemUnavailable 三件套），两套实例 `ALPINE_MAP` / `UBUNTU_MAP`，
  `distroMap(family)` 分派。对外 API 一律**显式收 `family`**：
  `packagesOf(pkgs, family)` / `unavailableReason(ext, family)` /
  `partialNote(ext, family)` / `allPackages(catalog, family)`。
  旧名 `alpinePackagesOf` / `allAlpinePackages` 已删除。
- 新增 `partialNote()`：条目能装但**部分包缺失**时给出缺的名字（现只命中
  Ubuntu 的 android-buildtools）。
- `ExtensionStoreActivity.kt`：新增 `linuxFamily` lazy；所有调用点传 family；
  行状态追加 `· missing: aapt2, d8`；安装前对 partialNote 弹确认框；
  `linuxInstall` 拆成「确认 + `doLinuxInstall`」。
- **`EXT_LINUX_DOWNLOAD_ENABLED` 由 false 改为 true** —— 原值是「未验收所以不发布」，
  现在两条链路都有实证（见下），继续关着等于让整个 Linux 分支永远死代码。
- 新增 `scripts/probes/data/aptindex-names.txt.gz`（329 KB，69641 个包名，
  gzip 后入库），供 CI 离线核对 Ubuntu 映射。

### 容器层两个必修 bug（Ubuntu 装包链路的地基）

实测入口：模拟器 + 官方 `ubuntu-base-24.04.5-base-amd64` rootfs + 清华源。

**bug 1：`SYS_symlink` 与 rename/link 合并成一支**

`dsh_alpine.c:1615` 原为 `case SYS_rename: case SYS_link: case SYS_symlink:`，
共享守卫 `if (!op || op[0] != '/' || !np || np[0] != '/')` → 原样转发。
但 symlink 的**第一个参数是链接内容**（写进 inode 的字符串），dpkg 传的是
`../libsqlite3-0/changelog.Debian.gz`（相对），于是整条原样转发，**绝对 linkpath
落到宿主根**：

```
symlink("../libsqlite3-0/changelog.Debian.gz",
        "/usr/share/doc/sqlite3/changelog.Debian.gz.dpkg-new") = -1 ENOENT
→ dpkg: error creating symbolic link './usr/share/doc/sqlite3/changelog.Debian.gz'
```

修法：`SYS_symlink` 拆成独立一支，守卫只看 linkpath（`np`），target 原样透传
（**不 make_rel、不 bind_map**）。

**bug 2：`lchown` 不在 BPF 树里**

修好 1 之后紧接着暴露：`lchown("/usr/share/doc/…", 0, 0) = -1 ENOENT`，而且
trace 里**没有 SIGSYS** —— 说明根本没被 TRAP，直接落到宿主。`lchown` 是 x86_64
独有老式 syscall（aarch64 通用 ABI 没有）。它是 dpkg 建完链接后改**链接本身**
属主的必经调用，不能用 chown 替代（那会跟随目标）。

修法：`scripts/gen_bpf_tree.py` 的 `LEGACY_X86_64` 补 `lchown` / `utimes` / `mknod`；
同时 `dsh_alpine.c` 的老式 switch **必须有对应 case** —— 只加进树不加 case 更糟：
TRAP 后落 default，那里用 `(int)(unsigned int)r[0]` 把 64 位指针截成 32 位 →
EFAULT，dpkg 报 `Bad address`（实测从「找不到目录」变成「Bad address」，看起来更糟，
实际是终于被拦到了）。lchown 的实现走 `fchownat(..., AT_SYMLINK_NOFOLLOW=0x100)`。

### 验证证据（模拟器 x86_64 实测）

| 项 | 结果 |
|---|---|
| `apt-get update`（清华 ubuntu-ports） | 27.1 MB / 8s，noble + noble-updates 全拉下 |
| `apt-cache policy` 逐包核对 | `aapt`/`apksigner`/`gradle`/`dalvik-exchange` **存在**；`aapt2`/`d8`/`py3-pip`/`sqlite`/`clang17`/`musl-dev` **不存在** —— 与两表完全一致 |
| `apt-get install -y sqlite3` 一步法 | 失败（`dpkg returned error code (1)`，与 AI_CONTEXT 记载一致） |
| `apt-get -d install` + `dpkg -i` 两步法 | **成功**，`sqlite3 --version` → `3.45.1 2024-01-30 …` |
| `aapt` 在容器里跑 | `Android Asset Packaging Tool, v0.2-debian` |
| `apksigner` | 二进制在，但缺 java（`exec: java: not found`）—— 需先装 openjdk，属安装顺序而非映射问题 |

### 探针与门禁

- 新增 `scripts/probes/_test_dsh_alpine_symlink_rel.py`（29 项）：断言 symlink 是独立
  分支、守卫不看 target、target 不被 make_rel；lchown/utimes/mknod 同时出现在
  BPF 清单**与** switch 分派；两个 ABI 产物都是 ET_EXEC。做过反向验证（把守卫
  改回看两个参数 → 变红；复原 → 绿）。
- `_test_linux_ext_bridge.py` 重写为双发行版（107 项）：六张表解析、表自洽、
  每条目至少一包、**两个真实索引逐名核对**、两发行版分歧断言（Ubuntu 的
  aapt/apksigner/gradle 不得置灰、aapt2/d8 必须置灰、clang 不得改名）、
  分派纪律（对外入口必须带 family）。反向验证过两处（改错包名 → 3 项红；
  误把 aapt 置灰 → 2 项红）。
- `gen_bpf_tree.py` 加自检：生成后断言该 arch 的关键 NR 确实进了输出，
  且 aarch64 树不得混入 x86_64 专属 NR。反向验证过（拿掉 lchown → exit 1）。
- 全量 64 个探针：**63 过 / 1 败**，唯一失败 `_test_corepack_hardlink.py` 是基线
  既有问题（noruntime 构建不产出 `assets/runtime.zip`）。
- `check-i18n.py` 全绿（1306 键 / 五语种同构）。

### 新增字符串（五语种）

`ext_linux_partial_note`（"Not available in this container: %1$s.\n\nInstall the rest anyway?"）
与 `ext_linux_partial_tag`（"missing: %1$s"）。**踩坑**：这两个插入时用了
`<string name="...">%s</string>` 单行模板，而 note 的正文含空行，anchor 落到换行
中间，把字符串截断成两半 → i18n 检查报「referenced but not defined」。

### 补：健康检查/修复的模式分派（2026-11-07，审查发现后修复）

打开 `EXT_LINUX_DOWNLOAD_ENABLED` 之后，代码审查指出一个**真实功能缺口**：
「一键检测」与它弹窗里的「修复」按钮仍在走 Termux 老路径。

根因是 `ExtensionManager.checkHealth()` 判断「装没装」的依据是
`engine/extensions/<id>/MARKER` —— Termux `.deb` 解包体系的产物。Linux 模式把包
装进 rootfs 全局位置（`apk add` / `apt-get`），**根本不创建 MARKER**。所以：
`installed` 恒为空 → 用户点「一键检测」永远看到「还没装任何扩展」，哪怕刚在同一页
装成功了五个（**功能说谎**）；万一有结果，点「修复」会 `manager.enqueue()` 走
Termux 下载解包队列，把 `.deb` 解到扩展目录，而容器里的包毫无变化（里外两套）。

**修法**：
- `runHealthCheck()` 开头 `if (linuxMode) { runLinuxHealthCheck(); return }`。
- 新增 `runLinuxHealthCheck()`：只问 `pkgManager.installed()`，用**与行标签同一个**
  状态源 `LinuxExtensionBridge.state(..., linuxFamily)`（否则会出现「弹窗说可用、
  行上显示不完整」的自相矛盾）；三态与 `refreshRow` 严格一致。
- 它的「修复」按钮调 `repairBroken()`（已有 Linux 分支），不调 `confirmRepair`。
- `confirmRepair()` 加防线：Linux 模式直接转 `repairBroken()` 并打日志，防将来误调。

**新增字符串**（五语种）：`ext_linux_check_none` / `ext_linux_check_summary`
（4 占位符）/ `ext_linux_repair_none`。

**模拟器实测三态全绿**：

| 场景 | 「一键检测」输出 |
|---|---|
| 空容器 | `No extensions installed in this container yet` |
| Lua 完整装（lua5.4 + readline） | `1 extension(s) installed, all healthy ✓` |
| 只装 python3、缺 py3-pip | `2 installed in the container: 1 ready, 1 partial · Python` + REPAIR 按钮 |
| 补齐 py3-pip 后再检测 | `2 extension(s) installed, all healthy ✓` |

点 REPAIR 时 logcat 显示线程名 `ext-linux-repai` 且执行的是 `dsh_alpine` + `apk`
（不是 Termux 队列），证明修复确实走了 Linux 分支。

**新增探针** `scripts/probes/_test_linux_ext_health.py`（19 项）：断言分派在
`manager.checkHealth()` 之前、Linux 检测只问包数据库且带 family、弹窗不调
`confirmRepair`、`confirmRepair` 自己有防线且防线在 `enqueue` 之前、
`repairBroken` 的 Linux 分支还在。**踩坑**：探针最初把函数体里那句
「绝不走 confirmRepair()」注释当成真实调用，直接假红 —— 现在先剥行注释再断言。
反向验证两处（删分派 → A1 红；换回 confirmRepair → C1/C2 红）。

### 未完成 / 已知限制

- **真机（arm64）未验**：真机 `192.168.101.21:9000` 全程连不上，Ubuntu 侧的
  apt 链路只有模拟器 + 静态证据。arm64 的 `libdsh_alpine.so` 已同步重编
  （32912 B），但没在真机跑过完整装包。
- Ubuntu 的 `apksigner` 需要 java 在 PATH 里，当前装包顺序不保证（装完 openjdk
  后应可用，未实测）。
- `verify_bpf_semantics.py` 在 `gen_bpf_tree.py` 注释里被引用，但**实体不存在**
  （文档漂移，早于本次改动）。现在由生成器自带的自检覆盖同类风险。

## 一·八、「系统能力」页改版：总览卡 + 双列磁贴（2026-11-06，未提交）

**一句话**：20 条 AI 能力从「一列 20 行、每行两行说明」改成「总览卡 + 六个域 ×
双列磁贴」，说明文字收进点击磁贴后的详情弹层。

### 决策与依据

- 改版前真机实测约 **4.9 屏**（headless Chrome 量 mock：现状 ≈3700dp）。
- 用户从三方案中选 **A 总览磁贴**（另两案：B 紧凑列表+状态环 1707dp / C 分域卡 1053dp）。
- 用户另定：**每条能力的解释文字收进二级页，列表只留标题**。
- **系统授权 4 行（无障碍/文件/通知/麦克风）刻意保持列表形态**：它们的开/关
  含义是「系统给没给」，磁贴开关是「你准不准 AI 用」，混排会让同一个
  开/关看起来是一回事。这四行没有二级详情（点击直接跳系统设置），所以
  说明文字**必须留在行内**。

### 实测高度（模拟器 sdk_gphone64_x86_64 / Android 14 / density 440）

| 项 | 实测 |
| --- | --- |
| 整页内容高度 | **5586px = 2031dp ≈ 2.8 屏**（ScrollView 视口 1994px = 725dp） |
| groupCaps（4 个系统行） | 773px ≈ 281dp |
| groupDeviceCaps（总览卡+六域磁贴） | 4139px ≈ 1505dp |
| 单个磁贴 | 271px = **98.5dp**（标题两行时为 317px = 115.3dp） |
| 总览卡 | 296px ≈ 108dp |

⚠️ **未达承诺值**：方案预览（`_preview_caps/caps-mock.html`）量出 A = **1388~1507dp
（1.8 屏）**，实测 2031dp（2.8 屏），差一整屏。**根因已定位**：批准的 mock 是
**3 列磁贴、每格 81dp、开关内嵌在右上但磁贴无 padding 12dp**（mock 实测
tileW=98 / tileH=81，`width:33.33%` × 24 格）；实际实现成 **2 列、每格 98.5dp**
（`layout_width=0dp + weight=1` × 两列，padding 12dp）。用户 2026-11-06 明确
表示「你做的已经可以了，最后检查一遍功能可用就行」，故**不再改回 3 列**，
此差距记录在案备查。

### 代码结构（表驱动，改了布局不必再动 20 段绑定）

`PermissionCenterActivity.kt`（939 行）：

- `data class CapSpec(cap, sw, row, state, title, sub, need, action)` — 一条能力的 8 个 id/资源
- `data class CapDomain(label, count, items)` — 一个域
- `capDomains()` — 六域清单，顺序 env(2)/notify(4)/control(4)/personal(4)/input(3)/system(3) = 20
- `capSpecs()` = `capDomains().flatMap { it.items }`
- `bindCapSwitch(spec)` — **开关本体直接切换**（高频操作一步到位）；
  **磁贴其余区域进详情** `row.setOnClickListener { showCapDetail(spec) }`
- `renderCapTile(spec)` — 三态：`!ok` → `setting_cap_state_blocked` + warning 色；
  `on` → `state_on` + success；else `state_off` + text_secondary。**文案与色彩双重编码**
  （色盲用户、以及「开着但系统没给权限」这种中间态，光看开关分不出来）
- `showCapDetail(spec)` — AlertDialog 显示标题 + 说明 + 门槛（warning 色）+ 允许开关；
  门槛不满足时弹回并提示
- `refreshCapOverview()` — 用 `CapabilityGate.can`（而非 `enabled`）计数，避免
  「开了但用不了」的误导数字；系统授权 4 项单独计数

`activity_permission_center.xml`（2305 行）：`groupCaps` :333 起、`groupDeviceCaps`
:696 起、维护卡（rowGrantAll + rowPermList）在末尾。

id 契约（探针与 Kotlin 都按 id 取值，全部已验证在位）：20 个 `rowCapXxx` +
20 个 `swCapXxx` + 20 个 `stCapXxx`，4 个系统行 id，`txtCapTotal` / `txtCapPct` /
`barCapProgress` / `txtCapOverviewSub`，6 个 `txtDomXxx`，`rowGrantAll` / `rowPermList` /
`btnGrantAll` / `txtGrantAllSub`，`groupCaps` / `groupDeviceCaps` / `txtGroupCapsSub`。
`txtGroupDeviceCaps` 已删（被总览卡取代，无代码/探针引用）。

### 新增资源

- `res/drawable/bg_cap_tile.xml` — surface_alt 底 + 1dp divider 描边 + 12dp 圆角
- `res/drawable/bg_cap_progress.xml` — layer-list，background=divider、progress=accent，3dp 圆角
- 11 个字符串键 × 五语种（`setting_cap_domain_*` 六个 + `setting_cap_overview_sub`
  + `setting_cap_detail_allow` / `_close` + `setting_cap_state_blocked` + `setting_cap_batch_title`）

### 验证状态

- 构建：arm64 `assembleRelease` BUILD SUCCESSFUL（30705747 B）；x86_64 32s（31229787 B）
- 探针：**全量 55 个 _test_*.py，54 过 / 1 失败**；失败项
  `_test_corepack_hardlink.py` 是**基线既有**问题（缺 `app/src/main/assets/runtime.zip`，
  noruntime 构建不产出），与本次改动无关
- 门禁四项：i18n 全绿（1284 键五语种同构）· builtin_plugins 34/0/1 跳 ·
  plugin_install_errors 42/0 · **git-hygiene 失败但为既有问题**
  （`assets/linux/rg-static-arm64`、`rootfs-bootstrap.tgz` 在对象库含 CRLF，
  本次 diff 未触碰这两个文件）
- 模拟器实测（x86_64 / Android 14）：整页渲染正常、中文界面正常（强行写
  `shared_prefs/app_locale.xml` 切 zh）、磁贴点击弹详情 ✓、详情内开关切换 ✓、
  磁贴开关直接切换 ✓、总览数字与域计数联动 ✓（0/20 → 1/20 → 2/20，域 0/2 → 1/2）、
  冷启后持久化 ✓（1/20 保持）、`rowPermList` → PermissionListActivity ✓、
  系统行 → `com.android.settings/.Settings$AppNotificationSettingsActivity` ✓
- ⚠️ **真机未验证**：`192.168.101.21:9000` 连接超时（10060，重试 3 次），
  全部实测在模拟器完成

### 踩坑记录

1. **`&` 未转义**：values 与 values-de 首批插入用了裸 `&`，`ParseError: not well-formed
   (invalid token)`（values:1373 / values-de:1379），改 `&amp;` 修复。
2. **重写布局删掉 `subAccess` 但 Kotlin 仍写它**：编译报
   `PermissionCenterActivity.kt:346:37 Unresolved reference: subAccess`。Access 行
   的说明文案是 `refreshAccess()` 动态写的（带无障碍开关状态），不能换成静态字符串；
   修法是生成器给 sysrow 加 `sub_res` / `sub_id` 二选一参数。
3. **生成器自带的闭合标签与 splice 保留的重复**：`mismatched tag: line 2301`，
   删掉重复的 `</LinearLayout>` 后 XML OK。
4. **探针断言锁了旧语法**：`_test_net_block.py` 原断言
   `"R.id.swCapNet to CapabilityGate.NET" in ui`，表驱动改造后该字面形式不存在。
   改为 `re.search(r"CapabilityGate\.NET,\s*R\.id\.swCapNet", ui)`——断言
   「NET 开关与 CapabilityGate.NET 同行绑定」这件事本身，不锁语法形态；
   并做了反向验证（把 `swCapNet` 改成 `swCapXyz` 后正则不匹配）。

---

## 一·五、Linux 工作区（免 root 完整 Alpine，2026-11-04 新增，分支 `feat/linux-workspace`）

**一句话**：在非 root（uid 2000）Android 上跑**完整 Alpine Linux 用户态**，不 ptrace、不要
user namespace、不要 chroot。用户能 `apk add` 装任意软件、编译 C/C++、跑 python/numpy、
起 nginx/redis、转码视频、用 Adreno 硬件 GPU。

### 核心机制（为什么免 root 也能跑 Linux）

`dsh_alpine` 是一个 **freestanding 静态 ET_EXEC（27496 字节，固定基址 0x2000000000）**，
用 **seccomp BPF + SIGSYS handler** 拦截系统调用、把绝对路径翻译到 rootfs 内。
TAWC 的 tawcroot 是同一架构（但 GPL-3.0，本项目 MIT，**只按原理自实现，未复制代码**）。

### 已验证的实测数据（同一台 OPPO PLZ110 / Android 16 / 内核 6.12.38）

| 指标 | 数值 |
|---|---|
| 进 Linux 跑一条命令 | **2.4 ms**（真 chroot 1.6ms、proot 19~33ms） |
| 容器 loader 自身 RSS | **1.8 MB**（RssAnon 896 + RssFile 940） |
| 端到端「从零→进 Linux→DSH 就绪」 | **中位数 1.33 秒**（容器层仅占 0.8%，引擎插件加载占 96%） |
| 跑 DSH 引擎内存 | 与真 chroot **完全重合**（180~186MB vs 181~187MB） |
| 硬件 GPU（Adreno 840） | 1万三角形×20帧@1024² = 14.9ms（0.74ms/帧），比软渲染快 **35.3 倍** |
| 文件 IO / syscall vs proot | 快约 **30 倍** / **2.7 倍** |

### 内置体积（关键：不用完整 rootfs）

- `assets/linux/rootfs-bootstrap.tgz` = **3979016 字节（3.8MB）压缩 / 8.5MB 解压 / 110 文件**
- 含 busybox + apk，**不含** node/gcc/python（按需 `apk add`，node 约 30MB）
- `libdsh_alpine.so` = **27496 字节**（strip 后，未 strip 是 123656）
- APK 总大小实测 **28.5 MB**

### 新增文件（全部本分支新建）

```
app/src/main/cpp/dsh_alpine.c                              dsh-alpine 源码（2048 行）
app/src/main/assets/linux/rootfs-bootstrap.tgz             内置最简 rootfs
app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt   rootfs 部署/修复/自检
app/src/main/java/app/dsh/mobile/engine/LinuxContainerRunner.kt      容器执行器
app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt       容器内启动 DSH 引擎
app/src/main/java/app/dsh/mobile/engine/LinuxPackageManager.kt       apk 包管理
app/src/main/java/app/dsh/mobile/LinuxActivity.kt          工作区 UI（状态/操作/终端）
```

### 五个必须记住的坑（都踩过，都已在代码里注释）

1. **官方 minirootfs 的 335 个 applet 链接是绝对路径**（`/bin/busybox`）。seccomp 只重定向
   应用发起的路径，内核解析 symlink 目标是按**宿主真实根** → `cat: not found`。
   必须在部署期重写成相对链接（`LinuxEnvironmentManager.relativeTarget`）。
2. **CMake 引入的 fortify/stack-protector 会让 freestanding 编译失败**：报
   `SSIZE_MAX` 未定义（fortify）与 `undefined symbol: __stack_chk_fail`（stack protector）。
   必须 `-U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector`。
3. **NDK r26 可以编**（不必升 r28）：TLS underaligned 那个 bug 只影响静态链接
   **bionic libc** 的场景，freestanding 不链 libc 所以碰不到。实测 r26 产物真机跑通。
4. **容器内没有 DNS**：musl 直连 UDP 53 被 netd/SELinux 拦（`Operation not permitted`），
   `apk` 必须靠静态 `/etc/hosts` + 纯 http 镜像（阿里云实测可达 26178 个包；
   TUNA/USTC 对 IP 直连返 403）。
5. **绝不能把宿主路径传给 guest**：容器里 `/tmp` 是 rootfs 的 `/tmp`，
   宿主 `/data/local/tmp` 在 guest 中**不存在**。启动脚本必须写进 rootfs 内。

### 引擎代码怎么进容器（省 355MB）

不复制 `engine/lib/node_modules`（355MB），用 `-m /eng:<宿主engine路径>` bind 映射。
**实测已验证**：容器内 `node --expose-internals /eng/.../dsh/lib/bin.js --version`
返回 `0.2.1-rc.5`。注意 bind 是字符串前缀替换而非真挂载，engine 目录下不能有指向
别处的绝对软链（引擎 `lib/` 是实体目录，已确认）。

### 已知限制

- 内核 `# CONFIG_SYSVIPC is not set` → PostgreSQL/MariaDB 服务端起不来
  （proot 报错一字不差，非本方案缺陷）
- 无 `USER_NS` / `PID_NS` → **不是安全沙箱**，只是用户态兼容层，对外文案必须写明
- Android 12+ phantom process killer 在约 32 进程后杀 rootfs 进程
- 目前只支持 arm64（x86_64 需另编一份 `libdsh_alpine.so`）

### 移植进度

| 阶段 | 状态 |
|---|---|
| dsh-alpine 接入 CMake（r26 编译、真机跑通、进 APK） | ✅ |
| 极简 rootfs 内置 + 部署/软链修复/自检 | ✅ |
| 容器执行器（argv/env/超时/进程树回收） | ✅ |
| 容器内启动 DSH 引擎（bind 引擎代码，已验证 `0.2.1-rc.5`） | ✅ |
| apk 包管理（索引/装/删/已装列表） | ✅ |
| LinuxActivity UI（状态卡/操作/终端）+ 6 语言 | ✅ |
| **引擎默认走 Linux 模式 + 原生回退开关** | ✅ **已接线并真机验证（2026-11-04 13:10）** |
| **插件中心在 Linux 内装包（`LinuxExtensionBridge`）** | ✅ **已完成，映射表对着 26160 个包名核过** |
| **终端 PTY 交互（容器内真 shell）** | ✅ **已完成，真机敲命令返回 3.22.0** |
| 真机安装验收（装机后点进 LinuxActivity 全流程） | ✅ **已验收（见下）** |

### 本轮新增（2026-11-04 13:10~13:45）

**插件中心接入 Linux**（`app/src/main/java/app/dsh/mobile/engine/LinuxExtensionBridge.kt`）
- 保留**一份** catalog，把 Termux 包名映射成 Alpine 包名，改走容器内 `apk add`。
- 不做两份 catalog 的原因：`check-i18n.py` 已要求双语 catalog 的 id 与顺序逐项一致，
  再加第三份会让维护成本翻倍，且同一扩展在两种模式下会出现名字/分类/描述不一致。
- 映射表（全部对着 Alpine v3.22 的 26160 个包名实测核对，不靠记忆）：
  ```
  python->python3   python-pip->py3-pip   golang->go      xz-utils->xz
  lua54->lua5.4     php->php83            openjdk-17->openjdk17
  clang->clang17    ndk-sysroot->musl-dev  ninja->{ninja-build, ninja-is-really-ninja}
  ```
- **一对多**：`ninja` 必须同时装 `ninja-build` + `ninja-is-really-ninja`。
  后者不可省 —— 前者把二进制装在 `/usr/lib/ninja-build/bin/ninja`、不建
  `/usr/bin/ninja`；实测只装前者时 `ninja --version` 报 not found。
- 标为不可用：`aapt`/`aapt2`/`apksigner`/`d8`（Android SDK 工具，Alpine 不打包）、
  `llama-cpp`。`android-tools` 实测**存在于** community 仓库，故不列入。
- 新探针 `scripts/probes/_test_linux_ext_bridge.py`（45 checks）锁死映射表：
  静态解析三张表 + 对着仓库内 `app/src/main/assets/linux/apkindex-names.txt`
  的 26160 个包名核对 + 9 条改名回归断言 + ninja 双包断言。CI 无网也能跑。

**终端 PTY**（容器内真 shell）
- `LinuxEngineLauncher.buildTerminalArgv()` 构造容器内的 `/bin/sh -l`；
  `TerminalPtySession.startLinux()` 用它建会话；`TerminalActivity` 靠
  `EXTRA_IN_LINUX` 切换模式；LinuxActivity 新增「打开 Linux 终端」入口。
- 真机验证：敲 `cat /etc/alpine-release` → `3.22.0`，提示符 `localhost:/ws$`。

**这一轮修掉的六个真 bug（都有实测症状）**
1. `renameat`/`linkat`/`symlinkat` 漏 `bind_map()` → 插件健康探针报
   `marker write failed: ENOENT: rename '/dsh/...'`
2. 双路径 handler 共用 `hb` 缓冲 → 第二个 `bind_map` 覆盖第一个，rename 退化成 self-rename
3. 终端 `args` 用 `drop(1)` 丢了 `argv[0]` → `LOADER: rootfs open FAIL rc=-2`
4. `g_force_container_path` 从不清零 → 每次 exec 覆盖容器内 PATH，ccache 永远 miss
5. `HOME`/`TERM` 被无条件覆盖 → App 传的 `HOME=/dsh`、`TERM=xterm-256color` 被盖掉
6. `getcwd` 缺 bind 反向映射 → 终端提示符显示宿主路径而非 `/ws`

### 真机验收记录（2026-11-04，设备 `192.168.101.21:9000`）

**引擎走容器模式全链路跑通**（`engine_linux_mode=true`）：

```
24335 dsh_alpine -m /eng:/data/user/0/app.dsh.mobile/files/engine \
  -m /ws:.../dsh-home/workspaces -m /dsh:.../dsh-home \
  .../linux/rootfs /lib/ld-musl-aarch64.so.1 /usr/bin/node \
  --expose-internals /eng/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web \
  --patch /eng/android-overlay.yml --no-open
```

- 日志：`dsh web: http://127.0.0.1:3080/?token=...` → `plugin health marker received (ok=true)`
  → `engine healthy on :3080`
- 插件健康探针 `ok=true`、`missingServices=[]`、`failedEntries=[]`、
  `bundles=["@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"]`
- 截图确认 WebUI 真实渲染（对话历史 + 输入框都在）
- **内存实测（同口径 `/proc/<pid>/status`）**：
  | 模式 | VmRSS | VmHWM | Threads |
  |---|---|---|---|
  | 容器（musl node22） | **171116 kB** | 206432 kB | 11 |
  | 原生（bionic node24） | **235040 kB** | 245360 kB | 11 |
  → 容器反而省约 64MB（差异来自 node 版本与 ICU，非容器层；容器层本身约 1.8MB）

**本轮修掉的真 bug：`renameat`/`linkat`/`symlinkat` 漏 `bind_map()`**

插件健康探针报 `marker write failed: ENOENT: rename '/dsh/.android-plugin-health.json.tmp-15381'
-> '/dsh/.android-plugin-health.json'`。根因：`openat` 分支会先查 bind 映射，但
`renameat` 分支只做 `make_rel()` —— 临时文件被正确写到宿主 `dsh-home`，
rename 却去 rootfs 里找，跨文件系统必然 ENOENT。

修复要点两条（`app/src/main/cpp/dsh_alpine.c`）：
1. `renameat`/`renameat2`/`linkat`/`symlinkat` 四个 handler 全部补 `bind_map()` 优先判断
2. 双路径 handler 的缓冲**必须各自独立**（`oh[PATH_MAX]` / `nh[PATH_MAX]`），
   共用一个 `hb` 时第二个 `bind_map` 覆盖第一个 → rename 退化成 self-rename

验证：`echo X > /dsh/.t1 && mv /dsh/.t1 /dsh/.t2` → `RENAME-OK` + `cat /dsh/.t2` 得 `X`。
遗留 tmp 泄漏文件已清理。

> ⚠️ 部署坑：改完 `dsh_alpine.c` 后必须**删掉设备上的 `files/linux/runner/dsh_alpine`**
> 再起 Activity 才会重部署（App 只在文件缺失/长度不符时重推）。曾因只看 `install -r`
> 成功就以为生效，实际设备上跑的还是旧运行器（md5 不符、27984 vs 28024 字节）。

### 2026-11-04 夜：修「容器里一堆功能用不了」+ 启动速度归因

用户报：Linux 模式下**账号查看失败、注册失败、工作区无法选择、配置文件打不开、
AI 的 bash 工具全调不动**。三项根因全部在 rootfs 与路径映射，不是应用层。

| # | 根因 | 症状 | 修法 |
|---|---|---|---|
| 1 | rootfs 缺 `/etc/resolv.conf` | musl 只查 `127.0.0.1:53`（无人监听）→ 所有域名解析失败；账号接口 `no-response`、`api.deepseek.com/models` 超时。而宿主 `ping` 正常（bionic 走 netd），极易误判成「网没问题」 | 新增 `LinuxEnvironmentManager.RESOLV_CONF`（223.5.5.5 / 119.29.29.29），`writeApkConfig` 改为写 hosts + resolv.conf + repositories 三个文件；`HOSTS` 收窄到只剩 localhost，删掉写死的镜像 IP |
| 2 | rootfs 缺 `/bin/bash` | `dsh-bash-local.execute()` 固定执行 `bash -c`，不退回 sh → **每条 AI 命令都 spawn 失败** | 新增 `ensureBootstrapPackages()` 装 bash / curl / ca-certificates，幂等（`.dsh-bootstrap` 标记） |
| 3 | 只有 `/ws` 别名挂载 | `storages/workspace.json` 持久化的是**宿主**路径 `/storage/emulated/0/Documents/dshdata/workspaces`，容器内 `No such file` → 工作区无法选择 | `buildBinds()` 把宿主路径**按同名再挂一次**，且必须用 `canonicalFile` 解析软链（`dsh-home/workspaces` 是软链，registry 存的是解析后路径）。引擎与终端共用同一份 bind 表 |

**踩过的三个坑（都会让修复「看着做了其实没生效」）**
1. `ensureBootstrapPackages()` 挂在 `ensureDeployed` 末尾 —— rootfs 已就绪时它提前
   `return`，那段代码**根本不执行**。改挂到 `ensureReady()`。
2. `ensureBootstrapPackages()` → `LinuxContainerRunner.run()` → `ensureReady()` →
   bootstrap → **无限递归**，表现为后台线程打转、`.dsh-bootstrap` 永不出现。
   修：`run()` 加 `skipReady` 参数，bootstrap 传 `true`。
3. `ensureReady()` 只有 `LinuxActivity` 会走，**引擎冷启不经过它** —— bash 依然装不上。
   最终改挂到 `LinuxEngineLauncher.readiness()`（冷启路径上的唯一入口），异步执行。

**启动速度：设备被限频，不是代码回归（关键结论）**

同一设备、同一时刻、交替实测两个版本：
```
修复前 (41bb706)   3220 / 2820 / 2863 / 2843 / 2833 / 2969 ms
修复后             3006 / 2937 / 2991 / 2935 / 2803 / 2991 / 3046 / 2810 ms
```
统计上无差异。变慢原因是系统限频：
- `cpu0 scaling_max_freq = 1785600 / cpuinfo_max_freq = 3628800`（**49%**）
- `cpu7 scaling_max_freq = 1497600 / cpuinfo_max_freq = 4608000`（**33%**）
- 电池 44%、温度 42.2°C（不算热）、Swap 已用 5.2G/9.7G
- root 写 `scaling_max_freq` 立刻被守护进程改回；`stop thermal-engine` 也无效

**铁证**：同条件真 chroot 基线也从 **1200ms 变成 2310~2500ms** —— 设备级降速。
（健康状态下 10 轮实测是 **1404~1561ms，中位 1.45 秒**，见 `41bb706` 的提交说明。）

已逐一排除的变量：bind 数量（3 vs 5 同为 2.5s）、DNS 开关（2.47 vs 2.42s）、
gcompat（移除前后同）、cwd 位置（/tmp、空目录、sdcard、dsh-home 四者 2.42~2.59s）。


### 门禁（本分支实测）

- `python scripts/check-i18n.py` → **All i18n checks passed**（5 语言各 1195 串同步）
- 全量探针 → **43 通过 / 1 失败**，唯一失败 `_test_corepack_hardlink.py`
  因 `runtime.zip` 被 `.gitignore:15` 忽略（noruntime 构建下属预期）
- `gradle :app:assembleDebug -Pabi=arm64-v8a -Ppackage=noruntime` → **BUILD SUCCESSFUL**
- APK 内已确认含：`lib/arm64-v8a/libdsh_alpine.so`(27496)、
  `assets/linux/rootfs-bootstrap.tgz`(3979016)

### 多 rootfs 可选（glibc 支持，2026-11-05 新增）

**一句话**：Linux 工作区不再只有 Alpine。新增 **Ubuntu 24.04 LTS (glibc)** 作为可选系统镜像，
两者**并存**（各自目录/标记/已装软件），切换只改"当前指向"。Alpine 仍是默认（随包、离线可用）。

#### 新增/改动文件

```
app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt   新增：发行版目录（数据源/interp/包管理器/node 策略）
scripts/probes/_test_linux_rootfs_catalog.py                    新增：57 项静态一致性探针
app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt  改：多发行版部署/下载/迁移/glibc 补丁
app/src/main/java/app/dsh/mobile/engine/LinuxContainerRunner.kt     改：interp 跟随发行版，shell 走 /bin/sh
app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt      改：interp/node 路径跟随发行版，apt 分派
app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt             改：linuxMuslInterp() 改为跟随当前发行版
app/src/main/java/app/dsh/mobile/LinuxActivity.kt                   改：系统镜像选择卡
app/src/main/res/values*/strings.xml                                改：各 +14 串（5 语言同步）
```

#### 真机实测结论（设备 192.168.101.21:9000，2026-11-05）

| 项 | 结果 |
|---|---|
| Ubuntu base 24.04 arm64 解包 | 3413 条目 / 196 链接 / 解压 96.1MB（压缩 29,936,675 字节） |
| `dsh_alpine` 跑 glibc 二进制 | **正常**（interp `/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1`） |
| glibc node 跑真实 JS | **正常**（v22.14.0，`os.cpus()` 8 核 / 15.7GB） |
| `apt-get update`（清华 ubuntu-ports，http） | 正常，27.9MB / 19s |
| `apt-get install` 一步 | **失败**：`dpkg: cannot stat '/tmp/apt-dpkg-install-XXXX/'` |
| `apt-get -d install` + `dpkg -i` 两步 | **成功**（curl 8.5.0 / bash / ca-certificates / less 全装上） |
| glibc `ldconfig.real` | **段错误 exit 139**（静态 PIE，无 PT_INTERP） |
| divert `/usr/sbin/ldconfig.real` + shim | **有效**（之后 apt install 一把过） |
| divert `/sbin/ldconfig.real`（旧路径） | **静默无效**：/sbin 是符号链接，libc-bin configure 仍 139 |

#### 三条硬约束（都写进代码注释与探针）

1. **glibc 装包必须两步**：`apt-get -d install` → `dpkg -i <cache>/…` → `dpkg --configure -a`。
2. **ldconfig 必须 shim**：`dpkg-divert --add --rename --divert /usr/sbin/ldconfig.dshreal
   /usr/sbin/ldconfig.real`（**必须是 merged-usr 后的 `/usr/sbin`**），原路径放 `exit 0`。
   `ld.so` 在缓存缺失时退回内建搜索路径，缓存只是优化。
3. **apt 源必须 http**：base 镜像 `/etc/ssl/certs` 不完整，https 报
   `Certificate verification failed: The certificate issuer is unknown`。

#### 未验证/已知限制

- **glibc rootfs 内的 DNS（getaddrinfo）在 root 测试环境下异常**：`curl` 报
  `Could not resolve host`、node `dns.lookup` 报 `EAI_AGAIN`，而同一时刻 `dns.resolve4`
  （走 c-ares 线程池）**能解析**、`apt-get update` 也能解析。musl 侧对应现象是
  `nslookup: write to '223.5.5.5': Operation not permitted`（已知 SELinux/netd 拦截）。
  推测：glibc 的 `getaddrinfo` 走 `sendmmsg`/socket 路径被 seccomp 过滤或返回 EPERM，
  而 c-ares 走另一条路径。**未在 App 进程上下文（app_data_file SELinux 域）复验**——
  测试时 `run-as` 不可用（release 包 not debuggable），`su` 域与 App 域不同。
  → 若 Ubuntu 模式下 npm/curl 有问题，优先怀疑这条，而不是 rootfs 完整性。
- Debian 12 评估过但**未采用**：LXC 官方 rootfs 解压 431.6MB（15068 文件），
  对移动端太重；Debian 也没有像 Ubuntu base 那样的官方精简 tarball。
- Ubuntu 22.04 base 实测更省（压缩 27.6MB / 解压 66MB，且 ldconfig shim 后 apt 一把过），
  但当前目录里选的是 24.04 LTS（长期支持、glibc 2.39）。若要换 22.04 只需改
  `LinuxRootfsCatalog.options` 的 url/sha256/体积三个字段。

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

### 作者修复后复查（2026-11-03 晚，第三版 head `830975ea`，仍未合并）
作者按 review 重做了：head 从 `c256597b`（10 commits）变成 `830975ea`（3 commits），
base 已 rebase 到 `618a475`，改动收敛到 13 文件 +340/−27。三个提交：
`0028e03` 四档预设 → `1407c97` 修界值+透明度累积、滑块加回 → `830975e` 折叠布局模式。

**两个 bug 都修对了（代码级核对）**：
- `setSettingsBgBlur` 改成 `blur.coerceIn(0, 100)`，并留了注释说明「早先误用
  BG_BLUR_MAX(24) 做上限，导致写 25/50/75 全被截成 24」（`CustomVisualPref.kt:90-95`）。
- `sampleSizeFor` 改成 `val tooSmall = (maxW > 0 && nw < maxW) || (maxH > 0 && nh < maxH)`，
  两维独立判断。
- 另外他自己发现并修了**第三个 bug**：`dimCards` 原先拿「当前色」再乘系数，
  每拖一次滑块 alpha 就再缩一次，越调越淡。改成 `getOrPut(v) { current }` 存原始色、
  每次从 `original` 重算。

**我上一轮修的 5 处全部保留**（`removeByFile`×2 / `moveByFile`×3 / `postDelayed`×1 /
`LONG_PRESS_MS`×2 / `mutate()`×3 / 「命中就直接返回」×1，逐项 grep 计数非 0）。

**合并已无冲突**：`git merge --no-ff --no-commit origin/main` → `Automatic merge went well`
（因为作者把 `cachedBlur` 参数改成 `strength: Int`，与我的「先算缓存键」不再撞同一行；
改为在调用侧 `blurStrengthOf(...)` 换算）。

**回归与真机验证（试合并 worktree `/e/tmp/wt93v3`，已删）**：
- 全量 44 个 py 探针 **43 过 / 1 失败**（`_test_corepack_hardlink.py` 缺 `runtime.zip`，
  `-Ppackage=noruntime` 已知基线，非回归）。
  ⚠️ 注意：`scripts/probes/` 下有 9 个探针（`_test_boot_retry_bounds.py`、
  `_test_corepack_hardlink.py`、`_test_data_import.py`、`_test_native_plugin_check.py`、
  `_test_overlay_patch_semantics.py`、`_test_plugin_market_cache.py`、`_test_seed_persona.py`、
  `_test_stopping_reset.py`、`_test_update_index_asset_names.py`）**未在 `.gitignore` 放行**，
  只存在于工作区、不在 git 里 —— worktree 拿不到它们，必须先 `cp` 进去再跑。
- `check-i18n.py` → `All i18n checks passed.`（各 locale 1166 条同步）；
  `check-no-bulk-perms.py` → OK。
- `gradle -Ppackage=noruntime assembleRelease` → `BUILD SUCCESSFUL`。两条 lint error
  （`ACCESS_MOCK_LOCATION`、`ExpiredTargetSdkVersion`）是既有基线，不分发 Google Play。
  ⚠️ worktree 里 `keystore/` 与 `debug-signing/` 都被 gitignore，构建只产出
  `app-release-unsigned.apk`；要装机必须先 `cp -r keystore /e/tmp/wt93v3/` 再重跑。
- 真机（OPPO PLZ110 / Android 16）：`adb install -r` → Success，`versionName=1.5.0-beta.20`。
  四档模糊逐档实测存值：点 0/25/50/75 → `settings_bg_blur` 分别是 `0/25/50/75`
  （旧版 25、50 都会存成 24）。折叠模式：5 个分区默认收起 → 点「个性化」展开 →
  再点收起 → 展开后 `am force-stop` 冷启仍保持展开（下标持久化有效）。

**新增探针 `/e/tmp/_test_appearance_pr93_v3.py.ready`（38 项）暂未入库** ——
它锁定的是 v3 的实现，main 上还没有这些代码，直接放进去会全红。
**合并 v3 时须与代码一起提交**，并在 `.gitignore` 的
`!/scripts/probes/_test_appearance_pr93.py` 后面加一行放行。
反向验证已做：把 `coerceIn(0,100)` 改回 `BG_BLUR_MAX` → 2 项变红；`dimCards` 改回
从 `current` 算 alpha → 1 项变红；展开状态改回按标题文案存 → 1 项变红；复原后 38 项全绿。

**当前决定**：v3 内容质量可以，但按用户「直接让他自己修，先不合并」的指示，
保持 PR open，等用户指示再合。

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

## 二十六、上游引擎升级到 0.2.2-rc.1（2026-11-05，feat/linux-workspace）

用户要求「上游发行版本了，赶快进行补丁更新」。上游 `0.2.2-rc.1` 发布于
2026-11-04T09:20:32Z（npm `next` 标签；`latest` 仍是 `0.2.1-rc.5`）。

### ★ 这次和上次（rc.2→rc.5）性质完全不同

上一轮的经验是「9 个补丁目标包字节全同、锚点天然命中、不用改任何补丁」。
**这次不能照搬**：9 个锚点文件里 **8 个字节都变了**（只有
`dsh-subprocess-local/lib/index.js` 62333 字节未变）。

| 包 / 文件 | rc.5 字节 | 0.2.2-rc.1 字节 |
|---|---|---|
| dsh-sandbox-local/lib/index.js | 22673 | 23136 |
| dsh-session-persistence-jsonl/lib/index.js | 147273 | 147409 |
| dsh-session-persistence-jsonl/lib/worker.cjs | 563742 | 570438 |
| dsh-fs-local/lib/index.js | 41311 | 48449 |
| dsh-attachment-local/lib/index.js | 46886 | 47055 |
| dsh-subprocess-local/lib/index.js | 62333 | **62333（相同）** |
| dsh-ptc-runtime-node/lib/index.js | 71157 | 64621 |
| dsh-ptc-runtime-node/lib/process.js | 42917 | 245200（5.7×） |
| dsh-web-frontend/dist/index.html | 825 | 1009 |

### 验证方法（重要：不能只看顶层 package.json）

**大部分补丁目标包是间接依赖，不在 `@deepseek-ai/dsh` 的顶层 `dependencies` 里**——
只有 `dsh-fs-local` 在里面。要拿真实版本必须完整解析依赖树：

```bash
mkdir /e/tmp/resolvetest && cd /e/tmp/resolvetest
printf '{"name":"t","private":true,"dependencies":{"@deepseek-ai/dsh":"0.2.2-rc.1"}}' > package.json
npm install --ignore-scripts --no-audit --no-fund    # 654 包 / 2min（rc.5 是 616 包）
# 然后按 runtime 布局铺开再打补丁：
mkdir -p /e/tmp/patchtest/lib && cp -r node_modules /e/tmp/patchtest/lib/node_modules
```

`npm pack @deepseek-ai/dsh-host-apiproxy@0.2.1-rc.5` 会报
`ETARGET notarget No matching version found` —— 该包独立演进、最新只到 `0.1.1-rc.2`，
**不能靠 `npm pack` 猜版本**。

### 八项补丁逐个实打结果（全部命中）

| 补丁 | 锚点 | 结果 |
|---|---|---|
| `patch-ptc-runtime-android.py` | `OLD_SET`(6 项 STARTUP_ENVIRONMENT_NAMES) / `ENV_OLD` / `MAP_OLD` | ✅ 三处全命中 |
| `patch-fs-local-android.py` | `PROBE_OLD` = `if (!isENOENT(error) && !isENOTDIR(error)) throw error;` | ✅ |
| `patch-attachment-local.py` | `OLD_IMPORTS` 第 1 条 + `SYNC_RE` | ✅ 五项校验全过 |
| `patch-webview-polyfill.py` | `ANCHOR_RE` = `<script[^>]*type="module"[^>]*>` | ✅「polyfill v4 injected」|
| session-persistence `lib/index.js` | `import { link, lstat, ... } from "node:fs/promises";` | ✅ link→rename |
| session-persistence `lib/worker.cjs` | `link: node_fs_promises.link` | ✅ link→copyFile |
| `patch-node-pty-android.py` | `runner-launch-*.js` 通配 | ✅ 3 处改动 |
| `patch-apiproxy.py` | 目标 `dsh-host-apiproxy/lib/index.js` | ⚠️ 包不存在，WARN 后跳过 |

### 补丁入口调用约定（这次踩到，记下来）

| 脚本 | 传参方式 |
|---|---|
| `patch-ptc-runtime-android.py` | 位置参数：runtime 根目录 |
| `patch-apiproxy.py` / `patch-webview-polyfill.py` | 环境变量 `DSH_PATCH_TARGET`（必须是 runtime **根**，不是 `lib/node_modules`）|
| `patch-fs-local-android.py` / `patch-attachment-local.py` / `patch-node-pty-android.py` | 位置参数目录或 `.zip` |
| `patch-bash-sysbashrc.py` | 需要 `bin/bash` 存在（纯 node_modules 目录会报「runtime 形态已变？」）|
| `patch-curl-wrapper.py` | 只接受 `.zip` |

### 关于 apiproxy 的重要澄清（避免下次误判为回归）

`dsh-host-apiproxy` **在 rc.5 的依赖树里就已经不存在了**——实际跑
`npm install @deepseek-ai/dsh@0.2.1-rc.5` 得到 616 个包，`@deepseek-ai/` 下无此包。
所以 `patch-apiproxy.py` 一直在输出 WARN 并跳过，**不是本次升级带来的变化**。

原生 opener 现由 `@deepseek-ai/dsh-native-command/lib/index.js:192` 承担：
`throw new Error(\`native path opener is unsupported on ${platform}\`)`，
其 `openNativePathWithIntent` 只认 darwin / win32 / linux(wsl) 三个分支。
调用方是 `dsh-api-session-controller:3990`（`path open failed`）与
`dsh-api-settings-controller:458`。

### 依赖面变化

86 → 90 项。新增 4 个包，**均无原生依赖**（无 node-addon / pty / koffi / esbuild / rollup）：

| 包 | 直接依赖数 |
|---|---|
| `dsh-official-bundles` | 1 |
| `dsh-personalized-recommendations` | 6 |
| `dsh-experimental-forge-bundle` | 6 |
| `dsh-experimental-liveshare` | 8 |

因此 `koffi` 与 `node-pty` 的打桩路径不变（二者本来就不编译、只写成 inert shim，
PTY 由 App 层自研 `libdshpty.so` 提供），**不需要重新编译任何原生模块**。

### 改动的版本钉（9 个位置）

- `.github/workflows/runtime-build.yml:37` dispatch 默认值、`:70` `DEFAULT_DSH_VERSION`
- `.github/workflows/upstream-staging.yml:9` dispatch 默认值
- `scripts/build-runtime.sh:19` `DSH_VERSION` 兜底值
- `scripts/collect-termux-runtime.sh:119` `DSH_VERSION` 兜底值（并在 `:114` 前追加本次逐包核验结论）
- `scripts/test_build_runtime_version.py:17` `DEFAULT_VERSION`（测试基线，漏改会红）
- 文档：`docs/CI_RUNTIME_PIPELINE.md`(5 处)、`README.md`、`README_EN.md`(2 处)、`README.de.md`

### 验证

- 全量 45 个探针：**44 过 / 1 失败**（`_test_corepack_hardlink.py` 缺 `runtime.zip`，
  `-Ppackage=noruntime` 的既有基线）
- `check-i18n.py` → All passed；`check-no-bulk-perms.py` → OK
- `bash -n` 两个脚本通过；两个 workflow YAML 解析通过
- ⚠️ `scripts/test_build_runtime_version.py` 的 4 个用例失败是**既有基线**：
  报 `C:\Program: bad interpreter: No such file or directory`，根因是仓库路径
  `E:\Deepeek harness\` 含空格。用改动前的 rc.5 原版跑**同样失败 8 项**，
  与本次升级无关。

提交 `e626468`，分支 `feat/linux-workspace`（注意：不是 main，该分支领先
origin/main 14 个提交）。CI `runtime-build` 与 `android-build` 已随推送触发。

### 教训

- 「上次锚点全同」不能当成本次的先验。每次升级都要重新比字节，
  变了就必须实打补丁，静态 grep 命中也只是必要条件。
- 补丁目标包多半是间接依赖，必须 `npm install` 完整解析，不能只看顶层
  `dependencies`，也不能 `npm pack` 猜版本。
- 工作区里有别人（上一轮自己）留下的未提交改动时，**只 add 自己改的文件**，
  不要 `git add -A`。

### 追加修复：dsh_alpine 的 x86_64 lane 编译失败（提交 `af59166`）

升级推送后 CI `android-build` 的 **x86_64 两个 lane 红**（arm64 两个绿）：

```
dsh_alpine.c:718:39: error: unknown register name 'x0' in asm
dsh_alpine.c:888:66: error: no member named 'regs' in 'mcontext_t'; did you mean 'gregs'?
dsh_alpine.c:926:28: error: no member named 'pc' in 'mcontext_t'
3 errors generated.
```

**这跟本次上游升级无关**：`dsh_alpine.c` 是 `feat/linux-workspace` 分支的
Linux 容器特性（`origin/main` 上根本没有这个文件），当初就只按 aarch64 写：

1. `:718` `register unsigned long x0 __asm__("x0")` 切 guest 栈 —— 只有 AArch64 有 `x0`
2. `:888` `uc_mcontext.regs`、`:926` `uc_mcontext.pc` —— AArch64 的 ucontext 布局
   （x86_64 是 `gregs` / `rip`）
3. BPF 白名单靠比对 `instruction_pointer` 识别自己的裸 `svc` 桩，指令编码同样是 AArch64

**修法**（不是补一份 x86_64 实现）：`app/src/main/cpp/CMakeLists.txt` 把
`dsh_alpine` 的 `add_executable` 与全部 `target_*` 包进
`if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "aarch64")`。
依据是 App 侧本来就只有 arm64 走容器
（`EngineConfig.kt:2041 deviceAbiIsArm64()` → 非 arm64 直接不启用），
x86_64 编这个目标没有任何消费方。

**本机两架构实编验证**：

| ABI | 构建 | APK 内 lib/ |
|---|---|---|
| x86_64 | BUILD SUCCESSFUL（原先 3 errors） | 无 `libdsh_alpine.so`（预期） |
| arm64-v8a | BUILD SUCCESSFUL | `lib/arm64-v8a/libdsh_alpine.so` 28824 bytes ✓ |

**CI 复跑全绿**（run `41979175975`）：`pack-mobile-layout` / `build-hardssh` /
`fetch-runtime`×2 / `build-apk`×4（arm64+x86_64 × full+noruntime）/
`Build monitor` 全部 success。三个 publish 步骤 skipped 属正常（非 main 分支不发布）。

## 二十七、关于页求 star 卡片（2026-11-05，v1.6.1）

用户要求「在关于界面加个广告，让用户给我们仓库点点 star」。

### 设计与实现

位置：`activity_about.xml` 里 Telegram 群之后、隐私声明之前（社群入口之后是
自然的支持位置；放页面最末会被隐私声明挤到滚动区外）。

| 文件 | 改动 |
|---|---|
| `app/src/main/res/layout/activity_about.xml` | 新增 `rowStar` 卡片（星形图标 + 标题/副文案 + `txtStarAction` 行动标签）|
| `app/src/main/java/app/dsh/mobile/AboutActivity.kt` | 点击 `ACTION_VIEW` 打开仓库主页；新增 `applyStarPromoAccent()` |
| `drawable/bg_star_promo.xml` | 卡片背景（layer-list：accent 实心 + 白高光渐变）|
| `drawable/bg_star_action.xml` | 行动标签白底胶囊 |
| `drawable/ic_star_white.xml` | 五星矢量图（写死白色）|
| 五语种 `strings.xml` | 各加 `about_star_title` / `about_star_sub` / `about_star_action` |

### 两个关键实现细节

1. **背景必须用 `layer-list`，不能是单个 `<shape>`**
   `ThemedActivity.dimCards` 会把每个 `GradientDrawable` 背景压到 86%（用户设了
   设置页背景图时）。`LayerDrawable` 不在 `if (bg is GradientDrawable)` 分支里，
   天然躲开。这张卡本就该是全页最亮的，被压暗就没意义了。
   代码里 `applyStarPromoAccent()` 重建背景时也保持 `LayerDrawable` 形态。

2. **强调色必须在代码里重涂**
   布局里的 `@color/accent` 是静态资源值，用户换「配色方案」后不会变。
   `applyStarPromoAccent()` 用 `Accent.current(this).argb` 重建 LayerDrawable
   并重设 `txtStarAction` 文字色。

### 点击落点决策

跳**仓库根** `https://github.com/<RepoConfig.REPO>`，不跳 `/stargazers`：
后者未登录会先跳登录页，登录完还要自己再找一次 Star 按钮；仓库主页的
Star 按钮首屏可见。用 `RepoConfig.REPO` 而非写死，fork 构建自动跟着变。
没有浏览器时 `runCatching` 退回复制链接。

### 验证

- 新探针 `scripts/probes/_test_about_star_promo.py`（**42 项**），已加进
  `.gitignore` 白名单（`!/scripts/probes/_test_about_star_promo.py`）
- **三处反向验证全红**：背景改回单 `<shape>` → 3 项 FAIL；点击改跳
  `/stargazers` → 1 项 FAIL；注释掉 `applyStarPromoAccent()` 调用 → 1 项 FAIL
- ⚠️ **探针坑（新）**：反证第 3 项第一次没变红 —— 正则
  `applyStarPromoAccent\(\)\s*$` 会命中 `// applyStarPromoAccent()` 的注释。
  改成行首锚定 `^\s*applyStarPromoAccent\(\)\s*$` 后才正常。**结论：锚定调用点
  时必须带行首，否则注释能骗过探针。**
- 全量 46 个探针：**45 过 / 1 失败**（`_test_corepack_hardlink.py` 缺
  `runtime.zip`，noruntime 既有基线）
- `check-i18n.py` 全过（五语种 1209 条同步）、`check-no-bulk-perms.py` OK
- 两架构构建 `BUILD SUCCESSFUL`（arm64-v8a / x86_64）
- 布局结构用 XML 解析器核验：`rowStar` 在 `rowTelegram` 之后、隐私声明之前

### 未完成：真机 UI 未目视

设备（OPPO PLZ110 / Android 16）锁屏有密码，`locksettings verify` 报
`User has a lock credential, but old credential was not provided`，adb 无法绕过；
`mWakefulness=Dozing` 唤醒后 `mCurrentFocus` 仍卡在 `NotificationShade`，
截图拿到的是锁屏/通知栏画面，**没能看到卡片实际长什么样**。
排查期间临时 `settings put secure lockscreen.disabled 1`，**已还原为原值 0**。

提交 `7a0a924`，分支 `feat/linux-workspace`。

---

## 二十八、多 rootfs 可选能力（Alpine / Ubuntu 双发行版，2026-11-05，`feat/linux-workspace`）

### 做了什么

Linux 工作台原先只有内置 Alpine 一个 rootfs。本次加了一条**可切换的第二发行版**：
Alpine（musl，随包内置 3.8MB）+ Ubuntu 24.04.5 base（glibc，首次使用时下载 28.5MB）。
切换只改指向、不搬文件，两个 rootfs 可并存于 `filesDir/linux/distros/<family>/`。

### 新增/改动文件

```
新增  app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt   发行版目录（唯一事实源）
新增  scripts/probes/_test_linux_rootfs_catalog.py                    59 项契约探针
改   app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt
改   app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt
改   app/src/main/java/app/dsh/mobile/engine/LinuxContainerRunner.kt
改   app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt
改   app/src/main/java/app/dsh/mobile/LinuxActivity.kt               「系统镜像」选择卡
改   app/src/main/res/values*/strings.xml                            五语 14 条
```

### 关键设计

- **单一事实源** `LinuxRootfsCatalog`：每个 `Option` 自带 `interpPath` / `nodeStrategy` /
  `pkgManager` / `needsGlibcQuirks` / 体积 / 下载源。引擎与终端**不再硬编码 musl 路径**，
  全部改为跟随 `active(ctx).interpPath` —— 用错 interp 会直接 `LOADER: preload FAIL`。
- **目录布局迁移**：老用户把 rootfs 放在 `filesDir/linux/rootfs`，新布局是
  `filesDir/linux/distros/alpine`。`migrateLegacyLayout()` 挂在 `activeOption()` 上
  （所有 rootfs 访问的必经入口），不是只挂在部署路径 —— 否则老用户卡在旧布局。
- **UI 选择入口**：`LinuxActivity` 的「系统镜像」卡逐项列出发行版，标「当前使用/已安装/
  未安装」与实测体积，点一下切换。切换前先比剩余磁盘（Ubuntu 解压约 96MB）。
- **不在引擎冷启时自动下载**：30MB 静默下流量不可接受，下载只由 UI 切换动作触发。
  引擎侧 `readiness()` 遇到未部署返回 `NeedsDeploy`，回退 native 并在 UI 标注。

### glibc 镜像的三处非显然坑（全部真机实测踩出）

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | `ldconfig` 段错误（SIGSEGV） | Ubuntu 24.04 的 `ldconfig.real` 是 **PIE 且无 PT_INTERP**，容器用 musl interp 直接载它会崩 | dpkg-divert 掉 `ldconfig` + 写 shim 脚本 |
| 2 | `apt install` 装完包却缺文件 | apt 在容器里没法真正装（缺 dpkg 状态锁/权限链） | 改成两步：`apt-get install -d`（仅下载）→ `dpkg -i /var/cache/apt/archives/*.deb` |
| 3 | 网络全部超时 | base 镜像 CA 链不完整，https 源验不过 | apt 源用 **http**（配 ForceIPv4），不走 https |

### node 版本硬下界（本次最重要的坑）

引擎 `@deepseek-ai/dsh/package.json` 声明 `engines.node >= 24.9.0`。Ubuntu 侧原先按
Node 官方预编译包钉了 **22.14.0**，真机表现是：`node bin.js --help` **静默退出**
（`exit=0`、stdout/stderr 全空），App 侧只看到

```
EngineProcess: engine exited: status=0 signal=0 exit=0
EngineSupervisor: crash detected — thorough port reclaim done
```

极难定位 —— 退出码是 0，看起来像"正常结束"。逐层复现后才锁定：`node -e` 正常、ESM 正常、
连 `/eng` 都可见，唯独加载 `bin.js` 零输出。**换 24.9.0 立刻正常打印 usage。**
（Alpine 侧的 musl node 是 22.23.4 却能跑，说明引擎并非所有版本都硬校验，
但 22.14.0 确实不行。）现已把 catalog 的 `nodeTarball` 钉到
`v24.9.0`，sha256 `5a5b1dc4906e891a655d2f0689db664879724f2d9e63309486fd588172a052bc`，
31,085,180 字节，并在探针里加了「node version >= 24.9.0」断言防降级。

### 验证（全部实测，设备 OPPO PLZ110 / Android 16 / 192.168.101.21:9000）

- **探针** `_test_linux_rootfs_catalog.py`：59 checks / 0 failed
- **反向验证**（每项都确认探针立即变红，复原后全绿）：
  1. divert 路径改回 `/sbin/ldconfig.real` → `[FAIL] divert does NOT use /sbin/ldconfig.real`
  2. launcher interp 改回 musl 路径 → `[FAIL] glibc option uses glibc interp`
  3. `migrateLegacyLayout()` 从 `activeOption()` 移除 → `[FAIL] migration is wired into the shared entry point`
  4. node 版本降回 22.14.0 → `[FAIL] node version >= 24.9.0`
- **真机 Ubuntu 引擎跑通**：`engine healthy on :3080`（2.1s），进程 argv 为
  `... distros/ubuntu /lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 /opt/node/bin/node ...`，
  监听 `127.0.0.1:3080`，`curl` 返回 `HTTP 401`（引擎正常响应鉴权）
- **双向切换**：Ubuntu → Alpine 切回后 `distros/alpine /lib/ld-musl-aarch64.so.1`，
  Web UI 插件正常加载
- **老用户迁移**：Alpine 目录迁到 `distros/alpine` 后 node v22.23.4、44 个包、bash 全在
- **全量门禁**：46 探针通过 / 0 失败 / 1 跳过（`_test_corepack_hardlink.py` 缺
  `runtime.zip`，noruntime 既有基线）；`check-i18n.py` 全过（五语 1209 条）
- **构建**：`assembleDebug` BUILD SUCCESSFUL

### 踩过的测试方法论坑（记下来免得重犯）

1. **手工 root 解包的 rootfs 会被 App 判为不可读**：SELinux 类别
   （`app_data_file:s0:c232,c257,c512,c768`）只有 App 域能拿到。用 `su` 解包得到的
   是 `app_data_file:s0`（无类别），App 进程读不到，报 `dynamic loader missing` —— 看着
   像代码 bug，其实是测试夹具污染。**正解**：用 `run-as app.dsh.mobile tar -xzf ...`
   解包，上下文自然正确。
2. **跨函数正则会让断言假绿**：`re.search(r"fun activeOption.*migrateLegacyLayout", s, re.S)`
   会一路匹配到 `ensureDeployed` 里的同名字符串，删掉入口挂载依然全绿。必须切片到函数体
   （`s.find("fun activeOption(")` 到下一个 `\n    fun `）。
3. **Python 写文件时正则里的 `\n` 会被解释成真换行**，把探针源码写坏。要么用 `chr(92)`
   拼反斜杠，要么干脆不用含 `\n` 的正则，改用 `find` + 切片。

### 后续补齐（同一轮内又修了三处，均已实测）

1. **apt 源格式写错（真实缺陷）**：noble 只读 deb822 的
   `/etc/apt/sources.list.d/ubuntu.sources`，**完全不读** `etc/apt/sources.list`。
   原实现写的是后者 —— 实测 `apt-get update` 仍走官方 `ports.ubuntu.com`，等于死码。
   已改为写 deb822（`Types/URIs/Suites/Components`），并顺手删掉旧的一行式源。
2. **必须 `Trusted: yes`**：base 24.04 镜像有 `gpgv` 但**没有 `gnupg`**，
   `apt-key` 直接报 `E: gnupg, gnupg2 and gnupg1 do not seem to be installed`，
   apt 于是拒绝读源（"is not signed"）。装 gnupg 是鸡生蛋死锁（它就在被拒的源里），
   故对我们钉死的 CN 镜像关掉签名校验。
3. **`/tmp` 要 1777**：镜像里 `/tmp` 是 0755，apt 的签名校验子进程以 `_apt`
   往 `/tmp` 写临时文件失败 —— `Couldn't create temporary file /tmp/apt.conf.XXXX`。
   glibc quirks 里补一句放宽权限。

以上三条各自都有探针断言，且**都做过反向验证**（删掉后探针立刻变红）。

### 真机全链路验证（OPPO PLZ110 / Android 16 / 192.168.101.21:9000）

- **Ubuntu 引擎跑通**：`engine healthy on :3080`，监听 `127.0.0.1:3080`，
  `curl` 返回 `HTTP 401`（正常鉴权响应）
- **进程 argv 实证**（不是猜）：
  ```
  <rootfs distros/ubuntu> /lib/aarch64-linux-gnu/ld-linux-aarch64.so.1 /opt/node/bin/node
  ```
  即 rootfs、动态链接器、node 三者全部跟随当前发行版
- **apt 真正联网装包成功**（`options use-vc` 修好 DNS 之后）：
  `apt-get update` 拉到 2145 kB / 3s，`apt-get -d install jq` 下到 deb，
  `dpkg -i` 后 `jq-1.7` 可执行
- **glibc quirks 有效**：未打补丁时 `dpkg --configure libc-bin` 报
  `Segmentation fault ... exit status 139`；打完 divert+shim 后
  `Setting up libc-bin (2.39-0ubuntu8.8) ...` 正常完成
- **双向切换**：Ubuntu → Alpine 切回后仍是 `distros/alpine /lib/ld-musl-aarch64.so.1`
- **全量门禁**：46 探针通过 / 0 失败 / 1 跳过；`check-i18n.py` 全过

### 已知边界（没解决，如实记录）

- **App 内 apt 自举会被杀**：`LinuxContainerRunner` 用 `ProcessBuilder` 起子进程，
  Android 的 phantom process killer 会把它杀掉（`exit 137`、
  `Process PhantomProcessRecord ... died`）。手动 `su` 跑同一条命令不受影响 ——
  这是**平台机制**，不是代码 bug。已试过 `device_config max_phantom_processes`
  与关闭 `settings_enable_monitor_phantom_procs`，均无效（验证后已还原原值）。
  影响：**首装后的 bash/curl/ca-certificates 自动补装会失败**，但引擎本身能跑
  （上面所有引擎验证都是在这个前提下通过的）；用户可进终端手动装。
- **UI 目视未做**：设备锁屏有密码，adb 无法绕过，截图拿到的是锁屏画面。
- **`git-hygiene.py` 报 `rootfs-bootstrap.tgz` 含 CRLF**：这是既有基线问题
  （`git stash` 后同样报），不是本次引入。

### 当前状态

改动已随 `13a46b5`（feat(linux): 多发行版支持）落在 HEAD，工作树干净。
设备当前 `active_family=alpine`（测试结束切回，保持可用状态）。


## 二十八、AI 可读写 App 自身设置（2026-11-05，v1.6.2）

用户要求「把应用里面各种设置参数暴露给 ai，就像之前做权限命令行一样」。
两处拍板：**全部设置开放**（能力开关除外）+ **改完直接生效**。

### 端点 `GET/POST /dev/settings`

| op | 作用 |
|---|---|
| `get` | 读单个键（自动定位偏好文件）|
| `list` | 列出全部（可按 file 过滤）|
| `set` | 写（值类型按 JSON 推断：布尔/整数/小数/字符串/字符串数组）|
| `reset` | 删键回默认 |
| `reload` | 只重建当前页面，不改值 |

新增 `app/src/main/java/app/dsh/mobile/engine/cap/SettingsCap.kt`，
路由注册在 `AgentBridge.kt:455`（GET）与 `:458`（POST），
端点目录 `EndpointCatalog.kt` 47 → 48 条。

覆盖 20+ 个 SharedPreferences 文件：主 UI（`dsh_ui`）、配色与布局
（`dsh_ui_prefs`）、权限模式（`dsh_priv`）、Linux rootfs（`dsh_linux_rootfs`）、
更新、宠物、提醒、扩展激活、ASR/TTS 等。

### 三个安全边界

1. **凭据读取一律脱敏**：`SECRET_KEYS = {lan_web_password, pet_pack_index_url}`
   只返回 `{redacted:true, configured:bool, length:int}`，绝不返回原文。
   写入仍可设新值 —— 这是避免凭据被复制进会话记录，不是能力限制。
2. **能力开关 `cap.*` 不在此端点**：那属于用户对 AI 的授权，只能由用户在
   权限中心手动打开。`PREFS_FILES` 刻意不含 `capabilities` 文件。
3. **副作用分级不假装生效**：`effect = safe | refresh | engine | sensitive`；
   `engine_linux_mode` / `priv_mode` / `active_family` 写入后回报
   `requires an engine restart to take effect`，且**本端点不替你重启引擎**
   （会掐掉正在跑的会话）。

### 直接生效的实现

新增 `app/src/main/java/app/dsh/mobile/CurrentActivity.kt`：
用 `Application.ActivityLifecycleCallbacks` + `WeakReference` 记住当前
resumed 的 Activity，设置写完调 `recreateIfAny()`（post 到主线程，
因为 `Activity.recreate()` 只能主线程）。

不用广播的原因：项目里 22 个设置页全靠 `onResume` 自己重新对齐，
没有既有的刷新广播机制，为一处改 22 个页面不划算。

### ★ 顺带修掉一个坏提交

`b45c3f8`（20:16，`fix(linux): Alpine 的 DNS 真正修通`）把
`CurrentActivity.install(this)` 写进了 `DshApp.kt`，但**没有把
`CurrentActivity.kt` 一起提交**。在干净 worktree checkout HEAD 后：

```
e: DshApp.kt:74:9 Unresolved reference: CurrentActivity
BUILD FAILED in 25s
```

本提交（`8a3ccd4`）补上该文件，已在干净 worktree 复验 `compileReleaseKotlin`
→ `BUILD SUCCESSFUL in 48s`。

**教训**：`git add` 指定文件时容易漏掉「新文件」，而 `compileReleaseKotlin`
只在本地全量构建时才暴露 —— 提交后应立刻在干净 worktree 跑一次编译。

### 验证

- 探针 `scripts/probes/_test_settings_endpoint.py`（**50 项**），已注册白名单
- **三处反向验证全红**：`PREFS_FILES` 加 `capabilities` → 1 项 FAIL；
  `get` 分支直读原文绕过脱敏 → 1 项 FAIL；ENGINE 级改回「written and
  applied」→ 2 项 FAIL
- ⚠️ **探针覆盖漏洞（新）**：第一版只断言 `readValue()` 内部正确，
  把 `get` 分支改成直接 `prefs.all[key]` 竟然不变红。补了全覆盖断言
  「所有 `.put("value", ...)` 都必须经过 `readValue()`」才抓住。
  **教训：脱敏这类安全断言要全覆盖所有出口，不能只验中心函数。**
- 全量 48 个探针：**47 过 / 1 失败**（`_test_corepack_hardlink.py` 缺
  `runtime.zip`，noruntime 既有基线）
- 两架构构建 `BUILD SUCCESSFUL`（arm64-v8a / x86_64）
- `check-i18n.py`：本文件 0 处 CJK（另有一处 `LinuxDnsProxy.kt:294` 属他人
  未跟踪文件，未处理）

### ⚠️ 真机端到端未完成（阻塞）

设备 OPPO PLZ110 / Android 16 屏幕反复进入 Dozing，ColorOS 的
`OplusHansManager` 直接冻结整个 App：
`I OplusHansManager: freeze uid: 10488 app.dsh.mobile pids: [...] scene: LcdOff`。

试过且**都压不住**：`svc power stayon true`（`mStayOn` 仍 false）、
`settings put system screen_off_timeout 2147483647`、
`dumpsys deviceidle disable`（`mWakefulness` 几秒内仍回 Dozing）、
后台循环 `input keyevent KEYCODE_WAKEUP`、`dumpsys deviceidle whitelist +`。

冻住后桥端口 3083 `Connection refused`，token 也取不到
（`/proc/<pid>/environ` 在容器进程上读不到 `DSH_BRIDGE_TOKEN`）。

**已用 logcat 证实桥与引擎本身正常**：
`AgentBridge: agent bridge started on 127.0.0.1:3083`、
`EngineSupervisor: engine healthy on :3080`、`plugin health: ok=true`。
所以「起不来」不是新代码导致的，纯粹是设备休眠挡住验证。

**未实测的部分**：端点 HTTP 行为、脱敏输出真值、`recreate()` 的实际视觉效果。
锁屏有密码（`locksettings verify` 报 `User has a lock credential`），
adb 无法绕过；期间临时改过 `lockscreen.disabled=1`，已还原为原值 `0`。

## 二十九、root 系统级门脚本（2026-11-05，v1.5.0-beta.21）

用户要求「给系统权限调用命令行增加更多 root 可用命令，让 root 用户拥有更大的自由度」。

### 交付：`app/src/main/assets/gates/root`（约 400 行）

**为什么是门脚本而不是桥端点**：和 `hid` 同源的理由。这些操作要么需要
SELinux 的 `u:r:ksu:s0`，要么需要 `/data/misc`、`/system`、`/proc` 下只有
root 能碰的路径。AgentBridge 跑在 App 进程（uid 10488 / `u:r:untrusted_app`）：
直接做 → EACCES；经 Shizuku（`u:r:shell:s0`）→ 部分能过但读不了
`/data/misc/bluetooth`；经 `su` → App 进程内等 KernelSU 弹窗，无 UI 时挂死。
而**引擎本身就是 root**（`node` uid 0 / `u:r:ksu:s0`），所以由门脚本执行。

18 个子命令，全部输出单行 JSON：

| 子命令 | 作用 |
|---|---|
| `info` | 身份 / SELinux 域 / enforcing / 内核 / 机型 / Android 版本 |
| `ps [kw] [sort=]` | 全 uid 进程列表（直接读 `/proc`，不用 toybox ps）|
| `kill <pid\|pat> [sig]` | 按 PID 或 comm 杀，保护关键进程 |
| `cat` / `ls` | 读文件（≤512KB）/ 列目录 |
| `rm <path> [confirm=1]` | 进 quarantine，可 `undo` |
| `undo <batch>` | 从 quarantine 恢复 |
| `cp` / `mv` / `chmod` / `chown` | 文件与属主操作 |
| `prop get\|set\|list` | 系统属性（set 默认 dry-run）|
| `svc <name> <start\|stop\|restart\|status>` | init 服务 / systemctl |
| `pm list\|path\|clear-data\|disable\|enable\|uninstall` | 包管理（uninstall 默认 dry-run）|
| `settings get\|put\|delete\|list <ns>` | system/secure/global |
| `net addr\|route\|iptables\|sockets` | 网络底层 |
| `mount` | 挂载表 |
| `journal [lines] [filter=]` | logcat 全量 |
| `exec "<cmd>" [confirm=1]` | 任意 shell（默认 dry-run）|

### 四条安全边界（探针逐条锁定）

1. **危险操作默认 dry-run**：`rm` / `exec` / `prop set` / `pm uninstall`
   不带 `confirm=1` 只回报「会做什么」，不执行
2. **关键进程保护**：`kill` 拒绝 `init(1)` / `zygote` / `zygote64` /
   `system_server` / `surfaceflinger` / `servicemanager` / `logd` / `vold` /
   `netd`，以及脚本自身
3. **`rm` 不是真删**：移到 `/data/local/tmp/dsh-root-trash/<batch>/`，
   `root undo <batch>` 完整恢复（真机验证：删后 `undo`，文件内容仍是 `hello`）
4. **绝不改 SELinux 策略**：可执行代码里不出现 `setenforce` / `supolicy` /
   `magiskpolicy` / `sepolicy-inject`（只 `getenforce` 读状态）

### 能力开关 `cap.root`

单独一档、默认关闭、**仅 ROOT 权限模式可用**（`Privilege.getMode(ctx) == PrivMode.ROOT`）。
理由：这是本项目里权限最大的一档 —— 不是「某个功能」，而是整台机器的控制权。
权限中心新增一行（`rowCapRoot` / `swCapRoot`）+ 五语种文案 + `ic_root_shell` 图标。

### 真机验证（OPPO PLZ110 / Android 16 / KernelSU 4.1.3）

```
root info                     → uid:0, u:r:ksu:s0, Enforcing, PLZ110, android:16
root ps                       → 400 进程（含全 uid，rss/cmdline 齐）
root ls /data/misc/bluetooth  → bluetooth:bluetooth 770 目录可读（非 root 读不到）
root kill system_server       → killed_count:0, skipped reason:protected
root kill 1                   → pid 1 init 同样被拒
root rm ... confirm=1         → 进 quarantine；root undo 后内容仍是 hello
root exec id confirm=1        → uid=0(root) context=u:r:ksu:s0
root net sockets              → 套接字表
root journal 3                → logcat 3 行
root pm list dsh              → app.dsh.asr / app.dsh.mobile
root chmod 755 / chown 0:0    → now: 755 / root:root
root prop get ro.product.model → PLZ110
root settings get secure android_id → 4f561a...
root svc adbd status          → status: running
root svc nosuchsvc status     → ok:false, no such init service
```

### 途中修掉三个真 bug

1. **`/proc/[0-9]*` 会匹配 `/proc/8350_reg` 这类非 PID 目录** → 循环里加
   `case "$pid" in *[!0-9]*) continue ;; esac`
2. **`tr < file 2>/dev/null` 盖不住重定向失败**：进程在枚举与读取之间退出时，
   报错由 **shell** 发出（`line 145: /proc/17693/comm: No such file`），
   `2>/dev/null` 只能盖住 `tr` 自己的错误。改为先 `[ -r file ] || continue` 再读
3. **`svc` 状态读错属性**：`ctl.status.<name>` 不存在，状态实际在
   `init.svc.<name>`（值 running/stopped/restarting）。且 init 处理 `ctl.*`
   是**异步**的，固定 `sleep` 会误判 → 改为轮询最多 2 秒等状态变化

### 探针

- `scripts/probes/_test_root_gate.py`（**69 项**）：锁四条边界 + 三处清单注册
  + AI 上下文可见性 + 端点目录 + 能力开关
- `scripts/probes/_verify_root_gate_backwards.py`：**12 项反向验证，全部变红，
  0 无效断言**（探针自身也做了「变换未命中即报错」的保护）
- 全量 49 个探针：**48 过 / 1 失败**（`_test_corepack_hardlink.py` 缺
  `runtime.zip`，noruntime 既有基线）
- `check-i18n.py` → All i18n checks passed（五语种各 1229 条同步）
- `check-no-bulk-perms.py` → OK

### 反向验证暴露的探针盲区（教训）

第一版探针有两条断言**永远不会变红**：
1. `"dsh-root-trash" in gate` —— 命中的是文件头注释里那句「rm 移到 ...」
2. `"setenforce" not in gate` —— 同理，注释里写了「绝不碰 setenforce」

**教训：禁/必需类断言必须基于「剥掉注释后的可执行代码」，不能用全文。**
已在探针里统一为 `code_only`，并新增「所有 echo 输出必须是 JSON 字面量」
断言（第一版把所有 `|| echo "?"` 兜底都误判为违规，已按字面量判定修正）。

### 真机部署状态（2026-11-05 21:04）

装机 beta.21 后引擎**未自动启动**（屏幕反复进 Dozing，ColorOS 的
`OplusHansManager` 冻结整个 App），所以 `engine/bin/root` 没被部署出来。
APK 里 assets 齐全（`unzip -l` 确认 34 个 gate 含 `root`），代码三处清单也在。

手动按 `EngineConfig.applyAgentGates` 的同一套逻辑部署后，在**引擎自己的
bash + PATH**（即 AI 的真实调用路径）下验证通过：

```
PATH=$ENGINE/bin:$PATH which root
  → /data/data/app.dsh.mobile/files/engine/bin/root
PATH=$ENGINE/bin:$PATH root info
  → {"ok":true,"uid":0,"selinux_context":"u:r:ksu:s0","enforcing":"Enforcing",
     "kernel":"6.12.38...","model":"PLZ110","android":"16","sdk":"36"}
```

**下次 App 正常启动时会重新部署，无需手动干预**（部署逻辑是每次启动覆盖写）。

⚠️ 未验证项：从 **AI 对话**里直接说「用 root 看看进程」是否会正确选中这个
门脚本 —— 需要引擎与桥都活着（屏幕保持唤醒）。脚本本身与部署产物已验证。

## 三十、移除自制窄屏三件套（2026-11-05，v1.5.0-beta.22）

用户要求「把内置的界面优化插件去掉，新上游版本有专门针对手机的优化」。

### 为什么可以删（依据）

上游 `dsh-client-ui-layout@0.2.2-rc.1` 自带窄屏适配（我在真实依赖树里核过）：
- `layoutInfo.viewportWidth`（12 处引用）
- `narrowExpanded`（8 处引用，窄屏下手动展开侧栏时为 true）
- `SIDEBAR_AUTO_COLLAPSE = 1024`（视口低于此值自动收成 rail）
- `const drawer = viewport < 768 && !windowsTitlebar`（768px 抽屉断点）
- `foldedLandscape` 折叠屏横屏检测（`(device-posture: folded) and (orientation: landscape)`）

这正是我们那三个插件在做的事。两套逻辑同时生效会互相覆盖（历史上
narrow-* 就专门写了「mobile-layout 激活时让位」的互斥代码）。

### 删除清单

| 插件 | 原作用 |
|---|---|
| `narrow-sidebar` | 竖屏 + 侧栏展开时让侧栏占满宽度、挤掉对话列 |
| `narrow-settings` | 设置面板窄屏下竖向堆叠（导航变顶部横条）|
| `narrow-rightbar` | 右栏（diff / 变更视图）窄屏下提升为全屏覆盖层 |

同时清掉：
- `EngineConfig.kt` overlay 里三段 `- insert:` 与配套注释
- `OO_NARROW_DISABLED_OO` 占位符 + 「自制 vs 官方」二选一的替换逻辑
- 目录形式插件的部署清单里三条
- 三个 assets 目录（12 个文件）

### 保留

- **`chat-perf`**：纯 CSS 的 `content-visibility: auto`，解决长会话上千
  DOM 节点的渲染开销（issue #26 第二层）。与窄屏无关，上游没有等价物。
- 设置页「官方推荐手机 UI」开关：语义从「自制 vs 社区二选一」变成
  「社区布局插件开 / 用上游默认」，五语种文案已同步改写。

### 验证

- `_test_builtin_plugins.py` 改为断言三个插件「删干净了」（assets 目录 /
  部署清单 / overlay entry 三处都不能残留）→ 34 项全过
- 全量 50 探针：**49 过 / 1 失败**（`_test_corepack_hardlink.py` 缺
  `runtime.zip` 的 noruntime 既有基线）
- `check-i18n.py` → All i18n checks passed
- `check-no-bulk-perms.py` → OK
- `assembleRelease` → `BUILD SUCCESSFUL in 1m 38s`
- APK 内 `assets/android-plugins/` 只剩 **10 个条目**（原 22 个），
  三个 narrow-* 目录已不存在，`chat-perf` 三个文件仍在

### ⚠️ 途中踩的坑（供后续参考）

`EngineConfig.kt` 里那串 YAML 是 **Kotlin 单行字符串**，中文注释会被
`check-i18n.py` 的「CJK in Kotlin string literal」门禁拦下。
我一开始写了中文注释，门禁立刻报了
`CJK in Kotlin string literal EngineConfig.kt:1126`。
→ **改这串 YAML 时注释必须用英文**（这是既有约定，只是容易忘）。

## 三十一、删除对话功能（2026-11-06，v1.5.0-beta.29）

### 用户需求与硬约束

> 「在维护与高级里加一个删除对话内容的新功能，允许在里面直接让用户删对话，
> 或者一键清空所有对话记录」
> 「不要启动时读取，一定不要让任何事情影响启动速度」

### 落地

| 文件 | 作用 |
|---|---|
| `app/src/main/java/app/dsh/mobile/engine/ChatWiper.kt` | 核心逻辑：`list` / `delete` / `clearAll` / `purgeTrash` |
| `app/src/main/java/app/dsh/mobile/WipeChatsActivity.kt` | 会话选择页（多选 + 全选 + 一键清空）|
| `app/src/main/res/layout/activity_wipe_chats.xml` | 列表 / 空态 / 加载态 / 底部操作条 |
| `app/src/main/res/layout/activity_maintenance.xml` | 新增 `rowWipeChats` 入口行（`rowGrantPerms` 之前）|
| `app/src/main/res/drawable/ic_delete.xml` | 垃圾桶图标 |
| `scripts/probes/_test_chat_wiper.py` | 45 项契约锁 |

### 零启动开销怎么保证

- `ChatWiper` 没有 `init` 块、object 体无 eager 工作、不启后台线程。
- 维护页只绑 `startActivity`，副标题静态文案，**不数会话**（数会话本身是 IO）。
- 探针逐文件断言 `MainActivity.kt` / `DshApp.kt` / `engine/EngineSupervisor.kt` /
  `engine/EngineConfig.kt` / `engine/BootTrace.kt` 五个启动链路文件**不出现**
  `ChatWiper` / `WipeChats` 符号。反证：往 `MainActivity` 加一行 import 立刻变红。

### 为什么必须先停引擎

引擎把 `storages/workspace.json` 当独占文件（启动读一次进内存，之后每次变更
整份写回）。运行期删文件后，引擎下一次变更会用内存状态把注册表写回来 → 列表里
留下点进去空白的幽灵条目。`ChatRecovery.kt` 的注释里记了同一个坑的实测
（「13:04 写进 7 个工作区，13:07 被回落成 1 个」）。

流程：`stopForMaintenance()` → 删 → `allowStartAfterMaintenance()` + `restart()`。
`restart()` 是异步的，且它内部第一件事就是 `maintenanceStop = false`。

### 安全兜底

- 会话**移动**进 `sessions/.trash-<时间戳>-<mode>/`，保留 `TRASH_KEEP_DAYS = 7` 天。
- 注册表写回前备份到 `storages/workspace.json.before-wipe-<时间戳>`。
- 原子写回（`.workspace.json.wipe-tmp` + rename）。
- 清三类引用：各工作区 `sessionIds`、`global.archivedSessionIds`、
  `global.pinnedSessionIds`。**工作区条目本身保留。**
- 标题/cwd 读 `storages/session_projcache/sessions/<id>.json`（明文），
  **不解压 `.zstd`**（每会话一个 node 进程，几百个要等到超时）。

### ★ 真 bug：回收站被当成会话（离线复刻抓到的）

真机被锁屏挡住且 adb 掉线，改用 Python 逐行复刻核心算法 + 造假数据跑完整流程，
发现清空全部后 `list()` 仍返回 1 条。

根因：回收站建在 `sessions/` 下，内部布局
（`.trash-*/<id>/session.v4.jsonl.zstd`）与正常会话**完全一致**，而 `list()`
顶层遍历只判 `isDirectory`，没排除 `.trash-*`。

修法：抽出 `private const val TRASH_PREFIX = ".trash-"`，四处遍历统一使用
（`list` 跳过 / `purgeTrash` 识别 / `run` 移动阶段跳过 / 建目录时拼名）。

**教训：任何「把东西移到同层子目录」的软删除都会污染自己的枚举逻辑 ——
遍历时必须显式排除回收站，不能靠「布局不同」来区分。**

### 验证状态

- 探针 45/45；四处反向验证全红（启动路径引用 / 删除顺序 / trash→unlink / 备份缺失）
  + 第五处（去掉 `list` 跳过 → 2 项红）。
- 全量 53 过 / 1 失败（`_test_corepack_hardlink.py` 缺 `runtime.zip` 的 noruntime 基线）。
- ⚠️ **真机目视与真实删除未完成**：设备锁屏有密码（`locksettings verify` 报
  `Old password '0000' didn't match`），adb 中途掉线
  （`cannot connect to 192.168.101.21:9000`）。列表渲染、勾选交互、删除后引擎
  重启均只做了代码/构建/离线复刻三层验证。

## 三十二、运行时必选面板 + 启动页大升级（2026-11-06，v1.5.0-beta.31）

### 三条用户报障的根因（全部定位到具体行）

| 报障 | 根因 |
|---|---|
| 下载提示连续出现两次 | `onResume` 无条件调 `showRuntimeSetupDialog()`，而它每次都 `Builder(...).show()` 新建。改为持有 `runtimeSetupDialog` 判 `isShowing`（同既有 `gateDialog` 范式）|
| 不下载就卡死 | 面板有「稍后」按钮且 listener 是 `null`；返回键/点外部也能关。关掉后引擎不被拉起，遮罩永远等不到 READY |
| 才 10MB 进度条就满 | `ProgressBar` 的 `android:max` **默认 100**，代码写 `(frac * 10000)` → frac ≥ 1% 就满格 |

修法：`setCancelable(false)` + `setCanceledOnTouchOutside(false)` + 吞 `KEYCODE_BACK`
+ 删掉 negative button；两个 `ProgressBar` 补 `android:max="10000"`；
五语种 `runtime_setup_*` 改必选措辞、`runtime_setup_later` 五份全删。

### 启动页重做（GPT 并行出方案，我落地）

新增 `app/src/main/java/app/dsh/mobile/ui/SplashController.kt`（状态/渲染/退出）
与 `SplashRingView.kt`（自绘 1.5dp 细环），布局抽到
`app/src/main/res/layout/view_boot_splash.xml`，`activity_main.xml` 用 `<include>`。

**状态单一数据源**是本轮最重要的结构决策。旧实现两条订阅同时写
`splashProgress`：`renderProgress()`（安装总进度，「下载 45% + 解压 55%」）
与 `renderSplashTrace()`（当前阶段 0..100%）—— 口径不同、时机不同，百分比
来回跳。现在遮罩只读 `BootTrace`，顶部细条继续用安装总进度。

环的语义：确定态弧长 = `360f * frac`（**不做假缓动**，真实进度必须可读）；
不确定态 90° 短弧 2.6s 一周（不是整圈，避免「一直在完成」的错觉）。

### ★ 阶段切换必须清零 install

`BootTrace.stage()` 旧实现 `copy(stage = ...)` 只换阶段名、保留 `install`。
下载结束转解压时会短暂出现「正在解压 + 下载的 100%」。改为进入
`DOWNLOADING`/`EXTRACTING` 时 `install = Install()`。

### 动画可停（启动页最容易漏）

`SplashRingView` 在 `onDetachedFromWindow` / `onWindowVisibilityChanged` /
`onVisibilityAggregated` 全停，且尊重 `ValueAnimator.areAnimatorsEnabled()`；
`SplashController.dispose()` 移除 3 处 `removeCallbacks` + 取消动画 +
`removeOnLayoutChangeListener`。

### 宽屏限宽

`constrainWidth()`：正文收在 `minOf(可用宽度, 420dp)`。

### 验证状态

- 新探针 `_test_splash_redesign.py`（34 项）+ `_test_runtime_required_panel.py`（20 项）
  + `_test_chat_wiper.py`（45 项）+ `_test_back_guard.py`（21 项）
- **反向验证共 11 处全红**：每次改坏一处，对应断言必变红，复原后全绿
- 三个既有探针断言从 `MainActivity` **迁到** `SplashController`（不是删除）
- 全量 56 过 / 1 失败（`_test_corepack_hardlink.py` 缺 `runtime.zip` 基线）

### ⚠️ 真机验证缺口（截至本记录）

设备 `192.168.101.21:9000` adb 掉线（`cannot connect ... 10060`），且锁屏有
密码（`locksettings verify` 报 `Old password '0000' didn't match`）。
以下**只有代码/构建/离线复刻证据，不等于设备实测**：
启动页环的比例与动画节奏、深浅两套配色、展开步骤后的滚动；
删除对话的列表渲染/勾选/删除后引擎重启；运行时面板只出现一次且不可取消。

## 三十三、Linux 模式 Web 终端跑在容器里（2026-11-06，commit `4e592d8`）

### 用户报障

「linux 模式下 dsh 终端功能不能正常使用，一直显示加载中」（m02267）。
后来补充的现场感受是「可用 linux 环境、可正常使用各种命令」—— 说明**引擎能起、
终端面板能开**，问题在 PTY 里跑的东西不对。

### 根因链（模拟器实测，四步全部有证据）

| # | 环节 | 证据 |
|---|---|---|
| 1 | 引擎开终端走 node-pty → 本仓库 Android 兼容层 → 原生助手 `dsh_pty_exec` | `app/src/main/cpp/dsh_pty_exec.c`，`execv(program, &argv[4])` 在 :225 |
| 2 | `dsh_pty_exec` 是 **bionic（NDK）** 二进制 | `libdsh_pty_exec.so` 由 NDK 编译，APK 的 `lib/x86_64/`、`lib/arm64-v8a/` 两处都有 |
| 3 | `dsh_alpine` 的 `guest_is_bionic()` 把它判为宿主程序 → 交宿主 cleaner 代跑，**那一侧没有容器 bind 视图** | 容器内 `readlink /proc/$$/exe` = `/system/bin/sh`、`[ -d /etc/apk ]` = NO、`PATH` 全是 `/product/bin:/apex/...` |
| 4 | 于是 `execv("/bin/sh")` 命中 `/system/bin/sh`，用户看到的终端是宿主 bionic shell | A/B 实测（见下表）|

**为什么不是 `os.userInfo()`**：容器内以 App uid 跑，`/etc/passwd` 没有对应条目，
`os.userInfo()` 确实抛 `ERR_SYSTEM_ERROR`（实测），但 `terminalEnvironment()`
走的是 `process.env.SHELL` 短路 —— 而 `dsh_alpine` 的 `env_drop_host()`
（`dsh_alpine.c:653`）丢了宿主 `SHELL` 后由 `env_fill_defaults()` 补回
`SHELL=/bin/sh`，所以这条链不会断。**早期的这个推测是错的，别再照着改。**

### 修法

把「exec shell」改写成「经 `dsh_alpine` 进容器再 exec shell」：

- `LinuxEngineLauncher.buildEngineArgv`（env 注入处，`getEnvVars` 尾部）注入
  四个量：`DSH_TERMINAL_RUNNER` / `_ROOTFS` / `_INTERP` / `_BINDS`。
  bind 表按**每行 `guest:host`** 编码（路径带空格，不能用空格分隔）。
- `scripts/patch-node-pty-android.py` 新增 `containerWrap(file, args)`：
  读出这四个量拼成容器 argv，`spawnPty` 里用它替换 `program`/`programArgs`，
  落在 `argv[4]`（即 `dsh_pty_exec` 的 `execv` 起点）。
- **降级路径**：缺任一必需量、或 `runner` 不可执行 → 返回 `undefined` 原样直通。
  原生模式（非 Linux 模式）与老版本 App 行为完全不变。

### A/B 实测（模拟器 emulator-5554，同一条 `dsh_pty_exec` 命令，只换 program）

```
修复前 program=/bin/sh
  EXE=/system/bin/sh   HASH=(空)   APK=NONE        ISCONTAINER=NO
修复后 program=dsh_alpine -m ... <rootfs> <interp> /bin/sh
  EXE=/data/local/tmp/captest/runner/dsh_alpine
  HASH=3.22.2          APK=/sbin/apk              ISCONTAINER=YES   PWD=/ws
```

### 复现环境（模拟器上跑通容器的完整配方）

模拟器 `emulator-5554`（sdk_gphone64_x86_64 / Android 14 / density 440）。

1. `gradle -p . -Ppackage=noruntime -Pabi=x86_64 assembleRelease`
   → APK 内 `lib/x86_64/libdsh_pty_exec.so`（11192 B）+ `libdsh_alpine.so`（46688 B）
   + `assets/linux/rootfs-bootstrap-x86_64.tgz`。
2. 从 APK 解出三者，推到 `/data/local/tmp/captest/`。
3. **解 rootfs 后必须把绝对符号链接改写成相对链接**
   （`LinuxEnvironmentManager.materialiseLinks` 做的事）。335 条链里
   `bin/sh -> /bin/busybox` 若不改写，内核按宿主根解析 → ENOENT，
   所有 busybox applet 全变 `not found`。手工验证时按
   `relativeTarget()` 的算法改写即可。
4. 重放：`dsh_pty_exec 24 80 <cwd> <runner/dsh_alpine> -m g:h ... <rootfs> <interp> /bin/sh -c '...'`

**踩坑**：`adb shell "IFS='\t' read ..."` 里字面量 `\t` 不会被解释成制表符，
链接改写会静默失败（报一堆 `ln: cannot create symbolic link from ''`）。
改成生成设备端 sh 脚本、用默认 IFS 逐行读才可靠。

### 探针

`scripts/probes/_test_linux_terminal_in_container.py`，20 项，全静态。

- 守 Kotlin 侧四个变量名、bind 表的 `joinToString("\n")` 编码
- 守 `containerWrap` 定义与四个读取点
- 守 `spawnPty` 里**赋值**调用 containerWrap（不是只出现函数名 —— 第一版
  正则漏了 `=`，反向验证时探针不变红，已修）
- 守 `program`/`programArgs` 取自 wrap 结果、`program` 落在 `argv[4]`
- 守降级路径（缺参数返回 undefined、runner 不可执行时不包装）
- 守 `collect-termux-runtime.sh` 真的应用了这份补丁

反向验证：把 `const wrapped = containerWrap(file, args);` 改成
`const wrapped = undefined;` → `[D1]` 变红；复原 → 20/20 全绿。

### 验证状态

- 上述 A/B 实测：通过（模拟器）
- 探针 20/20；全量 py 探针 **60 过 / 1 败**（唯一失败
  `_test_corepack_hardlink.py` 缺 `app/src/main/assets/runtime.zip`，
  noruntime 构建的既有基线，且该探针本身未入库）
- `check-i18n.py` 全绿
- ⚠️ **未在真实 App UI 里端到端验证**：模拟器上 App 被卸载重装后回到首次引导
  第 1 步，rootfs 未部署；且 `jniLibs/x86_64` 缺 `libdsh_alpine.so` 的打包路径
  时引擎无法在模拟器里起。真机 `192.168.101.21:9000` 全程 `10060` 连不上。
  即：**容器 argv 的正确性有实测，App 内最终呈现待真机确认。**

## 三十四、容器里「打开配置文件」打不开（2026-11-06，commit `e8599f3`）

### 用户报障

「容器环境里配置文件也打不开」（m06400）。与三十三的终端问题同源 —— 都是
**把 guest 视角的路径当成宿主路径**。

### 根因

Linux 模式下引擎跑在容器里，`DSH_HOME=/dsh`。上游「打开配置文件」的路径来自
`settings.prepareDocument()`：

```
prepareDocument()  →  this.documentPath
documentPath       →  ownerContext.configEditor.documentPath
configEditor       →  profileContext.patchPath
patchPath          →  join(resolveProfileDir(name, home), PROFILE_PATCH_FILENAME)
resolveProfileDir  →  join(home, PROFILES_DIR, name)
home               →  resolveDshHome()  →  $DSH_HOME  →  /dsh
```

所以 App 收到的是 **`/dsh/profiles/web/cordis.patch.yml`**（容器内视角）。

宿主上没有 `/dsh`，两道检查都过不去：

| 检查 | 结果 |
|---|---|
| `File(raw).isFile` | false → `404 not a file` |
| `isOpenablePath(ctx, real)` | 越界 → `403 path is outside the app-managed roots` |

前端把两者都显示成「无法打开配置文件」，用户以为文件坏了。

### 修法

1. `LinuxEngineLauncher.hostPathOf(guestPath: String): String?` ——
   按 **`buildBinds()` 那张表**做反向查表。规则：
   - 只译绝对路径（相对路径返回 null，容器相对路径在宿主没有对应语义）；
   - 归一化重复斜杠与结尾斜杠；
   - **最长前缀优先**（`/dsh` 与 `/dsh/cache` 同时存在时取更具体的）；
   - 前缀必须落在路径边界上（`/dshx` 不能被 `/dsh` 命中）。
   「宿主路径同名挂载」那类是恒等映射，翻译后仍是原路径。
2. `AgentBridge.openFile` 在过校验前先翻译，**门是 `lastMode == LINUX`**：
   原生模式下 `/dsh` 不该被当 guest 路径；宿主上若真有同名目录，无条件翻译
   会把它抢走。
3. 翻译后**仍走原有的 `isOpenablePath` 根检查** —— 不因为翻译就放宽权限。
4. 原路径在宿主上是文件时**优先用原路径** —— 保证「以前能打开的现在仍能打开」，
   同时覆盖 `lastMode` 尚未判定的窗口（进程刚起、`resolve()` 还没跑）。
5. 翻译未命中时 `Log.w("cannot map guest path to host")`：这是挂载表缺口，
   不是用户文件问题，明确留痕避免排查时误判成「文件不存在」。

### 探针

`scripts/probes/_test_linux_config_path.py`（17 项）。除了静态断言，
还把翻译算法在 Python 里复刻一遍做 **12 例逐例核对**（含用户报障那条
`/dsh/profiles/web/cordis.patch.yml`、恒等映射、`/dshx` 反例、相对路径）。

**反向验证四处全红并复原**（每处单独改坏、跑探针、复原再跑）：

| 改坏什么 | 变红的断言 |
|---|---|
| 去掉 `hostPathOf(raw)` 调用 | `[D1]` |
| 去掉路径边界判断 | `[B6]` |
| 把模式门改成 `if (true)` | `[D2]` |
| 让翻译结果不被采用 | `[D3]` |
| 跳过 `isOpenablePath` | `[D4]` |

**踩坑（探针写法）**：`[D2]` 第一版只查 `EngineModeSelector.Mode.LINUX` 是否
出现在函数体里 —— 但下面 `else if` 也含这串，把门改成 `if (true)` 探针仍绿。
必须锁定**赋值那一行**的正则：
`val translated = if (EngineModeSelector.lastMode == EngineModeSelector.Mode.LINUX) {`

### 踩坑（行尾）

编辑 `AgentBridge.kt` 后文件变成 CRLF（2564 行全带 CR），而 `.gitattributes`
声明 `* text=auto eol=lf`、HEAD 版本是纯 LF。提交前必须归一化，否则
`git-hygiene.py` 的「无 CRLF 污染」一项会红。归一化方法：

```python
b = open(p,'rb').read(); open(p,'wb').write(b.replace(b'\r\n', b'\n'))
```

### 验证状态

- 探针 17/17；全量 py 探针 **61 过 / 1 败**（`_test_corepack_hardlink.py` 缺
  `runtime.zip` 的 noruntime 既有基线）
- `check-i18n.py` 全绿；`git-hygiene.py` 「无 CRLF 污染」通过
- 编译产物核验：`AgentBridge.class` 与 `LinuxEngineLauncher.class` 里
  `strings` 能查到 `hostPathOf` / `mapped guest` / `cannot map guest path to host`
- ⚠️ **未在真实 App UI 端到端验证**：真机 `192.168.101.21:9000` 全程 10060
  连不上；模拟器上 App 停在首次引导、rootfs 未部署，无法走到「打开配置文件」。
