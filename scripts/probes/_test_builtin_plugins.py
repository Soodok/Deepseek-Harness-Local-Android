#!/usr/bin/env python3
"""内置客户端插件装配护栏（v1.4.47）。

## 背景

引擎的客户端插件包必须是「目录 + package.json + index.js + client.js」三件套，
且同时满足三处才能真的加载：

1. `assets/android-plugins/<pkg>/` 三个文件齐全（缺 → client-modules 报
   "declares dsh.client but exports no ./client bundle" 或 "loaded without
   registering"）；
2. `EngineConfig` 的部署清单里有这个目录（否则文件不会落到 engine/ 下）；
3. `android-overlay.yml` 的 insert 段里有 entry 指向它（否则引擎根本不知道
   这个包要挂到 profile 上）。

历史上踩过：只加了 assets 却忘了 overlay（插件静默不生效）、
package.json 写了 `dsh.bundle.patch` 但没部署 patch 文件（加载直接失败）。

## v1.4.47 新增

社区插件 `@xiaopb/dsh-mobile-layout` 内置。它与内置的 narrow-sidebar /
narrow-settings 功能重叠（一个把侧栏做成抽屉、一个把设置页全屏），必须让
两个 narrow 插件在它激活时让位，否则同一个侧栏会同时被要求「全宽」和「抽屉」。
让位条件是 `html[data-dshm-mode]` 存在。
"""
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "android-plugins")
CONFIG = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "EngineConfig.kt")

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


cfg = open(CONFIG, encoding="utf-8").read()

CLIENT_PACKAGES = ["narrow-sidebar", "narrow-settings", "narrow-rightbar",
                   "chat-perf", "mobile-layout"]

# ── 每个客户端包：三件套齐全 + 部署清单 + overlay entry ──────────────
for pkg in CLIENT_PACKAGES:
    d = os.path.join(ASSETS, pkg)
    for f in ("package.json", "index.js", "client.js"):
        check("%s: 文件 %s 存在" % (pkg, f), os.path.isfile(os.path.join(d, f)))
    check("%s: 在部署清单里" % pkg, '"%s" to listOf(' % pkg in cfg)
    check("%s: overlay 有 insert entry" % pkg,
          re.search(r"name: \./android-plugins/%s/index\.js" % re.escape(pkg), cfg) is not None,
          "缺 entry → 文件落地但引擎不挂载，插件静默不生效")

# ── package.json 形状：不能声明未部署的 bundle.patch ─────────────────
for pkg in CLIENT_PACKAGES:
    p = os.path.join(ASSETS, pkg, "package.json")
    if not os.path.isfile(p):
        continue
    pj = json.load(open(p, encoding="utf-8"))
    dsh = pj.get("dsh") or {}
    check("%s: package.json 未声明 bundle.patch（靠 overlay 挂载）" % pkg,
          "bundle" not in dsh,
          "声明了就会去找 cordis.patch.yml，没部署该文件 → 加载失败")
    check("%s: 声明了 dsh.client.platform=web" % pkg,
          (dsh.get("client") or {}).get("platform") == "web")
    check("%s: main 指向存在的文件" % pkg,
          os.path.isfile(os.path.join(ASSETS, pkg, pj.get("main", ""))),
          "main 写 lib/index.js 但文件平铺在包根 → 解析失败")
    client_export = ((pj.get("exports") or {}).get("./client"))
    ok_export = False
    if isinstance(client_export, str):
        ok_export = os.path.isfile(os.path.join(ASSETS, pkg, client_export))
    elif isinstance(client_export, dict):
        target = client_export.get("default")
        ok_export = bool(target) and os.path.isfile(os.path.join(ASSETS, pkg, target))
    check("%s: exports['./client'] 指向存在的文件" % pkg, ok_export)
    check("%s: 声明了 engines.dsh" % pkg,
          "dsh" in (pj.get("engines") or {}),
          "内置插件必须自证兼容的引擎版本，否则升级引擎时无门禁")

# ── v1.4.47 重叠插件互斥 ────────────────────────────────────────────
for pkg in ("narrow-sidebar", "narrow-settings"):
    src = open(os.path.join(ASSETS, pkg, "client.js"), encoding="utf-8").read()
    check("%s: mobile-layout 激活时让位" % pkg,
          'hasAttribute("data-dshm-mode")' in src,
          "两者同时生效 → 侧栏既被要求全宽又被做成抽屉")

# mobile-layout 必须是「只在竖屏接管」，横屏/桌面像素级不变
ml = open(os.path.join(ASSETS, "mobile-layout", "client.js"), encoding="utf-8").read()
check("mobile-layout: 用 data-dshm-mode 作用域隔离",
      'data-dshm-mode' in ml and "mobile" in ml,
      "无作用域会污染桌面/横屏布局")
check("mobile-layout: 只走官方槽位（不碰布局网格）",
      "shell.bottom" in ml and "shell.overlay" in ml,
      "直接改 computeColumns / 网格会与引擎升级冲突")
check("mobile-layout: 桌面宿主不激活",
      "HOST_DESKTOP" in ml)

# v1.4.49：**不改作者的视觉设计**。
# 内置的是社区插件，视觉语言是作者的设计哲学；我们只做「内置装配 + 与自家
# 窄屏插件互斥」两件事。曾试过给输入框加描边/改圆角，用户确认后要求恢复原样。
# 这条断言锁住「上游文件不被本地改写」：client.js 必须与 npm 发布版逐字节一致
# （装配侧只允许改 package.json 的 main/exports/engines 字段）。
UPSTREAM_SHA = "70645ad48f7adfcbfe226b358684dfe153c4d333960c7663bae24d15d99b580c"


# ── v1.4.50：设置页页签必须横向排列（用户报「其他设置项没了」）────────
# 引擎的 settings nav / navList 都是纵向 column。插件只改了高度和 overflow-x
# 却没改 flex-direction，结果 4 个页签竖排后被 56px 容器裁掉，只剩第一个可见。
ml_src = open(os.path.join(ASSETS, "mobile-layout", "client.js"), encoding="utf-8").read()
check("mobile-layout: settings-nav 显式横向",
      "[data-dshm-settings-nav] {" in ml_src
      and ml_src.count("flex-direction: row !important;") >= 2,
      "nav 保持 column → navList 被挤成 0 高，页签全被裁掉")
check("mobile-layout: settings-tabs 禁止换行",
      "flex-wrap: nowrap !important;" in ml_src,
      "允许换行时第二行又被 56px 高度裁掉")

# ── 内置插件不得在客户端引用未打包的外部依赖 ─────────────────────────
for pkg in CLIENT_PACKAGES:
    src = open(os.path.join(ASSETS, pkg, "client.js"), encoding="utf-8").read()
    check("%s: client.js 无裸 import('包名')" % pkg,
          re.search(r"""\bimport\s*\(\s*['"][^./]""", src) is None,
          "浏览器端无 node_modules 解析，裸包名 import 必然失败")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
