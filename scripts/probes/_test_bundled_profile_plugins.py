#!/usr/bin/env python3
"""内置「用户态插件」注册表一致性探针。

覆盖一个长期没人检查的断裂面：**注册表、构建接线、CI 打包、脚本**四处必须
同时认识同一个插件资产目录名，否则表现是「打进 APK 了但永远不装」或
「CI 打包了但 APK 里没有」，两边都不报错。

断言分组：
  A. ProfilePluginProvisioner.REGISTRY 与 app/build.gradle.kts 的
     bundledProfilePlugins 一一对应
  B. CI 的 pack-profile-plugins 矩阵与上面一致
  C. 打包脚本存在、可执行，且被 CI 真正调用
  D. 升级路径绕过 pnpm（直接解包），且不在成功路径删暂存 tgz
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FAILS = []


TOTAL = 0


def check(name, ok, why=""):
    global TOTAL
    TOTAL += 1
    if ok:
        print("OK   " + name)
    else:
        print("FAIL " + name + ("  -> " + why if why else ""))
        FAILS.append(name)


def read(rel):
    p = os.path.join(ROOT, rel)
    if not os.path.isfile(p):
        return ""
    with open(p, encoding="utf-8") as f:
        return f.read()


def strip_comments(src):
    """剥掉 Kotlin/YAML 注释，避免注释里的字样造成假通过。"""
    out = []
    in_block = False
    for line in src.splitlines():
        s = line.strip()
        if in_block:
            if "*/" in s:
                in_block = False
            continue
        if s.startswith("/*"):
            if "*/" not in s:
                in_block = True
            continue
        if s.startswith("//"):
            continue
        out.append(line)
    return "\n".join(out)


prov = strip_comments(read("app/src/main/java/app/dsh/mobile/engine/ProfilePluginProvisioner.kt"))
gradle = strip_comments(read("app/build.gradle.kts"))
ci = strip_comments(read(".github/workflows/android-build.yml"))
script = read("scripts/pack-profile-plugin.sh")

print("== A. 注册表 vs 构建接线 ==")
prov_roots = re.findall(r'assetRoot\s*=\s*"([a-z0-9-]+)"', prov)
# 只取 bundledProfilePlugins 那张表内部 —— pluginOverrides 里有同名条目，
# 全文搜会把「表里少一行、别处还有一处」判成齐全（实测踩过这个假通过）。
# 只取 bundledProfilePlugins 那张表内部 —— pluginOverrides 里有同名条目，
# 全文搜会把「表里少一行、别处还有一处」判成齐全（实测踩过这个假通过）。
# 只取 bundledProfilePlugins 那张表内部 —— pluginOverrides 里有同名条目，
# 全文搜会把「表里少一行、别处还有一处」判成齐全（实测踩过这个假通过）。
TB_RE = "val bundledProfilePlugins:[^=]*=\s*listOf\((.{0,1200}?)\)\n"
m_tbl = re.search(TB_RE, gradle, re.S)
tbl_src = m_tbl.group(1) if m_tbl else ""
gradle_pairs = re.findall(r'"([a-z0-9-]+)"\s+to\s+[A-Za-z]+', tbl_src)
check("A1 REGISTRY 含 mobile-layout", "mobile-layout" in prov_roots, str(prov_roots))
check("A2 REGISTRY 含 solution-explorer", "solution-explorer" in prov_roots, str(prov_roots))
check("A3 gradle 的 bundledProfilePlugins 覆盖同一批",
      set(prov_roots) == set(gradle_pairs),
      "prov=%s gradle=%s" % (sorted(prov_roots), sorted(gradle_pairs)))
check("A4 gradle 用循环而不是为每个插件写一段",
      "for ((assetRoot, overrideDir) in bundledProfilePlugins)" in gradle)

print("== B. CI 打包矩阵 ==")
ci_assets = re.findall(r'^\s*asset:\s*([a-z0-9-]+)\s*$', ci, re.M)
check("B1 CI 矩阵资产与注册表一致",
      set(ci_assets) == set(prov_roots),
      "ci=%s prov=%s" % (sorted(ci_assets), sorted(prov_roots)))
check("B2 CI job 名为 pack-profile-plugins",
      "pack-profile-plugins:" in ci)
check("B3 build-apk 依赖该 job",
      re.search(r"needs:\s*\[[^\]]*pack-profile-plugins", ci) is not None)
# asset 目录名 → gradle 属性名（驼峰）。这张表是 CI 与 gradle 的唯一契约，
# 少一条的表现是「CI 打包了但 APK 里没有」，两边都不报错。
ASSET_TO_PROP = {
    "mobile-layout": "mobileLayoutTgzDir",
    "solution-explorer": "solutionExplorerTgzDir",
}
for root in prov_roots:
    prop = ASSET_TO_PROP.get(root)
    check("B4 %s 在属性映射表里" % root, prop is not None)
    if prop:
        check("B5 CI 把 %s 产物传给构建（-P%s）" % (root, prop),
              ("-P%s=" % prop) in ci,
              "缺这条 = CI 打包了但 APK 里没有该插件")
        check("B6 gradle 声明了 %s" % prop,
              prop in gradle)

print("== C. 打包脚本 ==")
check("C1 存在", bool(script))
check("C2 接受 <pkg> <asset-dir> [out] 三参",
      re.search(r'\$\{1:-\}', script) is not None and
      re.search(r'\$\{2:-\}', script) is not None)
check("C3 校验 bundle 契约（缺 cordis.patch.yml 即失败）",
      "cordis.patch.yml" in script and "exit 1" in script)
check("C4 校验包名与请求一致", 'PKG_NAME' in script and '$PKG"' in script)
check("C5 产出 bundle.json 含 pkg/version/tgz/sha256",
      all(k in script for k in ("pkg", "version", "sha256")))
check("C6 CI 调用的是这个脚本",
      "scripts/pack-profile-plugin.sh" in ci)
check("C7 旧脚本不再被 CI 引用",
      "pack-mobile-layout.sh" not in ci,
      "两个脚本并存会让 CI 打出不同的资产布局")

print("== D. 升级绕开 pnpm ==")
check("D1 升级走 extractTgzInto",
      re.search(r"val installed = extractTgzInto\(staged, dest\)", prov) is not None)
check("D2 不再有 prepareUpgrade", "prepareUpgrade(" not in prov)
check("D3 不再外科改 lockfile", "normalizeLockfileSpec(" not in prov)
check("D4 不把 file: 依赖改成纯版本号", "normalizeDependencySpec(" not in prov)
check("D5 成功路径保留暂存 tgz（否则 lockfile 悬空）",
      "staged.delete()" in prov and "keeping" in prov,
      "删了暂存文件会让 pnpm-lock.yaml 的 file: 记录全部悬空")
# D5 要更严：确认 finally 里是条件删除而不是无条件删除
m_fin = re.search(r"finally \{(.{0,1400}?)\n        \}", prov, re.S)
check("D6 finally 里是条件删除", m_fin is not None and
      ("installedVersion(ctx, plugin.pkg)" in m_fin.group(1)),
      "无条件删 = 装完就把 lockfile 弄坏")
check("D7 升级后校验版本严格变新",
      re.search(r"compareVersions\(installed, upgradeFrom\) <= 0", prov) is not None)
check("D8 有 npm 依赖的插件被标记",
      "hasNpmDependencies = true" in prov)

print()
print("通过 %d / 失败 %d / 跳过 0" % (TOTAL - len(FAILS), len(FAILS)))
sys.exit(1 if FAILS else 0)
