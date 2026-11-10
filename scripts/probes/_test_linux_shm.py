#!/usr/bin/env python3
"""_test_linux_shm.py —— POSIX 共享内存目录（/dev/shm）的静态守护

为什么需要这个探针：
  Android 宿主**没有** /dev/shm，而容器把 /dev 整棵透传给宿主。
  于是容器内所有走 POSIX 共享内存的程序都会失败：
    - musl 的 shm_open() 实现是 open("/dev/shm/<name>") → ENOENT
    - wlroots headless 后端报 Failed to allocate buffer，
      output 建得出但分配不到帧缓冲，grim 截图全空
    - dbus / PulseAudio / Chromium 同样依赖
  修复分两半，缺一不可：
    ① C 侧：is_shm_path() 把 /dev/shm 排除出 /dev 透传
    ② Kotlin 侧：部署 rootfs 时建 <rootfs>/dev/shm
  只改一半都不行 —— ①没做则仍指宿主，②没做则目录不存在。
"""
import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
fails = 0


def ck(name, ok, hint=""):
    global fails
    if ok:
        print(f"  \u2713 {name}")
    else:
        fails += 1
        print(f"  \u2717 {name}" + (f" — {hint}" if hint else ""))


def strip_c_comments(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//[^\n]*", "", src)
    return src


C_SRC = os.path.join(REPO, "app", "src", "main", "cpp", "dsh_alpine.c")
KT_SRC = os.path.join(REPO, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "LinuxEnvironmentManager.kt")

c = strip_c_comments(io.open(C_SRC, encoding="utf-8").read())
kt = io.open(KT_SRC, encoding="utf-8").read()

print("[A] C 侧：/dev/shm 必须排除出 /dev 透传")
ck("A1 定义了 is_shm_path",
   re.search(r"static\s+int\s+is_shm_path\s*\(", c) is not None)
ck("A2 is_shm_path 匹配 /dev/shm 字面量",
   re.search(r'"\/dev\/shm"', c) is not None)
# 关键断言：is_host_passthrough 里必须先判 shm
m = re.search(r"static\s+int\s+is_host_passthrough\s*\([^)]*\)\s*\{.{0,600}\}",
              c, re.S)
ck("A3 is_host_passthrough 存在", m is not None)
if m:
    body = m.group(0)
    ck("A4 is_host_passthrough 里先判 is_shm_path",
       re.search(r"if\s*\(\s*is_shm_path\s*\(\s*p\s*\)\s*\)\s*return\s+0", body) is not None,
       "不先判就会继续走 /dev/ 前缀，落到宿主")
    # 顺序断言：is_shm_path 判定必须在 "/dev/" 出现之前
    i_shm = body.find("is_shm_path")
    i_dev = body.find('"/dev/"')
    ck("A5 is_shm_path 判定在 /dev/ 前缀之前",
       i_shm != -1 and i_dev != -1 and i_shm < i_dev,
       f"i_shm={i_shm} i_dev={i_dev}")
ck("A6 /dev/ 前缀仍然保留（GPU/binder/ashmem 靠它透传）",
   re.search(r'"\/dev\/"', c) is not None)

print("[B] Kotlin 侧：部署 rootfs 时必须建 <rootfs>/dev/shm")
ck("B1 定义了 ensureShmDir",
   re.search(r"private\s+fun\s+ensureShmDir\s*\(", kt) is not None)
ck("B2 部署流程里调用了 ensureShmDir",
   re.search(r"ensureShmDir\s*\(\s*root\s*\)", kt) is not None)
ck("B3 ensureShmDir 拼的是 dev/shm 路径",
   re.search(r'File\s*\(\s*root\s*,\s*"dev/shm"\s*\)', kt) is not None)
ck("B4 ensureShmDir 幂等（先 isDirectory 判断）",
   re.search(r"if\s*\(\s*!\s*shm\.isDirectory\s*\)", kt) is not None)
ck("B5 ensureShmDir 设置三权限（readable/writable/executable）",
   kt.count("shm.setReadable") >= 1 and kt.count("shm.setWritable") >= 1
   and kt.count("shm.setExecutable") >= 1)
ck("B6 调试点在 installLinuxCompatShims 之后",
   kt.find("installLinuxCompatShims(root)") < kt.find("ensureShmDir(root)"),
   "必须在解包与 shim 之后，否则目录可能被后续步骤覆盖")

print("[C] 反向断言：不要用「在宿主建 /dev/shm」绕过")
ck("C1 Kotlin 里没有写 /dev/shm 的宿主绝对路径",
   re.search(r'"/dev/shm"', kt) is None,
   "宿主播 tmpfs 需要 root 且设备重启即失，必须在 rootfs 内建")

print()
if fails == 0:
    print("All linux shm checks passed.")
    sys.exit(0)
print(f"{fails} linux shm check(s) FAILED.")
sys.exit(1)
