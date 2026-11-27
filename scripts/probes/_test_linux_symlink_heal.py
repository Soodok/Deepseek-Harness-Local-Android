#!/usr/bin/env python3
"""Locks 容器扩展装包这条链路上三处「看起来没事、装完就是不能用」的修复。

## 为什么单独一条探针

issue #131（扩展中心补 mono / dotnet）暴露的三个缺陷都不是崩溃，而是
**装完之后功能不对**，任何一处被改回去都不会让编译或别的探针变红：

1. 镜像源太慢 → 大包在 600s 超时被砍，日志只有 `rc-ok=false verified=false`。
   现场表现「点安装、等十分钟、报 Install failed」，看起来像包坏了。
2. 老式无 dirfd syscall 在 App 进程里被 Android 的 zygote seccomp 二次拦截
   （它只按号判、不看 IP），我们自己的白名单放行了 loader 段的重发也没用
   → 重入守卫给 EPERM。后果是 `lstat`/`access`/`ln -s`/`rm` 全废。
3. 容器没有 chroot → 符号链接的**绝对目标**按宿主根解析 → ENOENT。
   `/usr/bin/dotnet` 是全 rootfs 唯一的绝对链接，恰好只有它跟不动，
   于是 `dotnet` 报 not found，而 `/usr/lib/dotnet/dotnet --version` 正常。

三条的共同点：**源码看起来完全合理**（源码里还有一段注释断言「绝对链接
也工作正常」），只有真机/模拟器实测才暴露。所以这里只钉「判据与接线」，
不钉数值 —— 数值由真机取证记录在 AI_CONTEXT 里。

## 变异验证（改坏 → 必须打红）

- 把 `apkRepositories` 的 tuna 换回 aliyun → C1/C2 红
- 删 `healPackageFiles` 里的 `repairAbsoluteLinks(` → B2 红
- 把 `repairAbsoluteLinks` 的 `targetInRoot.exists()` 判据删掉 → A4 红
- 把 `legacy_path_to_at` 的 lstat 分支改成 raw6(SYS_lstat) → D4 红
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENG = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine")
MODES = os.path.join(ENG, "RootfsModes.kt")
PKGM = os.path.join(ENG, "LinuxPackageManager.kt")
ENVM = os.path.join(ENG, "LinuxEnvironmentManager.kt")
ALPINE = os.path.join(ROOT, "app", "src", "main", "cpp", "dsh_alpine.c")

NL = chr(10)
_ok = 0
_bad = 0


def ok(cond, msg, detail=""):
    global _ok, _bad
    if cond:
        _ok += 1
        print("  ok   " + msg)
    else:
        _bad += 1
        print("  FAIL " + msg + ((" — " + str(detail)) if detail else ""))


def read(p):
    return io.open(p, encoding="utf-8").read()


def strip_block_comments(s):
    """先剥 /* */ 块注释。

    解释性注释里**正是**会写出被禁的字面量（比如「为什么不能用 DiscardPolicy」），
    不剥就会让断言恒红。
    """
    return re.sub(r"/\*[\s\S]*?\*/", "", s)


def strip_line_comments(s):
    lines = [l for l in s.split(NL) if not l.strip().startswith("//")]
    return NL.join(lines)


def fn_body(src, name):
    """截出函数体近似区间（到下一个**同级**成员声明为止）。

    用固定字符窗口会假绿：窗口开大一点就能把**下一个**函数里的调用算进来，
    于是「这里根本没接线」也能通过。而不按缩进切同样会假绿 ——
    `apkRepositories` 在 `companion object` 里，缩进是 8 空格；若只认 4 空格
    的兄弟声明，就永远找不到下界，函数体会一路延伸到文件末尾，于是
    「函数里有 tuna」这种断言会被文件别处的字面量蒙对。所以必须：
      1) 取目标声明的实际缩进；
      2) 下界 = 第一个缩进 ≤ 该缩进的成员声明。
    """
    i = src.find("fun " + name + "(")
    if i < 0:
        return ""
    line_start = src.rfind(NL, 0, i) + 1
    indent = len(src[line_start:i]) - len(src[line_start:i].lstrip())
    pat = re.compile(
        NL + r"(?:(?![^\n]*\S)[ \t]*)" + r"(?:private |internal |public |protected |override )?"
        r"(?:fun|val|var|object|class|companion|internal) ",
    )
    # 逐个候选成员声明，第一个缩进 ≤ 目标缩进的即为下界
    for m in pat.finditer(src, i + 5):
        nxt_start = m.start()
        nxt_line = src.rfind(NL, 0, nxt_start) + 1
        nxt_indent = len(src[nxt_line:nxt_start + 1]) - len(src[nxt_line:nxt_start + 1].lstrip())
        if nxt_indent <= indent:
            return src[i:nxt_start]
    return src[i:]


modes = read(MODES)
pkgm = read(PKGM)
envm = read(ENVM)
alpine = read(ALPINE)

print("[A] RootfsModes.repairAbsoluteLinks —— 绝对符号链接相对化")

body = fn_body(modes, "repairAbsoluteLinks")
ok(body != "", "A1 repairAbsoluteLinks 存在")
if body:
    ok("readSymbolicLink" in body, "A2 用 Files.readSymbolicLink 读目标（不是猜字符串）")
    ok('startsWith("/")' in body, "A3 只处理绝对目标")
    ok("targetInRoot.exists()" in body,
       "A4 目标必须真实落在 rootfs 内（指向宿主的 /proc /dev 保持原样）")
    ok("relativize" in body, "A5 用 Path.relativize 相对化（不是字符串拼 ../）")
    ok("createSymbolicLink" in body, "A6 写回用 createSymbolicLink")
    ok("runCatching" in body, "A7 全程兜错：修补失败不该让本来成功的装包变红")
    ok("BIN_DIRS" in body, "A8 只扫 BIN_DIRS，不递归全树")
    # delete 必须在 createSymbolicLink 之前：createSymbolicLink 不容忍路径已存在
    i_del = body.find("Files.delete(")
    i_new = body.find("createSymbolicLink")
    ok(0 <= i_del < i_new, "A9 先 delete 旧链接再 createSymbolicLink",
       "del=%d new=%d" % (i_del, i_new))

print("\n[B] LinuxPackageManager.healPackageFiles —— 三段接线与顺序")

hb = fn_body(pkgm, "healPackageFiles")
ok(hb != "", "B1 healPackageFiles 存在")
if hb:
    ok("RootfsModes.repairExecBits(" in hb, "B2 仍保留 ① 补 x 位")
    ok("RootfsModes.repairAbsoluteLinks(" in hb, "B3 新增 ② 绝对链接相对化已接线")
    ok("RootfsModes.wantsHeaders(" in hb, "B4 仍保留 ③ 头文件属主")
    i_x = hb.find("repairExecBits(")
    i_l = hb.find("repairAbsoluteLinks(")
    i_h = hb.find("wantsHeaders(")
    ok(0 <= i_x < i_l < i_h,
       "B5 顺序必须是 ①x位 → ②链接 → ③头文件",
       "x=%d l=%d h=%d" % (i_x, i_l, i_h))
    # ② 必须无条件执行（不能被 wantsHeaders 的早返回挡住）
    ok(hb.find("wantsHeaders(") > hb.find("repairAbsoluteLinks("),
       "B6 ②链接修复在 wantsHeaders 早返回之前（装 curl 也要修）")

print("\n[C] 镜像源 —— 必须是快的那家")

erb = fn_body(envm, "apkRepositories")
ok(erb != "", "C1 apkRepositories 存在")
if erb:
    # ★必须先剥行注释再断言：这段函数上方的注释里**正是**写着
    #   「https://mirrors.tuna.tsinghua.edu.cn 29,114,010 B → 3.6 MB/s」
    #   作为对照实验记录。不剥注释时，把实际 URL 换回 aliyun 这条照样绿
    #   —— 实测踩到过，C2 在变异下没红。
    erb_code = strip_line_comments(erb)
    ok("mirrors.tuna.tsinghua.edu.cn" in erb_code,
       "C2 实际 URL 用清华源（实测 3.6 MB/s vs 阿里云 324 KB/s，差 11 倍）")
ok("mirrors.aliyun.com/alpine" not in strip_line_comments(envm),
   "C3 阿里云 alpine 仓库地址不能还在（慢源会让 522MB 闭包必超 600s）")

print("\n[D] dsh_alpine.c —— 老式 syscall 必须翻译成 *at 号")

src_no_comments = strip_line_comments(alpine)
ok("static int legacy_path_to_at(" in src_no_comments,
   "D1 存在 legacy_path_to_at 翻译函数")
ok(alpine.count("legacy_path_to_at(nr,") >= 2,
   "D2 相对路径与透传出口两处都调用了它",
   "count=%d" % alpine.count("legacy_path_to_at(nr,"))
# 取函数体：从声明到下一个顶层 static 函数
i0 = alpine.find("static int legacy_path_to_at(")
i1 = alpine.find(NL + "static ", i0 + 10)
lf = alpine[i0:i1] if i1 > i0 else alpine[i0:]
ok("SYS_newfstatat" in lf and "AT_SYMLINK_NOFOLLOW" in lf,
   "D3 lstat 翻译成 newfstatat(AT_SYMLINK_NOFOLLOW)")
ok("SYS_lstat" not in strip_line_comments(lf).replace("case SYS_lstat:", ""),
   "D4 翻译表里不许把 lstat 原样重发（重发老式号会被 zygote seccomp 二次拦）")
ok("SYS_faccessat" in lf, "D5 access 翻译成 faccessat")
# unlink 的 flags 必须写死 0：老式 unlink 只有 1 个参数，a1 是无意义寄存器
i_un = lf.find("case SYS_unlink:")
seg = lf[i_un:i_un + 400] if i_un >= 0 else ""
ok(i_un >= 0 and "(long)p, 0," in seg,
   "D6 unlink 的 unlinkat flags 写死 0（a1 不是 flags）")
ok("DSH_ARCH_X86_64" in alpine[max(0, i0 - 4000):i0],
   "D7 翻译函数在 x86_64 条件编译块内（aarch64 通用 ABI 没有老式号）")

print("\nchecks=%d failed=%d" % (_ok + _bad, _bad))
if _bad:
    print("RESULT FAILED")
    sys.exit(1)
print("RESULT PASSED")
