#!/usr/bin/env python3
"""自动更新的索引安全回归（v1.4.37）—— 索引必须"直连优先 + 必须验签"。

## 为什么单独立一条探针
`update-index.json` 决定"什么才算正版安装包"：它给出每个 APK 的 sha256。
一旦索引被中间人替换，后续所有 sha 校验都会"校验通过"——投毒的包反而看起来
最合法。所以索引的抓取路径与验签顺序是安全边界，不能靠 code review 记着：

1. **首请求严格直连**，绝不套任何代理前缀（代理优先的 `orderUrls()` 只用于 APK）；
2. 直连失败后才允许经代理重取，且**必须验签通过**才使用；
3. 验签失败 → 整体失败，绝不降级为"无签名也用"；
4. https 强制（用户自建 http 代理是唯一例外）。

这四条都在 `update/SignedIndex.kt` 里，本探针用源码断言把它们钉住。

运行：python scripts/probes/_test_update_index_direct.py
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SIGNED = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/SignedIndex.kt")
CHECKER = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdateChecker.kt")
WRITER = os.path.join(ROOT, "scripts/write-update-index.py")

failures: list[str] = []


def check(name: str, cond: bool, detail: str = "") -> None:
    if cond:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}{(' — ' + detail) if detail else ''}")
        failures.append(name)


def read(p: str) -> str:
    with open(p, encoding="utf-8") as f:
        return f.read()


def main() -> int:
    print("== 1. 抓取顺序：直连在前，代理变体只作降级 ==")
    src = read(SIGNED)
    check("存在直连首请求", "sources.add(url to false)" in src)
    check(
        "代理候选由 orderUrls 派生且排除原地址",
        "RuntimeDownloadConfig.orderUrls(listOf(url), proxy)" in src
        and ".filter { it != url" in src,
    )
    # 首次请求必须是 false（非代理）：sources 的第一个元素
    first_add = re.search(r"sources\.add\(([^)]*)\)", src)
    check(
        "第一个来源标记为非代理",
        bool(first_add) and first_add.group(1).strip().endswith("false"),
        first_add.group(1) if first_add else "not found",
    )
    check("降级受设置项开关控制", "allowProxyFallback" in src and "proxyFallback" in read(CHECKER))

    print("== 2. 验签必须在解析/使用之前，且失败不降级 ==")
    verify_pos = src.find("if (!verify(ctx, body, sigBody))")
    ok_pos = src.find("return Result.Ok")
    check("fetch 内先验签再返回", 0 < verify_pos < ok_pos, f"verify={verify_pos} ok={ok_pos}")
    check("验签失败抛 SecurityException", "SecurityException(\"update-index signature mismatch" in src)
    # SignedIndex 自己不解析 JSON：解析只能在验签之后由调用方做
    check("抓取层不解析 JSON", "JSONObject(" not in src)
    # 缓存内容也要再验一次（filesDir 理论上可被 root 改）
    check("缓存二次验签", "cached index signature invalid" in read(CHECKER))

    print("== 3. https 强制 ==")
    check("存在 scheme 白名单函数", "isAllowedScheme" in src)
    check("非 https 且非用户 http 代理时拒绝", "refusing non-https index source" in src)

    print("== 4. 索引生成：每个资产都必须带 sha256 ==")
    w = read(WRITER)
    check("资产包含 sha256 字段", '"sha256": sha256_of(path)' in w)
    check("缺 sha 的资产被丢弃（App 侧亦拒绝）", "if (name.isBlank() || sha.isBlank()) return null" in read(
        os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update/AppUpdate.kt"),
    ))
    check("重复 (variant,abi,package) 直接失败", "duplicate asset for" in w)

    print()
    if failures:
        print(f"FAILED ({len(failures)}): {failures}")
        return 1
    print("All index-security checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
