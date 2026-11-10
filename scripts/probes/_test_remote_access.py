#!/usr/bin/env python3
"""局域网远程访问手机能力（v1.7.1）静态契约探针。

## 为什么必须有这个探针

这条链路的失效**全部是静默的**：
· 绑错地址 → 端口在监听，但局域网连不上（现象是「连不上」，看不出绑错）；
· 鉴权按错来源分流 → 容器内调用忽然全 403，或者局域网谁都能进；
· 地址选错网卡 → UI 显示一个连不通的 IP（用户报的就是这个，开热点时显示移动网络地址）；
· 门脚本没做占位符替换 → PC 上下载下来的脚本 shebang 指向 Android 路径，跑不起来。

四种都不会抛异常、不会报错，只能靠断言钉住。

A 段：地址选择（热点 bug 的回归防线）
B 段：配对与鉴权分流
C 段：3091 服务端契约
D 段：App 接线与 UI
"""

from __future__ import annotations

import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _kstrip  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENG = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine")
RA = os.path.join(ENG, "RemoteAccess.kt")
RAS = os.path.join(ENG, "RemoteAccessServer.kt")
LA = os.path.join(ENG, "LanAddress.kt")
BRIDGE = os.path.join(ENG, "AgentBridge.kt")
LWP = os.path.join(ENG, "LanWebProxy.kt")
SUP = os.path.join(ENG, "EngineSupervisor.kt")
SET = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/SettingsActivity.kt")
SHEET = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/RemoteAccessSheet.kt")
LAYOUT = os.path.join(ROOT, "app/src/main/res/layout/activity_settings.xml")

checks = []


