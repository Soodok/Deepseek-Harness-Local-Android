#!/usr/bin/env bash
# 生成 + 签名 `update-index.json`（App 自动更新的哈希清单）。
#
# 三个发布作业（stable / edge / preview）共用这一步，避免三处复制 30 行
# openssl 样板。密钥用 APK 正式签名那把（与 runtime-index.json 同源），
# App 侧因此可以复用同一套验签代码。
#
# 需要的环境变量：
#   RELEASE_KEYSTORE_BASE64 / RELEASE_STORE_PASSWORD —— 正式 keystore（secrets）
#   RUNTIME_INDEX_KEY_ID（可选）—— 索引里的 keyId 标识
#
# 用法：
#   bash scripts/publish-update-index.sh --channel stable --tag v1.4.37 \
#        --apk-dir apk --out-dir release --notes-url https://github.com/o/r/releases/tag/v1.4.37
set -euo pipefail

CHANNEL="stable"
TAG=""
APK_DIR="apk"
OUT_DIR="release"
NOTES_URL=""
MIN_APP_CODE="0"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --channel) CHANNEL="$2"; shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --apk-dir) APK_DIR="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --notes-url) NOTES_URL="$2"; shift 2 ;;
    --min-app-version-code) MIN_APP_CODE="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

: "${RELEASE_KEYSTORE_BASE64:?缺少 RELEASE_KEYSTORE_BASE64（索引复用 APK 正式签名密钥）}"
: "${RELEASE_STORE_PASSWORD:?缺少 RELEASE_STORE_PASSWORD}"

V=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -1)
C=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' app/build.gradle.kts | head -1)
mkdir -p "$OUT_DIR"

KS="$(mktemp)"
KEY="$(mktemp)"
PUB="$(mktemp)"
CERT="$(mktemp)"
trap 'rm -f "$KS" "$KEY" "$PUB" "$CERT"' EXIT
chmod 600 "$KS"

printf '%s' "$RELEASE_KEYSTORE_BASE64" | base64 -d > "$KS"
# 口令错时 openssl 只把 "Mac verify error: invalid password?" 写到 stderr 并 rc=1；
# 原先一律 2>/dev/null，用户看到的是后面 sign 阶段对空 PEM 的报错（#49 P2）。
if ! ERR=$(openssl pkcs12 -in "$KS" -nokeys -clcerts \
    -passin pass:"$RELEASE_STORE_PASSWORD" -out "$CERT" 2>&1); then
  if printf '%s' "$ERR" | grep -q "invalid password"; then
    echo "::error::RELEASE_STORE_PASSWORD 与 keystore 不匹配（openssl: invalid password）——检查该 secret 是否就是导出这个 keystore 时用的口令" >&2
  else
    echo "::error::无法读取 keystore 证书（openssl: $ERR）——文件可能损坏或不是 PKCS12" >&2
  fi
  exit 1
fi
openssl x509 -in "$CERT" -pubkey -noout > "$PUB"
# 取出正式签名私钥（RSA-4096），只用于本次索引签名，脚本结束即删（trap）。
if ! ERR=$(openssl pkcs12 -in "$KS" -nocerts -nodes \
    -passin pass:"$RELEASE_STORE_PASSWORD" -out "$KEY" 2>&1); then
  if printf '%s' "$ERR" | grep -q "invalid password"; then
    echo "::error::RELEASE_STORE_PASSWORD 与 keystore 不匹配（openssl: invalid password）" >&2
    exit 1
  fi
  # 老 keystore 可能使用 legacy 算法（RC2/3DES）：只有「非口令问题」才该回退
  if ! openssl pkcs12 -legacy -in "$KS" -nocerts -nodes \
      -passin pass:"$RELEASE_STORE_PASSWORD" -out "$KEY" 2>/dev/null; then
    echo "::error::无法从 keystore 取出私钥（非口令问题）：默认算法与 -legacy 都失败，keystore 可能不含私钥或用了更新的 PBE 算法" >&2
    exit 1
  fi
fi
if ! grep -q "PRIVATE KEY" "$KEY"; then
  echo "::error::从 keystore 导出的 PEM 里没有私钥（可能只导出了证书），索引签名需要私钥" >&2
  exit 1
fi
chmod 600 "$KEY"

python3 scripts/write-update-index.py \
  --apk-dir "$APK_DIR" \
  --version-code "$C" \
  --version-name "$V" \
  --tag "$TAG" \
  --channel "$CHANNEL" \
  --min-app-version-code "$MIN_APP_CODE" \
  --notes-url "$NOTES_URL" \
  --out "$OUT_DIR/update-index.json"

python3 scripts/sign-runtime-index.py --key "$KEY" \
  --in "$OUT_DIR/update-index.json" --out "$OUT_DIR/update-index.json.sig"
python3 scripts/sign-runtime-index.py --verify \
  --pubkey "$PUB" \
  --in "$OUT_DIR/update-index.json" --sig "$OUT_DIR/update-index.json.sig"

echo "update-index.json ready: channel=$CHANNEL version=$V ($C) tag=${TAG:-none}"
ls -lh "$OUT_DIR/update-index.json" "$OUT_DIR/update-index.json.sig"
