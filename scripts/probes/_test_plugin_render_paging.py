#!/usr/bin/env python3
"""Issue #62 回归护栏：插件中心必须增量渲染。

事件回顾：社区插件中心搜索/清空搜索时应用直接卡死并被系统杀掉，用户看到
「搜索就崩、然后引擎重启」。根因是 render() 把过滤后的**全部**条目一次性
addView 进一个没有视图回收的 ScrollView —— 目录有 3540 个插件，每行约 6 个
嵌套 View，两万多个 View 建在主线程上，必然 OOM/ANR。进程被杀后引擎看门狗
又误判「引擎异常退出」并重启，故障看起来像引擎问题。

本探针锁住三件事，防止以后又改回全量渲染：
  1. render() 里不再出现「对全部结果直接 addView」的循环；
  2. 存在分批常量与 appendNextPage()；
  3. ScrollView 有 id 且挂了滚动监听，否则追加永远不触发。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                  "PluginStoreActivity.kt")
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout",
                      "activity_plugin_store.xml")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


src = open(KT, encoding="utf-8").read()
layout = open(LAYOUT, encoding="utf-8").read()

# 1) 不允许「把结果集整个 forEach 加进 container」
check(
    "render: 没有对全量结果直接 addView 的循环",
    not re.search(r"ordered\.forEach\s*\{\s*container\.addView", src),
    "found ordered.forEach { container.addView(...) } — 会一次性渲染全部结果",
)
check(
    "render: 没有 sorted.forEach 直接 addView",
    not re.search(r"sorted\.forEach\s*\{\s*container\.addView", src),
)

# 2) 分批渲染的三个组成部分
check("code: 定义 RENDER_PAGE 常量", "RENDER_PAGE" in src)
check("code: 有 appendNextPage()", "fun appendNextPage()" in src)
check("code: 有 pendingRows 暂存", "pendingRows" in src)
check("code: 有 shownRows 计数", "shownRows" in src)
check(
    "code: 首屏只渲染一批（render 调用 appendNextPage）",
    re.search(r"pendingRows\s*=\s*ordered\s*\n\s*appendNextPage\(\)", src) is not None,
)
check(
    "code: 追加时有上界截断",
    re.search(r"minOf\(shownRows \+ RENDER_PAGE,\s*pendingRows\.size\)", src) is not None,
)

# 3) 滚动触发
check("layout: ScrollView 有 id=@+id/listScroll", 'android:id="@+id/listScroll"' in layout)
check("code: 绑定 listScroll", "findViewById(R.id.listScroll)" in src)
check(
    "code: 挂了滚动监听补充下一批",
    "setOnScrollChangeListener" in src and "appendNextPage()" in src,
)

# 4) 兜底：进度/追加逻辑要能终止（pendingRows 清空）
check(
    "code: 渲染完最后一批会清空 pendingRows",
    re.search(r"if \(shownRows >= pendingRows\.size\) pendingRows = emptyList\(\)", src)
    is not None,
)

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
