#!/usr/bin/env python3
"""用户反馈「插件下不了」的回归护栏（v1.4.37）。

## 事件

用户截图给了两种不同的失败，UI 上都只显示半截错误：
1. `失败：dsh: installation rejected:` —— 冒号后面什么都没有
2. `失败：Error: ERR_PNPM_IGNORED_BUIL` —— 错误码被截断

## 根因（两个独立问题）

**问题 1：引擎兼容性预检拒绝**
引擎 `dsh-plugin-manager` 在安装**前**调 `evaluatePluginCompatibility`：
插件 `engines.dsh` 要求的最低版本高于内置引擎时不通过，回滚并输出
`dsh: installation rejected: Plugin X@Y is incompatible with dsh Z: requirements {...}`。
旧实现把 stderr 截到 300 字符，正好截在冒号处 —— 真正的原因（以及引擎自己
给出的解法 `dsh plugin allow-version`）全被丢掉。

**问题 2：pnpm 默认不执行依赖 build 脚本**
pnpm 10+ 把依赖的 install/postinstall 列为 "ignored builds"，只警告不执行。
带原生模块的插件装完但没编译，`build/` 不存在 → 插件加载失败。

## 本探针守住什么

- stderr 截断长度够拿到真实原因（≥1000）
- 引擎拒绝被解析成结构化数据，并给用户可读解释（走 strings.xml）
- **豁免必须用户显式确认**（引擎原文：may cause crashes or data loss）
- pnpm 白名单只放当前包，**禁止 `*`**（通配 = 允许所有依赖执行任意脚本）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
                  "engine", "PluginCenter.kt")
STR = os.path.join(ROOT, "app", "src", "main", "res", "values", "strings.xml")

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


src = open(KT, encoding="utf-8").read()
strings = open(STR, encoding="utf-8").read()

# ------------------------------------------------ 问题 1：stderr 截断
# v1.4.64：不再用 take(N) 只留头部——pnpm 的 `Ignored build scripts:` 警告
# 落在输出末尾，只留头会把它整段切掉（批准入口永不出现的根因之一）。
check("截断：失败输出保留头尾各一段（不能只留头部）",
      "clipHeadTail" in src and "takeLast(half)" in src,
      "300 字符正好截在 'installation rejected:' 冒号处；只留头又会切掉末尾的"
      "Ignored build scripts 警告")
# 注意：成功分支的 stdout 仍用 take(300)，那是给 UI 显示的摘要，没问题。
# 要守的是**失败分支的 stderr** —— 引擎把原因写在 rejected 之后。
check("截断：失败分支的 stderr 不再 take(300)",
      not re.search(r"err,\s*Charsets\.UTF_8\)\.trim\(\)\.take\(300\)", src),
      "旧值会截掉引擎给出的解法提示")

# ------------------------------------------------ 问题 1：解析与提示
check("解析：有 parseEngineRejection", "fun parseEngineRejection" in src)
check("解析：识别 installation rejected 关键字",
      "installation rejected" in src,
      "引擎原文用的是这个措辞")
check("解析：能取出插件名@版本",
      'Plugin\\s+(\\S+?)@' in src or "Plugin\\s+" in src,
      "需要它来拼 allow-version 的 packageVersion")
check("解析：能取出 requirements",
      "requirements" in src)

# ------------------------------------------------ 问题 1：豁免必须显式确认
check("豁免：提供 forceInstallWithExemption", "fun forceInstallWithExemption" in src)
check("豁免：走官方 allow-version 命令",
      '"allow-version"' in src,
      "不手写 compatibility.json，走引擎自己的 API 才能保证格式正确")
check("豁免：必须带 --accept-risk",
      "--accept-risk" in src,
      "引擎强制要求显式确认风险，App 不能替用户静默接受")
check("豁免：必须带 --dsh-version",
      "--dsh-version" in src,
      "引擎会校验 runtime 版本与当前运行的一致")
# 豁免必须是独立入口，**不能**从 install() 里自动调用 ——
# 引擎原文「may cause crashes or data loss」，静默放行等于替用户承担风险。
_install_body = re.search(r"fun install\([\s\S]*?\n    \}", src)
check("豁免：install() 内部不自动调用豁免",
      _install_body is not None and
      "forceInstallWithExemption" not in _install_body.group(0),
      "自动放行会让用户在不被告知的情况下承担崩溃/丢数据风险")
check("豁免：install() 只做识别与提示",
      _install_body is not None and "parseEngineRejection" in _install_body.group(0))

# ------------------------------------------------ 问题 2：pnpm ignored builds
check("pnpm：识别 ERR_PNPM_IGNORED_BUILDS", "ERR_PNPM_IGNORED_BUILDS" in src)
check("pnpm：识别被截断的错误码", "ERR_PNPM_IGNORED_BUILD\"" in src or
      "ERR_PNPM_IGNORED_BUILD'" in src,
      "用户截图里错误码被 UI 截成了 ERR_PNPM_IGNORED_BUIL")
# v1.4.67：照抄上游 build-approval.js。pnpm 11 删除了 onlyBuiltDependencies，
# 策略位改成 pnpm-workspace.yaml 的 allowBuilds 映射；旧的 .npmrc 写法无效。
check("pnpm：策略位是 allowBuilds（不是已废弃的 onlyBuiltDependencies）",
      "allowBuilds" in src,
      "pnpm 11 起 onlyBuiltDependencies 被删除，写 .npmrc 永远不会生效")
check("pnpm：不再往 .npmrc 写白名单",
      "onlyBuiltDependencies=" not in src,
      "pnpm 11 起 .npmrc 只读 auth/registry/TLS/proxy/local-address，"
      "写 onlyBuiltDependencies= 从根上不生效（且会留下无效键）")
check("pnpm：占位值沿用上游原文",
      "set this to true or false" in src,
      "pnpm 用它标记「还没决定」的构建脚本，识别错就永远等不到批准")
_approve = re.search(r"fun approveBuilds.*?getOrDefault", src, re.S)
check("pnpm：approveBuilds 内部有「文件不存在就拒绝」的防线",
      _approve is not None and "if (!f.isFile) return@runCatching false" in _approve.group(0),
      "v1.4.59 事故：凭空造 pnpm-workspace.yaml 导致所有插件装不了")
# v1.4.64：上游 recordPendingBuilds 会**主动写**占位值（pnpm 非交互运行不会自己写）。
# v1.4.63 只实现了「回读文件」，文件里永远没有占位值 → 批准入口永不出现，
# 用户看到的还是 pnpm 原文报错。这是「还是同样的报错」的根因。
_record = re.search(r"fun recordPendingBuilds.*?getOrDefault", src, re.S)
check("pnpm：照抄上游 recordPendingBuilds —— 主动把占位值写进策略文件",
      _record is not None and "UNDECIDED_BUILD" in _record.group(0)
      and "writePolicyAtomic" in _record.group(0),
      "不做这一步，非交互运行下待批准名单永远是空的")
check("pnpm：recordPendingBuilds 只写合法 npm 包名",
      _record is not None and "matches(NPM_PACKAGE_NAME)" in _record.group(0),
      "把 pnpm 的说明性文字写成 YAML 键会把策略文件写坏")
check("pnpm：install() 失败分支会自动放行 pnpm 点名的包并重试",
      "allowBuildsFor(ctx, names)" in _install_body.group(0),
      "只把名字交给 UI 而不放行 = 用户点了也装不上（v1.4.63~v1.4.66 的死结）")
check("pnpm：自动放行后确实重试了同一个 spec",
      _install_body.group(0).count("runDshPluginCli(ctx, \"add\", spec)") >= 2,
      "放行了不重试 = 用户还得再点一次，等于没修")
# pnpm 的 `Ignored build scripts:` 警告在输出**末尾**，只留前 N 字符会整段切掉
check("pnpm：错误输出保留头尾（不能只留头部）",
      "clipHeadTail" in src and "takeLast(half)" in src,
      "警告落在输出末尾，只留前 1200 字符会让 ignoredBuildNames 永远解析不到")
# pnpm 的原文是一句英文：`Ignored build scripts: esbuild, sharp.` ——
# 最后一个包名天然带句号，而 npm 包名不允许以 `.` 结尾。不剥掉的话
# NPM_PACKAGE_NAME 校验会把这个包整个丢掉，用户永远批不了它（纯函数测试实测发现）。
check("pnpm：剥掉包名尾部句号（pnpm 原文是英文句子）",
      "trimEnd('.', ';', ':')" in src,
      "`Ignored build scripts: esbuild, sharp.` 的句号会被当成包名的一部分")
check("pnpm：报告里的包名过 NPM_PACKAGE_NAME",
      "filter { it.matches(NPM_PACKAGE_NAME) }" in src,
      "把说明性文字当包名写进 YAML 会把策略文件写坏")
check("pnpm：失败时合并 stderr 与 stdout",
      "val combined = if (out.isNotBlank()) raw" in src,
      "pnpm 的 ignored-builds 是警告，可能走 stdout，只拼 stderr 会解析不到")
check("pnpm：形状认不出就放弃改写",
      "return null" in src and "layout not recognised" in src or "refusing to write" in src,
      "按行改 YAML 遇到内联列表/锚点必须放弃，不能硬写")
check("pnpm：批准前校验 npm 包名",
      "matches(NPM_PACKAGE_NAME)" in src,
      "包名注入可改写别的 pnpm 策略（供应链防线）")
check("pnpm：写临时文件再 rename",
      "f.name + \".tmp\"" in src or 'f.name + ".tmp"' in src,
      "写一半失败会留下坏配置，插件全部装不了")
check("pnpm：只批准仍待决定的包（上游 stale-approval 语义：任一不在待定就整体拒绝）",
      "it !in pending" in src,
      "批准已被拒绝/已批准的包等于绕过用户的拒绝决定")
check("pnpm：待定过滤不是部分批准（不能只挑出在 pending 里的那几个就写）",
      "names.filter { it in pending }" not in src,
      "部分批准 = 拿着过期名单重试会静默写出用户没确认过的批准")
# v1.4.67 起设计改为「自动放行 pnpm 点名的具体包 + 重试」（早期 v1.4.37 就是
# 这么做的，宝宝反馈「之前都不会」证明这条路可行）。自动批准本身可接受，
# 但**授权范围**必须严格受限，否则就是替用户敞开供应链。
check("安全：自动放行绝不写通配",
      "!it.contains('*')" in src,
      "通配 = 允许所有传递依赖跑任意 postinstall，经典供应链攻击面")
check("安全：自动放行的包名必须过 NPM_PACKAGE_NAME",
      "allowBuildsFor" in src and
      re.search(r"fun allowBuildsFor.*?getOrDefault", src, re.S) is not None and
      "matches(NPM_PACKAGE_NAME)" in
      (re.search(r"fun allowBuildsFor.*?getOrDefault", src, re.S).group(0)),
      "包名可注入 YAML（如换行 + \"*\": true），绕过白名单")
check("安全：只写 pnpm 自己点名的包，不放行整个依赖树",
      "ignoredBuildNames(msg)" in _install_body.group(0),
      "凭空放大放行范围 = 静默批准用户没同意的包")

# ------------------------------------------------ i18n
check("i18n：不硬编码中文提示", "此插件要求" not in src and "无法读取" not in src,
      "项目门禁禁止 Kotlin 字符串里写 CJK，文案必须走 strings.xml")
for key in ("plugin_engine_old_why", "plugin_engine_old_req",
            "plugin_engine_old_note", "plugin_engine_old_no_version",
            "plugin_engine_old_exempt_failed"):
    check("i18n：strings.xml 有 %s" % key, 'name="%s"' % key in strings)

# ------------------------------------------------ 回归：旧自愈不能丢
check("keep：pnpm store 锁自愈仍在", "isPnpmStoreLockError" in src and "healPnpmStore" in src,
      "v1.4.18 的修复不能被这次改动挤掉")
check("keep：插件装箱串行执行器仍在", "installExecutor.execute" in src,
      "并发装插件会损坏 node_modules")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
