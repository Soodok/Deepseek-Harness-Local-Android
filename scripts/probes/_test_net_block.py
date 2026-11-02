#!/usr/bin/env python3
"""锁定 P0-4「按 UID 断网 / 查流量」（/dev/net）的契约。

这组检查全部来自真机踩坑，不是凭空写的：

1. **回读格式**：`iptables -vL` 输出的是 `owner UID match 10360`，
   不是命令行里那种 `uid-owner 10360`。第一版按后者 grep，规则明明在链上
   却读回 null，接口报 "rule inserted but could not be read back"。
2. **列偏移**：不带 `--line-numbers` 时第 0 列是 pkts、第 1 列是 bytes。
3. **不要用 ping 判成败**：`su <uid> -c ping` 拿不到 raw socket，
   有没有规则都 100% 丢包 —— 这是假阳性，真机已验证。
4. **插到链首**：ColorOS 的 OUTPUT 前面挂了一串定制链，
   追加到末尾会被提前 ACCEPT 掉。
5. **拒绝封自己/uid 0**：否则 AI 一执行就把自己断网，再也调不回来。
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/app/dsh/mobile"

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def read(p):
    return p.read_text(encoding="utf-8")


net = read(APP / "engine/cap/NetCap.kt")

# ── 1. 四个 op 都在 ─────────────────────────────────────────────────────
check("supports op=status", '"status" -> status(ctx)' in net)
check("supports op=list", '"list" -> list(ctx, body)' in net)
check("supports op=block", '"block" -> block(ctx, body)' in net)
check("supports op=unblock", '"unblock" -> unblock(ctx, body)' in net)
check("unknown op is reported, not silently ignored", "unknown op '" in net)

# ── 2. 回读格式必须是真实输出形态 ───────────────────────────────────────
check("parses the real `owner UID match <uid>` form",
      "owner UID match" in net,
      "iptables -vL prints 'owner UID match', not 'uid-owner'")
check("does NOT grep for the command-line spelling when reading back",
      "grep 'uid-owner $" not in net and "grep 'uid-owner " not in net,
      "grep 'uid-owner N' never matches -vL output")
check("uses iptables -S to test rule existence",
      "iptables -S OUTPUT" in net,
      "-S prints the command-line spelling, which is the stable way to test existence")

# ── 3. 列偏移：pkts 在 0，bytes 在 1 ────────────────────────────────────
i_cols = net.find("cols.getOrNull(0)")
check("reads pkts from column 0", i_cols != -1, "column 1 is bytes, not pkts")
check("reads bytes from column 1", "cols.getOrNull(1)" in net)
# 只锚定**真正拼进命令的** `iptables -vL ...` 调用；文件顶部的注释正是在解释
# 「为什么不能用 --line-numbers」，按全文判会误报（反证时踩过）。
_verbose_cmds = re.findall(r'"iptables -vL[^"]*"', net)
check("no --line-numbers in the actual iptables command",
      _verbose_cmds and all("--line-numbers" not in c for c in _verbose_cmds),
      "the commands found were: %r" % (_verbose_cmds,))

# ── 4. 插到链首，不是追加 ───────────────────────────────────────────────
check("inserts at the head of OUTPUT (-I OUTPUT 1)",
      "-I OUTPUT 1" in net,
      "ColorOS prepends customize_OUTPUT/fw_OUTPUT/... which ACCEPT before a tail-appended rule")
check("does not append (-A) the block rule", "-A OUTPUT" not in net)

# ── 5. 规则带标记，删除时同标记 ─────────────────────────────────────────
check("tags its own rules", 'RULE_TAG = "dsh-netblock"' in net)
# 插入与删除都必须引用同一个常量（而不是各自硬编码一份，那样改一处就漏）
check("both insert and delete attach the same tag",
      net.count("--comment $RULE_TAG") >= 2,
      "insert and delete must both use $RULE_TAG")
check("existence check also filters by the tag",
      "grep -F -- '$RULE_TAG'" in net,
      "otherwise a same-uid rule from someone else would be mistaken for ours")

# ── 6. 安全阀：拒绝封自己与 uid 0 ───────────────────────────────────────
check("refuses to block its own uid by default", "allowSelf" in net and "refusing to block myself" in net)
check("refuses to block uid <= 0", "refusing to block uid" in net)
check("allowSelf defaults to false", 'optBoolean("allowSelf", false)' in net)

# ── 7. 成败判据是计数器，不是 ping ──────────────────────────────────────
check("verifies by reading back the counter", "packets_dropped" in net)
check("warns against ping-based verification", "Do NOT verify by ping" in net)
check("explains why ping is useless here", "raw-socket" in net or "raw socket" in net)

# ── 8. 幂等 ─────────────────────────────────────────────────────────────
check("block is idempotent", "already_blocked" in net)
check("unblock reports was_blocked", "was_blocked" in net)

# ── 9. 能力开关 / 路由 / 目录 / UI 全链路 ────────────────────────────────
gate = read(APP / "engine/CapabilityGate.kt")
check("CapabilityGate has NET", 'const val NET = "net"' in gate)
check("NET appears in snapshot", ".put(NET, can(ctx, NET))" in gate)
check("NET permission check never hard-fails",
      "NET -> true" in gate,
      "hard-checking root would grey out the B-tier usage listing on non-root devices")

bridge = read(APP / "engine/AgentBridge.kt")
check("route POST /dev/net is registered", '"POST /dev/net" to Route("POST")' in bridge)
check("route GET /dev/net is registered", '"GET /dev/net" to Route("GET")' in bridge)

catalog = read(APP / "engine/EndpointCatalog.kt")
check("catalog documents the net endpoint", '"net", "POST", "/dev/net"' in catalog)
check("catalog documents op list", "op=status|list|block|unblock" in catalog)
check("catalog warns ping is not a valid check", "不要用 ping 验证成败" in catalog)
check("catalog warns about head insertion", "-I OUTPUT 1" in catalog)
check("catalog notes IPv4-only scope", "IPv4" in catalog)

ui = read(APP / "PermissionCenterActivity.kt")
check("permission center binds the NET switch", "R.id.swCapNet" in ui)
check("bind map includes NET", "R.id.swCapNet to CapabilityGate.NET" in ui)

layout = read(ROOT / "app/src/main/res/layout/activity_permission_center.xml")
check("layout has rowCapNet", 'android:id="@+id/rowCapNet"' in layout)
check("layout has swCapNet", 'android:id="@+id/swCapNet"' in layout)

# ── 10. 五语种字符串同构 ────────────────────────────────────────────────
for loc in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"):
    p = ROOT / ("app/src/main/res/%s/strings.xml" % loc)
    s = read(p)
    have = all(('name="setting_cap_net%s"' % suf) in s
               for suf in ("", "_sub", "_need"))
    check("%s has all three cap_net strings" % loc, have)

# 撇号：Android 把 ' 当转义符，裸撇号会让 aapt 报 "Invalid unicode escape sequence"
for loc in ("values", "values-de", "values-pt-rBR"):
    s = read(ROOT / ("app/src/main/res/%s/strings.xml" % loc))
    for line in s.split("\n"):
        if "setting_cap_net" in line:
            body = line[line.index(">") + 1:line.rindex("<")]
            if "'" in body:
                check("no bare apostrophe in %s cap_net strings" % loc, False,
                      "aapt: Invalid unicode escape sequence")
                break
    else:
        check("no bare apostrophe in %s cap_net strings" % loc, True)

# 图标资源要真实存在（布局引用了它）
check("ic_globe drawable exists",
      (ROOT / "app/src/main/res/drawable/ic_globe.xml").is_file())

failed = [c for c in checks if not c[1]]
for name, ok, detail in checks:
    print(("  ok    " if ok else "  FAIL  ") + name
          + (("\n         -> " + detail) if not ok and detail else ""))
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All net block checks passed (%d checks)." % len(checks))
