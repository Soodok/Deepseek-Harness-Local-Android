#!/usr/bin/env python3
"""真机「插件装不上」的四处根因护栏（v1.7.32）。

## 事件

2026-11-21 两条用户反馈：官方原生插件安装失败、插件中心安装失败。截图里
按钮上只有一串被截断的 file:///data/user/0/.../node_mod，没有原因。

## 四个独立根因（都在模拟器上单独复现过）

① @latest 覆盖本地来源，内置插件永久脱离 APK 资产
   install() 对已装包一律发 pkg@latest。内置插件的 spec 是 file:<staging tgz>，
   pnpm 收到后判定「来源变了」并原地改写，复现后 package.json 变成 ^0.4.12，
   不可逆。

② 暂存 tgz 缺失后 --fix-lockfile 必然 exit 1
   实测：lockfile 坏 + tgz 在 -> RC=0 且 importers 恢复；lockfile 坏 + tgz 缺失
   -> RC=1，一个字节都不改。旧实现无条件跑它，然后报 fix-lockfile ran, still
   failing，把「资产丢了」误报成「lockfile 修不动」。

③ 120 秒固定墙钟砍掉正常的大插件下载
   @yolk_vat-y/dsh-project-memory（leanvec 83MB + pdfjs-dist）在干净模拟器上
   就要 133 秒（实测 2m13s），弱网必被砍。而 pnpm 下载期最大无输出间隔只有
   3 秒（--reporter=append-only 实测），所以「慢」和「卡死」可判别。

④ 摘要兜底把位置行当原因
   body.take(60) 对「整份 msg 只剩 file:// 路径」的输入，正好截出那串 60
   字符路径 —— 与用户截图逐字一致。

## 本探针守住什么

每条断言都指向函数体而非整文件字符串：注释里也写满了同样的关键字，拿全文
判定必然假绿（本项目已踩过：_test_plugin_install_errors.py 里
isBrokenLockfileError 那条就专门加了「必须是函数体」的理由）。
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENGINE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
KT = os.path.join(ENGINE, "PluginCenter.kt")
IES = os.path.join(ENGINE, "InstallErrorSummary.kt")
PPP = os.path.join(ENGINE, "ProfilePluginProvisioner.kt")
PSTORE = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile",
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


def strip_comments(text):
    """去掉 // 行注释与 /* */ 块注释，保留代码本体。

    为什么必须去注释：本项目所有 Kotlin 改动都带大段中文注释，而注释里恰好会
    复述被删掉的**旧代码**（例如 `// 旧实现 p.waitFor(120, SECONDS)`、
    `// 旧实现这里走 body.take(60)`）。直接在原文上断言「不含某字符串」，会被
    这些历史说明判成失败 —— 这是假红；反过来，注释里写着修复说明而代码没改，
    又会被判成通过 —— 这是假绿。两种都必须消灭。
    """
    out = []
    i = 0
    n = len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def fn_body(text, header, tail=r"\n    }"):
    """取一个函数的**去注释**函数体；取不到返回空串（后续断言会因此变红）。"""
    m = re.search(re.escape(header) + r"[\s\S]*?" + tail, strip_comments(text))
    return m.group(0) if m else ""


src = open(KT, encoding="utf-8").read()
ies = open(IES, encoding="utf-8").read()
ppp = open(PPP, encoding="utf-8").read()
pstore = open(PSTORE, encoding="utf-8").read()

# ============================================ ① 本地来源不得被 @latest 覆盖
spec_fn = fn_body(src, "internal fun isLocalOrDirectSpec(")
check("spec：有来源判定函数", spec_fn != "",
      "取不到函数体，后面几条无从谈起")
if spec_fn:
    check("spec：认 file: 前缀", "file:" in spec_fn)
    check("spec：认 link: 前缀", "link:" in spec_fn)
    check("spec：认 git+/github: 直连", "git+" in spec_fn and "github:" in spec_fn)
    check("spec：认裸绝对路径", 'startsWith("/")' in spec_fn)
    check("spec：空串不算本地来源", "isEmpty()" in spec_fn,
          "空 spec 若判成 true 会拼出 pkg@ 这种非法参数")

install = fn_body(src, "fun install(ctx: Context")
check("spec：install() 函数体取到了", install != "")
if install:
    check("spec：install() 读得到已装 spec", "installedSpecs(ctx)[pkg]" in install,
          "只判在不在 dependencies 里，拿不到 spec 就无法区分本地与远端")
    check("spec：本地来源原样重装，不追 latest",
          "isLocalOrDirectSpec(installedSpec)" in install)
    check("spec：远端范围才用 @latest", '"$pkg@latest"' in install)
    check("spec：决策打了日志（真机可回读）",
          "installed=$installedSpec" in install,
          "真机上只能靠 logcat 取证，没有这行就无法确认走的是哪个分支")

specs_fn = fn_body(src, "fun installedSpecs(ctx: Context)")
check("spec：installedSpecs 读的是 dependencies 原样值",
      specs_fn != "" and "optString" in specs_fn)
check("spec：installedPackages 改为复用 installedSpecs",
      "installedSpecs(ctx).keys" in src,
      "两处各读一遍 package.json 迟早漂移")

# ============================================ ② 资产缺失先重建，再谈修 lockfile
missing = fn_body(src, "internal fun missingLocalSpecs(")
check("lock：有本地依赖目标缺失探测", missing != "")
if missing:
    check("lock：相对路径基于 profiles/web 解析",
          "File(profile, raw)" in missing and '"profiles/web"' in missing,
          "相对 file: 是相对 profile 目录的，基于别处解析会全部误判缺失")
    check("lock：只查 file: 与裸路径，不碰 link/git/http",
          "link:" not in missing and "http" not in missing,
          "link: 指向工作区目录、git+/http 要联网，都不该在这里判定成了")
    check("lock：用 exists() 判存在", ".exists()" in missing)

heal = fn_body(src, "private fun healBrokenLockfile")
check("lock：healBrokenLockfile 函数体取到了", heal != "")
if heal:
    check("lock：跑 fix 之前先查缺失目标", "missingLocalSpecs(ctx)" in heal,
          "目标不存在时 --fix-lockfile 必 exit 1 且不改文件（实测）")
    check("lock：缺失时直接放弃，不去跑注定失败的修复",
          "refusing to run" in heal,
          "跑到 --fix-lockfile 那一步只会得到 exit 1，报的仍是 fix 失败")
    check("lock：缺失判定在真正调用 pnpm 之前",
          "missingLocalSpecs" in heal and
          heal.index("missingLocalSpecs") < heal.index("nodeBin(ctx)"),
          "顺序反了等于没修：先起进程再判缺失，白白多跑一次 pnpm")

restore = fn_body(ppp, "fun restoreMissingStaged(")
check("staging：有重建暂存 tgz 的入口", restore != "")
if restore:
    check("staging：只为已缺失的文件重建",
          "staged.isFile" in restore and "return@runCatching" in restore,
          "无条件覆盖会把正在用的 tgz 换成半份文件")
    check("staging：重建内容过 sha256 校验", "sha256" in restore,
          "不校验等于相信同名即同内容，半个文件会让 pnpm 解包失败")
    check("staging：校验不过就删掉，不留坏文件", "staged.delete()" in restore)

ensure_all = fn_body(ppp, "fun ensureAll(")
check("staging：开机就补回丢失的暂存 tgz",
      "restoreMissingStaged(ctx)" in ensure_all,
      "只在安装失败时补，等于用户每次都要先失败一次才自愈；资产丢失是持久的")

# 设备实测：目标缺失时的报错**不含** Broken lockfile 也不含
# ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY → 旧实现所有自愈分支都不命中，
# 错误原样抛给用户。所以「目标缺失」必须是独立判据 + 独立分支。
missing_err = fn_body(src, "internal fun isMissingLocalTargetError(")
check("staging：目标缺失有独立判据（不靠 broken-lockfile 兜）",
      missing_err != "",
      "实测报错里 Broken lockfile 计数为 0，靠它兜等于不兜")
if missing_err:
    check("staging：判据认 pnpm 的\"as it does not exist\"原文",
          "as it does not exist" in missing_err and "Could not install from" in missing_err)
    check("staging：判据也认 ENOENT + .tgz 形态",
          "ENOENT" in missing_err and ".tgz" in missing_err)

import re as _re
# 只查「函数名出现过」会假绿：把条件改成 `if (false && isMissingLocalTargetError(msg))`
# 函数名照样在，分支却永远不执行。所以必须连条件形态一起钉死。
_mb = _re.search(r"if \((!ok && )?isMissingLocalTargetError\(msg\)\)", install)
missing_branch = bool(_mb) and _mb.group(1) is not None
check("staging：install() 里有目标缺失的自愈分支（条件未被短路）", missing_branch,
      "条件写成 false && ... 时函数名仍在源码里，只查出现会假绿")
if missing_branch:
    seg = install[install.index("isMissingLocalTargetError(msg)"):]
    seg = seg[:seg.find("isBrokenLockfileError(msg)")] if "isBrokenLockfileError(msg)" in seg else seg
    check("staging：目标缺失分支会重建资产", "restoreMissingStaged(ctx)" in seg)
    check("staging：重建后重试同一个 spec", 'runDshPluginCli(ctx, "add", spec)' in seg,
          "重试要带同一个 spec，否则会把本地依赖换成 registry 版本")

check("staging：install() 的 lockfile 分支先重建再修",
      "restoreMissingStaged(ctx)" in install and
      install.index("restoreMissingStaged") < install.index("healBrokenLockfile"),
      "先修 lockfile 会被缺失资产再次弄坏，顺序必须是先补资产")
check("staging：重建后仍失败时给出可操作提示",
      "rebuilt" in install and "please retry" in install,
      "把「资产丢了」误报成「lockfile 修不动」正是这次要修的东西")

# ============================================ ③ 双阈值超时（慢 != 卡死）
cli = fn_body(src, "private fun runDshPluginCli(")
check("timeout：runDshPluginCli 函数体取到了", cli != "")
if cli:
    check("timeout：不再用一次性 120 秒墙钟",
          "waitFor(120, TimeUnit.SECONDS)" not in cli and
          "waitFor(120, SECONDS)" not in cli,
          "大插件在模拟器上就要 133 秒，120s 必砍")
    check("timeout：每 500ms 轮询一次",
          "waitFor(500, TimeUnit.MILLISECONDS)" in cli)
    check("timeout：读线程每次读到字节就刷新时间戳",
          "lastOutputAt.set(" in cli,
          "刷不到时间戳，空闲判据永远命中，等于回到固定超时")
    check("timeout：同时有「无输出」与「硬上限」两个阈值",
          "CLI_IDLE_TIMEOUT_MS" in cli and "CLI_HARD_TIMEOUT_MS" in cli)
    check("timeout：空闲阈值是「连续无输出」而非累计时长",
          "now - lastOutputAt.get()" in cli,
          "拿总时长当空闲阈值，长下载照样被砍")
    check("timeout：超时文案区分卡死与太慢",
          "no output for" in cli and "hard limit" in cli,
          "两者处置不同：前者查网络/锁，后者只是慢")
    check("timeout：超时必须强杀并关流",
          "destroyForcibly()" in cli and "inputStream.close()" in cli,
          "不强杀，installExecutor 这个单线程池会被永久占住")

idle_m = re.search(r"CLI_IDLE_TIMEOUT_MS\s*=\s*([0-9_]+)L", src)
idle = int(idle_m.group(1).replace("_", "")) if idle_m else 0
check("timeout：空闲阈值远大于实测最大无输出间隔（3s）", idle >= 30_000,
      "实测 pnpm 下载期最大无输出间隔 3s；阈值低于 30s 就变成随机误杀")

hard_m = re.search(r"CLI_HARD_TIMEOUT_MS\s*=\s*([0-9]+)\s*\*\s*([0-9_]+)L", src)
hard = (int(hard_m.group(1)) * int(hard_m.group(2).replace("_", ""))) if hard_m else 0
check("timeout：硬上限不低于 30 分钟", hard >= 30 * 60_000,
      "底层先杀、UI 还在转圈（或反之）都会让用户看到自相矛盾的状态")

wd = fn_body(pstore, "private fun doInstall(")
check("timeout：UI 看门狗函数体取到了", wd != "")
if wd:
    check("timeout：看门狗不再一次性把按钮放开",
          "WATCHDOG_MAX_ROUNDS" in wd and "armWatchdog" in wd,
          "pnpm 还在正常下载时放开按钮，用户二次点击会排在单线程池后面再下一遍")
    check("timeout：到点仍保持「安装中」并续挂下一轮",
          "armWatchdog(round + 1)" in wd,
          "只提示不续挂，等于又变回一次性超时")
    # 续挂与放手必须互斥且有终点：`if (round + 1 < MAX) armWatchdog(round + 1)`
    # 这种写法永远走不到 round == MAX（最后一轮不再续挂，也就不再进来判 last），
    # 按钮会永久停在禁用态 —— 用户想重试都没有入口。
    check("timeout：最后一轮会真的把按钮放开，不会永久禁用",
          "val last" in wd and "if (!last) armWatchdog(round + 1)" in wd,
          "续挂条件写成 round+1<MAX 时，最后一轮永远不执行放手分支")
    check("timeout：看门狗间隔与轮数都已常量化",
          "WATCHDOG_INTERVAL_MS" in pstore and "WATCHDOG_MAX_ROUNDS" in pstore)

# ============================================ ④ 摘要永不为裸路径
summary = fn_body(ies, "fun summarize(")
check("summary：summarize 函数体取到了", summary != "")
if summary:
    check("summary：兜底不把原文前 60 字符当摘要返回",
          not re.search(r"return\s+body\.take\(60\)", summary) and
          not re.search(r"return\s+text\.take\(60\)", summary),
          "正是它截出用户截图那串 60 字符 file:// 路径")
    check("summary：兜底返回 UNKNOWN 而不是原文", "UNKNOWN" in summary)
    check("summary：只剩位置行时只留「文件名:行号」",
          "substringAfterLast('/')" in summary and "isDigit()" in summary,
          "带行号是唯一确定的事实；没有行号说明被上游截断，只能认输")
    check("summary：原因链与首行也跳过位置行",
          summary.count("isLocationOnly(it)") >= 2)

loc = fn_body(ies, "internal fun isLocationOnly(")
check("summary：位置行判据函数体取到了", loc != "")
if loc:
    check("summary：判据放过含错误词的行", "rror" in loc and "ERR_" in loc,
          "真错误行里也会有 file://（Cannot find module ...），缺这条会把线索一起删掉")
    check("summary：判据也认无 scheme 的裸绝对路径", 'startsWith("/")' in loc,
          "CJS 栈头是 /data/.../x.cjs:1，没有 file:// 前缀")

print("\n" + "-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
