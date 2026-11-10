#!/usr/bin/env python3
"""回到前台主动重连（v1.6.20）静态契约探针。

## 为什么需要这个探针

这条链路的失效**极其安静**：WebSocket 半开时前端状态仍是 `connected`，
界面照常渲染，只是所有增量都不来、消息静默失败。用户看到的是「卡住了」，
而代码层面没有任何异常抛出。所以必须有断言把「叫醒路径的每一环」钉住：

    App.onResume → evaluateJavascript → window.__dshResume
                                     → 探活 → connection.reconnect()

任何一环断了（插件没打进 APK、没注册进 overlay、钩子名改了、
App 没调、inject 漏了 connection），现象都跟现在一样 —— 用户只能手动重连。

## 断言的分级

A 段验插件产物本身（文件齐、格式对、inject 声明、钩子定义与清理）；
B 段验注册链路（overlay insert + assets 复制列表 —— 两处都缺一不可）；
C 段验 App 侧调用（位置在 onResume 内、且与 webView.onResume 同块）；
D 段验语义（探活判据不能只看 state、幂等保护、事件监听的对称清理）。

反向验证脚本 `_rv_resume_reconnect.py` 逐条改坏这些点，确认每个断言都会红。
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PLUG = os.path.join(ROOT, "app/src/main/assets/android-plugins/resume-reconnect")
CLIENT = os.path.join(PLUG, "client.js")
INDEX = os.path.join(PLUG, "index.js")
PKG = os.path.join(PLUG, "package.json")
ENGINE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")
MAIN = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/MainActivity.kt")

checks: list[tuple[str, bool, str]] = []


def check(tag: str, ok: bool, detail: str = "") -> None:
    checks.append((tag, bool(ok), detail))


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def main() -> int:
    for p in (CLIENT, INDEX, PKG):
        check("A1 产物存在 " + os.path.basename(p), os.path.isfile(p), p)

    if not os.path.isfile(CLIENT):
        report()
        return 1

    js = read(CLIENT)
    pkg = read(PKG)
    idx = read(INDEX)
    engine = read(ENGINE)
    main_kt = read(MAIN)

    # ---- A 段：插件产物 ----
    check(
        "A2 用 __ModuleLoader__.load 包装",
        "window.__ModuleLoader__.load(" in js,
        "client bundle 必须是 loader 工厂形态，裸 export 不会被引擎接纳",
    )
    check(
        "A3 声明 inject 含 connection",
        re.search(r'var\s+inject\s*=\s*\[\s*"connection"\s*\]', js) is not None,
        "inject 少写 connection → ctx.connection 为 undefined，重连静默不生效",
    )
    check(
        "A4 导出 apply/inject/name",
        all(k in js for k in ("exports.apply = apply", "exports.inject = inject", "exports.name = name")),
    )
    check(
        "A5 定义 window.__dshResume 钩子",
        "window.__dshResume = function" in js,
    )
    check(
        "A6 卸载时删掉钩子",
        re.search(r"delete\s+window\.__dshResume\s*;", js) is not None,
        "不删会留下指向已释放 ctx 的悬挂引用",
    )
    check(
        "A6b 诊断接口读 generation.id（重连的硬判据）",
        "window.__dshResumeDiag" in js
        and re.search(r"generation\.getSnapshot\(\)", js) is not None
        and re.search(r"generationId:\s*gen", js) is not None,
        "只用 state 无法证明真的重连过 —— 半开时 state 仍是 connected",
    )
    check(
        "A7 监听 visibilitychange 且成对移除",
        js.count('addEventListener("visibilitychange"') == 1
        and js.count('removeEventListener("visibilitychange"') == 1,
    )
    check(
        "A8 监听 pageshow 且成对移除",
        js.count('addEventListener("pageshow"') == 1
        and js.count('removeEventListener("pageshow"') == 1,
    )
    check(
        "A9 走 ctx.effect 注册清理",
        re.search(r"ctx\.effect\(\s*function", js) is not None,
    )
    check("A10 package.json 声明 dsh.client", '"client"' in pkg and '"platform"' in pkg)
    check("A11 index.js 有宿主机侧空实现", "export default function apply" in idx)

    # ---- D 段：语义（放在 B/C 之前，因为它们是「为什么这么写」的核心） ----
    check(
        "D1 探活用 HEAD /",
        re.search(r'method:\s*"HEAD"', js) is not None,
        "GET / 会把整页 HTML 拉下来，探活代价过大",
    )
    check(
        "D2 有「强制重连」分支且跳过探活",
        re.search(r"if\s*\(force\)\s*\{", js) is not None
        and "FORCE_AFTER_MS" in js,
        "★探活只能证明服务端在跑，证明不了 socket 还活着：解冻后 HEAD / 必然 200、"
        "state 仍是 connected，只靠探活永远不重连（第一版实测就栽在这）",
    )
    check(
        "D2b 短时切回仍走探活兜底",
        "serverAlive()" in js and "probe-failed" in js,
        "短时切回不该无脑重连（会白拉一次快照、界面闪一下）",
    )
    check(
        "D3 state === undefined 时直接返回（boot 早期不插手）",
        re.search(r'state\.getSnapshot\(\)\s*===\s*undefined', js) is not None,
    )
    check(
        "D4 幂等：probing 期间不重复探活",
        re.search(r"if\s*\(\s*probing\s*\)\s*return", js) is not None,
    )
    check(
        "D5 幂等：最小间隔限制",
        re.search(r"MIN_INTERVAL_MS", js) is not None
        and re.search(r"lastReconnectAt", js) is not None,
    )
    check(
        "D6 真调 connection.reconnect()",
        re.search(r"connection\.reconnect\(\)", js) is not None,
    )
    check(
        "D6b force 判据来自 elapsedMs（不是盲目强制）",
        re.search(r"window\.__dshResume\s*=\s*function\s*\(elapsedMs\)", js) is not None
        and re.search(r"elapsedMs\s*>=\s*FORCE_AFTER_MS", js) is not None,
        "无参调用（不知道离开多久）按保守处理；有参时按时长分档",
    )
    check(
        "D7 探活有超时（AbortController）",
        "AbortController" in js and "PROBE_TIMEOUT_MS" in js,
        "无超时会在半开连接上挂到浏览器默认超时（可能几十秒），用户感知是没反应",
    )

    # ---- B 段：注册链路 ----
    check(
        "B1 overlay 有 android-resume-reconnect entry",
        re.search(r"-\s*id:\s*android-resume-reconnect\s*\n[^\n]*name:\s*\./android-plugins/resume-reconnect/index\.js", engine)
        is not None,
    )
    check(
        "B2 assets 复制列表含 resume-reconnect 三文件",
        re.search(
            r'"resume-reconnect"\s+to\s+listOf\(\s*"package\.json",\s*"index\.js",\s*"client\.js"\s*\)',
            engine,
        )
        is not None,
        "漏一行 → 插件文件不进 engine 目录，overlay 指向不存在的路径",
    )

    # ---- C 段：App 侧调用 ----
    check(
        "C1 MainActivity 调 window.__dshResume",
        "window.__dshResume" in main_kt,
    )
    check(
        "C2 调用点用 evaluateJavascript",
        re.search(r"evaluateJavascript\(\s*\"[^\"]*__dshResume", main_kt) is not None,
    )
    # 定位 onResume 函数体，确认调用点在里面（不是 onPause / onCreate 里）
    m = re.search(r"override\s+fun\s+onResume\(\)\s*\{", main_kt)
    resume_body = ""
    if m:
        start = m.end()
        depth = 1
        i = start
        while i < len(main_kt) and depth > 0:
            ch = main_kt[i]
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
            i += 1
        resume_body = main_kt[start:i]
    check("C3 调用点在 onResume 函数体内", "__dshResume" in resume_body if resume_body else False)
    check(
        "C4 与 webView.onResume 同块（都在 webView.isInitialized 分支里）",
        bool(resume_body)
        and re.search(r"webView\.onResume\(\)[\s\S]{0,2000}__dshResume", resume_body) is not None,
        "放到 isInitialized 之外会在 webView 未初始化时 NPE",
    )
    check(
        "C5 调用包在 runCatching 里",
        re.search(r"runCatching\s*\{[\s\S]{0,1200}__dshResume", resume_body) is not None if resume_body else False,
        "JS 求值失败不该让 onResume 抛出去",
    )

    return report()


def report() -> int:
    fails = [c for c in checks if not c[1]]
    for tag, ok, detail in checks:
        print(("  OK   " if ok else "  FAIL ") + tag + (("  <- " + detail) if (not ok and detail) else ""))
    print()
    print(f"{len(checks) - len(fails)} passed, {len(fails)} failed, {len(checks)} total")
    if fails:
        print("\n失败项：")
        for tag, _, detail in fails:
            print("  · " + tag + (("  <- " + detail) if detail else ""))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
