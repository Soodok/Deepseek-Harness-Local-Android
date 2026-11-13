#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""审查修复回归探针（2026-11-12）：防止这一轮的六类缺陷被改回去。

每条断言都对应一个真实缺陷，注释里写明「改回去会怎样」——这是探针存在的唯一理由。
纯源码静态检查 + 少量文件存在性，不联网、不碰设备。
"""
import io
import os
import sys

# 探针在 scripts/probes/ 下，仓库根要上溯三级（probes -> scripts -> 仓库根）
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PASS = 0
FAIL = 0


def ok(cond, label, detail=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  OK   " + label)
    else:
        FAIL += 1
        print("  FAIL " + label + ("  [" + detail + "]" if detail else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    with io.open(p, encoding="utf-8") as f:
        return f.read()


print("== VScreen 帧闸门必须接在 AI 主路径上 ==")
cap = read("app/src/main/java/app/dsh/mobile/engine/cap/VScreenCap.kt")
ok("frameArg(" in cap, "VScreenCap 有 frameArg 翻译函数")
ok('optLong("frame_seq"' in cap, "VScreenCap 真的读 frame_seq")
ok('frame=" + seq' in cap or 'listOf("frame=" + seq)' in cap,
   "frame_seq 被翻成门脚本认的 frame=SEQ")
ok(cap.count("args += frameArg(obj)") >= 2,
   "tap 与 swipe 都接了 frame_seq（两个调用点）",
   "count=%d" % cap.count("args += frameArg(obj)"))
vsgate = read("app/src/main/assets/gates/vscreen")
ok("frame_seq=$frame" in vsgate, "门脚本把 frame= 转成 query 参数 frame_seq=")

print("== /stop 必须先回包再退出 ==")
core = read("app/src/main/java/app/dsh/mobile/engine/vscreen/VScreenCore.kt")
ok("pendingShutdown" in core, "核心用 pendingShutdown 延迟退出")
ok("pendingShutdown?.let { shutdown(it) }" in core,
   "退出发生在 flush 之后（否则响应永远发不回）")
ok('"/stop" -> { shutdown(' not in core,
   "处理线程不再直接 shutdown（会 System.exit 掐断响应）")

print("== 截图路径不可预测 ==")
ok('/data/local/tmp/dsh-vscreen.png' not in core,
   "不再用固定路径（并发覆盖 + symlink 攻击面）")
ok("shotDir()" in core, "截图画到私有目录")
# ★ 真机实测教训：context 是 system context，filesDir=/data/user/0/android/files，
#   那个目录根本不存在 → mkdirs 失败 → 每张图静默退回 /data/local/tmp，防护等于没生效。
shot_dir = core[core.index('private fun shotDir()'):]
shot_dir = shot_dir[:shot_dir.index(chr(10) + '    }')]
ok("readyFile" in shot_dir,
   "落盘目录取自 readyFile 的父目录（App 私有区），不依赖 system context.filesDir")
ok(shot_dir.index("readyFile") < shot_dir.index("context.filesDir"),
   "私有目录排在兜底之前（兜底只在前者不可用时生效）")
gate = read("app/src/main/assets/gates/vscreen")
shot_branch = gate[gate.index('  shot)'):gate.index('  launch)')]
ok("SHOT_PATH" not in shot_branch,
   "门脚本不再引用早已不存在的固定路径 SHOT_PATH")
ok('"path"' in shot_branch and "sed" in shot_branch,
   "从响应的 path 字段解析真实落盘路径")
ok("saved_error" in shot_branch,
   "抄图失败要显式报错，不能静默当成功")

print("== 屏上长任务互斥 ==")
ok(os.path.isfile(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/OnScreenTask.kt")),
   "OnScreenTask 互斥对象存在")
bridge = read("app/src/main/java/app/dsh/mobile/engine/AgentBridge.kt")
ok('OnScreenTask.runOrNull("loop/run")' in bridge, "loop/run 走互斥")
ok("another on-screen task is running" in bridge, "抢占时回 409 并说明原因")
routes = read("app/src/main/java/app/dsh/mobile/engine/MemoryRoutes.kt")
ok('OnScreenTask.runOrNull("memory/run")' in routes, "memory/run 走同一把锁")

print("== windowListJson 必须回收句柄 ==")
a11y = read("app/src/main/java/app/dsh/mobile/DshAccessibilityService.kt")
win = a11y[a11y.index("fun windowListJson"):]
win = win[:win.index("return org.json.JSONObject()")]
ok("rootNode?.recycle()" in win, "windowListJson 回收根节点")
ok("all.valueAt(i)?.forEach" in win and "recycle()" in win,
   "windowListJson 回收窗口对象（与 rootForDisplay 同口径）")
ok("quitSafely()" in a11y, "无障碍事件线程 onDestroy 时 quitSafely")

print("== toggle 词表不再子串误命中 ==")
replay = read("app/src/main/java/app/dsh/mobile/engine/MemoryReplay.kt")
ok('"开",' not in replay and '"开", ' not in replay,
   "TOGGLE_ON_WORDS 不再含单字「开」（会误命中「开发者选项」）")
ok('"关",' not in replay and '"关", ' not in replay, "TOGGLE_OFF_WORDS 不再含单字「关」")
ok('"open",' not in replay, "不再含 open（会误命中「打开方式」）")

print("== 门脚本安装失败必须判红 ==")
gate = read("scripts/gate-memory.sh")
ok("INSTALL_RC=$?" in gate or "INSTALL_RC=" in gate, "安装段收集退出码")
ok('grep -q "Success"' in gate, "按 Success 判据双验")
ok('install -r "$APK" 2>&1 | tail -1' not in gate,
   "不再用管道吞退出码（装旧包会全绿）")
ok("gate-token.txt" not in gate, "桥 token 不再明文落盘")

print("== 扩展链路纵深 ==")
ext = read("app/src/main/java/app/dsh/mobile/engine/ExtensionManager.kt")
ok("safeFilePart(" in ext, "deb 文件名做净化（防 ../ 穿越）")
ok("isInExtensionRoot(" in ext, "symlink 目标约束在扩展根内")

print("== CI 必须跑 JVM 断言 ==")
ci = read(".github/workflows/android-build.yml")
ok("jvm-behaviour:" in ci, "CI 有 jvm-behaviour job")
ok("scripts/run-a11y-jvm-test.sh" in ci, "CI 真的调用断言脚本")
ok('grep -q "^SKIP"' in ci, "CI 把 SKIP 当失败（否则等于没跑）")
ok('SOLUTION_EXPLORER_VERSION: "' in ci, "solution-explorer 版本钉死（不再回落 latest）")

print("== 环回判定默认收紧 ==")
ok("}.getOrDefault(false)" in bridge, "判不出地址时按非环回处理")
ok("a.isLoopbackAddress || a.hostAddress" in bridge, "同时认 ::1")

print("== CI 的 JVM 断言必须能真的找到 jar（v1.7.15） ==")
# 改回去会怎样：CI 上 gradle :app:dependencies 只解析元数据、不下载 artifact，
# 干净 runner 缓存里只有 pom；脚本按目录猜路径 -> 一个 jar 都没有 -> 整体 SKIP，
# 看起来“跑过了”实际一条断言没执行（run 43268294860 就是这么红的）。
jvm_sh = read("scripts/run-a11y-jvm-test.sh")
ok("KOTLIN_CP_FILE" in jvm_sh, "脚本支持 KOTLIN_CP_FILE 显式传入 jar 清单")
ok("from_list()" in jvm_sh, "有从清单挑 jar 的 from_list")
ok("printKotlinCompilerClasspath" in jvm_sh, "用法指向 :app:printKotlinCompilerClasspath")

# 改回去会怎样：configuration.files 不参与就不会下载 artifact，清单是空的。
app_gradle = read("app/build.gradle.kts")
ok("printKotlinCompilerClasspath" in app_gradle, "Gradle 提供 printKotlinCompilerClasspath 任务")
ok("configuration" in app_gradle and "kotlinCompilerClasspath" in app_gradle,
   "该任务真的解析 kotlinCompilerClasspath（files 会强制下载 artifact）")

# 改回去会怎样：用 ${p%<BS>r} 去 CR 时模式里是“字面 r”，会把 jar 名末尾的 r
# 啃掉变成 .ja，一条都匹配不上，于是静默回落到按目录猜 —— 带清单也照样编译失败。
BS = chr(92)
jvm_code = "\n".join(l for l in jvm_sh.splitlines() if not l.lstrip().startswith("#"))
ok("p=" + chr(34) + "${p%" + BS + "r}" + chr(34) not in jvm_code,
   "代码里没有 p=${p%<BS>r} 这种去 CR 写法（会把 .jar 截成 .ja）")
ok("tr -d " + chr(39) + BS + "r" + chr(39) in jvm_sh, "改用 tr 去掉 CRLF")

# 改回去会怎样：只匹配 /name- 时 Windows 的反斜杠路径一条都挑不中。
ok(chr(36) + chr(123) + "p##*/" + chr(125) in jvm_sh, "按 / 切 basename")
ok("##*" + BS + BS + "}" in jvm_sh, "同时认反斜杠路径分隔符")

# 改回去会怎样：CI 侧拿不到清单就会 SKIP 假绿，所以这两条必须留在 workflow 里。
ok("KOTLIN_CP_FILE" in ci, "CI 把 jar 清单通过 KOTLIN_CP_FILE 传给脚本")
ok(":app:printKotlinCompilerClasspath" in ci, "CI 真的执行生成清单的任务")

print("")
print("PASS=%d FAIL=%d" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
