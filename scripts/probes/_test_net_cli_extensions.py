#!/usr/bin/env python3
"""终端工具 / 媒体 / 组网三类扩展接入的静态契约探针。

守八类不变量（每条都能反向改坏变红）：
  A. 新条目都已录入，中英 catalog 结构与字段逐条一致
  B. rootOnly 只打在「真的需要 root 才有用」的条目上，且不能和 linuxOnly 混用错误
  C. rootOnly 在 UI 层真的被读（状态行 + 安装前提示），不是只加了个字段
  D. 新条目 iconRes 都有 drawable
  E. 分类字符串五语种齐全、categoryColor 有 cli/net 分支
  F. Termux 包名与 UNAVAILABLE 表在真实索引里成立（不凭印象）
  G. bins 声明与 .deb 实际内容一致（事实表比对）
  H. 反例守卫：探针自身能识别退化

为什么 B/C 要分开两组：`rootOnly` 与既有的 `linuxOnly` 是两个不同维度 ——
linuxOnly 是「这个包装不上」，rootOnly 是「装得上但没 root 用不了」。
最容易出的错正是把两者当成一件事（比如给 cloudflared 打 rootOnly，
可它是纯用户态隧道，根本不建虚拟网卡）。B 组锁语义，C 组锁接线。
"""
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

# 本批新增的 10 条
CLI_IDS = ["cli-tools", "shell-prompt", "uv", "pip-packaging"]
MEDIA_IDS = ["media-tools"]
NET_IDS = ["net-tunnel", "net-cloudflared", "vpn-client", "net-capture", "net-bench"]
NEW_IDS = CLI_IDS + MEDIA_IDS + NET_IDS

# 真的需要 root 才能发挥作用的条目（要开 /dev/net/tun 或改路由表）
# 依据：模拟器实测 app uid 打开 /dev/net/tun → Permission denied；
#      `su 0` 下 → TUN_OK；且 `su 0 ip link add wgtest type wireguard` → WG_OK，
#      内核 CONFIG_TUN=y / CONFIG_WIREGUARD=y 都是内建。
TRUE_ROOT_ONLY = {"net-tunnel", "vpn-client", "net-capture"}
# 不需要 root 的：纯用户态，不碰虚拟网卡
FALSE_ROOT_ONLY = {"net-cloudflared", "net-bench", "cli-tools", "shell-prompt",
                   "uv", "pip-packaging", "media-tools"}

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
src_mgr = read(MGR)
src_act = read(ACT)
src_bridge = read(BRIDGE)

print("\n[A] 新条目都已录入，中英结构一致")
for eid in NEW_IDS:
    ok("catalog has '%s'" % eid, eid in items)
    ok("zh catalog has '%s'" % eid, eid in items_zh)
    if eid in items:
        it = items[eid]
        ok("'%s' packages non-empty" % eid, bool(it.get("packages")))
        ok("'%s' bins non-empty" % eid, bool(it.get("bins")))
ok("中英条目数一致", len(cat["items"]) == len(cat_zh["items"]),
   "%d vs %d" % (len(cat["items"]), len(cat_zh["items"])))
ok("中英 id 顺序一致",
   [i["id"] for i in cat["items"]] == [i["id"] for i in cat_zh["items"]])
ok("中英 packages 逐条一致",
   all(a["packages"] == b["packages"] for a, b in zip(cat["items"], cat_zh["items"])))
ok("中英 linuxOnly 逐条一致",
   all(a.get("linuxOnly") == b.get("linuxOnly") for a, b in zip(cat["items"], cat_zh["items"])))
ok("中英 rootOnly 逐条一致",
   all(a.get("rootOnly") == b.get("rootOnly") for a, b in zip(cat["items"], cat_zh["items"])))

print("\n[A0] 同一分类在 catalog 里必须连续（否则 UI 重复插小节标题）")
# 为什么必须守这条：`buildList()`（ExtensionStoreActivity.kt:209-215）只在
# `ext.category != lastCategory` 时插一个 sectionHeader —— 它假定同分类的条目
# **相邻**。catalog 里一旦把一个分类拆成好几段（历史遗留：Build & tooling 原本
# 就分成 clang…cmake 与 make…headers-c 两段，再加 nasm 变三段），屏幕上就会出现
# 好几个同名小节，读起来像不同分类。我加 media-tools 时又给 System & media 在
# 文件末尾添了第二段，正是用户抱怨过的那种「割裂」。
for label, doc in (("en", cat), ("zh", cat_zh)):
    seen, prev, dup = set(), None, []
    for it in doc["items"]:
        k = it.get("categoryKey", "")
        if k != prev:
            if k in seen:
                dup.append(k)
            seen.add(k)
            prev = k
    ok("%s catalog 每个 categoryKey 只出现一段" % label, not dup,
       "重复出现: %s" % dup)

