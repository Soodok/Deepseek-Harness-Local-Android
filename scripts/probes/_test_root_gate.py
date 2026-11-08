#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""契约锁定：`root` 系统级门脚本（app/src/main/assets/gates/root）。

这个脚本是本项目权限最大的一档能力（uid 0 任意命令）。所以探针的重心
不是「命令能不能跑」，而是**四条安全边界是否真的在代码里**：

  1. 危险操作默认 dry-run（rm / exec / prop set / pm uninstall）
  2. 关键进程拒绝被 kill（init / zygote / system_server / …）
  3. rm 进 quarantine 而不是真删，且能用 undo 恢复
  4. 绝不改 SELinux 策略（不出现 setenforce / supolicy）

任何一条被改坏都意味着「AI 拿到了比设计更大的权力」，探针必须立刻变红。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.dirname(ROOT)
GATE = os.path.join(REPO, "app", "src", "main", "assets", "gates", "root")
CONFIG = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt"
)
SEED = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentContextSeed.kt"
)
CATALOG = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EndpointCatalog.kt"
)
GATEKEEPER = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "CapabilityGate.kt"
)

checks = []
failed = []


def check(name, cond, detail=""):
    checks.append(name)
    if not cond:
        failed.append((name, detail))


gate = io.open(GATE, encoding="utf-8").read()

# 只看可执行行：注释里记录「绝不碰 X」是约束说明，不是实现。
# 曾经两次踩到「注释命中导致断言永远为真」，所以凡是禁/必需类断言
# 一律基于 code_only，不用全文。
code_only = "\n".join(
    ln for ln in gate.split("\n") if not ln.lstrip().startswith("#")
)

# ── 1. 脚本自身形态 ────────────────────────────────────────────────
check("gate exists", os.path.isfile(GATE))
check("bootstrap re-exec to engine bash", "@DSH_BASH@" in gate)
check("re-exec guard is idempotent", "_DSH_REEXEC" in gate)
check(
    "bootstrap substitutes libdir",
    "@DSH_LIBDIR@" in gate,
    "门脚本需要 LD_LIBRARY_PATH 指向引擎 lib",
)
check("has shebang", gate.startswith("#!/system/bin/sh"))

# ── 2. 必须 root 才执行 ────────────────────────────────────────────
check(
    "need_root checks uid",
    re.search(r'need_root\(\)\s*\{[^}]*id -u', gate, re.S) is not None,
    "need_root 必须实际读 id -u 判断，不能只看环境变量",
)
check(
    "need_root exits non-zero",
    "exit 77" in gate,
    "非 root 时必须退出码非 0，不能静默继续",
)
# 每个需要 root 的子命令都要调 need_root（info 除外也不算例外，它也要报身份）
# 分支写法有两种：独占行（`  ps)`）与联合行（`  cp|mv)`）。
# 用分支体而不是分支标题做判定 —— 只看标题会漏掉 cp|mv 这种合并写法。
root_cmds = [
    "ps", "kill", "cat", "ls", "rm", "undo", "cp", "mv", "chmod", "chown",
    "prop", "svc", "pm", "settings", "net", "mount", "journal", "exec",
]
for cmd in root_cmds:
    heads = [
        m for m in re.finditer(r"^  (\S[^)\n]*)\)\n", gate, re.M)
        if cmd in [t.strip() for t in m.group(1).split("|")]
    ]
    ok = False
    for m in heads:
        body_end = gate.find("\n    ;;", m.end())
        body = gate[m.end():body_end] if body_end > 0 else ""
        if "need_root" in body:
            ok = True
            break
    check(f"{cmd} requires root", ok, "该子命令分支里没有调用 need_root")

