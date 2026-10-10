"""pick 端点测试（v1.2.120）—— 正确编码 + 直接调用 adb"""
import json, subprocess, urllib.request, sys

ADB = "E:/Android/Sdk/platform-tools/adb.exe"
SERIAL = "emulator-5554"


def adb(*args, timeout=30):
    r = subprocess.run([ADB, "-s", SERIAL] + list(args), capture_output=True, timeout=timeout)
    return r.stdout


def adb_shell(cmd, timeout=30):
    return adb("shell", cmd, timeout=timeout).decode("utf-8", "replace")


np_out = adb_shell("run-as app.dsh.mobile pidof node").strip()
if not np_out:
    print("引擎未运行（node 不存在）")
    sys.exit(1)
np = np_out.split()[0]
print("node pid:", np)

env = adb("shell", f"run-as app.dsh.mobile cat /proc/{np}/environ").decode("utf-8", "replace")
token = ""
for kv in env.split("\x00"):
    if kv.startswith("DSH_BRIDGE_TOKEN="):
        token = kv.split("=", 1)[1].strip()
print("token len:", len(token))
if not token:
    print("token 未取到")
    sys.exit(1)

adb("forward", "tcp:3083", "tcp:3083", timeout=20)


def pick(target, tap=False, clickable_only=True):
    body = json.dumps({"target": target, "tap": tap, "clickableOnly": clickable_only}).encode("utf-8")
    req = urllib.request.Request(
        "http://127.0.0.1:3083/pick", data=body,
        headers={"X-DSH-Token": token, "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode("utf-8"))


print()
print("=== pick 测试（设置页）===")
for t in ["网络和互联网", "通知", "WLAN", "蓝色大象"]:
    try:
        r = pick(t)
        line = f"target={t!r:20} via={r.get('via'):9} cost={r.get('costMs')}ms"
        if r.get("text"):
            line += f"  → {r.get('text')!r} @({r.get('x')},{r.get('y')}) score={r.get('score')}"
        print(line)
        c = r.get("candidates")
        if c:
            print(f"    候选前 5: {[x['t'] for x in c[:5]]}")
    except Exception as e:
        print(f"target={t!r} 失败: {e}")
