#!/usr/bin/env python3
"""逆向类扩展接入的静态契约探针。

守七类不变量（每条都能反向改坏变红）：
  A. 逆向条目都已录入且中英 catalog 结构一致
  B. linuxOnly 标记只打在「Termux 真的没有这个包」的条目上
  C. linuxOnly 在数据层与 UI 层都真的被拦住（不是只加了个字段）
  D. 新条目的 iconRes 都有 drawable
  E. 分类字符串五语种齐全、categoryColor 有 reverse 分支
  F. Alpine 映射与 UNAVAILABLE 表在真实 APKINDEX 里成立
  G. 反例守卫：探针自身能识别退化

为什么需要 B/C 两组：`linuxOnly` 是个新增字段，最容易出的问题正是
「字段加了、解析漏了」或「UI 灰了、AI 通道照样能装」。这两组分别锁住。
"""
import gzip
import io
import json
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
EXT = os.path.join(ROOT, "app", "src", "main", "assets", "extensions")
DRAW = os.path.join(ROOT, "app", "src", "main", "res", "drawable")
BRIDGE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "LinuxExtensionBridge.kt")
MGR = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                   "engine", "ExtensionManager.kt")
ACT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                   "ExtensionStoreActivity.kt")

RE_IDS = ["radare2", "binwalk", "yara", "patchelf", "upx", "hexdump",
          "elfutils", "sleuthkit", "ltrace", "net-tools", "apk-reverse"]
LINUX_ONLY = ["coverage", "cross-arm-none-eabi", "cross-aarch64-elf"]

fails = []
checks = [0]


def ok(name, cond, hint=""):
    checks[0] += 1
    if cond:
        print("  [OK]   %s" % name)
    else:
        print("  [FAIL] %s  -> %s" % (name, hint))
        fails.append(name)


def read(p):
    return io.open(p, encoding="utf-8").read()


cat = json.loads(read(os.path.join(EXT, "catalog.json")))
cat_zh = json.loads(read(os.path.join(EXT, "catalog.zh-rCN.json")))
items = {i["id"]: i for i in cat["items"]}
items_zh = {i["id"]: i for i in cat_zh["items"]}

print("\n[A] 逆向条目都已录入且结构合法")
for rid in RE_IDS:
    ok("catalog has '%s'" % rid, rid in items)
    if rid in items:
        it = items[rid]
        ok("'%s' packages non-empty" % rid, bool(it.get("packages")))
        ok("'%s' bins non-empty" % rid, bool(it.get("bins")))
        ok("'%s' categoryKey is reverse" % rid, it.get("categoryKey") == "reverse",
           str(it.get("categoryKey")))
ok("中文 catalog 条目数一致", len(cat["items"]) == len(cat_zh["items"]),
   "%d vs %d" % (len(cat["items"]), len(cat_zh["items"])))
ok("中英文 id 顺序一致",
   [i["id"] for i in cat["items"]] == [i["id"] for i in cat_zh["items"]])
ok("中英文 packages 逐条一致",
   all(a["packages"] == b["packages"] for a, b in zip(cat["items"], cat_zh["items"])))
ok("中英文 linuxOnly 逐条一致",
   all(a.get("linuxOnly") == b.get("linuxOnly") for a, b in zip(cat["items"], cat_zh["items"])))

print("\n[A1] 中英 catalog 的分类显示名一一对应")
# 为什么需要这条：`category` 是**本地化显示名**，UI 直接拿它当小节标题
# （ExtensionStoreActivity.kt:179 比较 ext.category 是否变化来开新小节）。
# 我恢复 linuxOnly 条目时把中文写成了「覆盖率报告 / ARM 裸机工具链 /
# AArch64 裸机工具链」，而英文侧仍是 Debugging / Cross compiling ——
# 结果同一个分类在中英界面下小节数不同，屏幕上真的多出三块独立小节。
# 探针当时没有任何一条断言覆盖「同名分类的翻译一致性」，属于漏检查。
CAT_OF_KEY = {}
for it in cat["items"]:
    CAT_OF_KEY.setdefault(it.get("categoryKey", ""), set()).add(it["category"])
