#!/usr/bin/env python3
"""签名不一致的失败文案必须带两边指纹（issue #94）。

## 现场

用户装的是 1.4.41 调试签名包，App 内「检查更新」提示 v1.4.76，从发布页下载的是
**正式签名**包，覆盖安装被系统拒绝：

    INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package app.dsh.mobile
    signatures do not match newer version; ignoring!

判定逻辑本来就是对的（两个包确实不同钥），错的是**文案**：只说「签名与已安装
应用不一致」，用户只会以为包坏了或没下完，既想不到「我装的是调试包」，也无法
提供任何可排查的信息。

## 守什么

1. 判定不一致时把两边证书指纹前 8 位记下来（ApkInstaller.lastMismatch），
   而不是只在 logcat 里说一句。
2. 下载失败文案用带 %1$s 的新串，把指纹塞进去；指纹为空时才退回旧串
   （不能把空值硬拼进界面）。
3. 六套 locale 都要有这条新串且带占位符，否则 i18n parity 会红。
4. 旧串 app_update_err_signature 仍保留 —— 它是回退路径，删了就崩。

## 注释处理

只按行首 // 过滤，不做块注释剥离（本文件的判据都是代码标识符，我的注释里
不写这些字面量，足够）。这是刻意的简化：正则里带反斜杠极易在多层转义下写坏。

跑法：python scripts/probes/_test_update_signature_hint.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
UPDATE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update")
RES = os.path.join(ROOT, "app/src/main/res")

checks = 0
failed = 0


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + msg)
    else:
        failed += 1
        print("  FAIL " + msg)


def strip_comments(src):
    keep = [ln for ln in src.splitlines() if not ln.lstrip().startswith("//")]
    return chr(10).join(keep)


def main():
    inst = open(os.path.join(UPDATE, "ApkInstaller.kt"), encoding="utf-8").read()
    inst_body = strip_comments(inst)

    ok("var lastMismatch" in inst_body, "[A1] ApkInstaller 暴露 lastMismatch")
    ok("digestPair(installed, archive)" in inst_body, "[A2] 失败分支真的写入指纹对照")
    ok("private set" in inst_body, "[A3] 外部只读（private set），调用方不能伪造")
    ok("take(8)" in inst_body, "[A4] 只取前 8 位（够区分渠道，不泄露完整证书）")

    svc = open(os.path.join(UPDATE, "AppUpdateDownloadService.kt"), encoding="utf-8").read()
    svc_body = strip_comments(svc)
    ok("ApkInstaller.lastMismatch" in svc_body, "[B1] 下载服务读取 lastMismatch")
    ok("app_update_err_signature_detail" in svc_body, "[B2] 失败文案用带指纹的新串")
    ok("mismatch.isBlank()" in svc_body, "[B3] 指纹为空时退回旧串，不硬拼空值")
    ok("app_update_err_signature)" in svc_body, "[B4] 旧串仍在（回退路径）")

    for loc in ("values", "values-zh-rCN", "values-de", "values-ru", "values-th", "values-pt-rBR"):
        f = os.path.join(RES, loc, "strings.xml")
        if not os.path.isfile(f):
            ok(False, "[C] " + loc + " 缺 strings.xml")
            continue
        text = open(f, encoding="utf-8").read()
        m = re.search('<string name="app_update_err_signature_detail">([^<]*)</string>', text)
        ok(m is not None, "[C] " + loc + " 有 app_update_err_signature_detail")
        if m:
            ok("%1$s" in m.group(1), "[C] " + loc + " 的文案带 %1$s 占位符")

    print("")
    print("checks=%d failed=%d" % (checks, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
