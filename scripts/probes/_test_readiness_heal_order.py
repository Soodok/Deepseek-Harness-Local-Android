#!/usr/bin/env python3
"""`readiness()` 里 SELinux 自愈必须排在所有静态文件检查之前的探针。

## 它防的是什么

2026-11-10 实测踩到的真 bug：

- rootfs 里有文件被 `su` 写入后 SELinux 上下文漂移成裸
  `u:object_r:app_data_file:s0`；
- App 进程的域是 `u:r:untrusted_app_27:s0:c217,c256,c512,c768`；
- **跨 MCS 类别读取被内核静默拒绝** —— 不是 EACCES 报错，而是
  `File.isFile()` 直接返回 false；
- 于是 `readiness()` 里 `gateDeps`（`bin/bash` / `usr/bin/curl`）判成"缺基础包"，
  直接 `return Readiness.NeedsPackages`，**永远走不到后面的自愈逻辑**；
- 用户看到「The Linux environment is missing base packages (bash / curl).
  Tap Repair above」，点了修复也修不好 —— 修复走的是同一段判据。

文件明明在磁盘上、`ls -la` 一切正常、属主全对，只有 App 视角看不到。

## 判据

顺序关系是唯一能表达"自愈必须先跑"的形态：
`SelinuxContext.healTree` 的调用位置必须早于 `gateDeps.firstOrNull` /
`nodeRel` 的静态存在性判断。

只断言「代码里有 healTree」是无效的 —— 它本来就有，只是位置错了。
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
LAUNCHER = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                        "engine", "LinuxEngineLauncher.kt")

fails = []
def check(name, ok, hint=""):
    if not ok:
        fails.append(f"[{name}] {hint}")

with open(LAUNCHER, encoding="utf-8") as f:
    src = f.read()


def body_of(fn_sig, text):
    """按大括号配平取一个函数体。"""
    m = re.search(fn_sig, text)
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


body = body_of(r"fun readiness\s*\(", src)
check("A1", body != "", "readiness() 函数体没找到 —— 被改名或重构了？")

# ---- 三个关键位置的相对顺序 ----
i_heal = body.find("SelinuxContext.healTree")
i_sentinel = body.find("SelinuxContext.firstDrifted")
i_gate = body.find("gateDeps.firstOrNull")            # gateDeps 的实际判据行
i_gate_alt = body.find("val missing = gateDeps")
i_node = body.find("val nodeRel")

check("A2", i_heal >= 0, "readiness() 里没有 SelinuxContext.healTree 调用")
check("A3", i_sentinel >= 0, "readiness() 里没有 firstDrifted 探测 —— 会变成无条件全量 chcon")

gate_pos = min([p for p in (i_gate, i_gate_alt) if p >= 0], default=-1)
check("A4", gate_pos >= 0, "没找到 gateDeps 的判据（bin/bash、usr/bin/curl 那组）")

# ★核心断言：自愈位置必须早于两个静态检查
if i_heal >= 0 and gate_pos >= 0:
    check("B1", i_heal < gate_pos,
          f"healTree(pos={i_heal}) 排在 gateDeps(pos={gate_pos}) 之后 —— "
          "漂移时先判「缺 bash/curl」直接 return，自愈永远不执行")
if i_heal >= 0 and i_node >= 0:
    check("B2", i_heal < i_node,
          f"healTree(pos={i_heal}) 排在 node 检查(pos={i_node}) 之后 —— "
          "node 若漂移会先被判成缺 node")

# ---- gateDeps 必须在 node 之后（既有语义，别被这次搬动带偏）----
if gate_pos >= 0 and i_node >= 0:
    check("B3", i_node < gate_pos,
          "node 检查应仍在 gateDeps 之前（node 是硬前提，报错更具体）")

# ---- 自愈还必须在 installCompatShims 之后：shim 自己也往 rootfs 写文件 ----
i_shims = body.find("installCompatShimsPublic")
check("C3", i_shims >= 0, "readiness() 里找不到 installCompatShimsPublic")
if i_shims >= 0 and i_heal >= 0:
    check("C4", i_shims < i_heal,
          f"healTree(pos={i_heal}) 排在 shim(pos={i_shims}) 之前 —— "
          "shim 会往 rootfs 写文件，先自愈再写等于刚修好又被写脏")

# ---- 变量声明顺序：rootfs 必须在自愈之前就有 ----
i_option = body.find("val option = env.activeOption()")
i_rootfs = body.find("val rootfs = env.rootfsDir(option)")
check("C1", i_option >= 0 and i_rootfs >= 0,
      "readiness() 里找不到 option / rootfs 的声明")
if i_heal >= 0 and i_rootfs >= 0:
    check("C2", i_rootfs < i_heal,
          f"rootfs 声明(pos={i_rootfs}) 在 healTree(pos={i_heal}) 之后 —— 编译不过")

# ---- 自愈不能挡启动（缺 su 的普通模式下本来就改不了标签）----
check("D1", "onFailure {" in body,
      "自愈没有 onFailure 兜底 —— 无 su 设备上抛异常会把整个 readiness 弄挂")
check("D2", "selinux self-heal skipped" in body,
      "自愈失败没有留日志，现场无从判断「为什么还是 Permission denied」")

# ---- D3/D4: 探测到却修不了时必须显式报错，不能静默继续 ----
# 这是 2026-11-10 实测暴露的第二层缺陷：su 在 `/system/xbin/su` 但该目录
# `drwxr-x--- root shell`，App 进不去 → runPrivileged 返回 null → healTree
# 失败 → 旧代码什么都不说，继续用 File.isFile 判 bash（被 MCS 静默拒绝）
# → 报「缺基础包」，把用户引向错误方向。
check("D3", re.search(r"if\s*\(\s*!healed\s*\)", body) is not None,
      "healTree 失败后没有分支处理 —— 漂移会一路静默走到「缺基础包」的误报")
check("D4", "linux_err_selinux_drift" in body,
      "漂移无法修复时没有给出专门文案（必须说明是标签漂移 + 无特权通道）")
if "linux_err_selinux_drift" in body:
    check("D5", re.search(r"linux_err_selinux_drift\s*,\s*drifted", body) is not None,
          "文案没带上漂移路径（%1$s），用户无从判断是哪个文件")

# ---- 反向自检：断言真的能区分顺序 ----
# 造两个假样本，一个顺序对、一个顺序错，验证判据本身有判别力
good = "A=SelinuxContext.firstDrifted B=SelinuxContext.healTree C=val missing = gateDeps"
bad = "A=val missing = gateDeps B=SelinuxContext.healTree C=SelinuxContext.firstDrifted"
gi = good.find("healTree")
bg = bad.find("val missing = gateDeps")
check("E1", gi >= 0 and gi < good.find("gateDeps") and bad.find("healTree") > bg,
      "判据在自造样本上失效 —— 它没有真正比较位置")


# ---- 探测不得依赖特权通道（2026-11-10 修）----
SELINUX = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                       "engine", "SelinuxContext.kt")
with open(SELINUX, encoding="utf-8") as f:
    sel = f.read()

sel_body = body_of(r"fun firstDrifted\s*\(", sel)
check("F1", sel_body != "", "firstDrifted 没找到")

# 关键：探测必须有一条不依赖 runPrivileged 的退路
check("F2", "readContexts" in sel_body,
      "firstDrifted 没有经过 readContexts —— 探测可能仍然只走特权通道")
rc_body = body_of(r"private fun readContexts\s*\(", sel)
check("F3", rc_body != "", "readContexts 没找到")
check("F4", "runPrivileged" in rc_body,
      "readContexts 完全不走 runPrivileged —— 有 Shizuku 时读别人的文件会失败")
# 退路必须真的存在：ProcessBuilder 或 等价直接 fork
check("F5", "ProcessBuilder" in rc_body or "Runtime.getRuntime" in rc_body,
      "readContexts 没有非特权的退路 —— 无 Shizuku/su 的设备上探测恒返回 null，"
      "「检测不到」会被当成「没漂移」")
# 反向自检：证明判据能区分「只有特权路径」的实现
only_priv = 'Privilege.runPrivileged(ctx, cmd) ?: return null'
check("F6", ("ProcessBuilder" in only_priv) is False,
      "判据失效：纯特权实现被判成有退路")



# ---- 漂移路径必须 trim（ls -Zd 会为列对齐补空格）----
# 实测事故：报错文案里出现「标签已漂移（                    /data/user/0/.../bin/bash）」
# ★必须锚在「取路径那一行」上：sel_body 里还有 line.trim() 与 out.trim()，
#   只查 `".trim()" in sel_body` 会让「去掉路径 trim」仍然全绿（实测踩过）。
_PATH_PICK_RE = re.compile(r"val\s+path\s*=\s*t\.substringAfter\([^)]*\)\s*\.trim\(\)")
check("G1", _PATH_PICK_RE.search(sel_body) is not None,
      "firstDrifted 取出的路径没有 trim —— ls -Zd 的列对齐空格会原样进用户可见文案")
# 反向自检：证明判据能区分「没 trim」的写法
no_trim = 'val path = t.substringAfter(' ', "").ifEmpty { null }'
check("G2", _PATH_PICK_RE.search(no_trim) is None,
      "判据失效：无 trim 的写法被判成有 trim")


print("readiness order checks done")
if fails:
    print("FAILED (%d):" % len(fails))
    for f in fails:
        print("  " + f)
    sys.exit(1)
print("OK")
