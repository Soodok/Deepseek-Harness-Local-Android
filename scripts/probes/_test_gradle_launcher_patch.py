#!/usr/bin/env python3
"""issue #86：android-buildtools 的 gradle 启动脚本补丁（安装期 + 启动迁移期）。

两个独立断点，缺任一个 gradle 都开箱即坏：
  1) toybox xargs 不剥引号 —— Gradle 的 DEFAULT_JVM_OPTS 是 '"-Xmx64m" "-Xms64m" …'，
     靠 `xargs -n1` 剥引号；/system/bin/xargs 原样输出 → java 收到字面量 '"-Xmx64m"'
     当主类名 → `Could not find or load main class "-Xmx64m"`。
  2) 内嵌 Termux JDK 把 user.home / java.io.tmpdir 硬编码到 /data/data/com.termux/files/**
     （别的包、不可写；JVM 无视 $HOME）→ jansi native 加载失败、`gradle help` 死于
     `java.io.tmpdir is set to a directory that doesn't exist`。

本探针**从 Kotlin 源码抽真实补丁正文**（不复制），套到保留真实结构的 fixture 上，验证
锚点存在、补丁落地、bash 语法合法、幂等，并在 GNU 与 toybox 两种 xargs 下跑行为对照。
"""
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/ExtensionManager.kt")
SUP = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/EngineSupervisor.kt")

errors = []


def check(cond, msg):
    if not cond:
        errors.append(msg)


src = io.open(KT, encoding="utf-8").read()

# ---- 1) 幂等标记常量 ----
m = re.search(r'private const val GRADLE_PATCH_MARK = "([^"]+)"', src)
check(m is not None, "GRADLE_PATCH_MARK constant not found")
mark = m.group(1) if m else "# dsh-android-patch: gradle-launcher"

# ---- 2) 两个锚点必须与真实启动器一字不差 ----
ANCHOR_XARGS = (
    '# Stop when "xargs" is not available.\n'
    'if ! command -v xargs >/dev/null 2>&1\n'
    'then\n'
    '    die "xargs is not available"\n'
    'fi'
)
ANCHOR_OPTS = 'eval "set -- $('

# ---- 3) 补丁体必须包含剥引号包装与两个目录改写 ----
check("command xargs" in src and "sed -e 's/^\"//'" in src,
      "quote-stripping xargs wrapper missing")
check("GRADLE_USER_HOME" in src and "java.io.tmpdir" in src and "user.home" in src,
      "user.home/java.io.tmpdir rewrite missing")
check("org.gradle.native=false" in src,
      "native-platform guard missing")

# ---- 4) 安装期与启动迁移期都要挂上 ----
check(re.search(r"^\s*patchGradleLauncher\(finalDir\)", src, re.M) is not None,
      "patchGradleLauncher not called from installPhase")
check("fun migrateGradleLaunchers" in src, "migrateGradleLaunchers missing")
check("activeRoots(ctx).forEach" in src and "patchGradleLauncher(dir)" in src,
      "migration does not iterate active roots")
sup = io.open(SUP, encoding="utf-8").read()
check("migrateGradleLaunchers()" in sup,
      "EngineSupervisor does not invoke migration before spawn")

# ---- 5) 幂等早退 ----
check("if (text.contains(GRADLE_PATCH_MARK)) return" in src,
      "idempotency guard missing")

# ================= 行为验证：对 fixture 跑 replace =================
# fixture 保留真实启动器中被改动的两处结构（含 eval 段的完整 printf/xargs/sed 链）
FIXTURE = """#!/data/data/com.termux/files/usr/bin/sh

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m" "-javaagent:$APP_HOME/lib/agents/gradle-instrumentation-agent-9.8.2.jar"'

# Stop when "xargs" is not available.
if ! command -v xargs >/dev/null 2>&1
then
    die "xargs is not available"
fi

eval "set -- $(
        printf '%s\\n' "$DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS" |
        xargs -n1 |
        sed ' s~[^-[:alnum:]+,./:=@_]~\\\\&~g; ' |
        tr '\\n' ' '
    )" '"$@"'

exec "$JAVACMD" "$@"
"""


