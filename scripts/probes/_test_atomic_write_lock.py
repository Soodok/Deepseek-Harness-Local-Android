#!/usr/bin/env python3
"""插件安装 EACCES on package.json.lock 的修复护栏（v1.6.18）。

## 用户报错（真机 1.5.0 / Node v24.22.2）

    Error: EACCES: permission denied, open
      '/data/user/0/app.dsh.mobile/files/dsh-home/profiles/web/package.json.lock'
        at async withFileLock (…/@deepseek-ai/dsh-atomic-write/lib/index.js:197:4)
        at async runPlugin (…/@deepseek-ai/dsh/lib/plugin-DscIHF4k.js:94:25)

插件中心装 `@yolk_vat-y/dsh-project-memory` 直接失败，报错每次都一样。

## 为什么已有的两套自愈都救不了

| 自愈 | 认的错误码 |
| --- | --- |
| `isPnpmStoreLockError` | `ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK` |
| `isModulesDirError` | `ERR_PNPM_PACKAGE_MANAGER_REMOVE_MODULES_DIR` |

这条是**引擎自己的** `dsh-atomic-write` 在写 profile 清单时持锁失败，既不经过
pnpm、也不碰 node_modules —— 两个判据都不命中，直接上抛给用户。

它和 `healProfileOwnershipIfNeeded`（#100）修的也不是同一堵墙：

| 场景 | 报错落点 | 修法 |
| --- | --- | --- |
| profile **目录**属主是 root | `.../profiles/web/package.json` | 目录属主自愈 |
| 目录正常，**锁文件**属主是 root | `.../package.json.lock` via withFileLock | 陈旧锁清理 |

两道互补，缺一条另一半场景继续失败。

## 根因（模拟器实测复现）

上游 `withFileLock()` 用 `writeFile(lockPath, pid, { flag: "wx" })` 建锁，失败时
只把两种情形当「竞争、可以等」：

- `EEXIST` —— 锁已存在
- `EPERM` + 重新 lstat 确认存在 —— Windows 独占创建语义

**`EACCES` 不在名单里，原样 throw。** 于是只要那个 `.lock` 属主不是 App
（root 模式跑过 pnpm、或用 `su` 手动装过插件就会留下 `root:root 600`），
引擎自己永远无法接管。

实测把锁造成 `root:root 600` 后：

    app 身份写入   → Permission denied      ← 与用户报错逐字相同
    app 身份 unlink → 成功（删文件只看父目录权限，不看文件本身）

最后一行是修法依据：**删得掉**，所以可以在调 CLI 前清掉陈旧锁，不用 root。

## 修法边界（本探针守的就是这些）

- 只删**属主不是当前 uid** 的 `.lock` —— 正常安装里的活锁属主恒为自己，
  天然被排除，绝不跟进行中的安装抢锁
- 取不到属主（`owner <= 0`）时不删，宁可留给人工判断
- 扫描范围覆盖 dsh-home 下所有已知写点，不只 `profiles/web`
  （#100 的真机报错第二处 EACCES 落在 `.cache/node/corepack/lastKnownGood.json`）
- 读属主走 `Files.getAttribute(unix:uid)` 而不是 fork `stat`：
  每次装插件前都会扫一遍，逐文件 fork 在低端机上是几百毫秒级开销
- 预清理挂在 `runDshPluginCli()` 入口，覆盖 add/remove/本地 tgz 全部路径
- 清理失败只记日志，不阻断安装（最坏情况退回原来的报错，不变更差）
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PC = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine", "PluginCenter.kt")

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


src = open(PC, encoding="utf-8").read()
no_block = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
code = re.sub(r"//[^\n]*", "", no_block)


def fn_body(signature_re, span=3000):
    """取某个函数体的源码（剥注释后），找不到返回 None。"""
    m = re.search(signature_re + r"\s*\{(.{0,%d}?)\n    \}" % span, code, re.S)
    return m.group(1) if m else None


print("== A. 不要复活死代码 ==")

# v1.6.18 最初写过一个 isAtomicWriteLockError() 报错签名判定，但它是**死代码**：
# 清理挂在 CLI 入口无条件执行，不需要先分类报错。已删除。
# 这条断言防日后有人「看起来更严谨」又把它加回来 —— 那会让清理变成有条件的，
# 而条件（报错文案）在不同 pnpm / engine 版本里会变。
check("A1 死代码 isAtomicWriteLockError 已删除",
      "isAtomicWriteLockError" not in code,
      "清理在 CLI 入口无条件跑，不需要报错分类")
check("A2 仍保留真机报错原文（便于日后定位）",
      "package.json.lock" in src and "withFileLock" in src)
check("A3 根因写清了「上游只认 EEXIST/EPERM」",
      "EEXIST" in src)
check("A4 注释给出「删得掉」的实测依据",
      "unlink" in src and "只看父目录权限" in src)

print("")
print("== B. 清理逻辑的边界（核心：不许碰活锁）==")

heal = fn_body(r"private fun healForeignLocks\(ctx: Context\): Int")
check("B1 healForeignLocks 存在", heal is not None)
if heal:
    dirs = fn_body(r"private fun lockScanDirs\(ctx: Context\): List<File>", 1500)
    dirs_body = dirs or ""
    check("B2 扫描目录含 profiles / .cache/node / .local/share",
          "profiles" in dirs_body and ".cache/node" in dirs_body
          and ".local/share" in dirs_body,
          "只扫 profiles/web 会漏掉 corepack 那处 EACCES")
    check("B3 扫描目录按 isDirectory 过滤（不存在的不白跑）",
          re.search(r"filter\s*\{\s*it\.isDirectory", code) is not None)
    check("B4 只处理 *.lock 文件",
          re.search(r'endsWith\(\s*"\.lock"\s*\)', heal) is not None)
    check("B5 比较属主与当前 uid",
          "myUid()" in heal and "ownerUidOf" in heal,
          "不比属主就会把正在用的活锁也删掉")
    check("B6 取不到属主时不删（owner <= 0 守卫）",
          re.search(r"owner\s*<=\s*0\s*\|\|\s*owner\s*==\s*uid", heal) is not None)
    owner = fn_body(r"private fun ownerUidOf\(f: File\): Int =")
    owner_body = owner or ""
    # ⚠️ ownerUidOf 是**表达式体**（`= runCatching { ... }.getOrDefault(-1)`），
    # 不是块体 `{ }`。fn_body 的收尾锚 `\n    }` 对它失效，所以直接对
    # 定义位置之后的一段源码做断言（直到下一个成员声明）。
    if not owner_body:
        m_o = re.search(
            r"private fun ownerUidOf\(f: File\): Int =(.{0,600}?)\n\n",
            code, re.S,
        )
        owner_body = m_o.group(1) if m_o else ""
    check("B7 读属主走 Files.getAttribute(unix:uid)",
          "unix:uid" in owner_body and "getAttribute" in owner_body,
          "换成 fork stat 会让每次装插件多几百毫秒")
    check("B8 不许退回 fork /system/bin/stat",
          "/system/bin/stat" not in code)
    check("B9 删除失败不抛，只记日志",
          "cannot remove" in heal)
    check("B10 返回删除计数（便于日志与测试）",
          re.search(r"return\s+removed", heal) is not None)
else:
    for n in ("B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9", "B10"):
        check("%s (跳过：B1 未命中)" % n, False)

print("")
print("== C. 挂点覆盖所有 CLI 调用 ==")

# 预清理必须在 runDshPluginCli 内部：一处覆盖 add/remove/本地 tgz 全部路径
# ⚠️ 参数表里有 `extraArgs: List<String> = emptyList()`，所以**不能**用 [^)]*
# 去截参数 —— 它会在 emptyList() 的括号处提前闭合，永远匹配不到真实签名。
cli = fn_body(
    r"private fun runDshPluginCli\(.{0,300}?\): Pair<Boolean, String>",
    6000,
)
check("C1 runDshPluginCli 被定位到", cli is not None)
if cli:
    check("C2 预清理在 runDshPluginCli 内部调用",
          "healForeignLocks(ctx)" in cli,
          "挂在 CLI 入口才能覆盖 add / remove / 本地 tgz 三条路径")
    i_heal = cli.find("healForeignLocks(ctx)")
    i_spawn = cli.find("ProcessBuilder(")
    check("C3 预清理在起子进程之前",
          i_heal != -1 and i_spawn != -1 and i_heal < i_spawn,
          "heal@%d spawn@%d" % (i_heal, i_spawn))
    check("C4 清理包在 runCatching 里（失败不阻断安装）",
          re.search(r"runCatching\s*\{[^}]{0,300}healForeignLocks", cli, re.S) is not None)
else:
    for n in ("C2", "C3", "C4"):
        check("%s (跳过：C1 未命中)" % n, False)

# 所有 CLI 调用点都走同一个函数（没有旁路）
calls = re.findall(r"runDshPluginCli\(ctx,\s*\"(\w+)\"", code)
check("C5 所有 CLI 调用都经 runDshPluginCli（无旁路起子进程）",
      len(calls) >= 4,
      "找到 %d 处调用: %s" % (len(calls), sorted(set(calls))))
direct = len(re.findall(r"\"plugin\",\s*\"--profile\"", code))
check("C6 只有一处拼 plugin CLI 参数", direct == 1,
      "找到 %d 处（多处意味着有旁路，预清理覆盖不到）" % direct)

print("")
print("== D. 不破坏已有的自愈（含互补的属主自愈）==")

check("D1 isPnpmStoreLockError 仍在", "isPnpmStoreLockError" in code)
check("D2 isModulesDirError 仍在", "isModulesDirError" in code)
check("D3 healPnpmStore 仍在", "healPnpmStore(" in code)
check("D4 healSelinuxContext 仍在", "healSelinuxContext(" in code)
check("D5 目录属主自愈仍在（与锁清理互补，两道都要）",
      "healProfileOwnershipIfNeeded(" in code)
# 两个自愈都必须在 CLI 入口、且目录属主那个在前（先修可写性，再清锁）
if cli:
    i_own = cli.find("healProfileOwnershipIfNeeded(ctx)")
    i_lock = cli.find("healForeignLocks(ctx)")
    check("D6 两道自愈顺序：先属主、后锁",
          0 <= i_own < i_lock,
          "own@%d lock@%d" % (i_own, i_lock))
else:
    check("D6 两道自愈顺序：先属主、后锁", False, "C1 未命中")
check("D7 三个 signature 互不吞并（各认各的码）",
      "ERR_PNPM_STORE_DIR_OPEN_OPERATION_LOCK" in code
      and "ERR_PNPM_PACKAGE_MANAGER_REMOVE_MODULES_DIR" in code
      and "EACCES" in src)

print("")
print("-" * 44)
print("通过 %d / 失败 %d" % (passed, failed))
sys.exit(1 if failed else 0)