CAT_OF_KEY_ZH = {}
for it in cat_zh["items"]:
    CAT_OF_KEY_ZH.setdefault(it.get("categoryKey", ""), set()).add(it["category"])
for key in sorted(set(CAT_OF_KEY) | set(CAT_OF_KEY_ZH)):
    if not key:
        continue
    en = CAT_OF_KEY.get(key, set())
    zh = CAT_OF_KEY_ZH.get(key, set())
    ok("categoryKey '%s' uses exactly one English category name" % key, len(en) == 1, str(en))
    ok("categoryKey '%s' uses exactly one Chinese category name" % key, len(zh) == 1, str(zh))
    if len(en) == 1 and len(zh) == 1:
        ok("categoryKey '%s' EN/ZH category names are distinct" % key,
           list(en)[0] != list(zh)[0], "%r == %r" % (list(en)[0], list(zh)[0]))

print("\n[A2] bins 声明与包实际提供的二进制一致")
# 为什么必须查内容而不是只查非空：`installPhase`（ExtensionManager.kt:771）
# 里 bins **全部**缺失时会直接判安装失败。写错一个名字不会报编译错，
# 只会让用户看到「Extension verification failed: none of the executables …」。
BINS_FIXTURE = os.path.join(ROOT, "scripts", "probes", "_data_termux_pkg_bins.txt")
provided = {}
if os.path.isfile(BINS_FIXTURE):
    for line in io.open(BINS_FIXTURE, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        parts = line.rstrip("\n").split("\t")
        provided[parts[0]] = [b for b in (parts[1] if len(parts) > 1 else "").split(",") if b]
    ok("bins fixture parsed", len(provided) >= 20, "parsed %d packages" % len(provided))
    for rid in RE_IDS + LINUX_ONLY:
        it = items.get(rid)
        if not it:
            continue
        # linuxOnly 条目的包名是 **Alpine** 名字（gcovr / gcc-arm-none-eabi …），
        # fixture 只覆盖 Termux 包，两者不是同一套命名，不能拿来比对 ——
        # 否则会把「fixture 里没有」误判成「声明错了」。
        if it.get("linuxOnly"):
            print("  [SKIP] '%s' is linuxOnly (Alpine package names, not in the Termux fixture)" % rid)
            continue
        # 该条目的每个声明二进制都必须由它的**某个**包真实提供
        pool = set()
        for pkg in it["packages"]:
            pool |= set(provided.get(pkg, []))
        if not pool:
            # 全部包都不带二进制（纯库/头文件条目）→ 只能要求 bins 为空
            ok("'%s' declares no bins (none of its packages ship binaries)" % rid,
               not it["bins"], "declared: %s" % it["bins"])
            continue
        unknown = [b for b in it["bins"] if b not in pool]
        ok("'%s' bins all really provided by its packages" % rid, not unknown,
           "not in %s" % sorted(pool)[:8])
else:
    print("  [SKIP] %s 不存在" % BINS_FIXTURE)

print("\n[B] linuxOnly 标记只打在 Termux 缺包的条目上")
TERMUX = os.environ.get("DSH_TERMUX_INDEX")
tm = None
if TERMUX and os.path.isfile(TERMUX):
    names = set()
    if TERMUX.endswith(".gz"):
        with gzip.open(TERMUX, "rt", encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("Package: "):
                    names.add(line[9:].rstrip("\n"))
    else:
        with io.open(TERMUX, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.startswith("Package: "):
                    names.add(line[9:].rstrip("\n"))
    tm = names
if tm is None:
    print("  [SKIP] $DSH_TERMUX_INDEX 未设置，跳过 Termux 索引核对")
    print("         -> 这不是通过，只是没验证；设置该变量可升为强制检查")
else:
    for eid in LINUX_ONLY:
        it = items.get(eid)
        if not it:
            ok("'%s' present" % eid, False)
            continue
        ok("'%s' marked linuxOnly" % eid, it.get("linuxOnly") is True)
        missing = [p for p in it["packages"] if p not in tm]
        ok("'%s' really absent from Termux (linuxOnly is justified)" % eid,
           bool(missing), "all packages present in Termux: %s" % it["packages"])
    bad = [iid for iid, it in items.items()
           if it.get("linuxOnly") and all(p in tm for p in it["packages"])]
    ok("no entry marked linuxOnly while installable from Termux", not bad, str(bad))

print("\n[C] linuxOnly 在数据层与 UI 层都真的被拦住")
mgr = read(MGR)
ok("Extension has linuxOnly field",
   re.search(r"val linuxOnly: Boolean = false", mgr) is not None)
ok("catalog parser reads linuxOnly",
   re.search(r'linuxOnly\s*=\s*o\.optBoolean\("linuxOnly"', mgr) is not None)
ok("installTask rejects linuxOnly",
   re.search(r"if \(ext\.linuxOnly\)\s*\{[^}]*throw IllegalStateException", mgr, re.S) is not None)
if "if (ext.linuxOnly)" in mgr and "if (ext.packages.isEmpty())" in mgr:
    ok("linuxOnly check runs after the packages-empty guard",
       mgr.index("if (ext.linuxOnly)") > mgr.index("if (ext.packages.isEmpty())"))
else:
    ok("linuxOnly check present alongside packages guard", False)

act = read(ACT)
ok("UI computes termuxUnavailable",
   "val termuxUnavailable = !linuxMode && ext.linuxOnly" in act)
ok("UI shows the linux-only state",
   "R.string.ext_state_linux_only) to getColor(COLOR_YELLOW)" in act)
ok("UI disables the action button for it",
   re.search(r"termuxUnavailable -> \{[^}]*isClickable = false", act, re.S) is not None)
ok("onAction guards linuxOnly with an explanation",
   re.search(r"if \(ext\.linuxOnly\)\s*\{[^}]*ext_state_linux_only_hint", act, re.S) is not None)

print("\n[D] 图标资源齐全")


def drawable_exists(res):
    """drawable 可以是 .xml 矢量，也可以是 .png/.webp 位图。

    ⚠️ 别只查 .xml —— `ic_ext_imagemagick` 就是 PNG，只查 XML 会把它误判成
    缺失（本探针第一版正是如此，报了一个并不存在的问题）。
    """
    for ext in (".xml", ".png", ".webp", ".jpg"):
        if os.path.isfile(os.path.join(DRAW, res + ext)):
            return True
    return False


for rid in RE_IDS + LINUX_ONLY:
    it = items.get(rid)
    if not it:
        continue
    res = it.get("iconRes", "")
    ok("'%s' icon '%s' exists" % (rid, res), bool(res) and drawable_exists(res))
all_icons = {i.get("iconRes") for i in cat["items"] if i.get("iconRes")}
missing_icons = sorted(r for r in all_icons if not drawable_exists(r))
ok("every catalog iconRes resolves", not missing_icons, str(missing_icons))

print("\n[E] 分类字符串与配色接线")
vals = os.path.join(ROOT, "app", "src", "main", "res")
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    p = os.path.join(vals, loc, "strings.xml")
    ok("%s has ext_cat_reverse" % loc, 'name="ext_cat_reverse"' in read(p))
ok("UI maps the reverse key to a colour",
   re.search(r'"reverse"\s*->\s*getColor\(R\.color\.reverse\)', act) is not None)
ok("legacy fallback knows reverse",
   re.search(r'getString\(R\.string\.ext_cat_reverse\)\s*->\s*"reverse"', act) is not None)
for pal in ("values", "values-night"):
    ok("%s defines color/reverse" % pal,
       'name="reverse"' in read(os.path.join(vals, pal, "colors.xml")))

print("\n[F] Alpine 映射与 UNAVAILABLE 表在真实 APKINDEX 里成立")
idx = os.path.join(ROOT, "app", "src", "main", "assets", "linux", "apkindex-names.txt")
if not os.path.isfile(idx):
    print("  [SKIP] apkindex-names.txt 不存在")
else:
    have = set()
    for line in io.open(idx, encoding="utf-8", errors="replace"):
        m = re.match(r"^([a-zA-Z0-9_.+-]+)-[0-9]", line.strip())
        if m:
            have.add(m.group(1))
    src = read(BRIDGE)
    # ⚠️ 切片起点必须锚在**声明**上，不能用裸 "ALPINE_NAMES" ——
    # 文件头 KDoc 里就写着「（[ALPINE_NAMES]）」，取到的是注释里那 49 个字符，
    # pairs 恒为空、下面的断言变成空转。反向验证 R5（把映射改到不存在的包）
    # 当初没变红就是因为这个，第一版探针是假绿的。
    # ★合并（2026-11-09）：表名从单发行版的 ALPINE_NAMES / UNAVAILABLE /
    #   ITEM_UNAVAILABLE 改成按发行版分组的 ALPINE_NAMES / ALPINE_UNAVAILABLE /
    #   ALPINE_ITEM_UNAVAILABLE（双发行版支持）。这里守的仍是 Alpine 那一侧。
    an = src[src.index("private val ALPINE_NAMES"):src.index("private val UBUNTU_NAMES")]
    pairs = re.findall(r'"([^"]+)"\s*to\s*listOf\(([^)]*)\)', an)
    ok("ALPINE_NAMES block actually parsed (guards against a silent empty slice)",
       len(pairs) >= 10, "parsed %d pairs" % len(pairs))
    bad = []
    for src_name, targets in pairs:
        for t in re.findall(r'"([^"]+)"', targets):
            if t not in have:
                bad.append("%s->%s" % (src_name, t))
    ok("all ALPINE_NAMES targets exist in the Alpine index", not bad, str(bad[:6]))

    un = src[src.index("private val ALPINE_UNAVAILABLE"):
             src.index("private val UBUNTU_UNAVAILABLE")]
    un_names = re.findall(r'^\s*"([^"]+)" to ', un, re.M)
    ok("UNAVAILABLE block actually parsed", len(un_names) >= 8, "parsed %d" % len(un_names))
    wrongly = [u for u in un_names if u in have]
    ok("no UNAVAILABLE entry actually exists in the Alpine index", not wrongly, str(wrongly))

    # 切片终点必须锚在**下一个表**上，不能锚在后面的函数文档：双发行版之后
    # ALPINE_ITEM_UNAVAILABLE 与 UBUNTU_ITEM_UNAVAILABLE 相邻，锚错会把 Ubuntu
    # 那张表也切进来（zig 只在 Ubuntu 侧不可用，会假红）。
    item_un = src[src.index("private val ALPINE_ITEM_UNAVAILABLE"):
                  src.index("private val UBUNTU_ITEM_UNAVAILABLE")]
    iu_ids = re.findall(r'^\s*"([^"]+)" to ', item_un, re.M)
    ok("ITEM_UNAVAILABLE block actually parsed", len(iu_ids) >= 2, "parsed %d" % len(iu_ids))
    ok("every ITEM_UNAVAILABLE id is a real catalog entry",
       all(i in items for i in iu_ids), str([i for i in iu_ids if i not in items]))
    for iid in iu_ids:
        it = items.get(iid)
        if not it:
            continue
        ok("'%s' unavailable is justified (a package is missing from Alpine)" % iid,
           any(p not in have for p in it["packages"]),
           "all present: %s" % it["packages"])

print("\n[G] 反例守卫（探针自身不能退化）")
ok("all RE_IDS are present",
   sum(1 for r in RE_IDS if r in items) == len(RE_IDS))
ok("reverse entries are NOT marked linuxOnly (both repos have them)",
   all(not items[r].get("linuxOnly") for r in RE_IDS if r in items))
actual_rev = sorted(i["id"] for i in cat["items"] if i.get("categoryKey") == "reverse")
ok("RE_IDS matches the actual reverse-keyed entries", actual_rev == sorted(RE_IDS),
   str(actual_rev))
ok("catalog grew beyond the pre-change 36 entries", len(cat["items"]) > 36,
   "now %d" % len(cat["items"]))

print("\n" + "-" * 68)
print("checks=%d failed=%d" % (checks[0], len(fails)))
print("RESULT: %s" % ("PASSED" if not fails else "FAILED: " + ", ".join(fails)))
sys.exit(1 if fails else 0)
