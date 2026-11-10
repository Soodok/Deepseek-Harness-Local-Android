#!/usr/bin/env python3
"""issue #58 回归护栏：插件中心的目录/分类必须缓存 + 切换分类走本地过滤。

背景（2026-10-27）：用户报「打开设置 → 社区插件频繁点击，约 30 秒后卡退并回到
重启引擎界面」。根因是主线程被反复阻塞触发系统 ANR：

  · refreshChipStyles() 在每个 chip 的循环里都调 PluginMarket.categories()
    —— 该函数每次读 1.7MB 目录 JSON 并完整解析，24 个分类 = 24 次全量解析；
  · 点分类走 reload(verbose=true) → 重读目录 + 重建 3540 条 Entry，连点即堆积。

本探针是**静态断言**，防这两个模式被重新引入（运行成本近乎零，可进 CI）。

运行: python scripts/probes/_test_plugin_market_cache.py
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MARKET = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "PluginMarket.kt")
STORE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "PluginStoreActivity.kt")

FAILS = []


def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (("  -> " + detail) if not ok and detail else ""))
    if not ok:
        FAILS.append(name)


market = io.open(MARKET, encoding="utf-8").read()
store = io.open(STORE, encoding="utf-8").read()

print("=== 1. 分类列表必须缓存（#58 第一层） ===")
check("categories 有缓存字段", "categoriesCache" in market)
check("命中缓存直接返回", re.search(r"categoriesCache\?\.get\([^)]*\)\?\.let \{ return it \}", market) is not None,
      "没有早返回 → 每次调用都会重新解析 1.7MB")
check("解析后写回缓存", "categoriesCache = (categoriesCache ?: emptyMap())" in market)

print("\n=== 2. 目录条目必须缓存（#58 第二层） ===")
check("catalog 有缓存字段", "catalogCache" in market)
check("fetchCatalog 命中缓存直接返回", re.search(r"catalogCache\?\.let \{ return it \}", market) is not None)
check("构建与缓存分离（buildCatalog）", "private fun buildCatalog(" in market)

print("\n=== 3. chip 样式循环里禁止读文件（#58 直接根因） ===")
m = re.search(r"private fun refreshChipStyles\(\)\s*\{.*?\n    \}", store, re.S)
check("refreshChipStyles 存在", m is not None)
if m:
    body = m.group(0)
    check("循环内不调 PluginMarket.categories()", "PluginMarket.categories" not in body,
          "循环内调用 = 每个 chip 都全量解析一次目录")
    check("改用预置 chipIds 查找", "chipIds" in body)

print("\n=== 4. 分类切换必须本地过滤（不重读文件/不堆积后台任务） ===")
cis = re.search(r"setOnClickListener \{\s*\n\s*if \(selectedCategory == id\).*?\n                \}", store, re.S)
check("分类 chip 点击处理存在", cis is not None)
if cis:
    handler = cis.group(0)
    check("点击不再走 reload(verbose = true)", "reload(verbose = true)" not in handler,
          "连点会反复读目录并堆积后台线程")
    check("点击调用本地重建", "rebuildChipsForCategory" in handler)
check("render 里按 category 本地过滤", "it.category == selectedCategory" in store)
check("本地重建函数存在", "private fun rebuildChipsForCategory()" in store)

print("\n=== 5. 缓存字段必须是 @Volatile（后台线程写、主线程读） ===")
for field in ("categoriesCache", "catalogCache"):
    idx = market.find("private var " + field)
    check(f"{field} 带 @Volatile", idx > 0 and "@Volatile" in market[max(0, idx - 120):idx])

print()
if FAILS:
    print(f"FAILED ({len(FAILS)}): " + ", ".join(FAILS))
    sys.exit(1)
print("All plugin-market cache checks passed.")
