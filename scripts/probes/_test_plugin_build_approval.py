#!/usr/bin/env python3
"""git 源插件「批准并重试」永远不出现 —— 运行时插件的构建批准解析（2026-11-28 截图反馈）

## 判据（先证旧实现转红，再证补丁后转绿）

夹具全部来自**真实产物**，不是手写的近似：

  scripts/probes/data/pnpm-git-dep-prepare.txt   pnpm 12.21.0 对
      pnpm add https://github.com/dsh-market/dsh-market 的原文输出
  scripts/probes/data/pm-0.2.2-rc.2-*.js        设备上现跑的 dsh-plugin-manager
      0.2.2-rc.2 的两段原文（index.js 的 LOG_KINDS + 内联 build-approval 区、
      lib/types/build-approval.js 全文）
  scripts/probes/data/pm-0.2.1-rc.2-*.js        仓库 _runtime_bak zip 里的 0.2.1-rc.2
      （旧形态：既没有该错误码、也没有 git 键解析）

断言分组：

  A 旧实现**必须**解析不出键（否则这条反馈无从解释，探针失去意义）
  B 补丁器在两种形态上都改对（幂等、锚点唯一、锚点缺失非零退出、node --check）
  C 补丁后**真跑**那段函数，能从真实输出里取出完整 git 键；且不误伤其它形态
  D 补丁后的文件仍是合法 JS

跑法：python3 scripts/probes/_test_plugin_build_approval.py
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PATCHER = os.path.join(ROOT, "scripts", "patch-plugin-build-approval.py")
DATA = os.path.join(ROOT, "scripts", "probes", "data")
FIXTURE = os.path.join(DATA, "pnpm-git-dep-prepare.txt")
A_BA = os.path.join(DATA, "pm-0.2.2-rc.2-build-approval.js")
A_IDX = os.path.join(DATA, "pm-0.2.2-rc.2-index-slice.js")
B_BA = os.path.join(DATA, "pm-0.2.1-rc.2-build-approval.js")
B_IDX = os.path.join(DATA, "pm-0.2.1-rc.2-index-slice.js")

EXPECTED_KEY = (
    "dshmarket@https://codeload.github.com/dsh-market/dsh-market/"
    "tar.gz/18004a19b1c5bca1cd3ea405e2171a5fb3c089ce"
)

PM_DIR = os.path.join("lib", "node_modules", "@deepseek-ai", "dsh-plugin-manager")
MARKER = "[dsh-android: build-approval]"


def read(p):
    with io.open(p, encoding="utf-8") as f:
        return f.read()


def upstream_022_key(output):
    """0.2.2-rc.2 里那条正则的**原文**（对真实输出实测为 None）。"""
    pat = re.compile(
        r"ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED[\s\S]*?^allowBuilds:\r?\n[ \t]+(.+?): true$",
        re.M,
    )
    m = pat.search(output)
    return None if m is None else m.group(1)


def run_patcher(target):
    proc = subprocess.run(
        [sys.executable, PATCHER, target],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def node_check(path):
    proc = subprocess.run(
        ["node", "--check", path],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode, (proc.stderr or "").strip()


def run_node(script_name, cwd):
    proc = subprocess.run(
        ["node", script_name],
        cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    return proc.returncode, (proc.stdout or "").strip(), (proc.stderr or "").strip()


def behaviour_script(ba_text, output):
    """把补丁后文件里的三个函数**原样截出来**执行 —— 不 import 上游模块（它要 yaml）。"""

    def grab(name, prefix=""):
        start = ba_text.index(prefix + "function " + name)
        end = ba_text.index("\n}", start) + 2
        return ba_text[start:end]

    parts = ["const IGNORED_BUILDS = /\\bIgnored build scripts: (.+)/;"]
    if "function carriesCredential(" in ba_text:
        parts.append(grab("carriesCredential"))
    parts.append(grab("gitDepPrepareKey"))
    if "export function blockedBuildNames" in ba_text:
        parts.append(grab("blockedBuildNames", "export "))
    else:
        parts.append(grab("ignoredBuildNames", "export "))
    parts.append("function callBuilds(output) { return "
                 + ("blockedBuildNames" if "export function blockedBuildNames" in ba_text
                    else "ignoredBuildNames") + "(output); }")
    parts.append("const out = %s;" % json.dumps(output))
    parts.append("const registry = %s;" % json.dumps(
        "Error: ERR_PNPM_IGNORED_BUILDS\n\n  \u00d7 adding a new package\n"
        "  \u2570\u2500\u25b6 Ignored build scripts: esbuild@0.28.2\n"))
    parts.append("const none = \"hello\";")
    parts.append("const truncated = out.split(\": true\")[0];")
    parts.append("const credential = \"dshmarket@git+https://user:sec@gitlab.com/a/b.git\";")
    parts.append("const crOutput = \"Error: ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED\\n\" "
                 "+ \"  help:\\n\" + \"        allowBuilds:\\n\" "
                 "+ \"          \" + credential + \": true\\n\";")
    parts.append("console.log(JSON.stringify({keys: callBuilds(out), registry: callBuilds(registry), "
                 "none: callBuilds(none), truncated: callBuilds(truncated), "
                 "credential: callBuilds(crOutput)}));")
    return "\n".join(parts)


def main():
    ok = 0
    fail = 0

    def check(label, cond, extra=""):
        nonlocal ok, fail
        if cond:
            ok += 1
        else:
            fail += 1
            print("FAIL %s%s" % (label, (" | " + extra) if extra else ""))

    for path in (PATCHER, FIXTURE, A_BA, A_IDX, B_BA, B_IDX):
        if not os.path.isfile(path):
            print("FAIL missing fixture/patcher: %s" % path)
            return 1

    output = read(FIXTURE)

    # ── A 旧实现必须解析不出（负对照）──────────────────────────────────
    check("A1 上游 0.2.2 正则对真实输出取不到键", upstream_022_key(output) is None,
          repr(upstream_022_key(output)))
    check("A2 真实输出里 allowBuilds: 是缩进的",
          re.search(r"^[ \t]+allowBuilds:$", output, re.M) is not None)
    check("A3 长键被折成两行（缩进续行）",
          re.search(r"dsh-market/\n[ \t]+tar\.gz/", output) is not None)
    check("A4 0.2.1 的 LOG_KINDS 认不出该错误码",
          "GIT_DEP_PREPARE_NOT_ALLOWED" not in read(B_IDX))
    check("A5 0.2.1 没有 git 键解析", "gitDepPrepareKey" not in read(B_BA))

    # ── B 补丁器两种形态都改对 ──────────────────────────────────────────
    for shape, ba_src, idx_src in (("0.2.2", A_BA, A_IDX), ("0.2.1", B_BA, B_IDX)):
        tmp = tempfile.mkdtemp(prefix="pm-%s-" % shape)
        try:
            dest = os.path.join(tmp, PM_DIR, "lib", "types")
            os.makedirs(dest, exist_ok=True)
            shutil.copy2(ba_src, os.path.join(dest, "build-approval.js"))
            shutil.copy2(idx_src, os.path.join(tmp, PM_DIR, "lib", "index.js"))
            rc, log = run_patcher(tmp)
            check("B1 补丁成功 %s" % shape, rc == 0, log[-300:])
            ba_txt = read(os.path.join(dest, "build-approval.js"))
            idx_txt = read(os.path.join(tmp, PM_DIR, "lib", "index.js"))
            for label, txt in (("build-approval", ba_txt), ("index", idx_txt)):
                check("B3 标记写入 %s/%s" % (shape, label), MARKER in txt)
                check("B4 助手函数存在 %s/%s" % (shape, label), "function gitDepPrepareKey(" in txt)
                check("B5 git 键解析已接线 %s/%s" % (shape, label),
                      "gitDepPrepareKey(output)" in txt and "GIT_DEP_PREPARE.exec(output)" not in txt)
                check("B6 不再留旧正则 %s/%s" % (shape, label), "GIT_DEP_PREPARE = /ERR_PNPM" not in txt)
            rc2, log2 = run_patcher(tmp)
            check("B7 幂等 %s" % shape, rc2 == 0 and "already-patched" in log2, log2[-200:])
            rc3, err3 = node_check(os.path.join(dest, "build-approval.js"))
            check("B8 node --check build-approval %s" % shape, rc3 == 0, err3[-200:])
            # index 夹具是**两段原文切片**（中间有省略注释），本身不是合法 JS，
            # 所以只对 build-approval.js 做 node --check；index 的正确性由 B5/B6 与
            # 行为测试里截出的函数覆盖。
            if shape == "0.2.1":
                check("B10 0.2.1 补上了错误码识别",
                      "ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED" in idx_txt)
                check("B11 0.2.1 补上了凭据过滤", "function carriesCredential(" in ba_txt)

            script = behaviour_script(ba_txt, output)
            name = "beh-%s.mjs" % shape.replace(".", "_")
            with io.open(os.path.join(tmp, name), "w", encoding="utf-8", newline="\n") as f:
                f.write(script)
            rc5, out5, err5 = run_node(name, tmp)
            data = json.loads(out5) if rc5 == 0 and out5 else {}
            check("C1 补丁后能解析出完整键 %s" % shape,
                  data.get("keys") == [EXPECTED_KEY], (out5 + err5)[-300:])
            check("C3 注册源形态仍取到裸包名 %s" % shape,
                  data.get("registry") == ["esbuild@0.28.2"], (out5 + err5)[-300:])
            check("C4 无该错误码时不产出键 %s" % shape, data.get("none") == [], out5[-200:])
            check("C5 截断输出（缺收尾）不产出键 %s" % shape, data.get("truncated") == [], out5[-200:])
            check("C6 凭据型 git 键被过滤 %s" % shape, data.get("credential") == [], out5[-200:])
        finally:
            shutil.rmtree(tmp, ignore_errors=True)

    # ── B12 锚点全不命中必须非零退出 ─────────────────────────────────────
    tmp = tempfile.mkdtemp(prefix="pm-broken-")
    try:
        dest = os.path.join(tmp, PM_DIR, "lib", "types")
        os.makedirs(dest, exist_ok=True)
        with io.open(os.path.join(dest, "build-approval.js"), "w", encoding="utf-8") as f:
            f.write("// upstream shape changed\nexport function blockedBuildNames() { return []; }\n")
        with io.open(os.path.join(tmp, PM_DIR, "lib", "index.js"), "w", encoding="utf-8") as f:
            f.write("// upstream shape changed\n")
        rc, log = run_patcher(tmp)
        check("B12 锚点缺失非零退出", rc != 0, log[-250:])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n%d passed, %d failed" % (ok, fail))
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