print("\n[A1] categoryKey 与显示名一一对应且中英不同")
by_key_en, by_key_zh = {}, {}
for it in cat["items"]:
    by_key_en.setdefault(it.get("categoryKey", ""), set()).add(it["category"])
for it in cat_zh["items"]:
    by_key_zh.setdefault(it.get("categoryKey", ""), set()).add(it["category"])
for key in sorted(set(by_key_en) | set(by_key_zh)):
    if not key:
        continue
    en, zh = by_key_en.get(key, set()), by_key_zh.get(key, set())
    ok("categoryKey '%s' 只有一个英文名" % key, len(en) == 1, str(en))
    ok("categoryKey '%s' 只有一个中文名" % key, len(zh) == 1, str(zh))
    if len(en) == 1 and len(zh) == 1:
        ok("categoryKey '%s' 中英显示名不同" % key,
           list(en)[0] != list(zh)[0], "%r == %r" % (list(en)[0], list(zh)[0]))

print("\n[B] rootOnly 的语义边界")
for eid in sorted(TRUE_ROOT_ONLY):
    ok("'%s' 标了 rootOnly（要开 tun / 改路由）" % eid,
       bool(items.get(eid, {}).get("rootOnly")), str(items.get(eid, {}).get("rootOnly")))
for eid in sorted(FALSE_ROOT_ONLY):
    ok("'%s' 没标 rootOnly（纯用户态，不碰 tun）" % eid,
       not items.get(eid, {}).get("rootOnly"),
       "rootOnly=%r" % items.get(eid, {}).get("rootOnly"))
ok("rootOnly 不等于 linuxOnly（cloudflared 只在 Termux 有，但不需 root）",
   items.get("net-cloudflared", {}).get("linuxOnly") in (None, False)
   and not items.get("net-cloudflared", {}).get("rootOnly"))
ok("linuxOnly 的组网条目确实是 Termux 缺包的那几个",
   all(items.get(e, {}).get("linuxOnly") for e in ("vpn-client", "net-capture")))
ok("net-tunnel 不标 linuxOnly（两边仓库都有 wireguard-tools/n2n/frp）",
   not items.get("net-tunnel", {}).get("linuxOnly"))

print("\n[C] rootOnly 在数据层与 UI 层都真的接线")
ok("Extension 数据类有 rootOnly 字段",
   re.search(r"val\s+rootOnly\s*:\s*Boolean\s*=\s*false", src_mgr) is not None)
ok("catalog 解析读了 rootOnly",
   'o.optBoolean("rootOnly", false)' in src_mgr)
# 状态行：必须真的把「需要 Root」插进 buildString 里
ok("状态行追加「需要 Root」标记",
   re.search(r"if\s*\(\s*needsRoot\s*\)\s*\{[\s\S]{0,200}?"
             r"getString\(R\.string\.ext_state_needs_root\)", src_act) is not None)
ok("needsRoot 由 rootOnly 与 rootAvailable 共同决定",
   re.search(r"needsRoot\s*=\s*ext\.rootOnly\s*&&\s*!\s*rootAvailable", src_act) is not None)
# 安装前提示：不拦死（允许装），但要说清楚
ok("安装前对 rootOnly 弹说明（保留安装按钮）",
   re.search(r"if\s*\(\s*ext\.rootOnly\s*&&\s*!\s*rootAvailable\)\s*\{[\s\S]{0,600}?"
             r"R\.string\.ext_state_needs_root_hint[\s\S]{0,400}?"
             r"doLinuxInstall", src_act) is not None)
ok("root 探测用轻量线索（不每次渲染都跑 su）",
   "Privilege.rootAvailableMinimal()" in src_act)
ok("rootAvailable 是 lazy（避免重复探测）",
   re.search(r"private\s+val\s+rootAvailable\s*:\s*Boolean\s+by\s+lazy", src_act) is not None)

print("\n[D] 新条目图标齐全")
for eid in NEW_IDS:
    it = items.get(eid)
    if not it:
        continue
    r = it.get("iconRes", "")
    ok("'%s' iconRes 非空" % eid, bool(r))
    ok("'%s' 图标文件存在" % eid,
       os.path.exists(os.path.join(DRAW, r + ".xml")) or
       os.path.exists(os.path.join(DRAW, r + ".png")), r)
