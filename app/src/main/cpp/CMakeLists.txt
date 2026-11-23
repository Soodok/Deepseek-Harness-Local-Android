# dsh-android PTY 原生层
cmake_minimum_required(VERSION 3.22.1)
project(dshpty C)

add_library(dshpty SHARED dsh_pty.c)

# dsh_pty_exec（roadmap M2）：终端用的 PTY 执行助手。
# 引擎内的 Web 终端与持久 bash 工具经 node-pty 申请伪终端，而 node-pty 在
# Android 无预编译产物 —— 这个**独立可执行文件**就是补上来的后端：引擎侧
# node-pty 兼容层 spawn 它，它再 fork 挂 PTY 的 shell 并双向搬运字节。
#
# ⚠️ 产物名刻意写成 lib*.so：AGP 只把 CMake 输出里的 *.so 当原生库收进 APK
# （裸名字会被忽略）。App 启动时从自己的 APK 里读出这个条目、落到
# engine/bin/dsh_pty_exec 并 chmod +x —— targetSdk 28 豁免 W^X，可以直接 execve。
add_executable(dsh_pty_exec dsh_pty_exec.c)
set_target_properties(dsh_pty_exec PROPERTIES OUTPUT_NAME "libdsh_pty_exec.so")

# ★必须静态链接（v1.8.3）。
#
# 动态 bionic 可执行文件的 `PT_INTERP` 是 `/system/bin/linker64`（实测从
# APK 里读 ELF program header：`PT_INTERP = /system/bin/linker64`、有 PT_DYNAMIC）。
# 而 **chroot 沙箱模式**下引擎跑在 rootfs 里，chroot 只挂
# `/proc /dev /sys /tmp /dev/pts` 与 `/data` 系（见 LinuxSandbox.REQUIRED_MOUNT_POINTS）
# —— **没有 /system**。于是 `execv(dsh_pty_exec)` 找不到解释器直接 ENOENT，
# node-pty 起不来 → Web / PTY 终端在整个 chroot 模式下不可用，而其它功能都正常
# （门脚本、引擎本身跑的是 guest 自己的 node）—— 症状正好是「只有 pty 终端不能用」。
#
# 静态链接没有 PT_INTERP，内核直接装载，任何 rootfs 里都能跑；
# 本文件不调用 `__android_log_print`，也不需要额外的 bionic 运行时。
target_link_options(dsh_pty_exec PRIVATE "-static")

# 16KB 页对齐：Android 15+ 部分 aarch64 真机内核使用 16384 字节页，
# LOAD p_align=4096 的 so 在其上 dlopen 会报 "program alignment" 失败。
# 不依赖 NDK r27+ 的默认行为，对所有 ndkVersion 显式生效。
target_link_options(dshpty PRIVATE
    "-Wl,-z,max-page-size=16384")
target_link_options(dsh_pty_exec PRIVATE
    "-Wl,-z,max-page-size=16384")

target_link_libraries(dshpty
    # 仅依赖 log，无 STL —— 纯 C11 实现，规避 NDK STL 兼容问题
    log)

