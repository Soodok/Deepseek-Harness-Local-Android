#!/usr/bin/env python3
"""运行模式浮层 + 插件区 3 行封顶的契约。

用户两条要求（2026-11-11）：
1. 插件超过 3 个时只保留 3 行的窗口高度，列表内部上下滑动；
2. 「语音与插件」那行换成「运行模式」，点开是**悬浮窗不是弹窗**：
   两个分组（原生只有且默认 Bionic；容器列出用户已下载的 Linux 系统，
   一个都没下时给下载入口），选完再弹窗问是否立刻重启。

这些断言都锚定真正会被执行的代码形态，不锚注释；反例已在提交信息里记录。
"""
import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PASS, FAIL = [], []


def check(name, ok, detail=""):
    (PASS if ok else FAIL).append(name)
    print(("  ok  " if ok else "  FAIL") + f" {name}" + (f"  [{detail}]" if detail and not ok else ""))


def read(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def code_only(text):
    """剥掉 // 行注释与 /* */ 块注释，禁/必需类断言必须基于可执行代码。"""
    out, i, n = [], 0, len(text)
    in_block = False
    for line in text.split("\n"):
        s = line
        if in_block:
            if "*/" in s:
                s = s.split("*/", 1)[1]
                in_block = False
            else:
                continue
        while "/*" in s:
            head, rest = s.split("/*", 1)
            if "*/" in rest:
                s = head + rest.split("*/", 1)[1]
            else:
                s = head
                in_block = True
                break
        if "//" in s:
            # 粗略：字符串里的 // 极少见于此项目
            s = s.split("//", 1)[0]
        out.append(s)
    return "\n".join(out)


overlay_path = "app/src/main/java/app/dsh/mobile/RunModeOverlay.kt"
check("RunModeOverlay.kt 存在", os.path.isfile(os.path.join(ROOT, overlay_path)))
check(
    "旧的 RunModeDialog.kt 已删除（用户要悬浮窗不要弹窗）",
    not os.path.isfile(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/RunModeDialog.kt")),
)

ov = read(overlay_path)
ov_code = code_only(ov)
check("浮层构造入口是 build(onPicked) 而不是 show()", "fun build(" in ov_code and "fun show(" not in ov_code)
# 选择窗口必须是浮层；只有"选完问是否立刻重启"那一步用系统弹窗（用户原话如此）。
check(
    "选择窗口不是弹窗：AlertDialog 只用于重启确认，且只出现一次",
    ov_code.count("AlertDialog.Builder") == 1 and "fun build(" in ov_code,
    f"AlertDialog.Builder 出现 {ov_code.count('AlertDialog.Builder')} 次",
)
# v1.7.7（2026-11-12 真机反馈）：必须按 flavor 判，不能只看 isDeployed。
# isDeployed 按 family 算（三个 flavor 共享同一 rootfs 目录），只下基础包时
# 会把 GUI / Dev 一起列出来，用户选它进不去。
check(
    "容器分组只列真正装好的形态（按 flavor，不按 family）",
    "usableCatalog" in ov_code and "flavorPackagesReady" in ov_code,
)
check("没有可用的 Linux 时给下载入口", "LinuxActivity" in ov_code)
check(
    "选完用弹窗问是否立刻重启",
    "R.string.run_mode_restart_q" in ov_code and "supervisor.restart()" in ov_code,
)

main = read("app/src/main/java/app/dsh/mobile/MainActivity.kt")
main_code = code_only(main)
check("MainActivity 有 showRunModeOverlay", "private fun showRunModeOverlay()" in main_code)
check("MainActivity 有 hideRunModeOverlay", "private fun hideRunModeOverlay()" in main_code)
check(
    "开浮层前先收侧面板（否则卡片左边露出面板一条）",
    "closeSidePanelNow()" in main_code and "onRunMode = { runOnUiThread { closeSidePanelNow(); showRunModeOverlay() } }" in main_code,
)
check("浮层挂在 statusBarOverlay 的父容器里", 'findViewById<View>(R.id.statusBarOverlay)?.parent' in main_code)
check("点空白收起（透明接收层带点击监听）", "setOnClickListener { hideRunModeOverlay() }" in main_code)

panel = read("app/src/main/java/app/dsh/mobile/IslandSidePanel.kt")
panel_code = code_only(panel)
check("插件行数封顶常量 = 3", "MAX_PLUGIN_ROWS = 3" in panel_code)
check("插件列表放进 ScrollView 以便内部滑动", "ScrollView" in panel_code)
check("插件段显示「已装 N 个」", "island_side_plugin_count" in panel_code)
check("侧面板有运行模式入口回调", "var onRunMode" in panel_code and "island_run_mode" in panel_code)

for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    s = read(f"app/src/main/res/{loc}/strings.xml")
    missing = [k for k in ("island_run_mode", "run_mode_native", "run_mode_container",
                           "run_mode_container_empty", "run_mode_restart_q") if f'name="{k}"' not in s]
    check(f"{loc}: 运行模式文案齐全", not missing, ",".join(missing))

print(f"\n{len(PASS)} passed, {len(FAIL)} failed")
sys.exit(1 if FAIL else 0)
