#!/usr/bin/env bash
# pack-mobile-layout.sh —— 组装 APK 内置的「用户态插件」tgz 资产（v1.5.0）。
#
# 与 pack-hardssh.sh 的区别：hardssh 是**内置能力**，脚本要解包、裁源码、组装
# node_modules；mobile-layout 是**用户态插件**，tgz 原样随 APK 分发，App 首启
# 用 `dsh plugin --profile web add <tgz>` 交给官方 CLI 安装，pnpm 记账完整，
# 之后由 WebUI 插件管理页接管（开关/升级/卸载）。
#
# 用法:
#   scripts/pack-mobile-layout.sh [输出目录]        # 默认 dist
#
# 环境变量:
#   MOBILE_LAYOUT_VERSION  npm dist-tag 或精确版本，默认 latest
#   MOBILE_LAYOUT_TGZ      **本地编译测试**：指定本地 tgz（跳过下载，仍会校验
#                          bundle 契约并生成 bundle.json）
#   NPM_REGISTRY           npm registry，默认 https://registry.npmjs.org
#   GH_PROXY               可选：前缀式代理（如 https://gh-proxy.org/）
#
# 产物:
#   <输出目录>/mobile-layout/xiaopb-dsh-mobile-layout-<ver>.tgz
#   <输出目录>/mobile-layout/bundle.json     {version, tgz, sha256}
#
# 契约（缺一即失败，避免打出「装了不挂载」的包）:
#   · tgz 内必须含 package/cordis.patch.yml（bundle 自挂载的唯一依据）
#   · package/package.json 的 dsh.bundle.patch 指向该 patch 文件
#   · package/package.json 的 version 与解析出的版本一致
set -euo pipefail

OUT="${1:-dist}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
WORK="$(mktemp -d)"
# Git Bash 的 mktemp 返回 C:\...\Temp/tmp.xxx（反斜杠 + 盘符），GNU tar 会把
# "C:" 当成远程主机、rm 的 safe-delete 也会拒绝。统一转成 POSIX 形式。
WORK="$(cd "$WORK" && pwd)"
trap 'rm -rf "$WORK"' EXIT

NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
ML_VERSION="${MOBILE_LAYOUT_VERSION:-latest}"
LOCAL_TGZ="${MOBILE_LAYOUT_TGZ:-}"
GH_PROXY="${GH_PROXY:-}"
PKG="@xiaopb/dsh-mobile-layout"
DEST="$OUT/mobile-layout"

echo "== pack-mobile-layout =="
echo "version spec: $ML_VERSION"
[ -n "$LOCAL_TGZ" ] && echo "local tgz:    $LOCAL_TGZ"

# Git Bash / Cygwin 下把 C:\... 归一为 POSIX 路径；Linux 上没有 cygpath。
# OUT 已经过 cd+pwd，天然是 POSIX；只有用户传入的 LOCAL_TGZ 可能是 Windows 写法。
if [ -n "$LOCAL_TGZ" ] && command -v cygpath >/dev/null 2>&1; then
  LOCAL_TGZ="$(cygpath -u "$LOCAL_TGZ")"
fi

rm -rf "$DEST"
mkdir -p "$DEST"

if [ -n "$LOCAL_TGZ" ]; then
  # ---- 本地编译测试路径：不联网，版本从包本身读 -------------------------
  test -f "$LOCAL_TGZ" || { echo "错误：MOBILE_LAYOUT_TGZ 不存在（$LOCAL_TGZ）" >&2; exit 1; }
  TGZ="$WORK/$(basename "$LOCAL_TGZ")"
  cp "$LOCAL_TGZ" "$TGZ"