# ---------------------------------------------------------------------------
# dsh_setuid_shim —— 让 xorg 的 Popen() 在 Android App 域里跑得通（v1.6.0-beta.23）
#
# Xvfb 初始化键盘时调 xkbcomp，走 xserver 自己封装的 "safer popen"
# （os/utils.c: Popen），其子进程分支是：
#
#     if (setgid(getgid()) == -1) _exit(127);
#     if (setuid(getuid()) == -1) _exit(127);
#     execl("/bin/sh", "sh", "-c", command, NULL);
#
# 即「先放弃所有特权再执行命令」。而 Android App 域
# （u:r:untrusted_app_27，CapEff=0）内核一律拒绝 setuid/setgid —— 即使传的
# 就是进程自己当前的 uid/gid —— 子进程按源码 _exit(127)，父进程 Pclose()
# 拿到 127，Xvfb 依次打出：
#
#     Error compiling keymap / XKB: Failed to compile keymap
#     Keyboard initialization failed... / Failed to activate virtual core keyboard: 2
#
# 当场退出、一个 listening socket 都不建 —— 用户侧只是「点开始画面无反应」。
#
# 这个 .so 通过 LD_PRELOAD 只对 Xvfb 生效，把「放弃自己本来就已经是的那个
# 身份」按恒等变换处理（返回成功）；任何别的 uid/gid 请求照常透传给内核，
# 失败就如实失败，绝不伪造提权成功。
#
# ⚠️ 构建约束：
#   - -ffreestanding -fno-builtin：不引入任何 libc 符号
#   - -nostdlib：产物无 NEEDED、无 UND 符号 —— 它会被同时灌进 musl(Alpine)
#     与 glibc 两个 rootfs，任何 libc 依赖都会在其中一边解析失败
#   - 全程裸 syscall（aarch64: svc #0 / x86_64: syscall）：xorg 在 fork 出来的
#     子进程里调 setuid，此时 dlsym 可能撞上 dl 锁
#   - 16KB 页对齐：Android 15+ 的 16KB 页内核要求 LOAD 段 p_align >= 页大小
# ---------------------------------------------------------------------------
add_library(dsh_setuid_shim SHARED dsh_setuid_shim.c)
# ⚠️ OUTPUT_NAME 只写裸名字，**不要**自己加 lib 前缀和 .so 后缀。
#   AGP 会把 SHARED 库按 OUTPUT_NAME 再套一层：实测写 "libdsh_setuid_shim.so"
#   得到 APK 条目 lib/arm64-v8a/liblibdsh_setuid_shim.so.so（大小正确但没人认得），
#   而 App 侧按 /libdsh_setuid_shim.so 找，于是永远找不到 → 永远 warn、永远没装上。
set_target_properties(dsh_setuid_shim PROPERTIES OUTPUT_NAME "dsh_setuid_shim")
target_compile_options(dsh_setuid_shim PRIVATE
    -ffreestanding
    -fno-builtin
    -fno-stack-protector)
target_link_options(dsh_setuid_shim PRIVATE
    "-nostdlib"
    # ⚠️ --as-needed 不是可选项，是这条构建约束的一部分。
    #
    #   "-nostdlib" 只管「不要默认启动文件与默认库」，它管不住 NDK toolchain 通过
    #   CMAKE_C_STANDARD_LIBRARIES 追加进来的 "-latomic -lm"：CMake 在生成阶段把
    #   它们直接拼进 Ninja 的 LINK_LIBRARIES 变量，那是生成器级变量，
    #   set_target_properties(... LINK_LIBRARIES "") 也覆盖不掉（两种写法都试过，
    #   build.ninja 里始终是 "-latomic -lm"）。
    #
    #   后果：产物 dynamic 段里会多一条 NEEDED libm.so（实测 APK 条目 3264 B）。
    #   而 Alpine musl rootfs 里根本没有 libm.so（musl 把 libm 并进 libc，只有
    #   lib/ld-musl-aarch64.so.1）—— musl ldso 会静默忽略这条 NEEDED 而不报错，
    #   功能上没坏，但和上面「产物无 NEEDED」的承诺不符；换到真 glibc rootfs 上
    #   还会凭空多拉一个库。
    #
    #   加上 --as-needed 后，链接器发现 libm / atomic 一个符号都没被引用，就不再
    #   写 DT_NEEDED：实测产物从 3264 B 降到 3240 B，dynamic 段只剩 SONAME。
    "-Wl,--as-needed"
    "-Wl,-z,max-page-size=16384")

