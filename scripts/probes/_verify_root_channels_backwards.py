#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""反向验证：故意改坏 root 通道的每个安全边界，确认探针会变红。

这个脚本存在的理由：正向全绿不能证明探针有效。必须逐条改坏目标
代码、确认对应断言失败、再复原。命中 0 次的替换会报错（说明锚点已漂移，
断言其实一直在空转）。

改坏点按「最坏后果」排序 —— 一条安全护栏失效就等于 AI 多了一份权力。
"""

import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

PROBE = os.path.join(HERE, "_test_root_channels.py")
GATE = os.path.join(REPO, "app", "src", "main", "assets", "gates", "root")
SCR = os.path.join(REPO, "app", "src", "main", "assets", "gates", "scr")
GK = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "CapabilityGate.kt"
)
SHZ = os.path.join(REPO, "app", "src", "main", "assets", "gates", "shz")
WATCH = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "cap", "WatchCap.kt"
)
PCA = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "PermissionCenterActivity.kt"
)
A11Y = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "DshAccessibilityService.kt"
)
BRIDGE = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentBridge.kt"
)
SEED = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "AgentContextSeed.kt"
)
CFG = os.path.join(
    REPO, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "EngineConfig.kt"
)

# (说明, 文件, 原文, 替换为, 期望失败的断言关键字)
MUTATIONS = [
    (
        "appdata 不再检查独立开关（root 一开就能读任何人隐私）",
        GATE,
        'case "$_caps" in\n      *\'"appdata":true\'*) ;;',
        'case "$_caps" in\n      *) ;;',
        "appdata checks its own capability switch",
    ),
    (
        "开关查不到时默认放行（静默读出隐私）",
        GATE,
        'cannot verify capability switch',
        'unknown switch, proceeding',
        "appdata refuses when switch check is impossible",
    ),
    (
        "db 放开写操作（别的 App 数据可被改）",
        GATE,
        "SELECT*|WITH*) ;;",
        "*) ;;",
        "appdata db allows only SELECT/WITH",
    ),
    (
        "去掉路径逃逸拦截",
        GATE,
        '*..*) printf \'{"ok":false,"error":"path traversal not allowed"}\\n\'; exit 64 ;;',
        ') : ;;',
        "appdata rejects path traversal",
    ),
    (
        "去掉包名校验（可注入路径）",
        GATE,
        '*[!A-Za-z0-9._]*) printf \'{"ok":false,"error":"invalid package name"}\\n\'; exit 64 ;;',
        ') : ;;',
        "appdata validates package name",
    ),
    (
        "uidump 改回信任退出码（uiautomator 会 segfault）",
        GATE,
        'if [ ! -s "$tmp" ]; then',
        'if false; then',
        "uidump detects empty dump instead of trusting exit code",
    ),
    (
        "scr dump 去掉空树回落（WebView/游戏读不到）",
        SCR,
        '*" 0nodes"*)',
        '*" NEVER_MATCHES"*)',
        "scr dump detects empty accessibility tree",
    ),
    (
        "tap 的 root 通道不再判 root（非 root 也走 input）",
        SCR,
        'if [ "$via" = "root" ]; then\n      [ "$(id -u 2>/dev/null)" = "0" ] \\\n        || { echo \'{"ok":false,"error":"--via root needs root engine (Priv mode = ROOT)"}\'; exit 77; }\n      input tap',
        'if [ "$via" = "root" ]; then\n      input tap',
        "scr tap root path requires root",
    ),
    (
        "默认通道从无障碍改成 root（丢掉毫秒级快路径）",
        SCR,
        'via="a11y"',
        'via="root"',
        "scr default stays on accessibility",
    ),
    (
        "APPDATA 复用 ROOT 常量（两个授权合并成一个）",
        GK,
        'const val APPDATA = "appdata"',
        'const val APPDATA = "root"',
        "APPDATA is separate from ROOT",
    ),
    (
        "能力总览漏掉 appdata（状态不可见）",
        GK,
        ".put(APPDATA, can(ctx, APPDATA))",
        "",
        "APPDATA appears in snapshot",
    ),
    (
        "_dsh_http.sh 改成硬依赖（缺文件则整个 root 工具全废）",
        GATE,
        'if [ -f "$DIR/_dsh_http.sh" ]; then . "$DIR/_dsh_http.sh"; fi',
        '. "$DIR/_dsh_http.sh"',
        "root sources _dsh_http.sh conditionally",
    ),
    (
        "root uidump 不清理 LD_LIBRARY_PATH（uiautomator 被引擎 lib 打死）",
        GATE,
        "env -u LD_LIBRARY_PATH uiautomator dump",
        "uiautomator dump",
        "root uidump clears LD_LIBRARY_PATH",
    ),
    (
        "shz uidump 不清理 LD_LIBRARY_PATH（同一坑的第二个入口）",
        SHZ,
        "env -u LD_LIBRARY_PATH uiautomator dump",
        "uiautomator dump",
        "shz uidump clears LD_LIBRARY_PATH",
    ),
    (
        "shz appdocs 不校验包名（路径注入）",
        SHZ,
        """      *[!A-Za-z0-9._]*) echo '{"ok":false,"error":"invalid package name"}'; exit 64 ;;""",
        '      *) ;;',
        "shz validates package name",
    ),
    (
        "shz appdocs 不拦截 .. 与 /（db 名穿越）",
        SHZ,
        """        case "$name" in *..*|*/*) echo '{"ok":false,"error":"invalid db name"}'; exit 64 ;; esac""",
        '        :',
        "shz refuses db name with traversal",
    ),
    (
        "shz appdocs 直接 SELECT *（把别人隐私全读出来）",
        SHZ,
        """SELECT name FROM sqlite_master WHERE type='table' ORDER BY name;""",
        "SELECT * FROM sqlite_master;",
        "shz appdocs returns structure not rows",
    ),
    (
        "部署清单漏掉 shz（脚本永远到不了设备）",
        CFG,
        '"shz"',
        '"shz-disabled"',
        "shz is registered in deploy list",
    ),
    (
        "applyShzGate 又在非 SHIZUKU 模式删 shz（ROOT 下资产版被清掉）",
        CFG,
        "if (mode != PrivMode.NORMAL) return",
        "if (mode != PrivMode.SHIZUKU) return",
        "applyShzGate only clears shz in NORMAL mode",
    ),
    (
        "shz 丢掉 `shz <cmd>` 旧用法（历史提示词全废）",
        SHZ,
        '$(json_escape "$CMD $*")',
        '$(json_escape "")',
        "shz keeps legacy",
    ),
    (
        "content 不限 scheme（变成任意文件访问）",
        GATE,
        "content://*) ;;",
        "*) ;;",
        "content refuses non-content:// uri",
    ),
    (
        "content uri 白名单失效",
        GATE,
        "outside the allowed set",
        "uri ok",
        "content uri uses whitelist not blacklist",
    ),
    (
        "content 写操作不再 dry-run",
        GATE,
        '"dry_run":true,"would_run":"content %s %s"',
        '"dry_run":false,"would_run":"content %s %s"',
        "content insert/update/delete need confirm",
    ),
    (
        "appops set 不再 dry-run（静默替别人授权）",
        GATE,
        '"dry_run":true,"would_run":"appops set %s %s %s"',
        '"dry_run":false,"would_run":"appops set %s %s %s"',
        "appops set/reset need confirm",
    ),
    (
        "无障碍不再请求 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS",
        A11Y,
        "AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or",
        "",
        "a11y flag FLAG_INCLUDE_NOT_IMPORTANT_VIEWS requested",
    ),
    (
        "无障碍方向滚动改成编译不过的 int 常量写法",
        A11Y,
        "AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id",
        "AccessibilityNodeInfo.ACTION_SCROLL_UP",
        "a11y directional scroll uses AccessibilityAction.id",
    ),
    (
        "launch-intent 去掉 extras（深链参数全丢）",
        BRIDGE,
        'obj.optJSONObject("extras")?.let { extras ->',
        'obj.optJSONObject("extras_none")?.let { extras ->',
        "launch-intent supports extras",
    ),
    (
        "/dev/node 去掉 read 的密码框说明",
        BRIDGE,
        "password field",
        "blocked",
        "node read documents password block",
    ),
    (
        "种子不再提 dev node",
        SEED,
        "`dev node`",
        "`dev nodee`",
        "seed documents dev node",
    ),
    (
        "密码框不再跳过（输入监听变成键盘记录器）",
        A11Y,
        "if (e.isPassword) return@runCatching",
        "if (false) return@runCatching",
        "a11y drops password fields in text events",
    ),
    (
        "onKeyEvent 改成消费按键（音量键失效）",
        A11Y,
        """        return false
    }

    override fun onInterrupt() {}""",
        """        return true
    }

    override fun onInterrupt() {}""",
        "a11y onKeyEvent never consumes",
    ),
    (
        "事件缓冲改为落盘（历史输入可翻）",
        WATCH,
        "private val inputBuf = ArrayDeque<JSONObject>()",
        "private val inputBuf = ArrayDeque<JSONObject>()\n    private val leak = android.content.SharedPreferences::class",
        "watch buffers are in-memory only",
    ),
    (
        "读输入不留痕（用户不知道被读）",
        WATCH,
        "SensitiveAccess.record(ctx, SensitiveAccess.Kind.INPUT_TEXT, arr.length())",
        "0",
        "watch records input via SensitiveAccess",
    ),
    (
        "since 不再归一化成数字（增量拉取失效）",
        BRIDGE,
        'if (k == "since") o.put(k, v.toLongOrNull() ?: 0L) else o.put(k, v)',
        'o.put(k, v)',
        "watch GET coerces since to a number",
    ),
    (
        "权限中心不接线（开关点了没效果）",
        PCA,
        "CapabilityGate.INPUTWATCH, R.id.swCapInputwatch",
        "CapabilityGate.INPUTWATCH, R.id.swCapInputwatchX",
        "permission center binds INPUTWATCH tile",
    ),
    (
        "能力快照漏掉 INPUTWATCH（AI 问不到开关状态）",
        GK,
        ".put(INPUTWATCH, can(ctx, INPUTWATCH))",
        "",
        "INPUTWATCH/KEYWATCH in snapshot",
    ),
    (
        "窗口事件不再去重（缓冲被同一界面灌满）",
        WATCH,
        'last.put("repeat", last.optInt("repeat", 1) + 1)',
        'last.put("repeat", 1)',
        "watch dedups consecutive windows",
    ),
    (
        "binder call 不再 dry-run（盲调直接执行）",
        GATE,
        '"would_run":"service call %s %s',
        '"would_run":"service callX %s %s',
        "binder call is dry-run by default",
    ),
    (
        "binder 不校验服务名（可注入路径）",
        GATE,
        "invalid service name",
        "service name accepted",
        "binder validates service name",
    ),
    (
        "sendevent 放行坐标类事件（触摸屏可能钉死）",
        GATE,
        "      0|1) ;;",
        "      0|1|2|3) ;;",
        "sendevent only accepts type 0 and 1",
    ),
    (
        "sendevent 不限设备路径（可写任意节点）",
        GATE,
        "device must be /dev/input/eventN",
        "device accepted",
        "sendevent validates device path",
    ),
    (
        "sendevent 不再 dry-run",
        GATE,
        '"would_run":"sendevent %s %s %s %s"',
        '"would_run":"sendeventX %s %s %s %s"',
        "sendevent is dry-run by default",
    ),
    (
        "binder 计数退回反斜杠-s（toybox 上直接报错）",
        GATE,
        "grep -cE '^[0-9]+[[:space:]]'",
        "grep -c '^[0-9]*" + chr(92) + "s'",
        "binder count uses POSIX class",
    ),
]


def run_probe():
    r = subprocess.run(
        [sys.executable, PROBE],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def main():
    rc, out = run_probe()
    if rc != 0:
        print("基线就是红的，先修好再谈反向验证：")
        print(out)
        return 1
    print("baseline: green")

    results = []
    for desc, path, old, new, expect in MUTATIONS:
        original = io.open(path, encoding="utf-8").read()
        if old not in original:
            results.append((desc, "ANCHOR_MISS", f"未命中任何内容：{old[:60]!r}"))
            continue
        count = original.count(old)
        mutated = original.replace(old, new)
        try:
            io.open(path, "w", encoding="utf-8", newline="\n").write(mutated)
            rc2, out2 = run_probe()
            if rc2 == 0:
                results.append((desc, "STILL_GREEN", "改坏了却全过 —— 断言在空转"))
            elif expect in out2:
                results.append((desc, "RED_OK", ""))
            else:
                results.append((desc, "WRONG_RED", f"变红了但不是预期的那条：{expect}"))
        finally:
            io.open(path, "w", encoding="utf-8", newline="\n").write(original)

    rc3, _ = run_probe()
    restored = rc3 == 0
    print(f"restored: {'green' if restored else 'STILL RED — 复原失败！'}")

    bad = [r for r in results if r[1] != "RED_OK"]
    print(f"\n反向验证 {len(results)} 项：{len(results) - len(bad)} 项变红符合预期，{len(bad)} 项有问题")
    for desc, status, detail in bad:
        print(f"  ! [{status}] {desc}" + (f" — {detail}" if detail else ""))

    if bad or not restored:
        return 1
    print("\n每一条护栏都能被改坏并被抓到。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
