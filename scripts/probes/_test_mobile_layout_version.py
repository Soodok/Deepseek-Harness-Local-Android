#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""内置 mobile-layout 的版本必须落在「已适配当前引擎」的区间（issue #122）。

## 为什么单独守版本

插件设置页错位（#122）不是 App 代码的 bug —— 内置的用户态插件 tgz 打进 APK，
装到手机上由 WebUI 插件管理页接管。**只要内置版落在未适配的区间，新装用户
第一次打开设置页就是坏的**，而 App 侧任何探针都看不见这件事。

上游 README 的兼容表（0.4.12 的 README，逐版本登记）明确写：

    0.4.4 – 0.4.6 | 0.2.2-rc.1 未登记 | ❌ 不兼容
      实测：布局模式选择器失效、原生预设设置页变悬浮小窗且标题/页签/内容裁切、
      设置页关不掉；请升级到 0.4.9
    0.4.7 起     | 0.2.2-rc.1 compatible | ✅

而本仓库 2026-11-09 打进包里的正是 **0.4.5**（vendor/mobile-layout/bundle.json），
恰好落在「❌ 不兼容」那一行。这解释了 #122 里「Our Free Model 插件页概率性错位」
—— 官方弹窗被当成 legacy 接管但几何算错。

## 本探针守什么

A. bundle.json 与 tgz 文件名里的版本必须一致（防止只改一处）
B. 版本必须 ≥ MIN_VERSION（0.4.7，首个适配 rc.1 的版本）
C. tgz 的 sha256 必须与 bundle.json 声明一致（内容没被悄悄替换）
D. 插件必须自带 cordis.patch.yml 且 package.json 指向它（否则装了不挂载）

## 反向验证

  把 MIN_VERSION 抬到 0.5.0 → B 红（证明判据真的在比大小）
  改 bundle.json 的 sha256   → C 红
  删 tgz 里的 cordis.patch.yml → D 红
"""
import hashlib
import io
import json
import os
import re
import sys
import tarfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
ASSET = os.path.join(ROOT, "vendor", "mobile-layout")
BUNDLE = os.path.join(ASSET, "bundle.json")

# 上游 README 兼容表：0.4.7 是首个对 0.2.2-rc.1 声明 compatible 的版本。
MIN_VERSION = (0, 4, 7)

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


def parse_ver(s):
    m = re.match(r"^(\d+)\.(\d+)\.(\d+)$", s.strip())
    return tuple(int(x) for x in m.groups()) if m else None


def main():
    print("[A] bundle.json 与 tgz 名一致")
    ok(os.path.isfile(BUNDLE), "A1 bundle.json 存在")
    if not os.path.isfile(BUNDLE):
        print("")
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    bundle = json.loads(io.open(BUNDLE, encoding="utf-8").read())
    ver = bundle.get("version", "")
    tgz = bundle.get("tgz", "")
    ok(tgz == "xiaopb-dsh-mobile-layout-%s.tgz" % ver, "A2 tgz 文件名与 version 一致（%s）" % ver)

    print("[B] 版本必须已适配当前引擎")
    pv = parse_ver(ver)
    ok(pv is not None, "B1 版本号可解析（%s）" % ver)
    ok(
        pv is not None and pv >= MIN_VERSION,
        "B2 版本 >= %s（rc.1 的首个兼容版本；0.4.4–0.4.6 实测设置页错位）"
        % ".".join(str(x) for x in MIN_VERSION),
    )

    print("[C] 内容指纹")
    path = os.path.join(ASSET, tgz)
    ok(os.path.isfile(path), "C1 tgz 存在")
    if os.path.isfile(path):
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        actual = h.hexdigest()
        ok(
            actual == bundle.get("sha256"),
            "C2 sha256 与 bundle.json 一致（%s）" % actual[:16],
        )

        print("[D] 挂载契约")
        try:
            tf = tarfile.open(path, "r:gz")
        except Exception as e:
            ok(False, "D1 tgz 可解（%s）" % e)
            tf = None
        if tf is not None:
            names = tf.getnames()
            ok(any(n.endswith("package/cordis.patch.yml") for n in names),
               "D1 tgz 里有 package/cordis.patch.yml（bundle 自挂载的唯一依据）")
            pj = [n for n in names if n.endswith("package/package.json")]
            if pj:
                data = json.loads(tf.extractfile(pj[0]).read().decode("utf-8"))
                patch = (data.get("dsh") or {}).get("bundle", {}).get("patch", "")
                ok(patch == "./cordis.patch.yml", "D2 package.json 的 dsh.bundle.patch 指向它")
            else:
                ok(False, "D2 tgz 里没有 package/package.json")
            tf.close()

    print("")
    if failed:
        print("FAILED (%d/%d)" % (failed, checks))
        return 1
    print("All passed (%d checks)" % checks)
    return 0


if __name__ == "__main__":
    sys.exit(main())