else
  # ---- 1. 解析版本 + npm integrity -------------------------------------
  META="$(npm view "${PKG}@${ML_VERSION}" version dist.integrity dist.tarball --json --registry "$NPM_REGISTRY")"
  VERSION="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m.version||""))' "$META")"
  INTEGRITY="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m["dist.integrity"]||""))' "$META")"
  NPM_TARBALL="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m["dist.tarball"]||""))' "$META")"
  [ -n "$VERSION" ] || { echo "错误：无法解析 $PKG 版本（$ML_VERSION）" >&2; exit 1; }
  echo "resolved:     $PKG@$VERSION"
  echo "integrity:    ${INTEGRITY:-<未提供>}"

  # ---- 2. 下载（上游 Release 优先，回退 npm tarball），sha512 校验 -------
  verify_tgz() {
    [ -n "${INTEGRITY:-}" ] || { echo "warning: npm 未返回 integrity，跳过 sha512 校验" >&2; return 0; }
    node -e '
      const crypto=require("crypto"), fs=require("fs");
      const file=process.argv[1], want=(process.argv[2]||"").replace(/^sha512-/,"");
      const got=crypto.createHash("sha512").update(fs.readFileSync(file)).digest("base64");
      if(!want || got!==want){ console.error("integrity mismatch: got "+got+" want "+want); process.exit(1); }
    ' "$1" "$INTEGRITY"
  }

  RELEASE_URL="https://github.com/XiaoPb/dsh-mobile-layout/releases/download/v${VERSION}/xiaopb-dsh-mobile-layout-${VERSION}.tgz"
  proxied() { if [ -n "$GH_PROXY" ]; then printf '%s%s' "$GH_PROXY" "$1"; else printf '%s' "$1"; fi; }
  TGZ="$WORK/xiaopb-dsh-mobile-layout-${VERSION}.tgz"

  if curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$TGZ" "$(proxied "$RELEASE_URL")" 2>/dev/null \
      && verify_tgz "$TGZ"; then
    echo "source:       GitHub Release (verified)"
  else
    echo "warning: 上游 Release 不可用或校验失败，回退 npm tarball" >&2
    [ -n "$NPM_TARBALL" ] || { echo "错误：npm 未返回 dist.tarball" >&2; exit 1; }
    curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$TGZ" "$NPM_TARBALL" >/dev/null
    verify_tgz "$TGZ"
    echo "source:       npm tarball (verified)"
  fi
fi

# ---- 3. bundle 契约自检 ---------------------------------------------------
mkdir -p "$WORK/pkg"
tar -xzf "$TGZ" -C "$WORK/pkg"
PKG_DIR="$WORK/pkg/package"
test -f "$PKG_DIR/package.json" || { echo "错误：tgz 缺少 package/package.json" >&2; exit 1; }
test -f "$PKG_DIR/cordis.patch.yml" || {
  echo "错误：tgz 缺少 package/cordis.patch.yml —— 该包不是 bundle 形态，装进 profile 也不会挂载" >&2
  exit 1
}
test -f "$PKG_DIR/lib/index.js" || { echo "错误：tgz 缺少 lib/index.js" >&2; exit 1; }
test -f "$PKG_DIR/lib/client.js" || { echo "错误：tgz 缺少 lib/client.js" >&2; exit 1; }

PKG_VERSION="$(node -e 'const p=require(process.argv[1]); process.stdout.write(String(p.version||""))' "$PKG_DIR/package.json")"
PATCH_DECL="$(node -e 'const p=require(process.argv[1]); const b=p.dsh&&p.dsh.bundle; process.stdout.write(String(b&&b.patch||""))' "$PKG_DIR/package.json")"
[ -n "$PKG_VERSION" ] || { echo "错误：package.json 缺少 version" >&2; exit 1; }
[ "$PATCH_DECL" = "./cordis.patch.yml" ] || {
  echo "错误：dsh.bundle.patch 应为 ./cordis.patch.yml（实际：${PATCH_DECL:-<缺失>}）" >&2
  exit 1
}
if [ -n "${VERSION:-}" ] && [ "$PKG_VERSION" != "$VERSION" ]; then
  echo "错误：包内版本 $PKG_VERSION 与解析版本 $VERSION 不一致" >&2
  exit 1
fi

# ---- 4. 落盘：tgz 原样 + 元数据 ------------------------------------------
TGZ_NAME="xiaopb-dsh-mobile-layout-${PKG_VERSION}.tgz"
cp "$TGZ" "$DEST/$TGZ_NAME"
SHA256="$(node -e '
  const crypto=require("crypto"), fs=require("fs");
  process.stdout.write(crypto.createHash("sha256").update(fs.readFileSync(process.argv[1])).digest("hex"));
' "$DEST/$TGZ_NAME")"

node -e '
  const fs=require("fs");
  const [out, pkg, version, tgz, sha] = process.argv.slice(1);
  fs.writeFileSync(out, JSON.stringify({ pkg, version, tgz, sha256: sha }, null, 2) + "\n");
' "$DEST/bundle.json" "$PKG" "$PKG_VERSION" "$TGZ_NAME" "$SHA256"

echo "packed:       $PKG@$PKG_VERSION ($(du -h "$DEST/$TGZ_NAME" | cut -f1))"
echo "sha256:       $SHA256"
echo "metadata:     $DEST/bundle.json"
