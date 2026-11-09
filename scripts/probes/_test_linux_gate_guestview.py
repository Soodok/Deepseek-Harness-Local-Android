#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""容器模式门脚本自检的宿主/容器视图分离。

## 为什么需要这个探针

`EngineConfig.applyAgentGates()` 部署完门脚本后会逐个自检首行，其中一条是
「shebang 指向的解释器是否存在」。原生模式下这没问题（都是宿主路径）。
但 Linux 模式把 shebang 换成**容器内**路径 `/bin/bash`，而自检用的却是
宿主侧 `java.io.File(interp).isFile` —— Android 宿主上只有 `/bin/sh`，
没有 `/bin/bash`，于是每个门脚本（约 34 个）全部被误报：

    agent gates BROKEN: scr (interpreter missing: /bin/bash); ...

危害不只是日志噪音：真实故障（rootfs 里 bootstrap 没装 bash，导致
`scr`/`notify`/`say` 在容器里 exec 失败）会被这条假报**盖住**，排查时
往「路径写错了」方向走，而真正要查的是「apk add bash 有没有跑」。

修法是给 `applyAgentGates` 加 `guestView` 形参，容器模式跳过的正是
「解释器存在性」这一项（结构 / CRLF 两项仍查）。

## 断言策略（按 AGENTS.md：先剥注释再同行正则，禁止跨行 .* + re.S）

每处断言都要能反向验证变红：把 guestView = true 改回 false、或删掉
`!guestView &&` 守卫，本探针必须报 FAIL。
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
CFG = os.path.join(ENGINE, "EngineConfig.kt")
BRIDGE = os.path.join(os.path.dirname(ROOT), "dsh-linux-runtime", "bench", "bridge_probe.sh")

checks = 0
failed = 0


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   %s" % msg)
    else:
        failed += 1
        print("  FAIL %s" % msg)


def strip_comments(src):
    """去掉行注释与块注释，保留字符串字面量。

    不剥注释会误判：源码注释里会举 ``/bin/bash`` 之类的例子。
    """
    out = []
    i = 0
    n = len(src)
    in_str = False
    in_raw = False
    backslash = chr(92)
    while i < n:
        c = src[i]
        if in_raw:
            if src.startswith('"""', i):
                in_raw = False
                out.append('"""')
                i += 3
            else:
                out.append(c)
                i += 1
            continue
        if in_str:
            if c == backslash:
                out.append(src[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if src.startswith('"""', i):
            in_raw = True
            out.append('"""')
            i += 3
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find(chr(10), i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def main():
    print("[A] applyAgentGates 的 guestView 形参")
    with open(CFG, encoding="utf-8") as f:
        raw = f.read()
    src = strip_comments(raw)

    # A1：deployLinuxAgentGates 必须传 guestView = true。
    a1 = re.search(r"fun deployLinuxAgentGates\([^)]*\)\s*\{(.{0,300}?)\n    \}", src, re.S)
    ok(a1 is not None, "deployLinuxAgentGates 函数体可定位")
    body = a1.group(1) if a1 else ""
    ok(re.search(r"guestView\s*=\s*true", body) is not None,
       "A1 容器模式传入 guestView = true")

    # A2：形参存在且默认 false（原生模式行为不变）
    ok(re.search(r"guestView\s*:\s*Boolean\s*=\s*false", src) is not None,
       "A2 guestView 形参默认 false（原生模式不受影响）")

    # A3：解释器存在性检查必须被 !guestView 守卫
    ok(re.search(r"!guestView\s*&&", src) is not None,
       "A3 解释器检查带 !guestView 前置条件")
    # A3b：守卫与 File( 必须**紧邻**（中间只允许空白/换行缩进），
    # 而不是「全文某处有 !guestView、另一处有 !File(」。
    ok(re.search(r"!guestView\s*&&\s*!File\(", src) is not None,
       "A3b 守卫与 !File( 紧邻（中间只允许空白）")

    # A4：结构与 CRLF 两项在容器模式下仍要查（不能被一起跳过）
    ok(re.search(r"!f\.isFile\s*->\s*broken", src) is not None,
       "A4a missing 检查无条件保留")
    cr = "head.contains('" + chr(92) + "r')-> broken".replace("->", "-\\>")
    ok(re.search(r"head\.contains\('" + chr(92) + chr(92) + r"r'\)\s*->\s*broken", src) is not None,
       "A4b CRLF 检查无条件保留")

    print("[B] 有效的容器解释器路径")
    ok('bashPath = "/bin/bash"' in src, "B1 容器 bash 路径为 /bin/bash（Alpine 约定）")
    ok('libDir = "/usr/lib"' in src, "B2 容器 lib 路径为 /usr/lib")

    print("[C] bridge 探针脚本")
    # C1~C6 读的是**兄弟仓** dsh-linux-runtime 的 bench/bridge_probe.sh，本仓库
    # 里没有它。CI 只检出本仓库，这段在 CI 上必然红 —— 那会让真正的回归被长期红
    # 掩盖（与本仓库给 corepack_hardlink 开例外的理由相同）。
    # 所以：兄弟仓缺席时整段标 SKIP 并打印原因；在场时照旧严格校验。
    _sibling_root = os.path.dirname(ROOT)
    _sibling_repo = os.path.join(_sibling_root, "dsh-linux-runtime")
    if not os.path.isdir(_sibling_repo):
        print("  SKIP C1-C6：兄弟仓 dsh-linux-runtime 不在（%s），"
              "本仓库不随附 bench/bridge_probe.sh" % _sibling_repo)
        print("       （CI 检出天然如此；本地想跑这段就把兄弟仓放在同一层目录）")
    else:
        ok(os.path.isfile(BRIDGE), "C1 bench/bridge_probe.sh 存在")
        if os.path.isfile(BRIDGE):
            with open(BRIDGE, encoding="utf-8") as f:
                bp = f.read()
            for key, label in [
                ("no_token_403", "C2 断言无 token 必须 403（鉴权真的在起作用）"),
                ("auth_ok", "C3 断言带 token 可达"),
                ("a11y_ok", "C4 断言无障碍服务可用（拿得到前台包名）"),
                ("launch_ok", "C5 断言真能操作设备（启动 app 并核回执）"),
                ("RESULT: BRIDGE_OK", "C6 有总判定"),
            ]:
                ok(key in bp, label)

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