# ── 3. 危险操作默认 dry-run ─────────────────────────────────────────
check(
    "rm defaults to dry-run",
    re.search(r'if \[ -z "\$confirm" \]; then\s*\n\s*printf .*dry_run.*would_remove', gate) is not None,
    "rm 不带 confirm=1 时必须只回报 would_remove",
)
check(
    "exec defaults to dry-run",
    re.search(r'if \[ -z "\$confirm" \]; then\s*\n\s*printf .*dry_run.*would_run', gate) is not None,
    "exec 不带 confirm=1 时必须只回报 would_run",
)
check(
    "prop set defaults to dry-run",
    re.search(r'prop set.*?if \[ -z "\$confirm" \]', gate, re.S) is not None
    or "would_set" in gate,
    "prop set 必须支持 dry-run",
)
check(
    "pm uninstall defaults to dry-run",
    "would_uninstall" in gate,
    "pm uninstall 必须支持 dry-run",
)
check(
    "dry-run does not execute the side effect",
    gate.count('exit 0') >= 3,
    "dry-run 分支必须以 exit 0 提前返回",
)

# ── 4. 关键进程保护 ────────────────────────────────────────────────
protected = [
    "init", "zygote", "zygote64", "system_server",
    "surfaceflinger", "servicemanager", "logd", "vold", "netd",
]
for p in protected:
    check(
        f"kill protects {p}",
        re.search(r"protected_comm\(\)\s*\{.*\b" + re.escape(p) + r"\b", gate, re.S) is not None,
        f"{p} 必须在 protected_comm 白名单里",
    )
check(
    "kill refuses pid 1",
    re.search(r'\[ "\$pid" = "1" \]', gate) is not None,
)
check(
    "kill refuses itself",
    re.search(r'\[ "\$pid" = "\$SELF" \]', gate) is not None,
)
check(
    "kill reports skipped with reason",
    "reason" in gate and "protected" in gate,
    "被拒的进程要明确回报，不能静默略过",
)

# ── 5. rm 进 quarantine，可 undo ───────────────────────────────────
check(
    "rm moves to quarantine dir",
    "dsh-root-trash" in code_only,
    "rm 必须是移动而不是 unlink（注释里提到不算）",
)
check(
    "rm never calls unlink on the target",
    not re.search(r"\brm\s+-rf?\s+\"\$p\"|\bunlink ", gate),
    "rm 子命令内部不能再调 rm/unlink 真删目标",
)
check(
    "undo restores from quarantine",
    re.search(r"undo\)\s*\n.*?trash=.*?mv ", gate, re.S) is not None,
    "undo 必须把文件搬回原位",
)
check(
    "rm response carries undo hint",
    "undo_hint" in gate,
    "rm 的返回里要给出 undo 命令，否则 AI 不知道怎么恢复",
)
check(
    "rm keeps paths in an array (no word splitting)",
    'paths+=("$a")' in gate and 'for p in "${paths[@]}"' in gate,
    "路径必须存进数组并用 \"${paths[@]}\" 引号展开，否则带空格的路径会被拆成两段",
)
check(
    "rm no longer rebuilds a space-joined path string",
    'paths="$paths $a"' not in gate and 'paths="${paths# }"' not in gate,
    "旧的「拼串 + 去前导空格」写法会吞掉带空格的路径，已由数组取代，不能回退",
)
check(
    "undo restores via quarantine manifest",
    'manifest="$trash/.manifest"' in gate and "while IFS=$'\\t' read -r orig src" in gate,
    "undo 必须按 manifest 的原始路径还原；从隔离区文件名反推会把含下划线的名字还原到错误目录",
)

# ── 6. 绝不改 SELinux 策略 ─────────────────────────────────────────
for forbidden in ["setenforce", "supolicy", "magiskpolicy", "sepolicy-inject"]:
    check(
        f"never calls {forbidden}",
        forbidden not in code_only,
        f"{forbidden} 会削弱整机安全策略，可执行代码里不允许出现",
    )
check(
    "reports SELinux state read-only",
    "getenforce" in gate,
    "只读取状态用于展示",
)