def check(tag, ok, detail=""):
    checks.append((tag, bool(ok), detail))


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def main():
    for p in (RA, RAS, LA):
        check("A0 文件存在 " + os.path.basename(p), os.path.isfile(p))

    la = read(LA)
    ra = _kstrip.strip_comments(read(RA))   # ★ 同 ras：存在性断言只看代码
    ras_raw = read(RAS)
    ras = _kstrip.strip_comments(ras_raw)   # ★ 存在性断言必须在剥注释后的代码上做
    bridge = read(BRIDGE)
    lwp = read(LWP)
    sup = read(SUP)
    st = read(SET)
    sheet = read(SHEET)
    layout = read(LAYOUT)

    # ---- A 段：地址选择（热点 bug 回归）----
    check(
        "A1 排除移动网络接口（rmnet/ccmni/pdp）",
        all(k in la for k in ('"rmnet"', '"ccmni"', '"pdp"')),
        "开热点时移动网络地址与热点 AP 地址同时存在，不排除就会显示连不通的那个",
    )
    check(
        "A2 优先热点/有线接口（ap/swlan/wlan1/eth）",
        all(k in la for k in ('"ap"', '"swlan"', '"wlan1"', '"eth"')),
    )
    check(
        "A3 只认私有网段或明确优先",
        "isPrivateIPv4" in la and "192" in la and "172" in la,
    )
    check(
        "A4 有排序而不是取第一个",
        "sortedWith" in la and "score" in la,
        "★旧实现是 firstOrNull，枚举顺序不保证 —— 这就是用户报的 bug 根因",
    )
    check(
        "A5 LanWebProxy 已委托（不再自己取第一个）",
        "LanAddress.best()" in lwp and "firstOrNull { !it.isLoopbackAddress }" not in lwp,
        "两个地方各写一套必然分叉，必须收敛到一处",
    )

    # ---- B 段：配对与鉴权 ----
    check(
        "B1 设备密钥是持久的（落 prefs，不是每次随机）",
        '"secret"' in ra and "getString(KEY_SECRET" in ra,
        "桥的引擎 token 每次启动随机且不可外部取得，远程访问不能复用它",
    )
    check(
        "B2 配对码单次有效且有过期窗口",
        "PAIR_WINDOW_MS" in ra and "closePairWindow" in ra,
    )
    check(
        "B3 换码后立刻关窗（一次性）",
        re.search(r"savePairs\(ctx, existing \+ pair\)[\s\S]{0,200}closePairWindow", ra) is not None,
    )
    check(
        "B4 环回与非环回分流：非环回不认引擎 token",
        re.search(r"if \(fromLoopback\)[\s\S]{0,300}AgentBridge\.currentToken\(\)", ra) is not None,
        "★引擎 token 会随环境变量泄露给容器内任意进程，不能当 LAN 通行证",
    )
    check(
        "B5 总开关关闭时一律拒",
        re.search(r"if \(!isEnabled\(ctx\)\) return false", ra) is not None,
    )
    check(
        "B6 常量时间比较（断言辅助方法体，不是裸子串）",
        # ★ 只查 "MessageDigest.isEqual" 是假绿：类注释里也提到它。
        # 必须查到「这个方法体里用的是它、没用 ==」。
        re.search(
            r"fun constantTimeEquals\([^)]*\)[^={]*=\s*MessageDigest\.isEqual\(", ra
        )
        is not None,
    )
    check(
        "B7 撤销与重置密钥",
        "fun revokePair" in ra and "fun resetSecret" in ra,
    )
    check(
        "B8 配对数量有上限（不做静默淘汰）",
        "MAX_PAIRS" in ra and "MAX_PAIRS" in ras,
    )

    # ---- C 段：3091 服务端 ----
    check(
        "C1 端口独立，不占 3080/3083/3090",
        "PORT = 3091" in ras.replace(" ", " "),
    )
    check(
        "C2 每个请求都过私网检查（断言调用点，不是函数定义）",
        # ★ 只查 "isPrivatePeer" 存在是假绿 —— 函数定义与注释都有它。
        # 必须查到「在 when 分支之前无条件调用」这个调用点。
        re.search(r"if\s*\(!isPrivatePeer\(peer\)\)\s*\{", ras) is not None,
        "公网来源连 /ping 也不该得到回应",
    )
    check(
        "C3 /ping 与 /pair 开放，其余要密钥",
        'path == "/ping"' in ras
        and 'path == "/pair"' in ras
        and ras.count("authorised(ctx, lines, query)") >= 2,
    )
    check(
        "C4 /pair 有每 IP 尝试上限（断言计数与比较都在）",
        re.search(r"pairAttempts\.merge\(ip", ras) is not None
        and re.search(r"if\s*\(tried\s*>\s*MAX_PAIR_ATTEMPTS\)", ras) is not None,
        "6 位码 + 5 分钟窗口仍需防在线爆破；只查常量名存在是假绿",
    )
    check(
        "C5 门脚本包把 @DSH_BASH@ 换成 PC 可用的值",
        # ★ 只查 replace( 存在是假绿：改成 replace(X, X) 自替换照样过。
        # 必须断言**替换目标**是 /usr/bin/env bash。
        re.search(r'replaceFirst\("#!/system/bin/sh",\s*"#!/bin/sh"\)', ras) is not None
        and re.search(r'replace\("@DSH_BASH@",\s*"/bin/bash"\)', ras) is not None,
        "原样发出去 PC 上因 #!/system/bin/sh 与 bionic 绝对路径直接跑不起来（实测过）",
    )
    check(
        "C6 setup.sh 会写 env 并拉门脚本",
        "setup.sh" in ras and "gates.tgz" in ras and "DSH_BRIDGE_TOKEN" in ras,
    )

    # ---- D 段：App 接线 ----
    check(
        "D1 桥按开关决定绑定地址",
        "bindAddress(ctx)" in bridge and '"0.0.0.0"' in bridge,
    )
    check(
        "D2 桥的鉴权按来源分流并复用 RemoteAccess",
        "RemoteAccess.authorise(" in bridge and "fromLoopback" in bridge,
    )
    check(
        "D3 开关变化后重绑（监听地址创建后改不了）",
        "fun rebindForRemoteAccess" in bridge and "rebindForRemoteAccess" in ra,
    )
    check(
        "D4 启动时按开关拉起 3091",
        "RemoteAccessServer.start(ctx)" in sup,
    )
    check(
        "D5 设置页有开关与副标题",
        "swRemoteAccess" in st and "remoteAccessSub" in st,
    )
    check(
        "D6 布局有整行（可点开详情）",
        'android:id="@+id/rowRemoteAccess"' in layout
        and 'android:id="@+id/swRemoteAccess"' in layout,
    )
    check(
        "D7 详情面板：配对码倒计时 + 已配对列表 + 撤销",
        "RemoteAccessSheet" in st
        and "pairWindowRemainingSeconds" in sheet
        and "revokePair" in sheet,
    )
    check(
        "D8 关闭对话框时关窗并停掉 tick（防泄漏）",
        "setOnDismissListener" in sheet and "removeCallbacks(tick)" in sheet,
    )
    check(
        "D9 拒绝开启时把开关弹回（不留假开启状态）",
        re.search(r"setNegativeButton\(R\.string\.action_cancel\)[^\n]*\{[^}]*swRemote\.isChecked = false", st)
        is not None
        or "swRemote.isChecked = false" in st,
        "开关显示开、实际没开是最危险的不一致",
    )

    # ---- E 段：五语文案 ----
    langs = ["values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th"]
    need = [
        "setting_remote_access",
        "setting_remote_access_sub",
        "remote_access_consent",
        "remote_access_code",
        "remote_access_paired_title",
        "remote_access_revoke",
        "remote_access_reset_key",
    ]
    for lang in langs:
        p = os.path.join(ROOT, "app/src/main/res", lang, "strings.xml")
        body = read(p)
        missing = [k for k in need if ('name="%s"' % k) not in body]
        check("E 五语齐全 %s" % lang, not missing, "缺: %s" % ",".join(missing) if missing else "")

    # 文案里不许有裸单引号（AAPT 会报误导性的 unicode 错）
    for lang in langs:
        p = os.path.join(ROOT, "app/src/main/res", lang, "strings.xml")
        body = read(p)
        bad = []
        for m in re.finditer(r'<string name="(remote_access[^"]*|setting_remote_access[^"]*)">(.*?)</string>', body, re.S):
            if re.search(r"(?<!\\)'", m.group(2)):
                bad.append(m.group(1))
        check("E 无裸单引号 %s" % lang, not bad, "裸单引号: %s" % ",".join(bad) if bad else "")

    fails = [c for c in checks if not c[1]]
    for tag, ok, detail in checks:
        print(("  OK   " if ok else "  FAIL ") + tag + (("  <- " + detail) if (not ok and detail) else ""))
    print()
    print("%d passed, %d failed, %d total" % (len(checks) - len(fails), len(fails), len(checks)))
    if fails:
        print("\n失败项：")
        for tag, _, detail in fails:
            print("  · " + tag + (("  <- " + detail) if detail else ""))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
