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
# dsh_alpine —— 已改为**预编译产物**，源码不再入库
#
# 原先这里 add_executable(dsh_alpine dsh_alpine.c) 现场编译。现在改为把
# 预先编好的 libdsh_alpine.so 放在 app/src/main/jniLibs/arm64-v8a/ 下，
# 由 AGP 直接打进 APK（与 libtermux.so 同一机制）。
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
# 原始 CMake 配置如下，保留备查：
#   add_executable(dsh_alpine dsh_alpine.c)
#   set_target_properties(... OUTPUT_NAME "libdsh_alpine.so")
#   target_compile_options(-O1 -ffreestanding -fno-builtin -fno-pie -fno-PIE
#                          -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector)
#   target_compile_definitions(PATH_MAX=4096)
#   target_link_options(-static -nostdlib -nostartfiles -nodefaultlibs -no-pie
#                       -Wl,-e,_start -Wl,--image-base=0x2000000000
#                       -Wl,-z,max-page-size=0x20000 -Wl,-s)

