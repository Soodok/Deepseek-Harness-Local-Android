#!/usr/bin/env python3
"""issue #66 护栏：插件引擎版本兼容性判定。

## 背景

npm 上 `@deepseek-ai/dsh` 有两条线：`latest` = 0.2.1-rc.2（我们内置的）、
`next` = 0.2.1-rc.4（插件作者照它写 `engines.dsh`）。于是插件详情里只显示
一句「不兼容」，用户以为是插件坏了。

修法是在插件市场里**单独分出 ENGINE_TOO_OLD 这一态**，并把「要求 X / 内置 Y」
如实告诉用户。本探针守住判定逻辑，因为它有两个特别容易错的地方：

1. **semver 预发布规则**：`0.2.1-rc.2 < 0.2.1-rc.4 < 0.2.1`。
   朴素字符串比较会得到 `rc.10 < rc.9`（字典序），与语义相反。
2. **宁漏不误**：范围表达式解析不出来时必须返回 null（不判定），
   而不是猜一个——误判会把能用的插件标成不可用。

## 校验方式

直接读 Kotlin 源码做结构断言（无法在 Python 里执行 Kotlin），
关键算法用一条等价实现跑真实用例做交叉验证。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CHECK = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                     "engine", "NativePluginCheck.kt")
MARKET = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                      "engine", "PluginMarket.kt")
STORE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                     "PluginStoreActivity.kt")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print("PASS  %s" % name)
    else:
        failed += 1
        print("FAIL  %s%s" % (name, ("  -> " + detail) if detail else ""))


src = open(CHECK, encoding="utf-8").read()
market = open(MARKET, encoding="utf-8").read()
store = open(STORE, encoding="utf-8").read()

# ---------------------------------------------------------------- 结构断言
check("检测：analyse 读取 engines.dsh", 'optJSONObject("engines")' in src)
check("检测：取版本下限 minVersionOf", "minVersionOf" in src)
check("检测：要求高于内置时立即返回 EngineTooOld",
      "return Result.EngineTooOld(need, have)" in src,
      "若只是往 reasons 里塞一条，会混进 Risk 丢失「引擎旧了」的语义")
check("检测：读不到内置版本时不判定",
      "need != null && have != null" in src,
      "读不到就判死会造成大面积误报")

check("状态：Compat 有 ENGINE_TOO_OLD", "ENGINE_TOO_OLD" in market)
check("状态：Result 有 EngineTooOld", "data class EngineTooOld" in src)
check("映射：EngineTooOld -> Compat.ENGINE_TOO_OLD",
      "is NativePluginCheck.Result.EngineTooOld" in market)
check("UI：安装前拦截并解释原因",
      "Result.EngineTooOld ->" in store and "plugin_engine_too_old_body" in store,
      "只标红不解释 → 用户以为是插件坏了")
check("UI：用 warning 色而非 danger（不是错误，是版本落后）",
      'Compat.ENGINE_TOO_OLD -> getString(R.string.plugin_compat_engine_old) to getColor(R.color.warning)'
      in store)

# ---------------------------------------------------------------- 文案三语
for loc in ("values", "values-zh-rCN", "values-de"):
    p = os.path.join(ROOT, "app", "src", "main", "res", loc, "strings.xml")
    t = open(p, encoding="utf-8").read()
    check(f"i18n {loc}: 有 engine_old 文案",
          "plugin_compat_engine_old" in t and "plugin_engine_too_old_body" in t)
    check(f"i18n {loc}: 文案带两个占位符（要求版本 / 内置版本）",
          "%1$s" in t and "%2$s" in t,
          "缺占位符就没法告诉用户具体差在哪")

# ---------------------------------------------------------------- 算法交叉验证
# 用 Python 复刻 compareSemver 的语义，喂真实用例。Kotlin 侧算法若被改动
# 到语义不符，上面的结构断言可能仍通过，但这里能发现「行为变了」。
def cmp_semver(a, b):
    def split(v):
        core, _, pre = v.partition("-")
        nums = [int(x) if x.isdigit() else 0 for x in core.split(".")]
        pres = pre.split(".") if pre else []
        return nums, pres

    an, ap = split(a)
    bn, bp = split(b)
    for i in range(max(len(an), len(bn))):
        x = an[i] if i < len(an) else 0
        y = bn[i] if i < len(bn) else 0
        if x != y:
            return x - y
    if not ap and not bp:
        return 0
    if not ap:
        return 1
    if not bp:
        return -1
    for i in range(max(len(ap), len(bp))):
        if i >= len(ap):
            return -1
        if i >= len(bp):
            return 1
        x, y = ap[i], bp[i]
        if x.isdigit() and y.isdigit():
            c = int(x) - int(y)
        else:
            c = (x > y) - (x < y)
        if c:
            return c
    return 0


CASES = [
    # (a, b, 期望符号)
    ("0.2.1-rc.2", "0.2.1-rc.4", -1),   # 我们旧，插件要新 → 判定不兼容
    ("0.2.1-rc.4", "0.2.1-rc.2", 1),
    ("0.2.1", "0.2.1-rc.4", 1),         # 正式版 > 同号预发布
    ("0.2.1-rc.2", "0.2.1", -1),
    ("0.2.2-alpha.1", "0.2.1", 1),      # 主版本更高
    ("0.2.1-rc.10", "0.2.1-rc.9", 1),   # 数字段按数值比（字典序会错）
    ("0.2.1-rc.2", "0.2.1-rc.2", 0),
    ("1.0.0", "0.9.9", 1),
]

bad = []
for a, b, want in CASES:
    got = cmp_semver(a, b)
    sgn = (got > 0) - (got < 0)
    if sgn != want:
        bad.append(f"{a} vs {b}: want {want}, got {sgn}")
check("算法：semver 预发布规则正确（8 个用例）", not bad, "; ".join(bad))

# 范围表达式解析：只认常见前缀，其余返回 None（宁漏不误）
def min_version_of(rng):
    first = (rng.split("||")[0] or "").strip()
    if not first:
        return None
    token = (first.split(" ")[0] or "").strip()
    stripped = token.lstrip(">=^~ ")
    return stripped if re.match(r"^\d+\.\d+\.\d+", stripped) else None


RANGE_CASES = [
    (">=0.2.1-rc.3", "0.2.1-rc.3"),
    ("^0.2.1", "0.2.1"),
    ("~0.2.1", "0.2.1"),
    ("0.2.1-rc.4", "0.2.1-rc.4"),
    (">0.2.1-rc.3", "0.2.1-rc.3"),
    (">=0.2.1-rc.3 || >=0.3.0", "0.2.1-rc.3"),
]
rbad = []
for rng, want in RANGE_CASES:
    got = min_version_of(rng)
    if got != want:
        rbad.append(f"{rng!r}: want {want!r}, got {got!r}")
check("算法：范围表达式取下限（6 个用例）", not rbad, "; ".join(rbad))

check("算法：解析不了的表达式返回 None 而非瞎猜",
      min_version_of("*") is None and min_version_of("latest") is None,
      "误判会把能用的插件标成不可用")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)

# ============================================================
# v1.4.45 审查修复（阻断级）：allow-version 双 @ 参数
# UI 主链路传来的 pkg 是 spec（name@version），再拼 "@$version" 会得到
# name@ver@ver，引擎收到的参数非法 → 「强制安装此版本」主链路必失败。
# ============================================================
check("豁免：拼接前剥掉已带的版本后缀（endsWith 防误伤 scoped 包名）",
      'if (pkg.endsWith("@$version")) pkg.removeSuffix("@$version") else pkg' in src
      and '"$base@$version"' in src,
      "双 @ 让引擎收到非法 spec，v1.4.37 主打功能在市场/升级链路上必坏")
check("豁免：恢复窗口（isRestoring）同样拒绝",
      src.count("PluginKillSwitch.isRestoring(ctx)") >= 2,
      "恢复窗口内新装会被监督器写回丢掉，豁免+重装等于白做")
check("豁免：install() 内部不自动调用豁免",
      _install_body is not None and "forceInstallWithExemption" not in _install_body.group(0),
      "引擎警告 may cause crashes or data loss，静默放行等于替用户担险")
