#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守「宿主 PYTHONHOME / PYTHONPATH 不得泄漏进 Linux 容器」（issue #134）。

## 为什么这条必须单独一个探针

宿主引擎的 `EngineConfig.buildEnv` 为扩展中心的 python 扩展设了
`PYTHONHOME=<engineRoot>/extensions/python`（Termux 编译的 3.14，编译期 prefix
硬编码成 Termux 路径，必须显式指）。这个变量会随进程环境继承到容器里，而容器内
`/usr/bin/python3` 是**发行版自己的** 3.12/3.13 —— 读到这个 PYTHONHOME 就去扩展
目录找 `encodings`，找不到即：

    ModuleNotFoundError: No module named 'encodings'
    Fatal Python error: init_fs_encoding: failed to get the Python codec

于是**所有走 import 的标准库全废**（asyncio/json/ctypes/zipfile/shutil/tarfile/
http…）。而 `python3 -V` 仍然正常打印版本（-V 不加载 encodings），所以常规冒烟
测试永远抓不到 —— 这正是它能在真机上活到 #134 的原因。

后果链条完整走一遍：dpkg 的 postinst（python3-minimal 里 `python3 -m compileall`）
一次 import 失败就以非 0 退出 → `dpkg --configure -a` 报 post-installation script
error → 整批包停在 `iU` → **每轮都撞同一堵墙**，所以「多跑几轮就收敛」不发生。

## 两条入口都必须覆盖

1. `LinuxContainerRunner.doExec()` —— `ProcessBuilder` 继承 **App 进程** 环境；
   产品链路（`/dev/linux/exec`、`/ext/install` 走 apt）全在这一条。
2. `dsh_alpine.c` 的 `env_drop_host()` —— loader 侧，覆盖引擎自己与 Web 终端。

漏任一条就漏一半场景，所以这里两条都断言。

## 判据必须是「按值」，不是「按名字」

第一版实现按名字无条件摘，实测把用户**在容器内自己**
`export PYTHONHOME=/tmp/mine` 也吃掉了（内层 sh 拿到空值）—— 那是合法用法。
真正要丢的那一份值必然以宿主前缀开头（`activeRoots` 返回的是宿主 File，
即 `/data/user/0/…`），所以两侧都用「值像不像 Android 宿主路径」判断。

跑法：python scripts/probes/_test_linux_python_env_leak.py
"""

import io
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RUNNER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxContainerRunner.kt")
LOADER = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")

fails = []


def chk(cond, msg):
    if not cond:
        fails.append(msg)
        print("FAIL  " + msg)
    else:
        print("ok    " + msg)


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_line_comments(s):
    """剥 Kotlin 的 // 行注释。URL 里的 https:// 要放过（前一个字符是 :）。"""
    out = []
    for ln in s.split(chr(10)):
        i = ln.find("//")
        while i >= 0 and i > 0 and ln[i - 1] == ":":
            i = ln.find("//", i + 2)
        out.append(ln if i < 0 else ln[:i])
    return chr(10).join(out)


def strip_c_comments(s):
    """剥 C 的 /* */ 与 // 注释（块注释要整段丢，含换行）。"""
    s = re.sub(r"/\*[^*]*\*+(?:[^/*][^*]*\*+)*/", "", s, flags=re.S)
    out = []
    for ln in s.split(chr(10)):
        i = ln.find("//")
        out.append(ln if i < 0 else ln[:i])
    return chr(10).join(out)


runner = read(RUNNER)
runner_nc = strip_line_comments(runner)
loader = read(LOADER)
loader_nc = strip_c_comments(loader)

print("[A] LinuxContainerRunner 侧（产品链路 / ProcessBuilder）")

# A1：真的处理了这两个变量（不是完全没提）
chk("PYTHONHOME" in runner_nc, "A1  runner 里出现了 PYTHONHOME（说明真的处理了）")
chk("PYTHONPATH" in runner_nc, "A1b runner 里出现了 PYTHONPATH")

# A2：无条件 remove 是错的写法 —— 既误伤容器内 export，也说明没真正想清楚判据
chk('e.remove("PYTHONHOME")' not in runner_nc,
    "A2  runner 不写无条件 `e.remove(\"PYTHONHOME\")`（会误伤容器内自己 export 的值）")
chk('e.remove("PYTHONPATH")' not in runner_nc,
    "A2b runner 不写无条件 `e.remove(\"PYTHONPATH\")`")

