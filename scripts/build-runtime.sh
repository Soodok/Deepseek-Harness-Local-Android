#!/usr/bin/env bash
# build-runtime.sh —— 运行时唯一流水线：收集 → 去重 → 校验 → BUILD.json。
#
# 用法:
#   scripts/build-runtime.sh --abi arm64-v8a --out dist/runtime-arm64-v8a.zip
#   scripts/build-runtime.sh --abi x86_64 --out dist/runtime-x86_64.zip
#   scripts/build-runtime.sh --check dist/runtime-arm64-v8a.zip --abi arm64-v8a
#
# 说明:
#   · full APK、tag Release、云端运行时全部用这个脚本的产物，保证 sha 一致；
#   · Gradle 不再打补丁/去重，只校验 + 嵌入；
#   · 需要 Linux + apt/dpkg（GitHub ubuntu runner），本地 Windows 请用 WSL/CI。
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ABI=""
OUT=""
CHECK=""
DSH_VERSION="${DSH_VERSION:-0.2.1-rc.2}"

usage() {
  echo "usage: $0 --abi arm64-v8a|x86_64 --out <zip> | --check <zip> --abi ..." >&2
  exit 2
}
while [ $# -gt 0 ]; do
  case "$1" in
    --abi) ABI="${2:?}"; shift 2 ;;
    --out) OUT="${2:?}"; shift 2 ;;
    --check) CHECK="${2:?}"; shift 2 ;;
    --dsh-version) DSH_VERSION="${2:?}"; shift 2 ;;
    -h|--help) usage ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
done

case "$ABI" in
  arm64-v8a) TERMUX_ARCH=aarch64 ;;
  x86_64) TERMUX_ARCH=x86_64 ;;
  *) echo "unsupported abi: $ABI (arm64-v8a|x86_64)" >&2; exit 2 ;;
esac

FIND=/usr/bin/find
[ -x "$FIND" ] || FIND=find

verify_zip() {
  local zip="$1"
  [ -f "$zip" ] || { echo "错误：找不到 $zip" >&2; return 1; }
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  # 必须用 unzip（Info-ZIP）恢复 zip 里的 Unix 权限位；Python extractall 会丢可执行位。
  unzip -q "$zip" -d "$tmp"

  test -x "$tmp/bin/bash" || { echo "错误：缺少 bin/bash" >&2; return 1; }
  test -x "$tmp/bin/rg" || { echo "错误：缺少 bin/rg" >&2; return 1; }
  for so in libreadline.so.8 libhistory.so.8 libncursesw.so.6 libncurses.so.6 \
            libiconv.so libpcre2-8.so; do
    test -e "$tmp/lib/$so" || { echo "错误：SONAME 库 lib/$so 缺失" >&2; return 1; }
  done
  test -x "$tmp/bin/pnpm" || { echo "错误：缺少 bin/pnpm" >&2; return 1; }
  test -x "$tmp/bin/curl" || { echo "错误：缺少 bin/curl" >&2; return 1; }
  test -e "$tmp/usr/bin/.keep" || { echo "错误：usr/bin 丢失" >&2; return 1; }

  local na
  case "$ABI" in
    arm64-v8a) na=arm64 ;;
    x86_64) na=x64 ;;
  esac
  test -x "$tmp/lib/node_modules/@vscode/ripgrep-android-$na/bin/rg" \
    || { echo "错误：ripgrep 平台包缺失（$na）" >&2; return 1; }
  if "$FIND" "$tmp" -type f -path '*@vscode/ripgrep-linux-*/*/rg' -print -quit | grep -q .; then
    echo "错误：runtime 混入宿主 Linux ripgrep" >&2; return 1
  fi
  if [ -e "$tmp/lib/node_modules/@xiaopb/dsh-hardssh" ]; then
    echo "错误：runtime.zip 不应包含 hardssh（已解耦）" >&2; return 1
  fi

  python3 - "$tmp/bin/node" "$ABI" <<'PY'
import sys
path, abi = sys.argv[1], sys.argv[2]
want = {"arm64-v8a": 183, "x86_64": 62}[abi]
head = open(path, "rb").read(20)
if head[:4] != b"\x7fELF":
    raise SystemExit(f"not ELF: {path}")
got = int.from_bytes(head[18:20], "little")
if got != want:
    raise SystemExit(f"node architecture mismatch: e_machine={got}, expected {want}")
PY

  # 补丁标记（Gradle 只校验，不现场打）
  python3 - "$zip" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
def text(name):
    return z.read(name).decode("utf-8", "replace") if name in z.namelist() else ""
