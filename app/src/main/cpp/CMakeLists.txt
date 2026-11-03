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
# ---------------------------------------------------------------------------
add_executable(dsh_alpine dsh_alpine.c)
set_target_properties(dsh_alpine PROPERTIES OUTPUT_NAME "libdsh_alpine.so")
target_compile_options(dsh_alpine PRIVATE
    -O1
    -ffreestanding
    -fno-builtin
    -fno-pie
    -fno-PIE
    # ⚠️ 必须显式关掉 fortify：AGP 的 Release 配置会注入 -D_FORTIFY_SOURCE=2，
    # 而 fortify 的 unistd.h 分支要求 SSIZE_MAX（由 libc 头提供）。本程序不链接
    # libc，该宏未定义 → 编译直接失败（实测报 use of undeclared identifier
    # 'SSIZE_MAX'，20 errors）。裸命令行编译时不带这个宏，所以之前没暴露。
    -U_FORTIFY_SOURCE
    -D_FORTIFY_SOURCE=0
    # ⚠️ 同理必须关掉栈保护：AGP 默认注入 -fstack-protector-strong，它会生成对
    # __stack_chk_fail 的调用，而本程序不链接 libc（该符号不存在）→ 链接失败
    # （实测 ld.lld: error: undefined symbol: __stack_chk_fail）。
    -fno-stack-protector)
target_compile_definitions(dsh_alpine PRIVATE
    PATH_MAX=4096)
target_link_options(dsh_alpine PRIVATE
    -static
    -nostdlib
    -nostartfiles
    -nodefaultlibs
    -no-pie
    -Wl,-e,_start
    -Wl,--image-base=0x2000000000
    -Wl,-z,max-page-size=0x20000
    # 去符号表：未 strip 的产物 123656 字节，strip 后约 29KB（实测裸命令行 -s
    # 构建为 29504 字节）。APK 里每多一个字节都要占用下载体积，这里省下近 95KB。
    -Wl,-s)