# 图标不能重复（每条一个独立文件，避免复制粘贴后忘了改）
icons = [items[e]["iconRes"] for e in NEW_IDS if e in items and items[e].get("iconRes")]
ok("10 个新条目用 10 个不同图标", len(icons) == len(set(icons)), str(icons))

print("\n[E] 分类字符串与配色接线")
STRINGS = {loc: os.path.join(ROOT, "app", "src", "main", "res", loc, "strings.xml")
           for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th")}
for loc, p in STRINGS.items():
    s = read(p)
    for key in ("ext_cat_cli", "ext_cat_network", "ext_state_needs_root",
                "ext_state_needs_root_hint"):
        ok("%s 有 %s" % (loc, key), 'name="%s"' % key in s)
ok("categoryColor 有 cli 分支", re.search(r'"cli"\s*->\s*getColor\(R\.color\.cli\)', src_act) is not None)
ok("categoryColor 有 net 分支", re.search(r'"net"\s*->\s*getColor\(R\.color\.net\)', src_act) is not None)
ok("legacyCategoryKey 认识 Terminal tools",
   re.search(r'"Terminal tools"[^\n]*->\s*"cli"', src_act) is not None)
ok("legacyCategoryKey 认识 Networking",
   re.search(r'"Networking"[^\n]*->\s*"net"', src_act) is not None)
for loc, palette in (("values", "colors.xml"), ("values-night", "colors.xml")):
    p = os.path.join(ROOT, "app", "src", "main", "res", loc, palette)
    s = read(p)
    ok("%s 定义 color/cli" % loc, 'name="cli"' in s)
    ok("%s 定义 color/net" % loc, 'name="net"' in s)

print("\n[F] 包名在真实仓库索引里成立")
# Alpine：每个新条目的**非映射**包名必须在 v3.22 索引里真实存在。
# 这里只查「映射表里没改名的」那些（改过名的另有映射断言）。
# 事实表与 _test_rev_extensions.py 用同一份（app/src/main/assets/linux/apkindex-names.txt，
# 每行 `<name>-<version>...`），不另建第二份 —— 两份索引会各自腐烂。
APKIDX = os.path.join(ROOT, "app", "src", "main", "assets", "linux", "apkindex-names.txt")
if os.path.isfile(APKIDX):
    have = set()
    for line in io.open(APKIDX, encoding="utf-8", errors="replace"):
        m = re.match(r"^([a-zA-Z0-9_.+-]+)-[0-9]", line.strip())
        if m:
            have.add(m.group(1))
    ok("Alpine 索引事实表已加载", len(have) > 5000, "%d 个包名" % len(have))
    for eid in NEW_IDS:
        it = items.get(eid)
        if not it:
            continue
        # Alpine 侧只对 linuxOnly 条目核（其余条目的 Termux 包名与 Alpine 名不同源）
        if not it.get("linuxOnly"):
            continue
        apk_pkgs = [p for p in it["packages"]]
        # vpn-client / net-capture 的包名在 Alpine 与 Termux 同名
        missing = [p for p in apk_pkgs if p not in have]
        ok("'%s' 的包名都在 Alpine 索引里" % eid, not missing, str(missing))
else:
    print("  [SKIP] 缺 Alpine 索引事实表，跳过 F 组的索引核对")

# ⚠️ 这里必须**分段**取，不能全文正则。Alpine 与 Ubuntu 两张表都含
# `"python-yt-dlp" to listOf("yt-dlp")`，全文搜索时删掉其中一张表的那行，
# 另一张仍会命中 → 断言假绿（反向验证 R16 实测就是这样没变红）。
def _map_body(name):
    pat = r"private val %s[^=]*=\s*mapOf\(([\s\S]*?)\n\s{4}\)" % name
    m = re.search(pat, src_bridge)
    return m.group(1) if m else ""

BODY_ALPINE = _map_body("ALPINE_NAMES")
BODY_UBUNTU = _map_body("UBUNTU_NAMES")
ok("ALPINE_NAMES 表能分段取到", len(BODY_ALPINE) > 200, "%d 字符" % len(BODY_ALPINE))
ok("UBUNTU_NAMES 表能分段取到", len(BODY_UBUNTU) > 200, "%d 字符" % len(BODY_UBUNTU))
ok("Alpine 映射含 python-yt-dlp -> yt-dlp（仅 Alpine 段）",
   re.search(r'"python-yt-dlp"\s*to\s*listOf\("yt-dlp"\)', BODY_ALPINE) is not None)
ok("Ubuntu 映射含 python-yt-dlp -> yt-dlp（仅 Ubuntu 段）",
   re.search(r'"python-yt-dlp"\s*to\s*listOf\("yt-dlp"\)', BODY_UBUNTU) is not None)
# 反向自检：两段必须真的不同（否则上面的分段形同虚设）
ok("两张映射表内容不同（分段有效）", BODY_ALPINE != BODY_UBUNTU)

UBUNTU_MAP = {"fd": "fd-find", "poetry": "python3-poetry"}
for s_name, d_name in UBUNTU_MAP.items():
    ok("Ubuntu 映射含 %s -> %s" % (s_name, d_name),
       re.search(r'"%s"\s*to\s*listOf\("%s"\)' % (re.escape(s_name), re.escape(d_name)),
                 BODY_UBUNTU) is not None)
# UNAVAILABLE 里每个 key 都必须被某条 catalog 条目真实用到（闲置表就是腐烂）
for tbl, label in (("ALPINE_UNAVAILABLE", "Alpine"), ("UBUNTU_UNAVAILABLE", "Ubuntu")):
    m = re.search(r"private val %s[^=]*=\s*mapOf\(([\s\S]*?)\n\s*\)" % tbl, src_bridge)
    ok("%s 表能解析" % tbl, m is not None)
    if m:
        body = m.group(1)
        keys = re.findall(r'^\s*"([a-z0-9\-\.\+]+)"\s*to\s', body, re.M)
        ok("%s 表解析到 key" % tbl, len(keys) > 0, "keys=%s" % keys)
        used = set()
        for it in cat["items"]:
            used |= set(it["packages"])
        dangling = [k for k in keys if k not in used]
        ok("%s 里没有闲置 key（每个都被某条目用到）" % tbl, not dangling, str(dangling))

print("\n[G] bins 声明与 .deb 实际内容一致")
BINS = os.path.join(ROOT, "scripts", "probes", "_data_termux_pkg_bins.txt")
provided = {}
if os.path.isfile(BINS):
    for line in io.open(BINS, encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        parts = line.rstrip("\n").split("\t")
        provided[parts[0]] = [b for b in (parts[1] if len(parts) > 1 else "").split(",") if b]
    ok("bins 事实表已加载", len(provided) >= 40, "%d 个包" % len(provided))
    for eid in NEW_IDS:
        it = items.get(eid)
        if not it:
            continue
        if it.get("linuxOnly"):
            print("  [SKIP] '%s' 是 linuxOnly（Alpine 包名，不在 Termux 事实表里）" % eid)
            continue
        pool = set()
        for pkg in it["packages"]:
            pool |= set(provided.get(pkg, []))
        declared = set(it["bins"])
        unknown = sorted(declared - pool)
        ok("'%s' 声明的 bins 都由其包真实提供" % eid, not unknown,
           "多声明了: %s；池里有: %s" % (unknown, sorted(pool)[:8]))
else:
    print("  [SKIP] 缺 bins 事实表")

print("\n[H] 反例守卫（探针自身不能退化）")
ok("NEW_IDS 全在 catalog 里", all(e in items for e in NEW_IDS))
actual_cli = sorted(i["id"] for i in cat["items"] if i.get("categoryKey") == "cli")
ok("CLI_IDS 与实际 cli 条目一致", actual_cli == sorted(CLI_IDS), str(actual_cli))
actual_net = sorted(i["id"] for i in cat["items"] if i.get("categoryKey") == "net")
ok("NET_IDS 与实际 net 条目一致", actual_net == sorted(NET_IDS), str(actual_net))
actual_root = sorted(i["id"] for i in cat["items"] if i.get("rootOnly"))
ok("TRUE_ROOT_ONLY 与实际 rootOnly 条目一致",
   actual_root == sorted(TRUE_ROOT_ONLY), str(actual_root))
ok("catalog 规模已超过 47 条（本批之前的数量）", len(cat["items"]) > 47,
   "now %d" % len(cat["items"]))
ok("01 没有把 UNAVAILABLE 表关掉来蒙混过关",
   "ALPINE_UNAVAILABLE" in src_bridge and "UBUNTU_UNAVAILABLE" in src_bridge)

print("\n" + "-" * 68)
print("checks=%d failed=%d" % (checks[0], len(fails)))
print("RESULT: %s" % ("PASSED" if not fails else "FAILED: " + ", ".join(fails)))
sys.exit(1 if fails else 0)
