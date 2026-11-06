#!/usr/bin/env python3
# _test_linux_cache_seed.py —— 预置编译缓存接线探针
import io, os, re, sys

FAILS = 0
def ck(name, ok, why=""):
    global FAILS
    if ok:
        print("  OK   " + name)
    else:
        FAILS += 1
        print("  FAIL " + name + (" -- " + why if why else ""))

def read(p):
    return io.open(p, encoding="utf-8").read()

ENV = "app/src/main/java/app/dsh/mobile/engine/LinuxEnvironmentManager.kt"
LAU = "app/src/main/java/app/dsh/mobile/engine/LinuxEngineLauncher.kt"
ASSET = "app/src/main/assets/linux/node-compile-cache.zip"

env = read(ENV); lau = read(LAU)

print("[A] 预置缓存包存在且尺寸合理")
ck("A1 assets 里有 node-compile-cache.zip", os.path.isfile(ASSET))
if os.path.isfile(ASSET):
    n = os.path.getsize(ASSET)
    ck("A2 大小在 2.5~4 MB（实测 3165667）", 2_500_000 < n < 4_000_000, "got %d" % n)

print("[B] seedCompileCache 实现要点")
ck("B1 存在 fun seedCompileCache", "fun seedCompileCache()" in env)
ck("B2 版本目录名用 NODE_ARCH 拼（不能写死 arm64）",
   re.search(r'val want = "v" \+ version \+ "-" \+ NODE_ARCH', env) is not None,
   "写死 arch 后 Node 不认缓存，实测退回无缓存水平")
ck("B3 已有当前版本缓存就跳过（不能每次冷启都解包）",
   re.search(r"startsWith\(want\).{0,40}isNotEmpty\(\) == true\) return", env) is not None)
ck("B4 版本从 node 二进制头部读，不执行它",
   env.count(r"[vV]?(\d{1,2}\.\d{1,2}\.\d{1,3})") >= 2,
   "冷启路径不能多一次 fork")
ck("B5 版本不匹配时清残留半成品",
   "seeded cache discarded" in env or "seeded cache skipped" in env)

print("[C] 接线顺序：prune 必须在 seed 之前")
i_prune = lau.find("env.pruneCompileCache()")
i_seed = lau.find("env.seedCompileCache()")
ck("C1 两者都挂在 readiness 路径", i_prune > 0 and i_seed > 0)
ck("C2 prune 在 seed 之前", 0 < i_prune < i_seed,
   "先 seed 的话刚解的包会被 prune 当陈旧删掉")
ck("C3 seed 在 installCompatShims 之前",
   i_seed < lau.find("env.installCompatShimsPublic()"))

print("[D] 解压在 Kotlin 侧完成，不经过容器")
ck("D1 COMPILE_CACHE_ASSET 常量存在",
   'COMPILE_CACHE_ASSET = "node-compile-cache.zip"' in env)
ck("D2 用 ZipInputStream 解压（tar 路线已证否）",
   "java.util.zip.ZipInputStream" in env,
   "LinuxContainerRunner 的 argv 不带 -m，容器里没有 /eng 也没有 /dsh")
# 剥注释：只在真实代码里断言，避免把「记录已否证路线」的注释当成违规
env_code = re.sub(r"/\*.*?\*/", "", env, flags=re.S)
env_code = re.sub(r"^\s*//.*$", "", env_code, flags=re.M)
ck("D3 代码里不再有容器 tar 调用",
   "tar xzf /eng" not in env_code and "LinuxContainerRunner.get(ctx).shell" not in env_code,
   "那条路实测必 ENOENT（runner argv 不带 -m）")
ck("D4 解包目标目录是 dsh-home 下的 .node-compile-cache",
   '".node-compile-cache"' in env)
ck("D5 拒绝含 .. 的 entry（不写到 DSH_HOME 之外）",
   'e.name.contains("..")' in env)
ck("D6 解压失败时清残留，不让下次误判已有缓存",
   "unzip failed after" in env)

if FAILS == 0:
    print("\nAll cache seed checks passed.")
    sys.exit(0)
print("\n%d cache seed check(s) FAILED." % FAILS)
sys.exit(1)