def _extract_kotlin_raw(name):
    """从 ExtensionManager.kt 抽 `val <name> = \"\"\" ... \"\"\".trimIndent()` 的真实正文。

    探针不复制补丁正文，避免源码改了探针没跟上（那才是最容易漏的假绿）。
    两种赋值形态都要认：`val X = \"\"\"…` 与 `val X = anchorXargs + \"\"\"…`。
    """
    pat = ('val ' + name + r'\s*=\s*(?:[A-Za-z_][A-Za-z0-9_]*\s*\+\s*)?"""'
           + r'\n(.*?)\n\s*"""\.trimIndent\(\)')
    mm = re.search(pat, src, re.S)
    if mm is None:
        return None
    body = textwrap.dedent(mm.group(1))
    body = body.replace("${'$'}", "$")
    body = body.replace("$GRADLE_PATCH_MARK", mark)
    return body


_xa_body = _extract_kotlin_raw("xargsPatch")
_xa = (ANCHOR_XARGS + _xa_body) if _xa_body is not None else None
_op = _extract_kotlin_raw("optsPatch")
check(_xa is not None, "cannot extract xargsPatch body from Kotlin source")
check(_op is not None, "cannot extract optsPatch body from Kotlin source")

# 源码里的 xargsPatch 自带锚点（anchorXargs + 补丁正文），原样替换即可
XARGS_PATCH = _xa if _xa else ""
OPTS_PATCH = ((_op if _op else "") + "\n")


def apply_patch(text):
    if mark in text:
        return text, False
    if ANCHOR_XARGS not in text or ANCHOR_OPTS not in text:
        return text, False
    return text.replace(ANCHOR_XARGS, XARGS_PATCH).replace(ANCHOR_OPTS, OPTS_PATCH + ANCHOR_OPTS), True


check(ANCHOR_XARGS in FIXTURE, "fixture missing xargs guard (fixture drift)")
check(ANCHOR_OPTS in FIXTURE, "fixture missing eval anchor (fixture drift)")

patched, changed = apply_patch(FIXTURE)
check(changed, "patch did not apply to fixture")
check(patched.count(mark) == 2, "expected exactly 2 patch marks, got %d" % patched.count(mark))
check(patched.index(mark) < patched.index(ANCHOR_OPTS),
      "opts patch must land BEFORE eval (option parsing)")

# 幂等：第二遍必须无变化
again, changed2 = apply_patch(patched)
check(not changed2, "patch is not idempotent")
check(again == patched, "second pass altered the file")

# bash 语法必须合法（漏引号会让整个启动器崩）
have_bash = subprocess.call("command -v bash", shell=True,
                            stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL) == 0
if os.name != "nt" or have_bash:
    fd, tmp = tempfile.mkstemp(suffix=".sh")
    os.close(fd)
    try:
        io.open(tmp, "w", encoding="utf-8", newline="\n").write(patched)
        rc = subprocess.call(["bash", "-n", tmp], stdout=subprocess.DEVNULL,
                             stderr=subprocess.PIPE)
        check(rc == 0, "patched launcher fails `bash -n`")
    finally:
        os.unlink(tmp)

PRINT_ARGV = ('_p() { for a in "$@"; do printf "%s\\n" "$a"; done; }; _p "$@"')


def run_argv(text, env_extra, path_prefix=None):
    body = text.replace('exec "$JAVACMD" "$@"', PRINT_ARGV)
    path = os.path.join(work, "case.sh")
    io.open(path, "w", encoding="utf-8", newline="\n").write(body)
    env = dict(os.environ, JAVA_OPTS="", GRADLE_OPTS="")
    env.update(env_extra)
    if path_prefix:
        env["PATH"] = path_prefix + ":/usr/bin:/bin"
    r = subprocess.run(["bash", path, "--version"], capture_output=True, text=True, env=env)
    return [l for l in r.stdout.splitlines() if l.strip()]


