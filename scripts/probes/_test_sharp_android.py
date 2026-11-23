#!/usr/bin/env python3
"""sharp Android 回退补丁的守护探针（scripts/patch-sharp-android.py）。

## 它守的是什么
用户真机上报的报错：

    DeepSeek Messages transport failed: Could not load the "sharp" module
    using the android-arm64 runtime / Possible solutions:
    - Manually install libvips >= 8.18.7 ...
    - Add WebAssembly-based dependencies: npm ...

这条消息有两个问题，任一都足以让用户被带偏：
  1. **Android 上 `runtimePlatform` 是 "android-arm64"**，上游 switch 没有这个
     case，只能靠 switch 之后的 `@img/sharp-wasm32` 兜底。一旦那个包/依赖缺件，
     没有一条 case 会命中 → 落到 `if (!sharp)` 兜底 → 抛上面那段 help。
  2. 那段 help 建议装 libvips 与 npm 装 wasm32 —— App 离线包做不到。

所以探针要钉两件事：
  A. **补丁脚本本身**：存在 `DSH_SHARP_ANDROID` 标记、加了 android-arm64 与
     android-arm 两个 case、case 命中后 break（不能把 Android 指到
     `@img/sharp-linux-arm64`，那是 glibc ELF）、整段 help 被替换。
  B. **接线**：`scripts/collect-termux-runtime.sh` 必须真的调用这个补丁，
     并且是 fail-fast（`|| { … exit 1; }`）—— 静默漏打等于把坏包发出去。
  C. **行为**（可选，需 runtime.zip）：把补丁打到副本上，再伪造
     `platform=android / arch=arm64` 加载 sharp，断言 wasm32 路径能出图；
     把 wasm32 目录藏起来，断言错误文案已是「打包问题，不是网络/API key」。

## 用法
    python scripts/probes/_test_sharp_android.py
    python scripts/probes/_test_sharp_android.py --runtime <runtime.zip|runtime 根目录>
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.environ.get("DSH_ANDROID_ROOT") or os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "..")
)
SCRIPT = os.path.join(ROOT, "scripts", "patch-sharp-android.py")
COLLECT = os.path.join(ROOT, "scripts", "collect-termux-runtime.sh")

PASS = 0
FAIL = 0


def check(name, ok, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  PASS  [%s]%s" % (name, (" " + detail) if detail else ""))
    else:
        FAIL += 1
        print("  FAIL  [%s]%s" % (name, (" " + detail) if detail else ""))


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_py_comments(text):
    """去掉 Python 的 # 注释与字符串之外的说明，避免「注释里写了也算」的假绿。"""
    out = []
    in_str = None
    for line in text.splitlines():
        buf = []
        i = 0
        while i < len(line):
            ch = line[i]
            if in_str:
                buf.append(ch)
                if ch == in_str and (i == 0 or line[i - 1] != "\\"):
                    in_str = None
            elif ch in "\"'":
                in_str = ch
                buf.append(ch)
            elif ch == "#":
                break
            else:
                buf.append(ch)
            i += 1
        out.append("".join(buf))
    return "\n".join(out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runtime", default="", help="runtime.zip 或解包后的 runtime 根目录（可选，跑 C 组）")
    args = ap.parse_args()

    check("A0 patch script exists", os.path.isfile(SCRIPT), SCRIPT)
    if not os.path.isfile(SCRIPT):
        print("\n%d checks, %d failed" % (PASS + FAIL, FAIL))
        return 1

    src = read(SCRIPT)
    body = strip_py_comments(src)  # 注释里提到不算
    _ = body

    # ---- A. 补丁脚本结构 ----
    check("A1 marker present", 'MARK = "DSH_SHARP_ANDROID"' in src)
    check("A2 android-arm64 case added", '\'      case "android-arm64":' in body)
    check("A3 android-arm case added", '\'      case "android-arm":' in body)
    check("A4 case breaks to wasm fallback", re.search(
        r'case "android-arm64":\\n\'\s*\n\s*\'      case "android-arm":\\n\'\s*\n\s*\'        break;', body) is not None)
    android_block = src.split("ANDROID_CASE = (")[1].split(")\n")[0]
    # 真正的判据是「这个 case 块里有没有 require 到 glibc 变体」——注释里提到
    # @img/sharp-linux-arm64 是为了解释为什么不能用它，那不算违规。
    check("A5 never requires glibc sharp for Android",
          "require(" not in android_block,
          android_block.strip().splitlines()[-1][:60] if android_block.strip() else "")
    check("A6 help section replaced", "HELP_SWAP_START" in body and "HELP_REPLACEMENT" in body)
    check("A7 replacement drops npm advice", "npm install --include=optional sharp" not in body)
    check("A8 replacement says packaging not network",
          "packaging problem, not a network or API-key problem" in src)
    check("A9 actionable next step", "Settings > Runtime > Update" in src)
    check("A10 idempotent by marker", "if MARK in src:" in body)
    check("A11 shape change raises", "PatchShapeError" in body and "raise PatchShapeError" in body)
    check("A12 exit code reflects failure",
          re.search(r"sys\.exit\(0 if ok else 1\)", src) is not None)

    # 验证锚点长度真实：ARM64_CASE 必须等于目标文件里那一行的 6 空格缩进
    m = re.search(r"ARM64_CASE = '(.*?)'", src)
    check("A13 anchor is 6-space indent", m is not None and m.group(1).startswith("      case") and "\\t" not in m.group(1))

    # ---- B. 接线 ----
    collect = read(COLLECT)
    call = re.search(r'python3 "\$SCRIPTS_DIR/patch-sharp-android\.py" "\$ROOT"', collect)
    check("B1 collect calls sharp patch", call is not None)
    tail = collect[call.end():call.end() + 200] if call else ""
    check("B2 fail-fast (abort build)", "exit 1" in tail, tail.split("\n")[1][:60] if "\n" in tail else "")
    order_att = collect.find("patch-attachment-local.py")
    order_sharp = collect.find("patch-sharp-android.py")
    check("B3 runs after attachment-local", order_att >= 0 and order_sharp > order_att,
          "att=%d sharp=%d" % (order_att, order_sharp))

    # ---- C. 行为（可选）----
    if args.runtime:
        ok = run_behaviour(args.runtime)
        check("C-group behaviour", ok)

    print("\n%d checks, %d failed" % (PASS + FAIL, FAIL))
    return 1 if FAIL else 0


def run_behaviour(runtime):
    node = shutil.which("node")
    if node is None:
        print("  SKIP  [C1] node not on PATH")
        return True

    tmp = tempfile.mkdtemp(prefix="sharpand_")
    try:
        root = os.path.join(tmp, "rt")
        if os.path.isdir(runtime):
            shutil.copytree(runtime, root)
        else:
            with zipfile.ZipFile(runtime) as z:
                z.extractall(root)

        py = sys.executable
        rc = subprocess.run(
            [py, SCRIPT, root], capture_output=True, text=True, encoding="utf-8", errors="replace"
        )
        if rc.returncode != 0:
            print("  FAIL  [C1] patch refuses to apply: %s" % rc.stdout[-300:])
            return False
        print("  PASS  [C1] patch applied on a copy")

        nm = os.path.join(root, "lib", "node_modules")
        sharp_mjs = os.path.join(nm, "sharp", "dist", "sharp.mjs")
        patched = read(sharp_mjs)
        if 'case "android-arm64":' not in patched:
            print("  FAIL  [C2] patched file lacks android case")
            return False
        print("  PASS  [C2] patched file carries android case")

        driver = os.path.join(nm, "_sharpand_probe.cjs")
        with open(driver, "w", encoding="utf-8") as f:
            f.write(
                "Object.defineProperty(process,'platform',{value:'android',configurable:true});\n"
                "Object.defineProperty(process,'arch',{value:'arm64',configurable:true});\n"
                "const b64='iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAAEklEQVR42mP8z8BQz0AEYBxVSF8DAAMBAAG7Gc1AAAAAAElFTkSuQmCC';\n"
                "setTimeout(async()=>{try{const s=require('sharp');\n"
                "const md=await s(Buffer.from(b64,'base64'),{failOn:'none'}).metadata();\n"
                "const j=await s(Buffer.from(b64,'base64'),{failOn:'none'}).jpeg({quality:85}).toBuffer({resolveWithObject:true});\n"
                "console.log(JSON.stringify({ok:true,format:md.format,w:md.width,h:md.height,bytes:j.data.length}));\n"
                "}catch(e){console.log(JSON.stringify({ok:false,msg:(e.message||'').split('\\n').slice(0,3).join(' / ')}))}},350);\n"
            )
        r = subprocess.run([node, driver], cwd=nm, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=180)
        line = [x for x in (r.stdout or "").splitlines() if x.startswith("{")]
        if not line:
            print("  FAIL  [C3] driver produced no JSON: %s" % (r.stderr or r.stdout)[-200:])
            return False
        payload = line[-1]
        if '"ok":true' not in payload:
            print("  FAIL  [C3] wasm fallback cannot decode: %s" % payload[:180])
            return False
        print("  PASS  [C3] wasm fallback decodes+encodes %s" % payload[1:120])

        # C4：把 wasm32 目录藏起来 → 报错文案必须是「打包问题」
        hidden = os.path.join(tmp, "_wasm_hidden")
        os.rename(os.path.join(nm, "@img", "sharp-wasm32"), hidden)
        with open(driver, "w", encoding="utf-8") as f:
            f.write(
                "Object.defineProperty(process,'platform',{value:'android',configurable:true});\n"
                "Object.defineProperty(process,'arch',{value:'arm64',configurable:true});\n"
                "try{require('sharp');console.log('LOADED')}\n"
                "catch(e){console.log('MSG:'+(e.message||''))}\n"
            )
        r2 = subprocess.run([node, driver], cwd=nm, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=180)
        out = r2.stdout or ""
        if "LOADED" in out:
            print("  FAIL  [C4] sharp still loads without wasm32 — probe assumptions stale")
            return False
        if "packaging problem" not in out:
            print("  FAIL  [C4] help text not rewritten: %s" % out[:180])
            return False
        if "Manually install libvips" in out:
            print("  FAIL  [C4] upstream npm/libvips advice still present")
            return False
        print("  PASS  [C4] failure message is actionable, upstream advice gone")
        return True
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
