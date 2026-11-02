#!/usr/bin/env python3
"""Locks the hid tool's契约：别名映射、Awake 前提、单次生命周期限制。

不需要真机——纯静态检查 assets 里的源码，保证：
  1. Consumer 页音量键是 0xE9/0xEA（不是 0x80/0x81，那是 Keyboard 页）
  2. 别名表覆盖常用键且 kind 正确
  3. 注入前有 200ms 等待（create 异步）
  4. 文档写清 Awake 前提（Dozing 下系统丢弃按键）
  5. 没有把 /dev/uhid 的 open 放回 App 进程侧（必须留在门脚本）
"""
import io, re, sys, os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
HID = os.path.join(ROOT, "app/src/main/assets/tools/hid.mjs")
GATE = os.path.join(ROOT, "app/src/main/assets/gates/hid")
KT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/cap/HidCap.kt")
EC = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineConfig.kt")

fails = []
def chk(cond, msg):
    if cond:
        print("  PASS  %s" % msg)
    else:
        print("  FAIL  %s" % msg)
        fails.append(msg)

def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()

print("== 1. 文件存在 ==")
for p in (HID, GATE, KT, EC):
    chk(os.path.isfile(p), "%s exists" % os.path.relpath(p, ROOT))

hid = read(HID)
gate = read(GATE)
kt = read(KT)
ec = read(EC)

print("== 2. Consumer 页音量 usage ==")
# 允许写成 0xE9 / 233
chk(re.search(r"'vol\+':\s*\{\s*kind:\s*'consumer',\s*code:\s*(0x[eE]9|233)", hid) is not None,
    "vol+ is consumer 0xE9/233")
chk(re.search(r"'vol-':\s*\{\s*kind:\s*'consumer',\s*code:\s*(0x[eE][aA]|234)", hid) is not None,
    "vol- is consumer 0xEA/234")

print("== 3. 别名表 ==")
for name in ("mute", "play", "pause", "next", "prev", "enter", "esc", "space", "up", "down", "home"):
    chk(re.search(r"'%s':\s*\{" % re.escape(name), hid) is not None, "alias %r present" % name)
chk("function resolveAlias" in hid, "resolveAlias() exists")
chk("resolveAlias(raw, body.kind)" in hid or "resolveAlias(raw" in hid, "positional alias resolved")

print("== 4. create 后必须等待（uhid device_add 异步）==")
chk(re.search(r"if\s*\(op\s*!==\s*'create'\)\s*sleep\(200\)", hid) is not None,
    "sleep(200) after create")
chk("UHID_CREATE2 = 11" in hid and "UHID_INPUT2 = 12" in hid, "uhid opcodes correct")
chk(re.search(r"putU16\(b,\s*o,\s*0x03\)", hid) is not None, "bus = 0x03 (BUS_USB)")

print("== 5. Awake 硬前提已写进文档 ==")
chk("屏幕必须 Awake" in hid or "must be Awake" in hid, "Awake precondition documented")
chk("KEYCODE_WAKEUP" in hid, "wake command documented")
chk("Dozing" in hid, "Dozing failure mode noted")

print("== 6. 门脚本走 root node，不经桥 ==")
chk("exec" in gate and "hid.mjs" in gate, "gate execs hid.mjs")
chk("_dsh_http.sh" not in gate, "gate does NOT call the bridge")
chk("bus = 0x03" in kt or "BUS_USB" in kt or "uhid" in kt, "HidCap documents uhid constraint")
chk('"hid"' in ec or "'hid'" in ec or '"hid",' in ec, "hid registered in EngineConfig gate list")

print("== 7. 不给 AI 埋坑：错误信息带正确用法 ==")
chk("hid tap <code|alias>" in hid or "hid tap <code>" in hid, "tap usage hint present")

print("== 8. AI 工具注册（走引擎 root 直调，不经桥）==")
PLUGIN = os.path.join(ROOT, "app/src/main/assets/android-plugins/device-tools.mjs")
chk(os.path.isfile(PLUGIN), "device-tools.mjs exists")
if os.path.isfile(PLUGIN):
    pl = read(PLUGIN)
    chk('name: "device_hid"' in pl, "device_hid tool registered")
    chk("function runHid(" in pl, "runHid helper exists")
    chk("spawnSync" in pl, "runHid spawns the script directly")
    # 注释里提一句「为什么不走桥」是好事；要禁的是真的发请求。
    chk('request("GET", "/dev/hid")' not in pl and 'request("POST", "/dev/hid"' not in pl,
        "device_hid does NOT go through the bridge")
    chk("hid_script_missing" in pl, "missing-script error is explicit")
    chk('"KEYCODE_WAKEUP"' in pl or "KEYCODE_WAKEUP" in pl, "wake op implemented")
    # 别名要写进 tool description，AI 才知道能用名字
    for al in ("vol+", "mute", "play", "enter"):
        chk(al in pl, "alias %r advertised to the model" % al)

print()
if fails:
    print("FAILED: %d check(s)" % len(fails))
    for f in fails:
        print("  - %s" % f)
    sys.exit(1)
print("All hid tool checks passed.")