work = tempfile.mkdtemp()

# ---- 行为 A：GNU xargs + HOME 有值 → 补丁必须是「加三参、不破坏原有」----
try:
    lines = run_argv(patched, {"HOME": "/h"})
    check("-Xmx64m" in lines, "GNU xargs path lost -Xmx64m (got %r)" % lines[:4])
    check(not any(l.startswith('"') for l in lines),
          "quote leaked into argv on GNU path: %r" % lines[:4])
    check(any(l.startswith("-Duser.home=/h") for l in lines), "user.home override missing from argv")
    check(any(l.startswith("-Djava.io.tmpdir=/h/.gradle/tmp") for l in lines),
          "java.io.tmpdir override missing")
    check("-Dorg.gradle.native=false" in lines, "native=false missing")
finally:
    pass

# ---- 行为 B：HOME 为空 → 不能把目录指到 "/"（否则等同没修）----
# bash 在 HOME 未设时会用 passwd 补一个，没法直接清空；改为把赋值行改成空来模拟。
no_home = patched.replace("dsh_home=${HOME:-}", "dsh_home=", 1)
check(no_home != patched, "cannot locate dsh_home assignment to simulate empty HOME")
lines_nohome = run_argv(no_home, {"HOME": "/h"})
check("-Xmx64m" in lines_nohome, "HOME-empty path lost -Xmx64m")
check(not any(l.startswith("-Duser.home=") for l in lines_nohome),
      "HOME empty must skip user.home override, got %r" % lines_nohome[:6])
check("-Dorg.gradle.native=false" in lines_nohome, "native=false must still be set without HOME")

# ---- 行为 B2：HOME="/"（adb/serial shell 的默认值）同样必须跳过 ----
lines_root = run_argv(patched, {"HOME": "/"})
check("-Xmx64m" in lines_root, "HOME=/ path lost -Xmx64m")
check(not any(l.startswith("-Duser.home=") for l in lines_root),
      "HOME=/ must skip user.home override, got %r" % lines_root[:6])
check("-Dorg.gradle.native=false" in lines_root, "native=false must still be set with HOME=/")

# ---- 行为 C：toybox xargs → 补丁必须救回来（issue #86 的真场景）----
# 假 xargs 只做「按空白切分、token 原样输出、不剥引号」，复刻 /system/bin/xargs (toybox)。
fakebin = os.path.join(work, "bin")
os.makedirs(fakebin)
fake = os.path.join(fakebin, "xargs")
io.open(fake, "w", encoding="utf-8", newline="\n").write(
    "#!/bin/bash\n"
    "while IFS= read -r line; do\n"
    "  set -f\n"
    "  for w in $line; do printf '%s\\n' \"$w\"; done\n"
    "done\n"
)
os.chmod(fake, 0o755)

bad = run_argv(FIXTURE, {"HOME": "/h"}, fakebin)
good = run_argv(patched, {"HOME": "/h"}, fakebin)

check(not any(a == "-Xmx64m" for a in bad),
      "toybox fixture unexpectedly clean - the fixture no longer reproduces #86")
check(any(a == "-Xmx64m" for a in good),
      "patch failed to unquote -Xmx64m under toybox xargs (got %r)" % good[:4])
check(any(a.startswith("-Duser.home=") for a in good),
      "user.home override lost under toybox xargs")
check(any(a.startswith("-Djava.io.tmpdir=") for a in good),
      "java.io.tmpdir override lost under toybox xargs")
check(not any('"' in a for a in good), "quotes still leaking under toybox xargs: %r" % good[:4])

shutil.rmtree(work, ignore_errors=True)

if errors:
    for e in errors:
        print("FAIL: %s" % e)
    sys.exit(1)
print("gradle launcher patch: all checks passed")
