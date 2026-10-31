#!/usr/bin/env python3
"""内置插件装配护栏（v1.5.0）。

## 两类内置插件，两套装配规则

**A. 宿主兼容层**（narrow-sidebar / narrow-settings / narrow-rightbar / chat-perf）
—— 必须恒开，继续以「assets 目录 + EngineConfig 部署清单 + overlay insert」三件套
装配。三处必须同时满足，缺任何一处都静默失效：

1. `assets/android-plugins/<pkg>/` 三个文件齐全（缺 → client-modules 报
   "declares dsh.client but exports no ./client bundle" 或 "loaded without
   registering"）；
2. `EngineConfig` 的部署清单里有这个目录（否则文件不会落到 engine/ 下）；
3. `android-overlay.yml` 的 insert 段里有 entry 指向它。

**B. 用户态插件**（@xiaopb/dsh-mobile-layout，v1.5.0 起）
—— 用户可开关/升级/卸载，因此**不能**走 overlay（overlay 最后应用、每次启动
重写，会盖掉用户的禁用）。装配链路已改为：

1. CI 用 `scripts/pack-mobile-layout.sh` 把上游 Release 的 tgz 原样放进
   `assets/mobile-layout/`（含 bundle.json）；
2. App 首启 `ProfilePluginProvisioner` 跑
   `dsh plugin --profile web add <tgz>` 装进 profile；
3. 之后由 WebUI 插件管理页接管。

历史教训（v1.4.47 ~ v1.4.5x）：mobile-layout 曾被直接内置进 assets 并由 overlay
insert 强制挂载 —— 插件页里既看不到也无法关闭，用户的禁用被每次启动覆盖。

它的视觉/交互契约（抽屉样式、设置页横向页签、单测等）由**插件仓库自己的 CI**
（`npm run check`）把关，不在本探针重复；本探针只守「装配链路没被改回内置」。
"""
import json
import os
import re
import sys
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "android-plugins")
CONFIG = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "EngineConfig.kt")
SUPERVISOR = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                          "engine", "EngineSupervisor.kt")
PROVISIONER = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                           "engine", "ProfilePluginProvisioner.kt")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")

passed = 0
failed = 0
skipped = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


def skip(name, detail=""):
    global skipped
    skipped += 1
    print("SKIP  %s%s" % (name, ("  -> " + detail) if detail else ""))


cfg = open(CONFIG, encoding="utf-8").read()

# ── A. 宿主兼容层：三件套 + 部署清单 + overlay entry ─────────────────
CLIENT_PACKAGES = ["narrow-sidebar", "narrow-settings", "narrow-rightbar",
                   "chat-perf"]

for pkg in CLIENT_PACKAGES:
    d = os.path.join(ASSETS, pkg)
    for f in ("package.json", "index.js", "client.js"):
        check("%s: 文件 %s 存在" % (pkg, f), os.path.isfile(os.path.join(d, f)))
    check("%s: 在部署清单里" % pkg, '"%s" to listOf(' % pkg in cfg)
    check("%s: overlay 有 insert entry" % pkg,
          re.search(r"name: \./android-plugins/%s/index\.js" % re.escape(pkg), cfg) is not None,
          "缺 entry → 文件落地但引擎不挂载，插件静默不生效")

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

# ── v1.4.47 重叠插件互斥（narrow-* 在 mobile-layout 激活时让位）────────
# mobile-layout 现在是用户态插件，可能装着也可能被用户关掉；让位逻辑不变。
for pkg in ("narrow-sidebar", "narrow-settings"):
    src = open(os.path.join(ASSETS, pkg, "client.js"), encoding="utf-8").read()
    check("%s: mobile-layout 激活时让位" % pkg,
          'hasAttribute("data-dshm-mode")' in src,
          "两者同时生效 → 侧栏既被要求全宽又被做成抽屉")

# ── B. 用户态插件装配链路 ─────────────────────────────────────────────
# 1) 不得回退到内置：assets 散文件、部署清单、overlay insert 三处都要干净。
check("mobile-layout: 不再以散文件内置（assets/android-plugins/mobile-layout 不存在）",
      not os.path.isdir(os.path.join(ASSETS, "mobile-layout")),
      "散文件内置 = 靠 overlay 挂载 = 插件页无法开关")
check("mobile-layout: EngineConfig 部署清单里没有它",
      '"mobile-layout" to listOf(' not in cfg)
check("mobile-layout: overlay 不再 insert 它",
      "./android-plugins/mobile-layout/index.js" not in cfg,
      "overlay 最后应用 → 会覆盖用户在插件页的禁用")

# 2) 预装器注册表 + boot 时序。
prov = open(PROVISIONER, encoding="utf-8").read() if os.path.isfile(PROVISIONER) else ""
check("mobile-layout: ProfilePluginProvisioner 已注册", "@xiaopb/dsh-mobile-layout" in prov)
check("mobile-layout: 注册的资产目录与 assets 约定一致", '"mobile-layout"' in prov)
sup = open(SUPERVISOR, encoding="utf-8").read()
check("mobile-layout: boot 序列调用预装器（spawn 之前）",
      "ProfilePluginProvisioner.ensureAll" in sup)
