#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""#108 第二层：TRANSPORT 文案必须带请求体字节数（只在诊断层，不改控制流）。

## 背景

第一层（2026-11 上旬）只把底层异常 code 拼进 TRANSPORT 文案，回答了「哪类网络错误」。
但 #108 的真实触发条件是**大会话**（40.4 MB / 3349 turn）：同一句「在吗」，
小会话一次过、大会话 5 次全 TRANSPORT。少了「请求体多大」这一项，用户仍无法判断
是网络抖还是会话已经大到发不出去。

第二层在发请求前采集 extensions.payload.length，抛 TRANSPORT 时拼成
「... | request body 40.2 MB」。**不动错误码、不动 cause、不动重试策略。**

## 本探针守什么（都在补丁源脚本上判定，不需要真实 runtime）

A. 采集点必须在 fetch **之前**（之后拿不到 payload）
B. 采集的值必须真的拼进 TRANSPORT 文案（只采集不用 = 白采集）
C. 采集与拼接都必须幂等（重复打补丁不能叠两层）
D. 不得改动 retry 相关代码（本补丁只做诊断）

## 反向验证

  B 段把拼接删掉            -> B 红
  A 段把采集挪到 fetch 之后 -> A 红
"""
import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
SCRIPT = os.path.join(ROOT, "scripts", "patch-llm-deepseek-transport.py")

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


def main():
    src = io.open(SCRIPT, encoding="utf-8").read()

    print("[A] 采集点在 fetch 之前")
    ok("PAYLOAD_ANCHOR" in src, "A1 定义了 payload 采集锚点")
    ok("requestBodyBytes = extensions.payload.length" in src, "A2 采集的是 payload 长度")
    m = re.search(r"PAYLOAD_PATCHED = \(([\s\S]{0,400}?)\n\)", src)
    blk = m.group(1) if m else ""
    i_bytes = blk.find("requestBodyBytes")
    i_fetch = blk.find("await fetch")
    ok(i_bytes >= 0 and i_fetch >= 0 and i_bytes < i_fetch,
       "A3 采集行在 fetch 行之前（之后拿不到 payload）")

    # beta.18 发布时踩到的假绿：本探针当时全绿，但 CI 的 runtime-build 产物只打了
    # 第一层。原因是 patch_text 有两条路径 —— 上游已 transportDetail 时直接转
    # patch_payload_bytes，而上游还没有第一层时打完第一层就 return。CI 的 npm
    # 原料正好属于后者，第二层永远补不上，而探针只看补丁源里有采集与拼接，
    # 两条路径的差异完全没覆盖。所以补一条结构判据。
    m_tx = re.search(r"def patch_text\([\s\S]{0,2600}?\n\ndef ", src)
    tx = m_tx.group(0) if m_tx else ""
    ok(tx != "", "A4 找到 patch_text 函数体")
    # 只看「第一层分支」那一段：从 ANCHOR 检查开始到 return text, applied。
    # 不能在整个函数体里找 —— 开头那个 if 分支也调 patch_payload_bytes，
    # 用它会让「末尾漏调」照样绿（第一版就这么假绿了）。
    seg_start = tx.find("if ANCHOR not in text")
    seg_end = tx.find("return text, applied")
    seg = tx[seg_start:seg_end] if seg_start >= 0 and seg_end > seg_start else ""
    ok(seg != "", "A4b 找到第一层分支区间")
    ok(
        "patch_payload_bytes(text)" in seg,
        "A4c 第一层分支在 return 之前也补第二层（CI 的 npm 原料走这条，漏了等于没修）",
    )

    print("[B] 采集值真的拼进文案")
    ok("TRANSPORT_WITH_BYTES_NEW" in src, "B1 定义了带字节数的新文案")
    ok("formatBodyBytes(requestBodyBytes)" in src, "B2 文案里真的插入了字节数")
    ok("request body " in src, "B3 文案里有人能看懂的字段名")

    print("[C] 幂等")
    ok("TRANSPORT_TAIL_MARKER" in src and "already-has-body-size" in src, "C1 有幂等短路")
    # 窗口要够宽：函数体（含三条 SystemExit 说明）接近 1 KB，900 字符会截断。
    m2 = re.search(r"def patch_payload_bytes[\s\S]{0,2000}?return text, applied", src)
    body_fn = m2.group(0) if m2 else ""
    ok("if TRANSPORT_TAIL_MARKER in text" in body_fn, "C2 短路在函数最前面（先判再改）")

    print("[D] 不动控制流")
    fn_region = re.search(r"def patch_payload_bytes[\s\S]{0,2000}", src)
    region = fn_region.group(0) if fn_region else ""
    for kw in ("retryPolicy", "maxRetries", "retryableCodes"):
        ok(kw not in region, "D 补丁函数不碰 %s（只做诊断）" % kw)

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())