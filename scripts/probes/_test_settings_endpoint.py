#!/usr/bin/env python3
"""App 自身设置端点（/dev/settings）的契约探针。

这个端点的风险不在逻辑复杂，而在**边界**：它把 App 的 20+ 个偏好文件摊开给 AI
读写。三个必须守住的点：

  1. **凭据读取必须脱敏**。写入允许（AI 可以设新密码），但读回来只给
     长度与是否已设置。凭据一旦被读进会话记录/日志就收不回来了。
  2. **能力开关 cap.* 不能在这里**。那是「用户对 AI 的授权」，只能由用户在
     权限中心手动打开。让 AI 能自己开 `cap.notify_action`（代用户回微信）
     会绕过整个逐步授权的设计。
  3. **副作用必须标注**。engine 级的改动（Linux 模式、root 模式）要明确告诉
     调用方「需要重启才生效」，而不是假装已生效。

删任一处理都应立刻变红。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "app/src/main/java/app/dsh/mobile"
fails = []
checks = 0


def check(ok, msg):
    global checks
    checks += 1
    print(("  ok    " if ok else "  FAIL  ") + msg)
    if not ok:
        fails.append(msg)



def body_of(src, signature_prefix):
    """取函数体（按花括号配平，不依赖缩进；签名里有嵌套括号也能处理）。"""
    i = src.find(signature_prefix)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j + 1:k]
    return ""

def read(p):
    p = Path(p)
    return p.read_text(encoding="utf-8", errors="replace") if p.is_file() else ""


cap = read(JAVA / "engine/cap/SettingsCap.kt")
bridge = read(JAVA / "engine/AgentBridge.kt")
catalog = read(JAVA / "engine/EndpointCatalog.kt")
cur = read(JAVA / "CurrentActivity.kt")
dshapp = read(JAVA / "DshApp.kt")

print("== 1. 端点存在且已注册路由 ==")
check(cap != "", "SettingsCap.kt 存在")
check("object SettingsCap" in cap, "SettingsCap 是 object")
check('"GET /dev/settings"' in bridge, "GET 路由已注册")
check('"POST /dev/settings"' in bridge, "POST 路由已注册")
check('"settings", "GET/POST", "/dev/settings"' in catalog, "端点目录有条目")

print("== 2. 五个 op 都在 ==")
for op in ("get", "list", "set", "reset", "reload"):
    check(f'"{op}"' in cap, f"op={op} 有分支")
check("unknown op" in cap, "未知 op 明确报错（不静默当 get）")

print("== 3. ★凭据读取脱敏 ==")
check("SECRET_KEYS" in cap, "有 SECRET_KEYS 清单")
check('"lan_web_password"' in cap, "局域网密码在脱敏清单里")
check("redacted" in cap, "返回值带 redacted 标记")
check("configured" in cap, "返回值带 configured（是否已设置）")
check("length" in cap, "返回值带 length（只给长度不给值）")
# 关键：脱敏分支必须在「取原值」之前，且不能把原文放进返回
rv = body_of(cap, "private fun readValue(")
check(rv != "", "readValue 函数被解析到")
check("key in SECRET_KEYS" in rv, "readValue 里判 SECRET_KEYS")
check(".put(\"redacted\", true)" in rv, "命中脱敏键时返回 redacted=true")
# 断言脱敏分支里没有把 raw 直接 put
seg = rv.split("key in SECRET_KEYS")[1].split("return when")[0] if "key in SECRET_KEYS" in rv else ""
check('put("value", raw' not in seg and ".put(\"value\"" not in seg,
      "脱敏分支里没有把原文当 value 返回")
# 关键：**所有**返回 value 的地方都必须经过 readValue()，不能有任何一处
# 直接 prefs.all[key] —— 否则 get/list 里任意一个绕过脱敏就泄漏了。
# （反证时踩过：只改 get 分支的 put，原探针盯着 readValue，一点没变红。）
value_sites = re.findall(r'\.put\("value",\s*([^)]+)', cap)
check(len(value_sites) >= 2, f"找到全部 value 返回点（{len(value_sites)} 处）")
bad = [v for v in value_sites if "readValue(" not in v]
check(not bad, f"每处 value 都走 readValue()（直读原文的有 {len(bad)} 处：{bad}）")

print("== 4. ★能力开关不在此端点 ==")
# PREFS_FILES 里绝不能有 capabilities 文件
m = re.search(r"PREFS_FILES = listOf\((.*?)\)", cap, re.S)
files = m.group(1) if m else ""
check(files != "", "PREFS_FILES 被解析到")
check('"capabilities"' not in files, "PREFS_FILES 不含 capabilities（能力开关不归本端点管）")
check("cap.*" in cap, "注释/返回里明确说明 cap.* 不在此列")
check('"cap."' not in cap.replace('catch', ''), "没有对 cap. 前缀做写入")

print("== 5. 副作用分级 ==")
for eff in ("SAFE", "REFRESH", "ENGINE", "SENSITIVE"):
    check(eff in cap, f"Effect.{eff} 存在")
check('"engine_linux_mode" to Spec(Effect.ENGINE' in cap,
      "engine_linux_mode 标为 ENGINE（需重启）")
check('"priv_mode" to Spec(Effect.ENGINE' in cap, "priv_mode 标为 ENGINE")
check("requires an engine restart" in cap,
      "ENGINE 级写入明确回报「需重启」而非假装已生效")
check("does not restart it" in cap,
      "明确说明本端点不会自动重启引擎（避免掐掉正在跑的会话）")

print("== 6. 直接生效：重建当前页面 ==")
check("CurrentActivity.recreateIfAny()" in cap, "写完调 recreateIfAny()")
check("object CurrentActivity" in cur, "CurrentActivity 存在")
check("WeakReference" in cur, "用弱引用持有 Activity（不漏视图树）")
check("onActivityResumed" in cur, "在 resumed 时记录")
check("runOnUiThread" in cur, "recreate 走主线程（Activity.recreate 只能在主线程）")
check("CurrentActivity.install(this)" in dshapp, "DshApp.onCreate 里装了生命周期回调")
check("isDestroyed" in cur, "重建前判 isDestroyed（post 期间可能已销毁）")

print("== 7. 值类型与错误处理 ==")
for t in ("putBoolean", "putInt", "putFloat", "putString"):
    check(t in cap, f"支持 {t}")
check("putStringSet" in cap, "支持字符串集合")
check("cannot write a JSON object" in cap, "JSONObject 值明确拒绝（不静默忽略）")
check("unsupported value type" in cap, "未知类型明确报错")
check("refusing unknown prefs file" in cap, "未知偏好文件拒绝写入")

print("== 8. 无 CJK 泄漏到结构化输出 ==")
# 给 AI 读的 note / 返回文案必须是 ASCII（项目 check-i18n.py 也管这条）
cjk = re.findall(r'\.put\((?:[^,]+),\s*"([^"]*[\u4e00-\u9fff][^"]*)"', cap)
check(not cjk, f"返回给 AI 的字符串里没有中文（发现 {len(cjk)} 处）")

print()
if fails:
    print(f"{len(fails)} FAILED / {checks} checks")
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print(f"All settings endpoint checks passed ({checks} checks).")