check("mobile-layout: 预装在 spawnEngine 之前",
      sup.find("ProfilePluginProvisioner.ensureAll") < sup.find("spawnEngine(bootId)"),
      "引擎运行中改 profile 会被退出时的写回吞掉")

# 2b) 与设置页「官方推荐手机 UI」的接线（v1.4.67 的两套竖屏方案互斥）。
#     mobile-layout 离开 overlay 之后，那个开关必须能落到 bundles 上，否则
#     「开」= 三件套禁用 + 没有任何布局 = 半残状态。
check("mobile-layout: 首装开关跟随「官方推荐手机 UI」",
      "officialMobileUiEnabled" in prov,
      "开关为关（默认）时只装不挂载，避免顶掉 narrow-* 三件套")
check("mobile-layout: overlay 里的三件套仍受同一开关控制",
      "OO_NARROW_DISABLED_OO" in cfg)
check("mobile-layout: 已移除失效的 OO_OFFICIAL_DISABLED_OO 占位符",
      "OO_OFFICIAL_DISABLED_OO" not in cfg,
      "insert 行已删，占位符还留着会让人误以为还能启用")
appearance = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/AppearanceActivity.kt")
app = open(appearance, encoding="utf-8").read() if os.path.isfile(appearance) else ""
check("mobile-layout: 设置页拨开关会请求同步 bundles",
      "ProfilePluginProvisioner.requestEnabled" in app,
      "用户显式动作 → 记待办，下次启动在引擎停止时落地")
check("mobile-layout: 待办在 boot 时落地（applyPending）", "applyPending" in prov)

# 3) 构建期资产接线（CI 传参 + 本地覆盖位）。
gradle = open(GRADLE, encoding="utf-8").read()
check("mobile-layout: gradle 支持 -PmobileLayoutTgzDir", "mobileLayoutTgzDir" in gradle)
check("mobile-layout: 预留本地覆盖目录 local-overrides/mobile-layout",
      "local-overrides/mobile-layout" in gradle,
      "本地编译测试放 tgz 即可覆盖，无需改 CI")

# 4) 若资产已就位（CI 打包后 / 本地覆盖 / 预置），校验 bundle 契约。
CANDIDATES = [
    os.environ.get("DSH_MOBILE_LAYOUT_ASSET"),
    os.path.join(ROOT, "local-overrides", "mobile-layout"),
    os.path.join(ROOT, "app", "src", "main", "assets", "mobile-layout"),
    os.path.join(ROOT, "build", "generated", "runtimeAssets", "mobile-layout"),
]
asset_dir = next((p for p in CANDIDATES if p and os.path.isdir(p)), None)
if asset_dir is None:
    skip("mobile-layout: tgz 资产契约校验", "尚无打包产物（CI 会先跑 pack-mobile-layout.sh）")
else:
    meta_path = os.path.join(asset_dir, "bundle.json")
    check("mobile-layout: bundle.json 存在", os.path.isfile(meta_path), asset_dir)
    if os.path.isfile(meta_path):
        meta = json.load(open(meta_path, encoding="utf-8"))
        tgz = os.path.join(asset_dir, meta.get("tgz", ""))
        check("mobile-layout: metadata 字段完整",
              all(meta.get(k) for k in ("pkg", "version", "tgz", "sha256")))
        check("mobile-layout: tgz 存在", os.path.isfile(tgz), tgz)
        if os.path.isfile(tgz):
            with tarfile.open(tgz, "r:gz") as tf:
                names = tf.getnames()
                check("mobile-layout: tgz 内含 cordis.patch.yml（bundle 自挂载）",
                      "package/cordis.patch.yml" in names,
                      "缺 patch 层 → 装进 profile 也不挂载，插件页显示但界面无变化")
                try:
                    pj = json.load(tf.extractfile("package/package.json"))
                except KeyError:
                    pj = {}
                check("mobile-layout: dsh.bundle.patch 指向 ./cordis.patch.yml",
                      ((pj.get("dsh") or {}).get("bundle") or {}).get("patch")
                      == "./cordis.patch.yml")
                check("mobile-layout: 包内版本与 metadata 一致",
                      pj.get("version") == meta.get("version"),
                      "%s vs %s" % (pj.get("version"), meta.get("version")))

# ── 内置插件不得在客户端引用未打包的外部依赖 ─────────────────────────
for pkg in CLIENT_PACKAGES:
    src = open(os.path.join(ASSETS, pkg, "client.js"), encoding="utf-8").read()
    check("%s: client.js 无裸 import('包名')" % pkg,
          re.search(r"""\bimport\s*\(\s*['"][^./]""", src) is None,
          "浏览器端无 node_modules 解析，裸包名 import 必然失败")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d / 跳过 %d" % (passed, failed, skipped))
sys.exit(1 if failed else 0)
