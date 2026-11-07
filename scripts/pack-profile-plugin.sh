#!/usr/bin/env bash
# pack-profile-plugin.sh —— 组装 APK 内置「用户态插件」tgz 资产（v1.5.0+）。
#
# 内置用户态插件指：以 profile bundle 形式随 APK 分发、首启由 App 交给官方
# `dsh plugin --profile web add <tgz>` 安装、之后由 WebUI 插件管理页接管的插件。
# 它们与「内置能力」（hardssh 那种解包重组 node_modules 的）不同：tgz **原样**
# 分发，不改一个字节。
#
# 用法:
#   scripts/pack-profile-plugin.sh <pkg> <asset-dir-name> [输出目录]
#
# 例:
#   scripts/pack-profile-plugin.sh @xiaopb/dsh-mobile-layout        mobile-layout        dist
#   scripts/pack-profile-plugin.sh @xiaopb/dsh-solution-explorer    solution-explorer    dist
#
# 环境变量:
#   <PLUGIN>_VERSION   npm dist-tag 或精确版本；默认 latest。
#                      变量名由包名大写、非字母数字转下划线得到，
#                      如 @xiaopb/dsh-solution-explorer → XIAOPB_DSH_SOLUTION_EXPLORER_VERSION
#                      另可用通用变量 PLUGIN_VERSION 覆盖任意包。
#   <PLUGIN>_TGZ       本地编译测试：指定本地 tgz（跳过下载，仍校验契约）
#   NPM_REGISTRY       npm registry，默认 https://registry.npmjs.org
#   GH_PROXY           可选：前缀式代理（如 https://gh-proxy.org/）
#
# 产物:
#   <输出目录>/<asset-dir-name>/<pkg>-<ver>.tgz
#   <输出目录>/<asset-dir-name>/bundle.json   {pkg, version, tgz, sha256}
#   （tgz 文件名里的 "@scope/" 会被替换成 "scope-"，与既有 mobile-layout 命名一致）
#
# 契约（缺一即失败，避免打出「装了不挂载」的包）:
#   · tgz 内必须含 package/cordis.patch.yml（bundle 自挂载的唯一依据）
#   · package/package.json 的 dsh.bundle.patch 指向该 patch 文件
#   · package/package.json 的 version 与解析出的版本一致
#   · tgz 内不得含 package.json 的 dependencies 中未随包分发的原生模块
#     （这里只警告：纯 JS 依赖会在安装时由 pnpm 从 registry 拉，属正常）
set -euo pipefail

PKG="${1:-}"
ASSET_DIR="${2:-}"
OUT="${3:-dist}"
if [ -z "$PKG" ] || [ -z "$ASSET_DIR" ]; then
  echo "用法: $0 <pkg> <asset-dir-name> [输出目录]" >&2
  exit 2
fi

OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
WORK="$(mktemp -d)"
# Git Bash 的 mktemp 返回 C:\...\Temp/tmp.xxx（反斜杠 + 盘符），GNU tar 会把
# "C:" 当成远程主机、rm 的 safe-delete 也会拒绝。统一转成 POSIX 形式。
WORK="$(cd "$WORK" && pwd)"
trap 'rm -rf "$WORK"' EXIT

NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
GH_PROXY="${GH_PROXY:-}"

# 包名 → 环境变量前缀：@xiaopb/dsh-solution-explorer → XIAOPB_DSH_SOLUTION_EXPLORER
ENV_PREFIX="$(printf '%s' "$PKG" | sed 's/^@//' | tr 'a-z-' 'A-Z_' | tr -cd 'A-Z0-9_')"
VERSION_VAR="${ENV_PREFIX}_VERSION"
TGZ_VAR="${ENV_PREFIX}_TGZ"
VERSION_SPEC="${PLUGIN_VERSION:-${!VERSION_VAR:-latest}}"
LOCAL_TGZ="${!TGZ_VAR:-}"

# tgz 文件名：@xiaopb/dsh-solution-explorer → xiaopb-dsh-solution-explorer
TGZ_STEM="$(printf '%s' "$PKG" | sed 's/^@//' | tr '/' '-')"

DEST="$OUT/$ASSET_DIR"

echo "== pack-profile-plugin =="
echo "pkg:          $PKG"
echo "version spec: $VERSION_SPEC"
echo "asset dir:    $ASSET_DIR"
[ -n "$LOCAL_TGZ" ] && echo "local tgz:    $LOCAL_TGZ"

