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
# 剥注释后再断言：实现说明里会引用「错误写法」作为反例
_env_code_b2 = re.sub(r"/\*.*?\*/", "", env, flags=re.S)
_env_code_b2 = re.sub(r"^\s*//.*$", "", _env_code_b2, flags=re.M)
ck("B2 arch 从目录名第 2 段取，不用 endsWith（结尾是 -0 不是 -x64）",
   re.search(r"val bundleArch = want\.split\('[-]'\)\.getOrNull\(1\)", _env_code_b2) is not None
   and 'endsWith("-" + NODE_ARCH)' not in _env_code_b2,
   "endsWith 恒为假会让 seed 每次静默退出，缓存永远不出现（实测踩到）")
ck("B3 已有当前版本缓存就跳过（不能每次冷启都解包）",
   re.search(r"startsWith\(want\).{0,200}isNotEmpty\(\) == true\)", env, re.S) is not None)
ck("B4 版本不靠执行 node，也不靠读它的头部 64KB",
   "seededCachePrefix()" in env and not re.search(r"nodeBin\.inputStream\(\)", env),
   "冷启路径不能多一次 fork；而二进制头部里根本没有版本串")
ck("B5 版本不匹配时清残留半成品",
   "seeded cache discarded" in env or "seeded cache skipped" in env)

print("[C] seed 在部署阶段，不在冷启路径")
ck("C1 prune 仍在 readiness（纯 listFiles，微秒级）",
   lau.find("env.pruneCompileCache()") > 0)
ck("C2 readiness 里**不再**调 seedCompileCache",
   "env.seedCompileCache()" not in lau,
   "解 1281 文件要 1.5~4 秒，收益仅 840ms —— 挂在冷启上是净亏")
ck("C3 seed 挂在 ensureDeployed 的 READY 早退分支",
   re.search(r"rootfs already ready.{0,600}seedCompileCache\(\)", env, re.S) is not None)
ck("C4 seed 也挂在解包完成之后（首次部署）",
   re.search(r"seed cache failed \(deploy\)", env) is not None)
ck("C5 两处都 runCatching 包裹（失败不影响部署/启动）",
   env.count("runCatching { seedCompileCache() }") >= 2)

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

print("[E] 版本来源必须是包内目录名，不能是 node 二进制头部")
env_code = re.sub(r"/\*.*?\*/", "", env, flags=re.S)
env_code = re.sub(r"^\s*//.*$", "", env_code, flags=re.M)
ck("E1 存在 seededCachePrefix()",
   "private fun seededCachePrefix()" in env)
ck("E2 prune 用 seededCachePrefix 取前缀（不再读二进制）",
   re.search(r"val keep = seededCachePrefix\(\) \?: return 0", env_code) is not None,
   "读 node 二进制前 64KB 从来读不到版本串（首个版本串在 10~37MB 偏移）")
ck("E3 seed 也用 seededCachePrefix",
   re.search(r"val want = seededCachePrefix\(\) \?: return", env_code) is not None)
ck("E4 代码里不再有 nodeBin 的 64KB 版本读取",
   not re.search(r"nodeBin\.inputStream\(\)", env_code),
   "两个函数都曾因此静默 return，prune 一直是死代码")
ck("E5 seed 会校验包内 arch 与设备一致（取第 2 段）",
   "bundleArch != NODE_ARCH" in env_code and "getOrNull(1)" in env_code)
ck("E6 前缀读取结果进程内缓存",
   "cachedPrefix" in env_code)

if FAILS == 0:
    print("\nAll cache seed checks passed.")
    sys.exit(0)
print("\n%d cache seed check(s) FAILED." % FAILS)
sys.exit(1)
