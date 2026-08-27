# dsh-android PTY 原生层
cmake_minimum_required(VERSION 3.22.1)
project(dshpty C)

add_library(dshpty SHARED dsh_pty.c)

# 16KB 页对齐：Android 15+ 部分 aarch64 真机内核使用 16384 字节页，
# LOAD p_align=4096 的 so 在其上 dlopen 会报 "program alignment" 失败。
# 不依赖 NDK r27+ 的默认行为，对所有 ndkVersion 显式生效。
target_link_options(dshpty PRIVATE
    "-Wl,-z,max-page-size=16384")

target_link_libraries(dshpty
    # 仅依赖 log，无 STL —— 纯 C11 实现，规避 NDK STL 兼容问题
    log)