if [ -n "$LOCAL_TGZ" ] && command -v cygpath >/dev/null 2>&1; then
  LOCAL_TGZ="$(cygpath -u "$LOCAL_TGZ")"
fi

rm -rf "$DEST"
mkdir -p "$DEST"

if [ -n "$LOCAL_TGZ" ]; then
  # ---- 本地编译测试路径：不联网，版本从包本身读 -------------------------
  test -f "$LOCAL_TGZ" || { echo "错误：$TGZ_VAR 不存在（$LOCAL_TGZ）" >&2; exit 1; }
  TGZ="$WORK/$(basename "$LOCAL_TGZ")"
  cp "$LOCAL_TGZ" "$TGZ"
else
  # ---- 1. 解析版本 + npm integrity -------------------------------------
  META="$(npm view "${PKG}@${VERSION_SPEC}" version dist.integrity dist.tarball --json --registry "$NPM_REGISTRY")"
  VERSION="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m.version||""))' "$META")"
  INTEGRITY="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m["dist.integrity"]||""))' "$META")"
  NPM_TARBALL="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m["dist.tarball"]||""))' "$META")"
  [ -n "$VERSION" ] || { echo "错误：无法解析 $PKG 版本（$VERSION_SPEC）" >&2; exit 1; }
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

  # 上游 Release 命名约定：<owner>/<repo>/releases/download/v<ver>/<stem>-<ver>.tgz
  # owner/repo 无从推断，故只在包名符合 @<owner>/dsh-<repo> 形态时尝试。
  OWNER="$(printf '%s' "$PKG" | sed -n 's#^@\([^/]*\)/.*#\1#p')"
  SHORT="$(printf '%s' "$PKG" | sed -n 's#^@[^/]*/\(.*\)#\1#p')"
  RELEASE_URL="https://github.com/${OWNER}/${SHORT}/releases/download/v${VERSION}/${TGZ_STEM}-${VERSION}.tgz"
  proxied() { if [ -n "$GH_PROXY" ]; then printf '%s%s' "$GH_PROXY" "$1"; else printf '%s' "$1"; fi; }
  TGZ="$WORK/${TGZ_STEM}-${VERSION}.tgz"

  if [ -n "$OWNER" ] && [ -n "$SHORT" ] \
      && curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$TGZ" "$(proxied "$RELEASE_URL")" 2>/dev/null \
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

PKG_VERSION="$(node -e 'const p=require(process.argv[1]); process.stdout.write(String(p.version||""))' "$PKG_DIR/package.json")"
PKG_NAME="$(node -e 'const p=require(process.argv[1]); process.stdout.write(String(p.name||""))' "$PKG_DIR/package.json")"
PATCH_DECL="$(node -e 'const p=require(process.argv[1]); const b=p.dsh&&p.dsh.bundle; process.stdout.write(String(b&&b.patch||""))' "$PKG_DIR/package.json")"
[ -n "$PKG_VERSION" ] || { echo "错误：package.json 缺少 version" >&2; exit 1; }
[ "$PKG_NAME" = "$PKG" ] || {
  echo "错误：包内 name（$PKG_NAME）与请求的包名（$PKG）不一致" >&2
  exit 1
}
[ "$PATCH_DECL" = "./cordis.patch.yml" ] || {
  echo "错误：dsh.bundle.patch 应为 ./cordis.patch.yml（实际：${PATCH_DECL:-<缺失>}）" >&2
  exit 1
}
if [ -n "${VERSION:-}" ] && [ "$PKG_VERSION" != "$VERSION" ]; then
  echo "错误：包内版本 $PKG_VERSION 与解析版本 $VERSION 不一致" >&2
  exit 1
fi

# 纯 JS 依赖会在安装时由 pnpm 从 registry 拉走，属正常；这里只是让它可见，
# 免得以后有人以为「装完不需要网络」。
DEPS="$(node -e 'const p=require(process.argv[1]); process.stdout.write(Object.keys(p.dependencies||{}).join(" ")||"")' "$PKG_DIR/package.json")"
if [ -n "$DEPS" ]; then
  echo "npm deps:     $DEPS（安装时由 pnpm 解析，需联网或已缓存）"
fi

# ---- 4. 落盘：tgz 原样 + 元数据 ------------------------------------------
TGZ_NAME="${TGZ_STEM}-${PKG_VERSION}.tgz"
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
