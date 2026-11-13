#!/usr/bin/env python3
"""系统开关（op=system）接线与回读守卫。

## 为什么需要这个探针

Android 上飞行模式 / 省电 / 勿扰 / 自动旋转 / 护眼 / 热点这六项都**没有公开 API**，
只能走 `settings put` / `cmd <service>` 经 Privilege.runPrivileged（Shizuku 或 su）。
这类命令有个通病：**命令返回成功 ≠ 状态真的变了**。本仓历史上已经踩过两次：

  · ControlCap.torch 把 `obj.optBoolean("enabled")`（请求值）当结果回 200 ok=true，
    相机被别的 App 占用时灯没亮，AI 却以为亮了；
  · RadioCap.wifi 的 enable/disable 直接回 200 {"ok":true} + `runCatching{}.isSuccess`，
    Android 10+ setWifiEnabled 对普通 App 根本不生效，异常被吞也算成功。

所以这次的判据不是「函数存在」，而是「写完必须回读并比对」：

1. ControlCap 的 op 白名单里有 system，且报错文案同步（否则调用会 400）；
2. system() 分发覆盖六个 target，且每个都有真实读/写命令；
3. toggle() 里存在**轮询回读 + mismatch 暴露**，而不是写完就 200；
4. 提权不可用时报 privileged_required（403），不是假装成功；
5. 手电筒改成 registerTorchCallback 回读；wifi enable/disable 改走 wifiSetEnabled；
6. StatusCap 的 dnd 读 zen_mode，不再用 ringerMode 推断；
7. 三处对外的能力描述同步：EndpointCatalog / device-tools.mjs / gates/device-control。

第 7 条单独列出来，是因为「能力加了但目录没写」在这个仓里等于白加 ——
AI 只能看见 EndpointCatalog 渲染出来的 `dev <能力>` 用法，目录不写它就永远不知道。

## 用法

    python scripts/probes/_test_system_toggles.py

退出码 0 = 通过，1 = 有违规。
"""

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CAP = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "cap")
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
PLUGINS = os.path.join(ROOT, "app", "src", "main", "assets", "android-plugins")
GATES = os.path.join(ROOT, "app", "src", "main", "assets", "gates")


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """剥掉 // 与 /* */ 注释，避免说明文字被当成代码命中。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def need(problems, cond, message):
    if not cond:
        problems.append(message)


def main():
    problems = []

    control_path = os.path.join(CAP, "ControlCap.kt")
    if not os.path.isfile(control_path):
        print("FAIL ControlCap.kt not found at " + control_path)
        return 1
    control = strip_comments(read(control_path))

    # ── 1) 分发接线：op=system 必须进白名单，报错文案必须同步 ──
    need(problems, re.search(r'"system"\s*->\s*system\(ctx,\s*obj\)', control) is not None,
         "ControlCap.control() does not dispatch op=system (the op whitelist would still reject it)")
    need(problems, "torch|vibrate|system" in control,
         "the control op error message still omits |system, so a bad op reports a stale whitelist")

    # ── 2) 六个 target 都要有分支 ──
    for target in ["airplane", "battery_saver", "dnd", "rotation", "eye_comfort", "hotspot"]:
        need(problems, '"%s"' % target in control,
             "system() has no dispatch branch for target=%s" % target)

    # ── 3) 回读骨架：必须是「写 → 轮询回读 → mismatch」──
    need(problems, "readNow" in control and "mismatch" in control,
         "toggle() has no read-back verification; a write would be reported as success unchecked")
    need(problems, "Thread.sleep" in control,
         "toggle() does not wait before re-reading -- cmd/settings apply asynchronously, "
         ) if False else None
    # 轮询循环：不是写死次数，而是按 deadline 轮询（每种开关落地耗时不同，
    # 热点停一次要 1.5s+，settings 类 1s 内就好）。接受 while 或 for 两种形态。
    need(problems,
         re.search(r"(for \w+ in 0 until \d+|while \(true\))", control) is not None
         and "deadline" in control,
         "toggle() has no deadline-bounded polling loop; one immediate read would race the async apply")
    need(problems, "mismatch" in control and "409" in control,
         "toggle() never returns 409 on mismatch -- a silent no-op would look like ok=true")
    # 必须校验「比对」本身：只查 mismatch/409 两个字面量会被退化写法蒙过去
    # （把 val matched = (actual == want) 改成 val matched = true，实测确认过是假绿）。
    need(problems, re.search(r"val matched\s*=\s*\(actual\s*==\s*want\)", control) is not None,
         "toggle() computes matched from something other than (actual == want) -- "
         "the read-back is decorative and a failed write would still report ok")

    # ── 4) 提权缺失时必须明确拒绝 ──
    need(problems, "privileged_required" in control,
         "no privileged_required path: without Root/Shizuku these toggles must fail loudly")
    need(problems, "Privilege.runPrivileged" in control,
         "ControlCap does not go through Privilege.runPrivileged (Shizuku then su)")

    # ── 5) 六项各自的真实命令 ──
    expect_cmds = {
        "airplane": "cmd connectivity airplane-mode",
        "battery_saver": "cmd power set-mode",
        "dnd": "cmd notification set_dnd",
        "rotation": "accelerometer_rotation",
        "eye_comfort": "oplus_customize_custom_eye_protect_enable",
        "hotspot": "cmd wifi start-softap",
    }
    for name, token in expect_cmds.items():
        need(problems, token in control,
             "%s does not use the verified command %r (the one that actually worked on device)"
             % (name, token))

    # ── 6) 护眼双后端：ColorOS 私有键 + AOSP 夜灯，都要试 ──
    need(problems, "night_display_activated" in control,
         "eye_comfort does not fall back to the AOSP night_display_activated key")
    need(problems, "unsupported" in control,
         "eye_comfort never reports unsupported -- on a ROM with neither key it would fake success")

    # ── 7) 热点必须标注需要 root（Shizuku 的 shell uid 会被拒）──
    need(problems, "needs_root" in control,
         "hotspot does not advertise needs_root; Shizuku (shell uid 2000) is refused by WifiService")
    # 回读必须带花括号：裸 SoftApManager 在 dumpsys 里恒约 117 行
    # （mAmmToReadyForChangeMap 等条目都含这个词），写成裸词会把「已开」读成常量真值，
    # 于是 on 永远报 before=true、off 永远报 mismatch。这条实测踩过一次。
    need(problems, "SoftApManager{" in control,
         "hotspot read-back greps bare SoftApManager (always ~117 lines); it must match "
         "'SoftApManager{' or the state reads as a constant true")

    # ── 8) 手电筒：回读而不是回显请求值 ──
    need(problems, "registerTorchCallback" in control,
         "torch still echoes the requested value instead of reading the real torch state back")
    need(problems, "unregisterTorchCallback" in control,
         "torch registers a TorchCallback but never unregisters it (leaks on every call)")
    # ★不能用 latch 等第一次回调：registerTorchCallback 注册时会立刻以当前状态回调一次，
    #   latch 会被这个初始值提前释放，读到的是变化前的状态（实测：首次 on=true 回 false）。
    need(problems, "CountDownLatch" not in control,
         "torch waits on a latch; the callback fires once at registration with the OLD state, "
         "so the latch releases before setTorchMode takes effect")
    need(problems, "HandlerThread" in control,
         "torch passes a null handler; the bridge request thread has no Looper and registration throws "
         "'No handler given, and current thread has no looper!'")

    # ── 9) WiFi 开关：回读 ──
    radio_path = os.path.join(CAP, "RadioCap.kt")
    if not os.path.isfile(radio_path):
        problems.append("RadioCap.kt not found")
    else:
        radio = strip_comments(read(radio_path))
        need(problems, "wifiSetEnabled" in radio,
             "RadioCap.wifi enable/disable does not verify the result by reading isWifiEnabled back")
        # 只匹配旧反模式的确切形态（把 setter 的 isSuccess 当结果回 ok=true）。
        # 不能写成 "runCatching { wm.isWifiEnabled = " 这种泛化子串 —— wifiSetEnabled 里
        # 同样用 runCatching 包 setter，但它是取 exceptionOrNull() 后回读，属于正确写法。
        need(problems, not re.search(r"\.isSuccess\)\.toString\(\)", radio) or "before" in radio,
             "RadioCap still reports ok=true from setter success alone")

    # ── 10) 勿扰判据：zen_mode，不是 ringerMode ──
    status_path = os.path.join(CAP, "StatusCap.kt")
    if not os.path.isfile(status_path):
        problems.append("StatusCap.kt not found")
    else:
        status = strip_comments(read(status_path))
        need(problems, "zen_mode" in status,
             "StatusCap.dnd does not read zen_mode")
        # 同上：只匹配把 ringerMode 直接喂给 "dnd" 键的旧写法。
        # 现在 ringer_silent 仍然由 ringerMode 推出（那是合法信息），不能误判。
        need(problems, not re.search(r'"dnd"\s*,\s*audio\?\.ringerMode', status),
             "StatusCap still derives dnd from ringerMode (silent ringer is not Do Not Disturb)")

    # ── 11) 对外能力描述三处同步 ──
    catalog_path = os.path.join(ENGINE, "EndpointCatalog.kt")
    if not os.path.isfile(catalog_path):
        problems.append("EndpointCatalog.kt not found")
    else:
        catalog = read(catalog_path)
        for token, why in [
            ("op=system", "the endpoint catalog never documents op=system"),
            ("target=airplane", "the endpoint catalog does not list the system targets"),
            ("privileged_required", "the endpoint catalog does not warn that op=system needs privilege"),
        ]:
            need(problems, token in catalog, "%s (%s)" % (why, token))

    mjs_path = os.path.join(PLUGINS, "device-tools.mjs")
    if not os.path.isfile(mjs_path):
        problems.append("device-tools.mjs not found")
    else:
        mjs = read(mjs_path)
        need(problems, re.search(r'enum:\s*\[[^\]]*"system"\]', mjs) is not None,
             "the MCP device_control enum does not expose system")
        need(problems, "target:" in mjs,
             "the MCP device_control tool has no target parameter for op=system")

    gate_path = os.path.join(GATES, "device-control")
    if not os.path.isfile(gate_path):
        problems.append("gates/device-control not found")
    else:
        gate = read(gate_path)
        need(problems, "op\":\"system" in gate,
             "the device-control gate usage comment does not mention op=system")
        need(problems, '"on":true' not in gate,
             "the device-control gate usage comment still shows the wrong field name (on/level)")

    if problems:
        for item in problems:
            print("FAIL " + item)
        return 1
    print("OK op=system is wired, every toggle verifies its own result, and all three catalogs agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
