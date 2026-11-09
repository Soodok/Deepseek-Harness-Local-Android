#!/usr/bin/env python3
"""扩展中心 AI 通道的模式感知探针。

守的是 2026-11-09 实际存在过的两个缺陷 —— 都不是编译错误，也不会崩，
只会让 AI 做出与事实相反的操作：

  A. `/ext/list` 只返回 state，不告诉 AI 当前是 native 还是 linux 模式，
     也不告诉它某条目能不能装。而 state 在两种模式下来自**完全不同的
     数据源**（Termux marker 文件 vs 容器包数据库），于是 Linux 模式下
     装好的包会被报成 red，AI 据此让用户重装。
  B. `/ext/install` 完全不看模式，一律走 Termux 路径。用户在 Linux 模式
     下让 AI 装东西，linuxOnly 条目会被守卫挡下并提示「请切到 Linux 模式」
     —— 而用户本来就在。

判据只认源码里的实际接线（有没有按 mode 分派、字段有没有真的 put 出去），
不锁具体措辞 —— 文案会改，接线不该悄悄消失。
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BRIDGE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "AgentBridge.kt")
SEED = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                    "engine", "AgentContextSeed.kt")
CATALOG = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                       "engine", "EndpointCatalog.kt")
MGR = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                   "engine", "ExtensionManager.kt")

fails = []
def check(name, ok, hint=""):
    if not ok:
        fails.append(f"[{name}] {hint}")

def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()

src = read(BRIDGE)
seed = read(SEED)
cat = read(CATALOG)
mgr = read(MGR)


def body_of(name, text):
    """取一个 private fun <name>( 的函数体（按大括号配平，够用）。"""
    m = re.search(r"private fun %s\s*\(" % re.escape(name), text)
    if not m:
        return ""
    i = text.find("{", m.end())
    if i < 0:
        return ""
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[i + 1:j]
    return ""


ext_list = body_of("extList", src)
ext_install = body_of("extInstall", src)

# ---- A. /ext/list 必须暴露模式与可用性 ----
check("A1", ext_list != "", "extList body not found — 函数被改名或删掉了？")
check("A2", "EngineModeSelector.isLinuxEnabled" in ext_list,
      "extList 没有判断当前模式：AI 无法知道 state 来自哪个数据源")
check("A3", "LinuxExtensionBridge.state(" in ext_list,
      "extList 在 Linux 模式下仍读 Termux marker —— 容器里装好的包会被报成 red")
for field in ("available", "mode", "linuxOnly", "rootOnly"):
    check("A4.%s" % field, 'put("%s"' % field in ext_list,
          "extList 没有输出 %s 字段，AI 看不到这一维" % field)
check("A5", "unavailableReason" in ext_list,
      "extList 没有给出不可装的原因（note），AI 只能盲试")
# Linux 模式下取一次全表，而不是每条现查（每条都 fork 一次 apk info）
check("A6", "installed()" in ext_list and ext_list.count("installed()") <= 2,
      "extList 应该批量取一次已装表，而不是逐条查询")
# 响应必须是数组：AgentContextSeed 与既有调用方都按 [{...}] 解析
check("A7", "200 to arr.toString()" in ext_list,
      "extList 响应结构被改成了对象 —— 会破坏 seed 里文档化的 [{...}] 契约")

# ---- B. /ext/install 必须按模式分派 ----
check("B1", ext_install != "", "extInstall body not found")
check("B2", "EngineModeSelector.isLinuxEnabled" in ext_install,
      "extInstall 不看模式，Linux 模式下会走 Termux 路径")
check("B3", "LinuxExtensionBridge.install(" in ext_install,
      "extInstall 在 Linux 模式下没有走容器装包")
check("B4", "unavailableReason" in ext_install,
      "extInstall 在 Linux 模式下不检查条目可用性，会装一个空闭包")
# 分派必须在「已激活」短路之前 —— 否则 Linux 模式下永远走到 Termux 的短路判断
i_mode = ext_install.find("EngineModeSelector.isLinuxEnabled")
i_short = ext_install.find("ExtState.ACTIVATED")
check("B5", i_mode >= 0 and (i_short < 0 or i_mode < i_short),
      "模式分派被放在了「已激活」短路之后，Linux 模式下可能先命中 Termux 分支")
# APK 型扩展不该被塞进容器（它装的是 Android 应用）
check("B6", "apkUrl.isBlank()" in ext_install or "apkUrl.isNotBlank()" in ext_install,
      "extInstall 没排除 APK 型扩展 —— 它们与 Linux 模式无关")

# ---- B7/B8: 分派条件必须真的用模式变量，不能写成常量断点 ----
# 只断言「函数体里出现过 isLinuxEnabled / LinuxExtensionBridge.install」是不够的：
# 把条件改成 `if (false)` 后，这些字符串都还在，探针会假绿（实测踩到过）。
check("B7", re.search(r"if\s*\(\s*linuxMode\s*&&", ext_install) is not None,
      "Linux 分派条件没真的用 linuxMode —— 写成常量就能让整段变成死代码")
check("B8", re.search(r"if\s*\(\s*linuxMode\s*\)", ext_list) is not None,
      "extList 的状态分支没真的用 linuxMode —写成常量就永远读 Termux marker")
check("B9", re.search(r"val linuxMode = EngineModeSelector\.isLinuxEnabled\(ctx\)", ext_install) is not None,
      "extInstall 的 linuxMode 不是取自 EngineModeSelector")

# ---- C. 文档与 seed 必须同步（否则 AI 仍按旧契约理解）----
check("C1", "available" in seed and "linuxOnly" in seed,
      "AgentContextSeed 没写新增字段，AI 的系统提示里仍是旧契约")
check("C2", re.search(r'SEED_VERSION\s*=\s*(\d+)', seed).group(1) != "",
      "SEED_VERSION 缺失")
check("C3", "available=false" in cat or "available" in cat,
      "EndpointCatalog 的 ext/list 条目没说明 available 字段")
check("C4", "mode" in cat and "native" in cat,
      "EndpointCatalog 没说明 mode 语义（两种模式数据源不同）")

# ---- D. 反向自检：不能是「无脑加字段」而为空实现 ----
# available 必须由真实原因驱动，不能写死 true
check("D1", re.search(r'"available",\s*note\s*==\s*null', ext_list) is not None,
      "available 没有绑定到真实原因（必须是 note == null 这类判据，不能写死 true）")
# note 的来源必须真的问过 bridge 或 linuxOnly
check("D2", "linuxOnly" in ext_list and ("unavailableReason" in ext_list),
      "note 没有从 linuxOnly / unavailableReason 推导")
# Linux 分支必须真的 spawn（而不是只返回 202 什么都不做）
check("D3", "Thread({" in ext_install and "isDaemon = true" in ext_install,
      "Linux 分支没有真正启动后台线程，202 是空承诺")
# 失败必须通知，而且必须**在 Linux 分支里**发。
# 只断言「整个函数体里有这个字符串」是假绿：原生分支也有同一句，
# 实测把 Linux 分支的那句删掉后探针照样全绿。
_i_linux = ext_install.find("ext-install-linux-")
_linux_seg = ext_install[: _i_linux] if _i_linux > 0 else ""
_i_thread = _linux_seg.rfind("Thread({")
check("D4", _i_thread >= 0 and "notif_ext_failed_title" in _linux_seg[_i_thread:],
      "Linux 分支失败时不发通知，AI 轮询 /ext/list 只能看到一直是 red")

# ---- E. ExtensionManager 的 linuxOnly 守卫仍在（本改动不该把它删掉）----
check("E1", "if (ext.linuxOnly)" in mgr,
      "ExtensionManager 里 Termux 路径的 linuxOnly 守卫被删了 —— AI 原生模式会装出半截")

print("ext ai/linux mode checks done")
if fails:
    print("FAILED (%d):" % len(fails))
    for f in fails:
        print("  " + f)
    sys.exit(1)
print("OK")
