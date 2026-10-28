#!/usr/bin/env bash
# 生成「共享 debug 签名密钥」（一次性；产物**不入库**，放 CI secret + 团队密码库）。
#
# ## 为什么需要这个
# AGP 默认的 debug 签名用的是 `~/.android/debug.keystore`，**首次构建时自动生成**。
# 于是每个开发者、每个 CI runner（干净 HOME）各有一把不同的 debug key，
# 导致 edge/preview 渠道的 debug 包**互相无法覆盖安装** —— 每个版本对系统来说
# 都是「签名冲突」，只能卸载重装。自动更新在 debug 渠道上就完全失效。
#
# ## 为什么不上传仓库
# 需要的只是「同一把 key」，不是「公开这把 key」。私钥进仓库的唯一后果是：
# 谁都能签一个"看起来是同一来源"的 debug 包，签名比对这道防线在 debug 渠道上
# 直接归零。所以材料只放两处：
#   · CI：repository secret `DEBUG_KEYSTORE_BASE64`（本脚本会打印）；
#   · 本地：`debug-signing/dsh-debug.keystore`（.gitignore 已忽略）。
# 都没有时 Gradle 退回本机默认 key —— 开发不受影响，只是产物不能覆盖 CI 的包。
#
# 用法：
#   bash scripts/make-debug-keystore.sh          # 已存在则拒绝覆盖，只打印 secret 内容
#   bash scripts/make-debug-keystore.sh --force  # 强制重建（会让所有 debug 包变孤儿！）
#   bash scripts/make-debug-keystore.sh --print  # 只输出 base64 + 指纹，不重建
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/debug-signing"
KEYSTORE="$OUT_DIR/dsh-debug.keystore"
PROPS="$OUT_DIR/dsh-debug.properties"
FINGERPRINT="$OUT_DIR/dsh-debug.fingerprint.txt"

ALIAS="dsh-debug"
# 口令**随机生成**，不写死在脚本里：
# 这把 key 虽然不进仓库，但"keystore 泄漏"与"口令公开"叠加就等于私钥泄漏。
# 口令与 keystore 分开存放（keystore 给 CI secret，口令给 secret + 团队密码库）。
STORE_PASS=""
DNAME="CN=DSH Debug,O=dsh-mobile,C=CN"
VALIDITY_DAYS="10000"
KEY_ALG="RSA"
KEY_SIZE="2048"

FORCE=0
PRINT_ONLY=0
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    --print) PRINT_ONLY=1 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# 28 位字母数字：够长、无需转义、能在 properties 里裸写
gen_pass() {
  if command -v python >/dev/null 2>&1; then
    python -c "import secrets,string;s=string.ascii_letters+string.digits;print(''.join(secrets.choice(s) for _ in range(28)))"
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c "import secrets,string;s=string.ascii_letters+string.digits;print(''.join(secrets.choice(s) for _ in range(28)))"
  else
    LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 28
  fi
}

read_props_pass() {
  [[ -f "$PROPS" ]] || return 1
  sed -n 's/^storePassword=//p' "$PROPS" | head -1
}

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

print_secrets() {
  local fpr=""
  [[ -f "$FINGERPRINT" ]] && fpr="$(tr -d '\r\n' < "$FINGERPRINT")"
  echo
  echo "── 一次性配置（本机执行，值不要提交进仓库）──────────────────────────"
  echo "GitHub Secrets（Repository secrets）："
  echo "  DEBUG_KEYSTORE_BASE64 = 本目录下 dsh-debug.keystore.base64 的内容（不打印，避免终端回滚留存）"
  echo "  DEBUG_STORE_PASSWORD  = $STORE_PASS"
  echo "  DEBUG_KEY_ALIAS       = $ALIAS"
  echo "GitHub Variables（防签错 key）："
  echo "  DEBUG_CERT_SHA256     = ${fpr:-<运行本脚本生成>}"
  echo
  echo "gh secret set DEBUG_KEYSTORE_BASE64 -R <owner>/<repo> < debug-signing/dsh-debug.keystore.base64"
  echo "gh secret set DEBUG_STORE_PASSWORD -R <owner>/<repo> --body $STORE_PASS"
  echo "gh variable set DEBUG_CERT_SHA256 -R <owner>/<repo> --body ${fpr:-...}"
  echo
  echo "本地如果也要和 CI 同签名（可覆盖安装 edge 包），保留 debug-signing/ 即可（已 gitignore）。"
  echo "─────────────────────────────────────────────────────────────────"
}

if [[ $PRINT_ONLY -eq 1 ]]; then
  [[ -f "$KEYSTORE" ]] || { echo "ERROR: keystore 不存在：$KEYSTORE" >&2; exit 1; }
  STORE_PASS="$(read_props_pass || true)"
  [[ -n "$STORE_PASS" ]] || STORE_PASS="<见 debug-signing/dsh-debug.properties>"
  base64 < "$KEYSTORE" | tr -d '\n' > "$OUT_DIR/dsh-debug.keystore.base64"
  echo "base64: $OUT_DIR/dsh-debug.keystore.base64"
  print_secrets
  exit 0
fi

if [[ -f "$KEYSTORE" && $FORCE -ne 1 ]]; then
  echo "keystore already exists: $KEYSTORE"
  echo "（如需重建请加 --force，注意：会让现有 debug 包全部无法覆盖升级）"
  STORE_PASS="$(read_props_pass || true)"
  [[ -n "$STORE_PASS" ]] || STORE_PASS="<见 debug-signing/dsh-debug.properties>"
  base64 < "$KEYSTORE" | tr -d '\n' > "$OUT_DIR/dsh-debug.keystore.base64"
  print_secrets
  exit 0
fi

mkdir -p "$OUT_DIR"

if [[ -f "$KEYSTORE" ]]; then
  echo "WARNING: --force 重建 debug 密钥，现有 debug 包将无法覆盖升级" >&2
  rm -f "$KEYSTORE"
fi

STORE_PASS="$(gen_pass)"
if [[ -z "$STORE_PASS" ]]; then
  echo "ERROR: 无法生成随机口令（/dev/urandom 与 python 都不可用）" >&2
  exit 1
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
# 共享 debug 签名材料（**不入库**，.gitignore 已忽略 debug-signing/*）
# 由 scripts/make-debug-keystore.sh 生成，请勿手工修改；口令随机生成、与 keystore 分开保管。
# CI 不读这个文件：那边用 DEBUG_KEYSTORE_BASE64 / DEBUG_STORE_PASSWORD secret。
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

base64 < "$KEYSTORE" | tr -d '\n' > "$OUT_DIR/dsh-debug.keystore.base64"

echo "done:"
echo "  $KEYSTORE            （不入库）"
echo "  $PROPS               （不入库）"
echo "  $FINGERPRINT ($FPR)  （公开，已入库）"
echo "  $OUT_DIR/dsh-debug.keystore.base64 （上传 secret 用，不入库）"
print_secrets