# ---------------------------------------------------------------------------
# dsh_alpine —— Linux 用户态运行器（免 root 的 seccomp 容器）
#
# 用 seccomp BPF + SIGSYS handler 拦截系统调用并把绝对路径翻译到 rootfs 内，
# 从而在**非 root**（uid 2000 即可）设备上运行完整的 glibc/musl Linux 用户态。
# 不走 ptrace、不需要 user namespace、不需要 chroot。
#
# ⚠️ 必须是 **freestanding 静态 ET_EXEC 且固定基址**，四个开关缺一不可：
#   1. -nostdlib -nostartfiles -nodefaultlibs -static —— 不链接 bionic libc。
#      handler 内绝不能调 libc：guest 的 musl 启动后改写 TPIDR_EL0 接管 TLS，
#      此后任何 snprintf/memcpy/malloc 都会 SEGV。
#   2. -Wl,--image-base=0x2000000000 —— BPF 白名单靠比对 instruction_pointer
#      识别自己的裸 svc 桩，地址必须每次运行都相同（PIE/ASLR 会让白名单失效）。
#   3. -ffreestanding -fno-builtin —— 阻止编译器把字节循环优化成 memcpy 调用。
#   4. -no-pie / -fno-pie -fno-PIE —— 同上，保证固定地址。
#
# ⚠️ 16KB 页对齐在这里给的是 **0x20000（128KB）**，比上面 PTY 助手的 16KB 更大：
# 本程序用 mmap(MAP_FIXED) 按段布局装载 ELF，段间需要足够大的对齐余量；
# 且 Android 15+ 的 16KB 页内核要求 LOAD 段 p_align >= 页大小。
#
# 产物名同样写成 lib*.so，理由与 dsh_pty_exec 一致。
#
# ⚠️ 仅 aarch64 构建。本实现是**架构专属**的：
#   · dsh_alpine.c:718 用 `register unsigned long x0 __asm__("x0")` 切 guest 栈，
#     只有 AArch64 有 x0；x86_64 直接报 unknown register name 'x0'。
#   · dsh_alpine.c:888 读 `u->uc_mcontext.regs`、:926 改 `uc_mcontext.pc`，
#     都是 AArch64 的 ucontext 布局（x86_64 是 gregs / rip）。
#   · BPF 白名单还要比对 instruction_pointer 识别自己的裸 svc 桩，这套桩也是
#     AArch64 指令编码。
# 而 App 侧本来就只有 arm64 会走容器（EngineConfig.deviceAbiIsArm64()），
# 所以 x86_64 干脆不编这个目标 —— 之前无条件 add_executable 让 x86_64 lane
# 一直编译失败（buildCMakeRelWithDebInfo[x86_64] 3 errors）。
# ---------------------------------------------------------------------------
# dsh_alpine —— 源码入库 + 预编译产物入库（v1.6.13 起）
#
# 历史：
#   v1.6.12 及之前  现场编译 dsh_alpine.c
#   v1.6.12 后期     源码移出仓库（.gitignore），只留预编译 .so
#   v1.6.13 起       源码重新入库（专有许可，见文件头声明），.so 仍入库
#
# 为什么两样都留：
#   · 源码入库 → 可审查、可 diff；多会话/多机器协作时不会出现「源码只存在于
#     某次 /tmp 里」的漂移（真实发生过：DNS 双向改写的源码一度只在 /tmp/work3.c）
#   · .so 入库 → CI 与本地构建都不需要 NDK 就能出包，且产物字节稳定
#   · 专有许可让我们不必为了「不公开」而藏源码；公开反而便于他人 review
#
# ⚠️ 两者必须同步：改 dsh_alpine.c 后必须重编 .so，否则源码与产物不一致。
#   scripts/probes/_test_dsh_alpine_source_sync.py 会校验这一点（比对源码里的
#   特征字符串与 .so 里的实际内容）。
#
# 编译命令（必须与下面的约束一致）：
#   NDK=/path/to/ndk/28.x
#   CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang
#   $CC -O1 -ffreestanding -fno-builtin -fno-pie -fno-PIE \
#       -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector -DPATH_MAX=4096 \
#       -static -nostdlib -nostartfiles -nodefaultlibs -no-pie \
#       -Wl,-e,_start -Wl,--image-base=0x2000000000 \
#       -Wl,-z,max-page-size=0x20000 -Wl,-s \
#       -o app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so \
#       app/src/main/cpp/dsh_alpine.c
#
# ⚠️ 预编译产物必须满足以下全部条件，否则运行时会崩（这些约束原先由下面的
#    target_compile_options / target_link_options 保证，改产物时务必复核）：
#   1. freestanding 静态、不链接 bionic libc（handler 内绝不能调 libc：
#      guest 的 musl 启动后改写 TPIDR_EL0 接管 TLS，之后 strcpy/malloc 都会 SEGV）
#   2. 固定基址 0x2000000000、ET_EXEC、无 PIE（BPF 白名单靠比对
#      instruction_pointer 识别自己的裸 svc 桩，地址必须每次运行都一样）
#   3. -ffreestanding -fno-builtin（阻止编译器把字节循环优化成 memcpy 调用）
#   4. 16KB 页对齐取 0x20000（Android 15+ 的 16KB 页内核要求 LOAD 段 p_align
#      >= 页大小；本程序按段 mmap(MAP_FIXED)，段间还需对齐余量）
#   5. 关掉 fortify 与 stack-protector（AGP 默认注入，会引用 libc 符号导致链接失败）
#   6. 仅 aarch64（源码用 register x0 与 AArch64 ucontext 布局，x86_64 编不过）
#
# 复核命令（改产物后跑一次）：
#   llvm-readelf -h libdsh_alpine.so | grep -E 'Type|Entry'
#   → 期望 Type: EXEC、Entry point address: 0x2000026...
#
# 为什么不在 CMakeLists 里 add_executable dsh_alpine：
#   输出名要叫 libdsh_alpine.so 才会被 AGP 当 jniLib 打进 APK（与 libtermux.so
#   同机制）；而 CMake 的 add_executable + OUTPUT_NAME 会与 AGP 的 strip 流程、
#   16KB 页对齐参数互相干扰（历史上踩过）。用上面的显式命令最稳，CI 与本地
#   字节一致。改源码后请照抄那条命令重编。
#
# ★DNS 免 root 桥接（2026-11-05，v1.6.13）
#
#   musl 的 resolver 把 DNS 端口硬编码成 53（`nameserver ip:port` 的端口部分被
#   整段忽略，实测 53 收到包、指定端口收到 0）。53 是特权端口，App uid 绑不上，
#   而 Android 又按 uid 拦掉发往外部 53 的包 —— 于是容器内 apk/wget/pip 全都
#   `bad address`。解法是在 loader 里做**双向**端口改写，端口来自环境变量
#   `DSH_DNS_PORT`（LinuxDnsProxy 绑在非特权高位端口）：
#
#     出程 sendto / sendmsg  : 127.0.0.1:53        -> 127.0.0.1:<DSH_DNS_PORT>
#     回程 recvfrom / recvmsg: 来源 <DSH_DNS_PORT> -> 改回 53
#
#   回程这一步不能省。musl 的 __res_msend_rc 会校验应答来源端口等于它发出的
#   目标端口，只改出程的话应答会被整包丢弃 —— 症状极具误导性：sendto 返回成功、
#   LinuxDnsProxy 日志也打出 "udp replied 144B"，但 getaddrinfo 仍返回 EAI_AGAIN。
#
#   改写只针对回环地址（外部 DNS 本就被 Android 拦，改了没用还会掩盖问题）。
#   未设 DSH_DNS_PORT 时完全不动，行为与之前一致。
#
#   这两个 syscall 已登记进 BPF 树（tools/gen_bpf_tree.py 的 NR 表：
#   sendto=206 / sendmsg=211 / recvfrom=207 / recvmsg=212），
#   改 NR 表后必须重新生成 bpf_tree.inc 并整体替换源码里的 f[] 数组，
#   否则新分支永远不会被 trap 到。

