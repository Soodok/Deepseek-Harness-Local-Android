#!/usr/bin/env python3
"""Linux 镜像删除残留的静态断言探针（2026-11-13）。

为什么要有这个探针：「删除镜像删不干净」是用户在真机上反复报的症状，
而它的根因分布在四个互不相干的落点上，任何一处漏掉都会让症状回来：

  1. **属主交还**（root 属主目录 App 删不掉）—— 只加删除不加交还，
     删不掉的条目数不变，用户看到的还是「还有 N 项没能删掉」；
  2. **Node 缓存**（cache/linux-node，实测 30MB）—— 不清就一直占盘；
  3. **装包日志**（files/linux/state/apt-last.log）—— 同上；
  4. **激活指向**（dsh_linux_rootfs.xml）—— 不清会让页面指向已删目录。

反向验证（改坏必须变红）：
  1. 删掉 normalizeOwnership 调用                → A2 红
  2. normalizeOwnership 里去掉 -h                → A4 红
  3. 去掉 linux-node 清理                        → B2 红
  4. 去掉 apt-last.log 清理                      → B3 红
  5. 去掉 clearActive 调用                       → C1 红
  6. clearActive 只 remove 一个 key              → C3 红
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
checks = 0
failed = 0


def ok(name, cond, detail=""):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + name)
    else:
        failed += 1
        print("  FAIL " + name + (("  [" + detail + "]") if detail else ""))


def read(rel):
    with io.open(os.path.join(ROOT, rel), encoding="utf-8") as f:
        return f.read()


def kt_code(src):
    """剥掉 Kotlin 注释，避免断言被注释里的字面量满足（这个坑踩过多次）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith("//", i):
            j = src.find("\n", i)
            i = n if j < 0 else j
            continue
        if src.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth:
                if src.startswith("/*", i):
                    depth += 1
                    i += 2
                elif src.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
            continue
        if src[i] == '"':
            j = i + 1
            while j < n:
                if src[j] == "\\":
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(src[i:j])
            i = j
            continue
        out.append(src[i])
        i += 1
    return "".join(out)

ACT = kt_code(read("app/src/main/java/app/dsh/mobile/LinuxActivity.kt"))
PRIV = kt_code(read("app/src/main/java/app/dsh/mobile/engine/Privilege.kt"))
CAT = kt_code(read("app/src/main/java/app/dsh/mobile/engine/LinuxRootfsCatalog.kt"))
ENV = kt_code(read("app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt"))


def slice_fn(src, header, max_lines=140):
    """从函数头切到下一个同缩进的 `}` —— 只用于静态断言，不追求语法正确。"""
    i = src.find(header)
    if i < 0:
        return ""
    lines = src[i:].split("\n")
    out = [lines[0]]
    for ln in lines[1:]:
        out.append(ln)
        if ln.strip() == "}" and (len(ln) - len(ln.lstrip())) == 4:
            break
        if len(out) > max_lines:
            break
    return "\n".join(out)


DEL = slice_fn(ACT, "private fun deleteImage(", 200)
NORM = slice_fn(PRIV, "fun normalizeOwnership(", 40)
CLEAR = slice_fn(CAT, "fun clearActive(", 20)

print("== A. 属主交还 ==")
ok("A1 Privilege 暴露了 normalizeOwnership(ctx, dir)",
   "fun normalizeOwnership(" in PRIV,
   "没有这个方法，删除代码就无从把 root 属主拷回来")
ok("A2 deleteImage 调用了 normalizeOwnership",
   "normalizeOwnership(" in DEL,
   "只删不交还 = 删不掉的条目数不变")
ok("A3 normalizeOwnership 用 find -xdev 限定在树内",
   "-xdev" in NORM,
   "少了 -xdev，susfs / 隐藏挂载下遍历会卡死（v1.4.46 实测）")
ok("A4 normalizeOwnership 的 chown 带 -h（不跟随符号链接）",
   "chown -h" in NORM,
   "rootfs 里到处是指到树外的链接，chown -R 会改到系统其它位置")
ok("A5 normalizeOwnership 只对非本 uid 的条目动手（! -user）",
   "! -user " in NORM,
   "无差别 chown 会每轮改动几千个 inode 的 ctime")
ok("A9 交还时同时重打 SELinux MCS 分类（chcon -R -h）",
   "chcon -R -h" in NORM,
   "只 chown 不 chcon 时 App 域仍 Permission denied —— 裸 s0 条目读删全拦（真机实测）")
ok("A10 chcon 用父目录的真实上下文做基准，且写死不了类别",
   "stat -c '%C'" in NORM and "dir.parentFile" in NORM,
   "MCS 类别因设备而异（c243/c257/c512/c768），写死会在别的设备上打错标签")
ok("A11 基准本身是裸 s0 时放弃打标（不把树改成无分类）",
   "*:s0" in NORM and "CTX= ;;" in NORM,
   "拿裸 s0 当基准会把整棵树打成无分类，比不修更糟")
ok("A6 交还在第二趟、整树 rm 在第三趟（顺序不能反）",
   DEL.find("normalizeOwnership(") >= 0 and
   DEL.find("normalizeOwnership(") < DEL.find("runPrivileged("),
   "先 rm 再交还 = 白交还一遍（树已经被删掉大半）")
