#!/usr/bin/env python3
"""容器模式下门脚本链路的静态门禁。

背景（2026-11-12 真机取证）：Linux 容器（Alpine/musl）里 `dev`/`scr`/`say`/`notify`
全部失效，`scr dump` 只吐 `REEXEC: preload FAIL` 且**退出码 0**。根因两条叠加：

① 运行模式已切到容器，但门脚本仍是 bionic 时代写法；
② 被外部 `timeout` fork+exec 出来的子进程经 runner reexec 回投宿主时 preload 失败。

这些断言锁的是「不许再依赖会 fork 的外部命令」与「失败必须非 0」。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
fails = []
passes = 0


def check(name, ok, detail=""):
    global passes
    if ok:
        passes += 1
    else:
        fails.append(name + ((" — " + detail) if detail else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        return ""
    return io.open(p, encoding="utf-8").read()


def code_only(text, marker="#"):
    """剥掉行注释。注释里解释「为什么不用 timeout」是文档，不是依赖。"""
    out = []
    for line in text.split("\n"):
        if line.lstrip().startswith(marker):
            continue
        out.append(line)
    return "\n".join(out)


http = read("app/src/main/assets/gates/_dsh_http.sh")
hcode = code_only(http)

check("_dsh_http.sh exists", bool(http))

# ★ 响应体读取不许依赖会 fork 的外部命令（容器里必崩）。
check(
    "response body read uses bash builtin, not timeout/head/cat",
    re.search(r"read\s+-r\s+-N", hcode) is not None,
    "必须用 LC_ALL=C read -N 精确按字节读；timeout+head 在容器里会 REEXEC FAIL",
)
for banned in ("timeout ", "head -c", "cat <&3"):
    check(
        f"no external '{banned.strip()}' in body read",
        banned not in hcode,
        "容器内 fork+exec 的 bionic 二进制经 runner reexec 会 preload FAIL",
    )

# Content-Length 解析不许用 tr。
check(
    "Content-Length parsed with parameter expansion, not tr",
    re.search(r'_clen=\$\{_clen//\[\[:space:\]\]/\}', hcode) is not None,
    "tr 也是外部进程",
)
check(
    "no tr in header parsing",
    not re.search(r"tr -d", hcode),
)

# 短读必须报错而不是当成功。
check(
    "short body read reports failure",
    re.search(r"_body_rc=1", hcode) is not None
    and re.search(r'if \[ "\$_body_rc" = 1 \]', hcode) is not None,
    "读不满 Content-Length 必须 return 3，不能把半截当完整响应",
)

# ★ 字节语义：read -N 默认按字符，必须 LC_ALL=C。
check(
    "byte-exact read sets LC_ALL=C",
    re.search(r"LC_ALL=C IFS= read -r -N", hcode) is not None,
    "不设 C locale 时 read -N 按字符计数，中文会读多/读少",
)

# 注意：门脚本 assets 里**没有** /bin/bash 分支是对的 —— 那个分支由
# EngineConfig 在部署时注入（见下方 guestView 断言）。assets 是源模板，
# 部署产物才是最终形态，判据要落在注入逻辑上而不是模板上。

# 部署侧：容器模式必须换 shebang，否则 #!/system/bin/sh 在容器里指向 bionic toybox。
cfg = read("app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
check(
    "container deploy rewrites shebang to /bin/bash",
    re.search(r'if \(guestView\)', cfg) is not None
    and 'replaceFirst("#!/system/bin/sh", "#!/bin/bash")' in cfg,
    "容器里 #!/system/bin/sh 是 bionic，runner 会转交宿主 cleaner → 路径视图错",
)
check(
    "container deploy prefers guest bash at runtime",
    'if [ -f /bin/bash ]; then exec /bin/bash' in cfg,
    "部署时写死路径会在两种模式下各错一半，必须运行时按存在性选",
)
check(
    "deployLinuxAgentGates wired into supervisor",
    "deployLinuxAgentGates" in read("app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt"),
)

# 自举段本身：_DSH_REEXEC 防递归标记必须在。
for gate in ("dev", "scr", "say", "notify"):
    g = read("app/src/main/assets/gates/%s" % gate)
    check(
        "%s has reexec guard" % gate,
        "_DSH_REEXEC" in g,
        "没有防递归标记时 exec $0 会无限套",
    )

# dev 的 node 探测：容器里 node 在 /usr/bin，不能只拼 bash 同目录。
check(
    "dev probes multiple node locations",
    "/usr/bin/node" in read("app/src/main/assets/gates/dev"),
    "容器里 bash 在 /bin、node 在 /usr/bin，只拼 /bin/node 必然找不到",
)

# 解析失败必须非 0（此前 rc=0 让 AI 把空输出当成功）。
#
# ⚠️ 不能用「文件里某处有 process.exit(6x)」这种全文件判定 —— dev 里有**三处**
# JSON.parse（目录列表、单个能力详情、能力元数据），只破坏其中一处时另一处
# 仍然命中，断言照样绿（反向验证抓到的第四条假绿）。
#
# 逐处校验：每一处 JSON.parse 的 catch 都必须「显式交代失败」——
# 要么 process.exit(非 0) 终止，要么打一个调用方会检查的哨兵（`|||0`）。
# 两者都没有 = 解析失败被静默当成成功，正是本 bug 的形状。
dev = read("app/src/main/assets/gates/dev")
_parse_lines = [ln for ln in dev.split("\n") if "JSON.parse" in ln]
_silent = []
for _ln in _parse_lines:
    _has_exit = re.search(r"process\.exit\(([1-9][0-9]*)\)", _ln) is not None
    _has_sentinel = "|||" in _ln
    if not (_has_exit or _has_sentinel):
        _silent.append(_ln.strip()[:70])
check(
    "every JSON parse failure is surfaced",
    _parse_lines and not _silent,
    "这些解析点失败后既不退出也不打哨兵，失败会被当成成功: " + " | ".join(_silent[:2]),
)

print(f"passes={passes} fails={len(fails)}")
for f in fails:
    print("  FAIL:", f)
sys.exit(1 if fails else 0)