ptc = text("lib/node_modules/@deepseek-ai/dsh-ptc-runtime-node/lib/index.js")
need = ['"LD_LIBRARY_PATH"', "Inject the runtime library path unconditionally",
        "OPENSSL_CONF", "SSL_CERT_FILE", '"MAGICK_CONFIGURE_PATH"',
        # v1.4.33（issue #65）：bootstrap 路径映射兜底，缺它 run_code 必报
        # "PTC runtime bootstrap is unavailable" 而文件其实存在。
        "[dsh-android] bootstrap path fallback"]
if any(n not in ptc for n in need):
    raise SystemExit("runtime lacks PTC patch; run collect/build-runtime again")
poly = text("lib/node_modules/@deepseek-ai/dsh-web-frontend/dist/index.html")
need = ["legacy-webview polyfill v4", "throwIfAborted", "AbortSignal.any", "Promise.withResolvers"]
if any(n not in poly for n in need):
    raise SystemExit("runtime lacks WebView polyfill v4")
PY

  echo "verify OK: $zip ($ABI)"
}

if [ -n "$CHECK" ]; then
  verify_zip "$CHECK"
  exit 0
fi

[ -n "$OUT" ] || usage
OUT="$(realpath -m "$OUT")"
mkdir -p "$(dirname "$OUT")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

RAW="$WORK/runtime-raw.zip"
DEDUPED="$WORK/runtime-zodded.zip"

echo "== build-runtime =="
echo "abi:          $ABI ($TERMUX_ARCH)"
echo "dsh version:  $DSH_VERSION"
echo "output:       $OUT"

# 1) 收集 Termux Node 闭包 + dsh 引擎（collect 内部已打全部 Android 补丁）
chmod +x "$SCRIPT_DIR/collect-termux-runtime.sh"
DSH_VERSION="$DSH_VERSION" "$SCRIPT_DIR/collect-termux-runtime.sh" "$RAW" "$TERMUX_ARCH"

# 2) 嵌套 zod 去重（已去重则跳过）
NESTED="$(python3 - "$RAW" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
n = 0
for name in z.namelist():
    if name.startswith("lib/node_modules/zod/"):
        continue
    parts = name[len("lib/node_modules/"):].split("/") if name.startswith("lib/node_modules/") else []
    for i, p in enumerate(parts):
        if p == "node_modules" and i + 1 < len(parts) and parts[i + 1] == "zod":
            n += 1
            break
print(n)
PY
)"
if [ "$NESTED" -gt 0 ]; then
  echo "zod:          $NESTED nested entries -> dedupe"
  python3 "$SCRIPT_DIR/dedupe-zod.py" "$RAW" "$DEDUPED" >/dev/null
else
  echo "zod:          no nested duplicates, skip"
  cp "$RAW" "$DEDUPED"
fi

# 3) SONAME 别名去重（就地）
python3 "$SCRIPT_DIR/dedupe-runtime.py" "$DEDUPED"

# 4) 写入 BUILD.json + 计算版本（内容 sha 前 8 位）
python3 - "$DEDUPED" "$ABI" "$DSH_VERSION" <<'PY'
import hashlib, json, os, sys, time, zipfile
path, abi, dsh = sys.argv[1], sys.argv[2], sys.argv[3]
h = hashlib.sha256()
with open(path, "rb") as f:
    for b in iter(lambda: f.read(1 << 20), b""):
        h.update(b)
sha = h.hexdigest()
version = time.strftime("%Y.%m.%d", time.gmtime()) + "-" + sha[:8]
meta = {
    "version": version,
    "abi": abi,
    "engineVersion": dsh,
    "builtAt": int(time.time()),
    "contentSha256": sha,
}
with zipfile.ZipFile(path, "a", zipfile.ZIP_DEFLATED) as z:
    z.writestr("BUILD.json", json.dumps(meta, indent=2, ensure_ascii=False) + "\n")
print(version)
PY

# 5) 校验 + 落地
verify_zip "$DEDUPED"
cp "$DEDUPED" "$OUT"
META="${OUT%.zip}.json"
python3 - "$OUT" "$META" <<'PY'
import hashlib, json, os, sys, zipfile
path, meta_path = sys.argv[1], sys.argv[2]
h = hashlib.sha256()
with open(path, "rb") as f:
    for b in iter(lambda: f.read(1 << 20), b""):
        h.update(b)
with zipfile.ZipFile(path) as z:
    build = json.loads(z.read("BUILD.json").decode("utf-8"))
    unpacked = sum(i.file_size for i in z.infolist())
meta = {
    "version": build["version"],
    "abi": build["abi"],
    "engineVersion": build["engineVersion"],
    "builtAt": build["builtAt"],
    "sha256": h.hexdigest(),
    "size": os.path.getsize(path),
    "unpackedBytes": unpacked,
}
open(meta_path, "w").write(json.dumps(meta, indent=2) + "\n")
print(f"runtime: {meta['version']} {meta['sha256'][:12]}… {meta['size']/1048576:.1f} MB (zip)")
PY

echo "done:         $OUT"
echo "meta:         $META"