# A3：必须按值判定
i_home = runner_nc.find('listOf("PYTHONHOME", "PYTHONPATH")')
chk(i_home >= 0, "A3  runner 用 listOf(\"PYTHONHOME\", \"PYTHONPATH\") 逐项处理")
if i_home >= 0:
    seg = runner_nc[i_home:i_home + 400]
    chk("looksLikeAndroidHostPath" in seg,
        "A3b 该处理块调用 looksLikeAndroidHostPath（按值判定）")
    chk("e.remove(k)" in seg, "A3c 命中时按变量名 remove")

# A4：辅助函数本体存在且判据是宿主前缀
i_fn = runner_nc.find("private fun looksLikeAndroidHostPath")
chk(i_fn >= 0, "A4  looksLikeAndroidHostPath 定义存在")
if i_fn >= 0:
    body = runner_nc[i_fn:i_fn + 900]
    chk('"/data/"' in body, "A4b 判据含 /data/ 前缀（宿主 python 扩展的真实值形状）")
    chk('"/system/"' in body or '"/apex/"' in body,
        "A4c 判据还含其它 Android 宿主前缀（/system/ 或 /apex/）")
    chk("startsWith" in body, "A4d 用 startsWith 判前缀，不是 contains")

print()
print("[B] dsh_alpine.c 侧（loader / 引擎与终端）")

# B1：env_drop_host 里必须把这两个名字并进「按值」那组，而不是 bad 名单
i_drop = loader_nc.find("static int env_drop_host(const char *e)")
chk(i_drop >= 0, "B1  找到 env_drop_host")
if i_drop >= 0:
    body = loader_nc[i_drop:i_drop + 1800]
    chk('"PYTHONHOME"' in body, "B1b env_drop_host 里出现 PYTHONHOME")
    chk('"PYTHONPATH"' in body, "B1c env_drop_host 里出现 PYTHONPATH")
    # 在 ld[] 数组里（按值分支），不是在 bad[] 里
    i_ld = body.find('static const char *ld[]')
    if i_ld >= 0:
        ld_seg = body[i_ld:i_ld + 400]
        chk('"PYTHONHOME"' in ld_seg and '"PYTHONPATH"' in ld_seg,
            "B1d 两个名字都在 ld[] 数组里（走按值判定分支）")
        chk("looks_like_host_path" in ld_seg,
            "B1e 该分支按 looks_like_host_path 判定")
    i_bad = body.find('static const char *bad[]')
    if i_bad >= 0:
        bad_seg = body[i_bad:i_bad + 900]
        chk('"PYTHONHOME"' not in bad_seg,
            "B1f PYTHONHOME 不在 bad[] 名单里（那会变成无条件丢弃）")

# B2：looks_like_host_path 的判据含 /data/
chk('"/data/"' in loader_nc, "B2  looks_like_host_path 判据含 /data/（宿主扩展根的真实形状）")

# B3：调用点必须存在（否则函数写了也不生效）
chk(loader_nc.count("env_drop_host(") >= 3,
    "B3  env_drop_host 至少 3 处出现（定义 + 两个 exec 调用点）")

print()
print("[C] 宿主设置侧仍在（不许为了修泄漏而把扩展的 PYTHONHOME 删掉）")

ENGINE_CFG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
cfg = read(ENGINE_CFG)
cfg_nc = strip_line_comments(cfg)
chk("PYTHONHOME=" in cfg_nc,
    "C1  EngineConfig 仍给引擎子进程设 PYTHONHOME=<扩展根>（扩展的 python 靠它定位 stdlib）")
chk('firstOrNull { it.name == "python" }' in cfg_nc,
    "C1b 仍按扩展 id=python 精确匹配（不是按目录里有没有 os.py 猜 —— 那是历史事故）")

print()
if fails:
    print("FAILED (%d)" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("All python-env-leak checks passed.")

# ==================== 变异验证记录（防退化） ====================
# 下列变异逐个施加后应报红，恢复后应复绿：
#   M1  runner 改成无条件 e.remove("PYTHONHOME")          → A2 / A3b 红
#   M2  runner 的 looksLikeAndroidHostPath 改成 contains   → A4d 红
#   M3  loader 把 PYTHONHOME 移进 bad[] 名单               → B1d / B1f 红
#   M4  loader 的 env_drop_host 整段删掉                   → B1 红
#   M5  EngineConfig 删掉 PYTHONHOME=<扩展根>              → C1 红
#
# 真机实测对照（模拟器 x86_64 / 全新 ubuntu rootfs / python3 已装好）：
#   旧 loader + PYTHONHOME=/data/user/0/…/extensions/python
#       → Python path configuration … Fatal Python error … No module named 'encodings'
#   新 loader + 同样输入 → `python3 -c "import json"` 正常
#   新 loader + PYTHONHOME=/tmp/mine（容器内自己 export）→ 变量保留（不误伤）
