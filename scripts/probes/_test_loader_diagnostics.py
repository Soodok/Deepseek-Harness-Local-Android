#!/usr/bin/env python3
"""守 dsh_alpine 的「崩溃可观测性」（issue #125）。

背景（issue #125，2026-11-20）：arm64 真机上 rootfs 部署成功后，runner 每次
有效调用都 SIGSEGV(rc=139)**一个字节都不输出**；而坏 interp 时能打出
"LOADER: preload FAIL"（rc=5）。所以 loader 确实启动了，崩在「装载 guest 之后」。

现场要求的正是可观测性：要么 DSH_ALPINE_DEBUG 能说出死在哪个阶段，要么装
SIGSEGV/SIGSYS handler 打印后退出。

本探针守四件事，每件都能被最小破坏打红：
  A. DEBUG 判定必须直扫 envp，不能读还没填的 g_env_store（否则恒 0，等于没有开关）
  B. 阶段标记覆盖「装载 guest」那一段（map-interp / map-guest / build-stack / jump-entry）
  C. 崩溃兜底打印「信号号 + 最后阶段」，且装 handler 时在 x86_64 上带 restorer
  D. 正常路径静默：阶段/崩溃打印全部挂在 g_dbg 上，未开 DEBUG 不多打一行

跑法：python scripts/probes/_test_loader_diagnostics.py
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app/src/main/cpp/dsh_alpine.c")
ARM = os.path.join(ROOT, "app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so")

checks = 0
failed = 0


def ok(cond, msg):
    global checks, failed
    checks += 1
    if cond:
        print("  ok   " + msg)
    else:
        failed += 1
        print("  FAIL " + msg)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def main():
    src = open(SRC, encoding="utf-8").read()
    body = strip_comments(src)

    # ---- A: DEBUG 判定直扫 envp ----
    ok("static int envp_has_(char **envp, const char *name)" in body,
       "[A1] 存在 envp_has_ 直扫实现")
    ok(re.search(r'envp_has_\(envp, "DSH_ALPINE_DEBUG"\)', body) is not None,
       "[A2] DEBUG 开关用 envp_has_ 读，不读 g_env_store")
    ok('env_has_("DSH_ALPINE_DEBUG")' not in body,
       "[A3] 不再调用会读到空 g_env_store 的 env_has_")
    ok("g_dbg = envp_has_(envp, \"DSH_ALPINE_DEBUG\");" in body,
       "[A4] 判定结果写进 g_dbg，后续所有打印都靠它")
    # 开关必须在 ldr_main **最开头**就求值并装 handler：放到 rootfs 打开之后，
    # 「rootfs 打不开 / stub 找不到」这类最早期失败期间仍然一句不打（#125 现场）。
    ok(re.search(r"g_dbg = envp_has_\(envp, \"DSH_ALPINE_DEBUG\"\);\s*\n\s*if \(g_dbg\) ldr_install_crash_handlers\(\);\s*\n\s*g_force_container_path = 1;", body) is not None,
       "[A5] 开关与 crash handler 在 ldr_main 最开头装好（rootfs 打开之前）")

    # ---- B: 阶段标记覆盖装载 guest 段 ----
    for st in ("rootfs-open", "find-stub", "preload", "install-filter", "jump-guest"):
        ok('ldr_stage("%s")' % st in body, "[B] 阶段标记 %s" % st)
    for st in ("map-interp", "map-guest", "build-stack", "jump-entry"):
        ok('ldr_stage("%s")' % st in body, "[B] 阶段标记 %s（child_run_raw 内）" % st)

    # ---- C: 崩溃兜底 ----
    ok("static void ldr_crash(" in body, "[C1] 存在崩溃兜底 handler")
    ok("LOADER: crash sig=" in body, "[C2] 打印信号号")
    ok("LOADER: crash last stage=" in body, "[C3] 打印最后阶段")
    ok(re.search(r"raw6\(SYS_exit_group, 128 \+ sig", body) is not None,
       "[C4] 不返回，直接 exit_group(128+sig) 保持 rc 语义")
    ok("ldr_install_crash_handlers" in body, "[C5] 有安装函数")
    # 注意：注释已剥掉，所以这里不能匹配 /\*SIGSEGV\*/ 字样，只认 syscall 号
    ok(re.search(r"raw6\(SYS_rt_sigaction, 11\s*,(?:\s*//[^\n]*)?", body) is not None,
       "[C6] 装 SIGSEGV")
    ok(re.search(r"raw6\(SYS_rt_sigaction,\s*7\s*,", body) is not None,
       "[C7] 装 SIGBUS")
    ok("0x04000000UL" in body and "__dsh_rt_sigreturn" in body,
       "[C8] x86_64 装 handler 时带 SA_RESTORER + restorer 地址")
    ok(re.search(r"if \(g_dbg\) ldr_install_crash_handlers\(\);", body) is not None,
       "[C9] 安装点挂在 g_dbg 上")
    ok('ldr_puts("LOADER: guard-hit nr=")' in body,
       "[C10] 重入守卫命中时把 syscall 号打出来（下次现场能直接定位）")

    # ---- D: 正常路径静默 ----
    ok(re.search(r"static void ldr_stage\(const char \*s\) \{.*?if \(!g_dbg\) return;.*?\}", body, re.S) is not None,
       "[D1] ldr_stage 在 g_dbg=0 时直接 return（不开 DEBUG 不多打）")

    # ---- E: 产物里真的有这些字符串（源码改了没重编 = 假绿） ----
    if os.path.isfile(ARM):
        raw = open(ARM, "rb").read()
        for s in (b"LOADER: crash sig=", b"LOADER: crash last stage="):
            ok(s in raw, "[E] 产物含 %r" % s.decode())
    else:
        ok(False, "[E] 缺 arm64 产物")

    # ---- F: 诊断页不许只看 ELF 头就报 OK（#125 的假绿色） ----
    act = open(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/LinuxActivity.kt"),
               encoding="utf-8").read()
    act_body = strip_comments(act)
    if "linux_workbench_diag_runner_selftest" in act:
        ok('guestPath = "/bin/busybox"' in act_body,
           "[F1] 诊断页真起一次容器跑 busybox（不再是只查 ELF 头）")
        ok("LinuxContainerRunner.get(this).run(" in act_body,
           "[F2] 自检走真实 run() 路径")
        ok("rc=\" + self.exitCode" in act_body,
           "[F3] 失败时贴原始 rc + 输出，不吞成一句 OK/FAIL")
        ok("skipReady = true" in act_body,
           "[F4] 自检带 skipReady，不在诊断里递归触发 bootstrap")
        dst = open(os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/LinuxContainerRunner.kt"),
                   encoding="utf-8").read()
        ok("skipReady: Boolean = false" in dst,
           "[F5] LinuxContainerRunner.run 确实有 skipReady 参数（自检依赖它）")
    else:
        ok(False, "[F1] 诊断页缺少运行器自检项")

    # 六套 locale 都必须有这个新键，否则 i18n 检查会报 parity 冲突
    base_dir = os.path.join(ROOT, "app/src/main/res")
    for loc in ("values", "values-zh-rCN", "values-de", "values-ru", "values-th", "values-pt-rBR"):
        f = os.path.join(base_dir, loc, "strings.xml")
        hit = os.path.isfile(f) and "linux_workbench_diag_runner_selftest" in open(f, encoding="utf-8").read()
        ok(hit, "[G] %s 含 self-test 字符串" % loc)

    print("\nchecks=%d failed=%d" % (checks, failed))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
