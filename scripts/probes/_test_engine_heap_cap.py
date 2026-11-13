#!/usr/bin/env python3
"""引擎堆上限守卫：启动 argv 必须显式给 --max-old-space-size（超大会话 OOM）。

## 为什么需要这个探针

Android 上 runtime 自带 node 的**默认老生代上限只有 1120MB**，与物理内存无关。
实测（真机 arm64 / Android 16 / node v24.22.2 / RAM 15017MB）：不给参数时
`v8.getHeapStatistics().heap_size_limit` = 1174405120 B = 1120MB；同机 free 4.2GB
时仍是 1120MB。桌面 node v24 才是 4288MB —— 所以「在桌面上试没问题」这件事
完全不能用来判断真机行为。

后果：一个 40MB 的 `session.v4.jsonl.zstd` 解压后 128.7MB / 61247 个事件，
引擎的 `readZstdPrefix` 会把它整体 parse 成事件对象、深度冻结、再做消息投影，
轻易把这 1120MB 吃穿：

    FATAL ERROR: Reached heap limit - Allocation failed - JavaScript heap out of memory

引擎进程直接死，App 侧只显示「重新连接中」，日志里一个字都没有 —— 用户看到的
是「某个会话打不开」，实际上整个引擎没了。15GB 内存的机型照样中招（实测复现）。

## 判据

三条同时成立才通过：

1. `EngineConfig.engineHeapFlag` 存在且返回 `--max-old-space-size=<mb>`；
2. 原生模式 spawn 的 extraFlags 里包含它（EngineSupervisor 的 EngineProcess.spawn 调用）；
3. 容器模式 argv 里包含它（LinuxEngineLauncher.buildEngineArgv）。

另外校验取值区间常量：下限不得低于 1536（低于 Android 默认 1120 等于倒退），
上限不得高于 8192（再往上就是在拿「被 LMK 静默杀掉」换「V8 抛 OOM」）。

> 第 2/3 条查的是**调用点**而不是函数定义：只定义不接线是本仓历史上反复出现的
> 假绿形态（函数写好了，没人调）。

## 用法

    python scripts/probes/_test_engine_heap_cap.py

退出码 0 = 通过，1 = 有违规。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """剥掉 // 与 /* */ 注释，避免说明文字被当成代码命中。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def main():
    problems = []
    config_path = os.path.join(ENGINE, "EngineConfig.kt")
    if not os.path.isfile(config_path):
        print("FAIL EngineConfig.kt not found at " + config_path)
        return 1
    config = strip_comments(read(config_path))

    # 1) 参数生成器必须存在
    if "fun engineHeapFlag" not in config:
        problems.append("EngineConfig.engineHeapFlag is missing")
    if "--max-old-space-size=" not in config:
        problems.append("EngineConfig does not build a --max-old-space-size flag")

    # 2) 取值区间：下限不能低于 Android 默认 1120，上限不能高到招 LMK
    low = re.search(r"MIN_ENGINE_HEAP_MB\s*=\s*(\d+)", config)
    high = re.search(r"MAX_ENGINE_HEAP_MB\s*=\s*(\d+)", config)
    if low is None or high is None:
        problems.append("MIN/MAX_ENGINE_HEAP_MB constants are missing")
    else:
        lo, hi = int(low.group(1)), int(high.group(1))
        if lo < 1536:
            problems.append(
                "MIN_ENGINE_HEAP_MB=%d is below the 1536 floor "
                "(Android node default is 1120MB, so this would be a regression)" % lo
            )
        if hi > 8192:
            problems.append(
                "MAX_ENGINE_HEAP_MB=%d exceeds 8192; above that the App gets killed "
                "by the kernel before V8 can report an OOM" % hi
            )
        if lo >= hi:
            problems.append("MIN_ENGINE_HEAP_MB (%d) must be < MAX_ENGINE_HEAP_MB (%d)" % (lo, hi))

    # 3) 调用点：原生模式
    supervisor = os.path.join(ENGINE, "EngineSupervisor.kt")
    if not os.path.isfile(supervisor):
        problems.append("EngineSupervisor.kt not found")
    else:
        text = strip_comments(read(supervisor))
        if "engineHeapFlag" not in text:
            problems.append(
                "EngineSupervisor.kt never calls EngineConfig.engineHeapFlag -- "
                "the native spawn would keep the 1120MB default"
            )

    # 4) 调用点：容器模式
    launcher = os.path.join(ENGINE, "LinuxEngineLauncher.kt")
    if not os.path.isfile(launcher):
        problems.append("LinuxEngineLauncher.kt not found")
    else:
        text = strip_comments(read(launcher))
        if "engineHeapFlag" not in text:
            problems.append(
                "LinuxEngineLauncher.kt never calls EngineConfig.engineHeapFlag -- "
                "the container argv would keep the 1120MB default"
            )

    if problems:
        for item in problems:
            print("FAIL " + item)
        return 1
    print("OK engine heap cap is wired for both native and container boot")
    return 0


if __name__ == "__main__":
    sys.exit(main())
