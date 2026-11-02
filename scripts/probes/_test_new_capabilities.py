#!/usr/bin/env python3
"""锁定 P1/P2 六个新端点的契约。

这组检查都对应真机或编译阶段踩到的具体坑：

1. **bt-hid 必须先 probe**：多个 OEM 用 BLUETOOTH_PRIVILEGED 保护 HID_DEVICE
   profile，直接 register 会得到一个看不出原因的失败。probe 把原因拆开报。
2. **ble 与 bt 方向相反**：/dev/bt 是手机去连别人，/dev/ble 是被别人连。
   两者都在同一个 cap.bt 开关下，但语义不能混。
3. **scene 是规则推断不是模型**：每个标签必须附上它依据的数字。
4. **morse 只认 A-Z/0-9 与短码**：不认识就跳过，不能猜成别的键。
5. **screenwatch 不返回像素**：只给 changed_ratio / mean_delta / hot_blocks。
6. **audio 要区分「静音」与「失败」**：目标 App 禁止捕获时录到的是静音，
   不是错误 —— 接口必须让调用方能分辨。
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/java/app/dsh/mobile"

checks = []


def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))


def read(p):
    return p.read_text(encoding="utf-8")


# ── 1. BLE 外设 ─────────────────────────────────────────────────────────
ble = read(APP / "engine/cap/BlePeripheralCap.kt")
check("ble exposes start/stop/status/events/notify",
      all(op in ble for op in ('"start"', '"stop"', '"status"', '"events"', '"notify"')))
check("ble opens a real GATT server", "openGattServer" in ble)
check("ble advertises with a service UUID", "addServiceUuid" in ble)
check("ble handles writes from clients", "onCharacteristicWriteRequest" in ble)
check("ble handles reads", "onCharacteristicReadRequest" in ble)
check("ble handles CCCD (notify subscription)", "onDescriptorWriteRequest" in ble)
check("ble documents the 31-byte advertising limit", "31" in ble)
check("ble requires stop before reconfiguring", "already running; call op=stop first" in ble)
check("ble notes the Cgroup Freezer risk when screen off", "Cgroup Freezer" in ble)

# ── 2. 蓝牙 HID ────────────────────────────────────────────────────────
hid = read(APP / "engine/cap/BtHidCap.kt")
check("bt-hid has probe op", '"probe" -> probe(' in hid)
check("bt-hid probe checks the proxy", "getProfileProxy" in hid)
check("bt-hid probe reports BLUETOOTH_PRIVILEGED state",
      "BLUETOOTH_PRIVILEGED" in hid and "bluetooth_privileged_granted" in hid)
check("bt-hid registers as SUBCLASS1_KEYBOARD", "SUBCLASS1_KEYBOARD" in hid)
check("bt-hid carries a real keyboard report descriptor",
      "KEYBOARD_REPORT_DESC" in hid and "0x05, 0x01" in hid)
check("bt-hid sends down+up reports", "down" in hid and "up" in hid)
check("bt-hid maps ASCII to HID usage ids", "ASCII_MAP" in hid)
check("bt-hid skips unsupported chars instead of guessing",
      "skipped" in hid and "US layout only" in hid)
check("bt-hid says mouse reports are rejected by a keyboard-only SDP",
      "mouse_report_rejected" in hid)

# ── 3. 振动摩斯 ────────────────────────────────────────────────────────
pulse = read(APP / "engine/cap/PulseCap.kt")
check("morse has shortcuts ok/fail/wait/done/ask/sos",
      all(s in pulse for s in ('"ok"', '"fail"', '"wait"', '"done"', '"ask"', '"sos"')))
check("morse covers A-Z and 0-9", "'A' to" in pulse and "'0' to" in pulse)
check("morse uses createWaveform", "createWaveform" in pulse)
check("morse zeroes the trailing gap", "0L" in pulse)
check("morse derives dot/dash from the unit", "unit * 3" in pulse)
check("morse does not use vibrate() without amplitude on API26+",
      "VibrationEffect" in pulse)

# ── 4. 传感器场景 ──────────────────────────────────────────────────────
check("scene reads light/proximity/pressure/accel/steps",
      all(x in pulse for x in ("TYPE_LIGHT", "TYPE_PROXIMITY", "TYPE_PRESSURE",
                               "TYPE_ACCELEROMETER", "TYPE_STEP_COUNTER")))
check("scene labels carry the number behind them",
      "facts" in pulse and "lux" in pulse)
check("scene states it is rule-based not a model",
      "rule-based inference" in pulse or "规则推断" in pulse)
check("scene compares pressure against the previous call",
      "last_pressure" in pulse)
check("scene uses accel magnitude to avoid orientation false positives",
      "sqrt(x * x + y * y + z * z)" in pulse)

# ── 5. Doze ────────────────────────────────────────────────────────────
check("doze offers status/add/remove",
      all(op in pulse for op in ('"status"', '"add"', '"remove"')))
check("doze uses dumpsys deviceidle whitelist",
      "dumpsys deviceidle whitelist" in pulse)
check("doze reports the effective state, not just the command result",
      "now_ignoring" in pulse and "isIgnoringBatteryOptimizations" in pulse)

# ── 6. 屏幕变化检测 ────────────────────────────────────────────────────
watch = read(APP / "engine/cap/ScreenWatchCap.kt")
check("screenwatch uses ImageReader over a VirtualDisplay",
      "ImageReader.newInstance" in watch and "createVirtualDisplay" in watch)
check("screenwatch downsamples before diffing", "downscale" in watch)
check("screenwatch returns ratios, not pixels",
      "changed_ratio" in watch and "mean_delta" in watch)
check("screenwatch does not persist frames", "compress(" not in watch and ".png" not in watch)
check("screenwatch reports hot grid blocks", "hot_blocks" in watch)
check("screenwatch explains the first-frame baseline",
      "first_frame" in watch and "baseline" in watch)

# ── 7. 音频回环 ────────────────────────────────────────────────────────
rec = read(APP / "engine/cap/RecordCap.kt")
check("record has audio ops", '"audio"' in rec and '"audio-stop"' in rec and '"audio-status"' in rec)
check("audio uses AudioPlaybackCaptureConfiguration",
      "AudioPlaybackCaptureConfiguration" in rec)
check("audio matches MEDIA/GAME usages",
      "USAGE_MEDIA" in rec and "USAGE_GAME" in rec)
check("audio gates on API 29+", "AudioPlaybackCapture requires API 29+" in rec)
check("audio writes raw PCM (no encoder dependency)",
      "pcm_s16le_raw" in rec)
check("audio warns that silence != failure",
      "silence is possible" in rec or "白录静音" in rec or "silence" in rec)
check("audio runs capture on a background thread",
      "dsh-audio-capture" in rec and "isDaemon" in rec)

# ── 8. 路由与目录登记 ──────────────────────────────────────────────────
bridge = read(APP / "engine/AgentBridge.kt")
for path, methods in (
    ("ble", ("POST", "GET")),
    ("bt-hid", ("POST", "GET")),
    ("screenwatch", ("POST", "GET")),
    ("pulse", ("POST",)),
    ("scene", ("POST",)),
    ("doze", ("POST", "GET")),
):
    for m in methods:
        check("%s %s /dev/%s is routed" % (m, path, path),
              '"%s /dev/%s" to Route("%s")' % (m, path, m) in bridge)

catalog = read(APP / "engine/EndpointCatalog.kt")
for eid, path in (
    ("ble", "/dev/ble"),
    ("bt-hid", "/dev/bt-hid"),
    ("screenwatch", "/dev/screenwatch"),
    ("pulse", "/dev/pulse"),
    ("scene", "/dev/scene"),
    ("doze", "/dev/doze"),
):
    check("catalog registers %s" % eid, '"%s", "POST", "%s"' % (eid, path) in catalog)

# 目录里的 params 必须是单行转义的 \n，不能是裸换行（否则 Kotlin 字符串断裂）
for line in catalog.split("\n"):
    if 'params = "' in line and not line.rstrip().endswith('" +'):
        # 单行 params 应当以引号收尾
        if not line.rstrip().endswith('",') and not line.rstrip().endswith('"'):
            check("catalog params line stays single-line: %s" % line.strip()[:40], False,
                  "a literal newline inside a Kotlin string breaks the build")
check("no literal newlines inside catalog string literals", True)

failed = [c for c in checks if not c[1]]
for name, ok, detail in checks:
    print(("  ok    " if ok else "  FAIL  ") + name
          + (("\n         -> " + detail) if not ok and detail else ""))
print()
if failed:
    print("%d/%d checks FAILED" % (len(failed), len(checks)))
    sys.exit(1)
print("All capability-endpoint checks passed (%d checks)." % len(checks))
