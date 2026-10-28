#!/usr/bin/env bash
# 生成「固定 debug 签名密钥」（一次性；产物入库）。
#
# ## 为什么需要这个
# AGP 默认的 debug 签名用的是 `~/.android/debug.keystore`，**首次构建时自动生成**。
# 于是每个开发者、每个 CI runner（干净 HOME）各有一把不同的 debug key，
# 导致 edge/preview 渠道的 debug 包**互相无法覆盖安装** —— 每个版本对系统来说
# 都是「签名冲突」，只能卸载重装。自动更新在 debug 渠道上就完全失效。
#
# 这里生成一把仓库内共享的 debug key，所有人/所有 CI 用同一把，覆盖升级才成立。
#
# ## 安全边界（有意为之，不是疏漏）
# 口令明文写在 `debug-signing/dsh-debug.properties`，密钥本体入库。这不是疏忽：
# debug key 从定义上就不是安全边界（AOSP 自带的 `debug.keystore` 口令就是公开的
# `android`），它只需要「全员一致」。真正的 release key 仍然只在 secrets 与本地
# `keystore/`，两者互不影响（见 docs/RELEASE.md）。
#
# 用法：
#   bash scripts/make-debug-keystore.sh          # 已存在则拒绝覆盖
#   bash scripts/make-debug-keystore.sh --force  # 强制重建（会让所有 debug 包变孤儿！）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/debug-signing"
KEYSTORE="$OUT_DIR/dsh-debug.keystore"
PROPS="$OUT_DIR/dsh-debug.properties"
FINGERPRINT="$OUT_DIR/dsh-debug.fingerprint.txt"

ALIAS="dsh-debug"
STORE_PASS="dsh-debug"
DNAME="CN=DSH Debug,O=dsh-mobile,C=CN"
VALIDITY_DAYS="10000"
KEY_ALG="RSA"
KEY_SIZE="2048"

FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# ── 找 keytool ──────────────────────────────────────────────────────────
# Windows 上 git-bash 的 PATH 里常常没有 java（Gradle 用的是自己找的 JDK），
# 所以这里按 KEYTOOL → JAVA_HOME → Android Studio JBR → PATH 的顺序找。
find_keytool() {
  if [[ -n "${KEYTOOL:-}" && -x "${KEYTOOL}" ]]; then echo "$KEYTOOL"; return; fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then echo "$JAVA_HOME/bin/keytool"; return; fi
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool.exe" ]]; then echo "$JAVA_HOME/bin/keytool.exe"; return; fi
  local candidates=(
    "/c/Program Files/Android/Android Studio/jbr/bin/keytool.exe"
    "/e/Android/AndroidStudio/jbr/bin/keytool.exe"
    "/d/Apps/Android Studio/jbr/bin/keytool.exe"
    "$HOME/AppData/Local/Programs/Android Studio/jbr/bin/keytool.exe"
  )
  for c in "${candidates[@]}"; do
    [[ -x "$c" ]] && { echo "$c"; return; }
  done
  if command -v keytool >/dev/null 2>&1; then command -v keytool; return; fi
  if command -v keytool.exe >/dev/null 2>&1; then command -v keytool.exe; return; fi
  return 1
}

KEYTOOL_BIN="$(find_keytool)" || {
  echo "ERROR: keytool not found. Set KEYTOOL=/path/to/keytool or JAVA_HOME." >&2
  exit 1
}
echo "keytool: $KEYTOOL_BIN"

if [[ -f "$KEYSTORE" && $FORCE -ne 1 ]]; then
  echo "keystore already exists: $KEYSTORE"
  echo "（如需重建请加 --force，注意：会让现有 debug 包全部无法覆盖升级）"
  exit 0
fi

mkdir -p "$OUT_DIR"

if [[ -f "$KEYSTORE" ]]; then
  echo "WARNING: --force 重建 debug 密钥，现有 debug 包将无法覆盖升级" >&2
  rm -f "$KEYSTORE"
fi

# PKCS12：Android 构建链默认格式，keytool 12+ 也已是默认，但显式指定避免歧义。
"$KEYTOOL_BIN" -genkeypair \
  -keystore "$KEYSTORE" \
  -storetype PKCS12 \
  -storepass "$STORE_PASS" \
  -keypass "$STORE_PASS" \
  -alias "$ALIAS" \
  -keyalg "$KEY_ALG" \
  -keysize "$KEY_SIZE" \
  -validity "$VALIDITY_DAYS" \
  -dname "$DNAME"

cat > "$PROPS" <<EOF
# 固定 debug 签名材料（产物入库，口令明文是有意为之 —— 见 docs/RELEASE.md）
# 由 scripts/make-debug-keystore.sh 生成，请勿手工修改。
storeFile=dsh-debug.keystore
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$STORE_PASS
EOF

# 证书 SHA-256 指纹（公开值）：CI 用它断言构建产物确实用的这把 key，
# 防止哪天 signingConfig 失效静默回落到 ~/.android/debug.keystore。
FPR="$("$KEYTOOL_BIN" -list -v -keystore "$KEYSTORE" -storepass "$STORE_PASS" -alias "$ALIAS" \
  | tr -d '\r' | grep -i "SHA256:" | head -1 | sed 's/.*SHA256:[[:space:]]*//')"
if [[ -z "$FPR" ]]; then
  echo "ERROR: cannot read certificate fingerprint" >&2
  exit 1
fi
printf '%s\n' "$FPR" > "$FINGERPRINT"

echo "done:"
echo "  $KEYSTORE"
echo "  $PROPS"
echo "  $FINGERPRINT ($FPR)"
