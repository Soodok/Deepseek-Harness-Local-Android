# -*- coding: utf-8 -*-
"""内置插件离线化 + 装包判据改读数据库 —— 探针。

覆盖两个本轮修的真实缺陷：

1. `allInstalled()` 用 `apk info -e` 作兜底判据，而它在无 root 的 Android 上
   会踩跟 `apk add` 同一个坑（post-install 建管道被拦 → apk 判自己失败）。
   兜底形同虚设。改为直接读 `/lib/apk/db/installed`。

2. 内置插件 `@xiaopb/dsh-solution-explorer` 的 package.json 声明了
   dependencies（highlight.js / omni-doc-viewer），而代码已内联进 client.js
   bundle。声明残留让 `dsh plugin add` 必需联网 → 冷启动被硬拖。
   打包脚本要把它剥掉。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

checks = 0
failed = 0


def ok(name, cond, why=""):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + name)
    else:
        failed += 1
        print("  FAIL " + name + ("  [" + why + "]" if why else ""))


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        return ""
    return io.open(p, encoding="utf-8", errors="replace").read()


PKG = read("app/src/main/java/app/dsh/mobile/engine/LinuxPackageManager.kt")
PACK = read("scripts/pack-profile-plugin.sh")
ACT = read("app/src/main/java/app/dsh/mobile/LinuxActivity.kt")
PROV = read("app/src/main/java/app/dsh/mobile/engine/ProfilePluginProvisioner.kt")

print("=== A. 装包成功判据不再依赖 apk 进程 ===")

# A1 函数体里不能再出现 apk info -e
m = re.search(r"private fun allInstalled\(packages: List<String>\): Boolean =(.*?)\n\n",
              PKG, re.S)
body = m.group(1) if m else ""
ok("A1 allInstalled 不再调 apk info -e",
   body != "" and '"info"' not in body and '"-e"' not in body,
   "apk info -e 在无 root 容器里同样会被 post-install 的 pipe() 失败波及，兜底形同虚设")

ok("A2 allInstalled 直接读 apk 数据库文件",
   'lib/apk/db/installed' in body,
   "数据库是 apk 的权威记账：装成功的包一定在里面")

ok("A3 解析 P: 行取包名",
   "'P'" in body and "':'" in body,
   "installed 文件是段落式文本，P: 才是包名")

ok("A4 数据库文件不存在时保守返回 false",
   "db.isFile" in body and "defaultValue" not in body,
   "文件缺失说明根本没装过，不能报成功")

print()
print("=== B. 打包脚本剥掉不实装的依赖声明 ===")

ok("B1 脚本里有离线化步骤",
   "离线化" in PACK or "offline" in PACK,
   "用户要求内置插件直接内置、不要联网")

# ★清空动作已挪进独立脚本 scripts/strip-plugin-deps.js
# （node -e 里要写一个换行转义，被 shell / heredoc 吃掉后变成真换行 → 语法错误，
#   实测踩过 `Expected ',', got 'string literal'`）。
# 所以判据分两处：pack 脚本要**调用**它，独立脚本里才看得到清空语句。
STRIP = read("scripts/strip-plugin-deps.js")
ok("B2 会清空 dependencies（清空动作在独立脚本里）",
   "strip-plugin-deps.js" in PACK and "p.dependencies = {}" in STRIP,
   "不改的话 dsh plugin add 会拉 pnpm 联网解析；"
   "清空语句必须真的存在于被调用的那个脚本里")

ok("B3 重打 tgz 后替换原变量",
   'TGZ="$WORK/offline.tgz"' in PACK,
   "不替换的话落盘的还是带 dependencies 的旧包")

ok("B4 只改解包副本，不动上游原件",
   '"$WORK/pkg"' in PACK and "cp \"$TGZ\" \"$DEST" in PACK,
   "改上游 tgz 会破坏 npm 上的包与 sha256 校验")

print()
print("=== C. 用户能看到「ERROR 但不是失败」的解释 ===")

# ★两条断言缺一不可，各自挡一种改法：
#   · C1 挡「把说明文案删掉」—— 说明本身必须存在
#   · C2 挡「说明只在退出码非 0 时出现」—— 触发判断必须参与分支条件
#
# 第一版只写了 C2 形态的判据（查两个字符串是否存在），而把条件从
# `verified && r.ok && !triggersMayBeNoisy` 改回 `verified && r.ok` 时
# 两处字符串都还在、断言照样绿 —— 反向验证实测过。反过来只写 C1 也不行：
# 把条件改坏时说明仍在文件里。所以两条必须同时存在。
ok("C1 说明文案存在于 install() 的成功分支",
   "linux_pkg_trigger_note" in PKG,
   "实测设备输出 OK: 76 MiB 后面紧跟 ERROR: 7 errors，用户会以为没装上")

_c1_cond = re.search(r"if \(verified && r\.ok([^)]*)\)", PKG)
ok("C2 「触发噪声」判断真的参与了成功分支的条件",
   _c1_cond is not None and "triggersMayBeNoisy" in _c1_cond.group(1),
   "只定义不参与就等于没有：实测同一台设备两次装 nodejs，一次 exit≠0、"
   "一次 exit=0 却仍有 ERROR，说明挂在单一分支上必然漏")

ok("C2 说明文案五语齐全",
   all(
       ('name="linux_pkg_trigger_note"' in read("app/src/main/res/%s/strings.xml" % d))
       for d in ("values", "values-zh-rCN", "values-de", "values-pt-rBR", "values-th")
   ),
   "缺一语就会在对应语言下显示英文兜底")

print()
print("=== D. DNS 代理有落盘诊断 ===")

DNS = read("app/src/main/java/app/dsh/mobile/engine/LinuxDnsProxy.kt")
# ★必须验「真的往文件写」而不是「有没有 log 函数」。第一版只查
# `private fun log(` 与 `dns.log` 是否出现 —— 在 log() 开头插一句
# `if (true) return` 把写盘彻底短路，两条断言照样全绿（反向验证实测过）。
# 现在要求函数体里确实有 appendText 落盘调用。
_d1 = re.search(r"private fun log\(level: Char, msg: String\) \{(.*?)\n    \}", DNS, re.S)
_d1_body = _d1.group(1) if _d1 else ""
# ★还要排除「在写盘之前无条件 return」这种改法。只查 appendText 是否出现在
# 函数体里挡不住它（在开头插 `if (true) return` 照样绿）。
#
# ⚠️ 不能写成「函数体里不许有 return」—— log() 里本来就有一句合法的
# `val f = logFile ?: return`（logFile 未设置时只走 logcat，这是设计）。
# 第一版那样写导致基线条目自己变红（反向验证跑之前就失败了）。
#
# 正确判据：每一处 return 都必须是 `?: return` 这种**带条件的守卫**形态；
# 裸 `return`（如 `if (true) return`）一律判红。
_d1_returns = [ln.strip() for ln in _d1_body.splitlines() if "return" in ln]
_d1_bare = [ln for ln in _d1_returns if "?:" not in ln]
ok("D1 代理真的往文件追加写（不是只声明、也没被无条件短路）",
   "dns.log" in DNS
   and "appendText" in _d1_body
   and not _d1_bare,
   "logcat 取不到本 App 日志，只能落盘；无条件 return 会把写盘短路"
   + ("；裸 return=" + str(_d1_bare) if _d1_bare else ""))

ok("D2 forward 成败都记",
   'forward OK via' in DNS and 'forward FAILED both endpoints' in DNS,
   "只记成功等于失败时无信息")

ok("D3 日志文件有大小上限（不会无限增长）",
   "64 * 1024" in _d1_body,
   "无限增长会吃用户磁盘；这条只认 log() 函数体内的上限判断")

# ★必须验「失败分支真的调用了它」。第一版只查 `installDiagnosis` 是否出现 ——
# 函数定义那一行也含这个名字，把两处调用删掉照样绿（反向验证实测过）。
#
# ⚠️ 第二代判据写成了 `len(_d4) == 2`（「插值恰好两处」），它比被测对象**过窄**：
# 328d25d7 给 fixDependencies 也加上了诊断（变成 6 处），这个本该更安全的改动
# 反而把探针打红，CI 连红两次、android-build 却是绿的 —— 红的是判据不是代码。
# 正确判据是**语义**而不是计数：
#   1. 每一个「装包 / 刷新索引失败」的输出都必须带 ${installDiagnosis()}；
#   2. 两类失败点（索引刷新、装包）都必须至少各有一个 —— 少了任何一类，
#      「用户能自己定位到是哪一层」这个契约就断了一半；
#   3. 诊断本身必须真的带上端口（LinuxDnsProxy.currentPort()）。
# 这样以后再加装包路径只会更安全，不会被判红。
_FAIL_RE = re.compile(r'(index update failed|installCommand\}\s+\w*\s*failed)')
_act_lines = ACT.splitlines()
_d4_sites = []          # [(行号, 是否带诊断)]
for _i, _ln in enumerate(_act_lines):
    if not _FAIL_RE.search(_ln):
        continue
    # 失败文案可能跨两三行拼接（"…failed:\n" + "…${installDiagnosis()}"），
    # 所以按「这一行起往后 3 行」判定，而不是只看当行。
    _window = "\n".join(_act_lines[_i:_i + 3])
    _d4_sites.append((_i + 1, "installDiagnosis()" in _window))
_d4_missing = [ln for ln, has in _d4_sites if not has]
_d4_has_index = any("index update failed" in _act_lines[ln - 1] for ln, _ in _d4_sites)
_d4_has_install = any("installCommand" in _act_lines[ln - 1] for ln, _ in _d4_sites)
ok("D4 装包与刷新索引失败都附诊断（端口 + resolv.conf + 代理日志尾）",
   bool(_d4_sites)
   and not _d4_missing
   and _d4_has_index
   and _d4_has_install
   and "LinuxDnsProxy.currentPort()" in ACT,
   "把决定性事实一次性打出来，用户复现一次就能定位层；失败点="
   + str(len(_d4_sites))
   + ("，缺诊断的行=" + str(_d4_missing) if _d4_missing else "")
   + ("" if _d4_has_index else "，没有索引刷新失败点")
   + ("" if _d4_has_install else "，没有装包失败点"))

print()
print("=== E. 内置插件仍只有两个且都有 bundle 契约 ===")

# ★别数 BundledProfilePlugin( 的出现次数：data class 定义那一行也算一次，
# 实测会得 3 而不是 2（第一条断言就这样假红过）。改成数 REGISTRY 列表里的
# `pkg = ` 赋值，那才是真正的条目。
_reg = re.search(r"private val REGISTRY = listOf\((.*?)\n    \)", PROV, re.S)
_entries = _reg.group(1).count("pkg = ") if _reg else 0
ok("E1 REGISTRY 仍是两条内置插件",
   _entries == 2,
   "实际条目数=" + str(_entries) + "（data class 定义不算条目）")

ok("E2 solution-explorer 标记了 hasNpmDependencies",
   "hasNpmDependencies = true" in PROV,
   "离线判定要按声明判断；剥掉声明后这条会自然走离线路径")

print()
print("%d checks, %d failed" % (checks, failed))
sys.exit(1 if failed else 0)