ok("A7 特权兜底走 runPrivileged（Shizuku 优先）而不是 runSu",
   "runPrivileged(" in DEL and "Privilege.runSu(" not in DEL,
   "runSu 只认 ROOT 模式；Shizuku 模式下永远是 null，兜底形同虚设")
ok("A8 特权 rm 之后仍以「目录是否真消失」为判据",
   "!dir.exists()" in DEL,
   "rm -rf 对部分失败也静默返回空输出，返回值为空不等于删成功")

print()
print("== B. 缓存与状态清理 ==")
ok("B1 rootfs 下载缓存按 family.id + \"-rootfs.\" 精确匹配",
   'opt.family.id + "-rootfs."' in DEL,
   "前缀写成裸 family.id 会把 alpine323 的缓存一起删掉")
ok("B2 OFFICIAL_TARBALL 发行版会清 cache/linux-node",
   '"linux-node"' in DEL and "OFFICIAL_TARBALL" in DEL,
   "实测这个 tarball 30MB，不清就永远占盘")
ok("B3 清 files/linux/state 下的装包日志",
   '"apt-last.log"' in DEL and "stateDir()" in DEL,
   "日志指向的环境已经没了，留着只占空间")
ok("B4 stateDir 是 public（Activity 能拿到）",
   "fun stateDir(): File" in ENV,
   "private 的话调用点根本编不过")
ok("B5 linux-node 缓存的写入点确实在 cacheDir/linux-node",
   'cacheDir, "linux-node"' in ENV,
   "写入点搬家后清理会失效，这条断言用来把两处钉在一起")

print()
print("== C. 激活指向 ==")
ok("C1 deleteImage 会清激活指向",
   "clearActive(" in DEL,
   "不清的话 prefs 仍指向已删除的目录，页面显示「使用中」但跑不起来")
ok("C2 clearActive 存在且是 public",
   "fun clearActive(ctx: Context)" in CAT,
   "没有它，Activity 只能直接摸 prefs（跨层写 key，迟早写错名字）")
ok("C3 clearActive 同时清 family 与 flavor 两个 key",
   "remove(KEY_ACTIVE)" in CLEAR and "remove(KEY_ACTIVE_FLAVOR)" in CLEAR,
   "只清一个会留下「无 family + 有 flavor」的半截状态")
ok("C4 clearActive 用同一个 edit() 提交（原子）",
   CLEAR.count(".edit()") == 1,
   "两次独立 apply 之间被杀会留下不一致状态")
ok("C5 清之前先比对「删的是不是当前激活的」",
   "LinuxRootfsCatalog.active(" in DEL,
   "无条件清会把用户正在用的另一个发行版的选择也抹掉")
ok("C6 clearActive 之后 active() 有回退分支",
   "options.firstOrNull { LinuxEnvironmentManager.get(ctx).isDeployed(it) }" in CAT,
   "没有回退，清空 key 后会直接落到 defaultOption，用户装着的镜像被无视")

print()
print("== D. 删除路径安全 ==")
ok("D1 删前核对父目录必须是 files/linux/distros",
   'File(filesDir, "linux/distros").canonicalFile' in DEL and "parentFile != root" in DEL,
   "递归删除一旦算错目标，没有第二次机会")
ok("D2 用 walkFileTree 而不是 deleteRecursively",
   "walkFileTree(" in ACT and "deleteRecursively" not in ACT,
   "deleteRecursively 会跟着符号链接钻进 rootfs 外的挂载点")
ok("D3 walkFileTree 不传 FileVisitOption（默认不跟随链接）",
   "walkFileTree(" in ACT and "FileVisitOption" not in ACT,
   "传了 FOLLOW_LINKS 就会把链接当目录展开，钻进 rootfs 外的挂载点")

print()
print("== E. 文案 ==")
LOCALES = ["values", "values-zh-rCN", "values-de", "values-ru", "values-th", "values-pt-rBR"]
need = ["linux_workbench_delete_title", "linux_workbench_delete_body",
        "linux_workbench_delete_running", "linux_workbench_delete_done",
        "linux_workbench_delete_partial", "linux_workbench_delete_hint"]
missing = []
for d in LOCALES:
    f = "app/src/main/res/" + d + "/strings.xml"
    if not os.path.isfile(os.path.join(ROOT, f)):
        missing.append(d + ":<no file>")
        continue
    x = read(f)
    for k in need:
        if ('name="' + k + '"') not in x:
            missing.append(d + ":" + k)
ok("E1 删除相关六条文案六语齐全", not missing, "; ".join(missing[:6]))

XML = read("app/src/main/res/values/strings.xml")
ok("E2 delete_partial 只带 1 个整型占位符",
   re.search(r'<string name="linux_workbench_delete_partial">[^<]*%1\$d[^<]*</string>', XML) is not None and
   ("%2$" not in re.search(r'<string name="linux_workbench_delete_partial">([^<]*)</string>', XML).group(1)),
   "多传参数会在运行时抛 IllegalFormatException")
ok("E3 delete_done 带 2 个占位符",
   re.search(r'<string name="linux_workbench_delete_done">[^<]*%1\$s[^<]*%2\$s[^<]*</string>', XML) is not None,
   "少一个参数 = 运行时崩")

print()
print(str(checks) + " checks, " + str(failed) + " failed")
sys.exit(1 if failed else 0)