# ── 7. JSON 单行输出（AI 要能直接解析） ─────────────────────────────
check(
    "json_escape helper exists",
    re.search(r"json_escape\(\)\s*\{", gate) is not None,
)
check(
    "outputs are single-line JSON",
    gate.count("printf '{\"ok\"") >= 10,
    "每个子命令都应打印一行 JSON",
)
check(
    "errors also emit JSON",
    '"ok":false' in code_only,
    "失败路径也要给 JSON，不能只写 stderr 文本",
)
# 面向 AI 的 stdout 必须是 JSON 对象字面量（`{"ok"...`）。
# 只认「echo 后面紧跟的字面量」—— `echo "$var"` 与 `|| echo "?"` 这类
# 是内部取值/兜底，不产生输出，不能算违规（第一版这么判过，全是误报）。
bare_echo = []
for ln in code_only.split("\n"):
    st = ln.strip()
    m = re.match(r'echo\s+([\'"])(.*)', st)
    if not m:
        continue
    literal = m.group(2)
    body = literal[:1]
    # 变量展开开头（$ 或 ${）不是字面量输出
    if body in ("$", "`"):
        continue
    # 兜底值（`?`、空串）出现在 || 之后，不产生独立输出
    if st.startswith("||") or "||" in st.split("echo")[0]:
        continue
    if body != "{":
        bare_echo.append(st)
check(
    "no bare-text echo output",
    not bare_echo,
    "这些 echo 输出的不是 JSON：" + "; ".join(bare_echo[:3]),
)

# ── 8. /proc 遍历的正确性（踩过的坑） ───────────────────────────────
check(
    "proc loop skips non-numeric dirs",
    gate.count("*[!0-9]*) continue") >= 2,
    "/proc/[0-9]* 会匹配 /proc/8350_reg 这类目录，必须再校验一次",
)
check(
    "proc loop tolerates vanished processes",
    '[ -r "$d/comm" ]' in gate or '[ -r "/proc/$pid/comm" ]' in gate,
    "先判可读再读，否则进程中途退出会在 stderr 刷出 No such file",
)

# ── 9. svc 的状态回读（踩过的坑） ─────────────────────────────────
check(
    "svc reads init.svc.<name> for status",
    'init.svc.$name' in gate,
    "状态在 init.svc.<name> 里，不在 ctl.status",
)
check(
    "svc does not claim success for unknown service",
    "no such init service" in gate,
    "服务不存在时必须报错而不是 ok:true",
)
check(
    "svc polls instead of fixed sleep",
    'while [ "$waited" -lt 20 ]' in gate,
    "init 处理 ctl.* 是异步的，固定 sleep 会误判",
)

# ── 10. 注册到三处清单 ─────────────────────────────────────────────
cfg = io.open(CONFIG, encoding="utf-8").read()
n_root = cfg.count('"root"')
check(
    "registered in all three gate lists",
    n_root >= 3,
    f'EngineConfig.kt 里 "root" 只出现 {n_root} 次（部署/可执行/自检 三处都要）',
)

# ── 11. AI 能发现它 ────────────────────────────────────────────────
seed = io.open(SEED, encoding="utf-8").read()
check(
    "documented in agent context seed",
    "`root` — **system-level operations" in seed,
    "AI 的上下文种子里必须提到 root，否则它永远不知道有这个工具",
)
check(
    "seed mentions dry-run",
    "dry-run unless confirm=1" in seed,
    "种子里要说明危险操作默认 dry-run",
)

catalog = io.open(CATALOG, encoding="utf-8").read()
check(
    "listed in endpoint catalog",
    '系统级（root）' in catalog,
    "端点目录要有这个分组，dev 才能列出来",
)

# ── 12. 能力开关 ───────────────────────────────────────────────────
gk = io.open(GATEKEEPER, encoding="utf-8").read()
check(
    "capability switch exists",
    re.search(r'const val ROOT = "root"', gk) is not None,
)
check(
    "switch defaults off (uses shared enabled())",
    "KEY_PREFIX + name" in gk,
    "所有开关默认 false，root 不能例外",
)
check(
    "switch requires ROOT priv mode",
    "Privilege.getMode(ctx) == PrivMode.ROOT" in gk,
    "非 ROOT 模式必须置灰",
)
check(
    "switch appears in snapshot",
    ".put(ROOT, can(ctx, ROOT))" in gk,
    "能力总览里要有它，否则 /dev/diag 看不到状态",
)

# ── 输出 ───────────────────────────────────────────────────────────
print(f"root gate contract: {len(checks)} checks")
if failed:
    print(f"\nFAILED ({len(failed)}):")
    for name, detail in failed:
        print(f"  ✗ {name}" + (f" — {detail}" if detail else ""))
    sys.exit(1)
print("All root gate checks passed.")
