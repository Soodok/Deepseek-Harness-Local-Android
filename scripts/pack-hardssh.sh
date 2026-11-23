#!/usr/bin/env bash
# pack-hardssh.sh —— 从上游 npm/Release 取 @xiaopb/dsh-hardssh，组装 APK 内置副本。
#
# 用法:
#   scripts/pack-hardssh.sh [输出目录]      # 默认 dist
#
# 环境变量:
#   HARDSSH_VERSION   npm dist-tag 或精确版本，默认 latest
#   NPM_REGISTRY      npm registry，默认 https://registry.npmjs.org
#   GH_PROXY          可选：前缀式代理（如 https://gh-proxy.org/）
#   HARDSSH_TGZ_URL   可选：直接指定 tgz 下载地址（覆盖 GitHub Release URL）
#
# 产物:
#   <输出目录>/hardssh/           APK 内置目录（含 node_modules/{ssh2,ws}）
#   <输出目录>/hardssh.json       构建元数据（version/integrity/tgz URL/bundle sha256）
#
# 设计约束:
#   · 不发布 tgz/zip；本脚本只服务 APK 内置副本。
#   · 包体优先从上游 GitHub Release 取（与 npm 包逐字节一致），缺失时回退 npm pack；
#     两条路径都必须通过 npm dist.integrity 校验。
#   · 运行期只需要 ssh2/ws（@xterm/* 已打进 lib/client.js），不安装其余依赖。
set -euo pipefail

OUT="${1:-dist}"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

NPM_REGISTRY="${NPM_REGISTRY:-https://registry.npmjs.org}"
HARDSSH_VERSION="${HARDSSH_VERSION:-latest}"
GH_PROXY="${GH_PROXY:-}"
FIND=/usr/bin/find
[ -x "$FIND" ] || FIND=find

PKG="@xiaopb/dsh-hardssh"

echo "== pack-hardssh =="
echo "version spec: $HARDSSH_VERSION"
echo "registry:     $NPM_REGISTRY"

# ---- 1. 解析版本 + npm integrity -------------------------------------------
META="$(npm view "${PKG}@${HARDSSH_VERSION}" version dist.integrity dist.tarball --json --registry "$NPM_REGISTRY")"
VERSION="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m.version||""))' "$META")"
INTEGRITY="$(node -e 'const m=JSON.parse(process.argv[1]); process.stdout.write(String(m["dist.integrity"]||""))' "$META")"
[ -n "$VERSION" ] || { echo "错误：无法解析 $PKG 版本（$HARDSSH_VERSION）" >&2; exit 1; }
[ -n "$INTEGRITY" ] || { echo "错误：npm 未返回 dist.integrity" >&2; exit 1; }
echo "resolved:     $PKG@$VERSION"
echo "integrity:    $INTEGRITY"

# ---- 2. 下载上游 Release tgz，校验 sha512 ---------------------------------
TGZ="$WORK/xiaopb-dsh-hardssh-${VERSION}.tgz"
TGZ_URL="${HARDSSH_TGZ_URL:-https://github.com/XiaoPb/dsh-hardssh/releases/download/v${VERSION}/xiaopb-dsh-hardssh-${VERSION}.tgz}"

verify_tgz() {
  node -e '
    const crypto=require("crypto"), fs=require("fs");
    const file=process.argv[1], want=(process.argv[2]||"").replace(/^sha512-/,"");
    const got=crypto.createHash("sha512").update(fs.readFileSync(file)).digest("base64");
    if(!want || got!==want){ console.error("integrity mismatch: got "+got+" want "+want); process.exit(1); }
  ' "$1" "$INTEGRITY"
}

download() {
  local url="$1" dest="$2"
  curl -fL --retry 3 --retry-delay 2 --connect-timeout 20 -o "$dest" "$url" >/dev/null 2>&1
}

proxied() {
  if [ -n "$GH_PROXY" ]; then printf '%s%s' "$GH_PROXY" "$1"; else printf '%s' "$1"; fi
}

echo "download:     $(proxied "$TGZ_URL")"
if download "$(proxied "$TGZ_URL")" "$TGZ" && verify_tgz "$TGZ"; then
  echo "source:       GitHub Release (verified)"
else
  echo "warning: 上游 Release 不可用或校验失败，回退 npm pack" >&2
  ( cd "$WORK" && npm pack "${PKG}@${VERSION}" --registry "$NPM_REGISTRY" >/dev/null )
  TGZ="$WORK/xiaopb-dsh-hardssh-${VERSION}.tgz"
  [ -f "$TGZ" ] || { echo "错误：npm pack 未产出 tgz" >&2; exit 1; }
  verify_tgz "$TGZ"
  echo "source:       npm pack (verified)"
fi

# ---- 3. 解包 + 裁剪 --------------------------------------------------------
mkdir -p "$WORK/pkg"
tar -xzf "$TGZ" -C "$WORK/pkg"
PKG_DIR="$WORK/pkg/package"
test -f "$PKG_DIR/package.json" || { echo "错误：tgz 缺少 package/package.json" >&2; exit 1; }
test -f "$PKG_DIR/cordis.patch.yml" || { echo "错误：tgz 缺少 cordis.patch.yml" >&2; exit 1; }
test -f "$PKG_DIR/lib/index.js" || { echo "错误：tgz 缺少 lib/index.js" >&2; exit 1; }

# 运行时用不到：TS 源码、类型声明、sourcemap、文档。
rm -rf "$PKG_DIR/src" "$PKG_DIR/lib/types" "$PKG_DIR/scripts"
"$FIND" "$PKG_DIR" -type f \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' \) -delete
rm -f "$PKG_DIR/README.md" "$PKG_DIR/SKILLS.md"

# ---- 3b. Windows 远端路径补丁 ---------------------------------------------
# 0.2.13 的远端路径层是纯 POSIX 假定：绑定 C:\Users\me 会在任何 SFTP 动作之前就
# 被 normalizeRemoteRoot 拒掉（Win32 OpenSSH 的 SFTP 只认 /C:/Users/me）。
# 补丁做三件事：① C:\x / C:/x / /c:/x 收敛成 /C:/x；② 根包含比较在 Windows 根下
# 大小写不敏感；③ 命令层对 Windows 根明示不支持（而非让 cmd.exe 报语法错）。
# 锚点失配会 exit 1 —— 上游改版必须重新 review 锚点，不能静默跳过。
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python "$HERE/patch-hardssh-windows-paths.py" --root "$PKG_DIR"
python "$HERE/patch-hardssh-windows-paths.py" --root "$PKG_DIR" --check

# ---- 4. 只安装运行期依赖 ssh2 / ws ----------------------------------------
SSH2_RANGE="$(node -e 'const p=require(process.argv[1]); process.stdout.write(p.dependencies?.ssh2||"")' "$PKG_DIR/package.json")"
WS_RANGE="$(node -e 'const p=require(process.argv[1]); process.stdout.write(p.dependencies?.ws||"")' "$PKG_DIR/package.json")"
[ -n "$SSH2_RANGE" ] && [ -n "$WS_RANGE" ] || { echo "错误：package.json 缺少 ssh2/ws 依赖" >&2; exit 1; }

DEPS="$WORK/deps"
mkdir -p "$DEPS"
cat > "$DEPS/package.json" <<EOF
{
  "name": "dsh-hardssh-runtime-deps",
  "private": true,
  "dependencies": { "ssh2": "$SSH2_RANGE", "ws": "$WS_RANGE" }
}
EOF
(
  cd "$DEPS"
  # --omit=optional: 跳过 cpu-features 等宿主架构 .node；ssh2 有纯 JS 回退。
  npm install --omit=dev --omit=optional --ignore-scripts \
    --no-audit --no-fund --loglevel=error --registry "$NPM_REGISTRY"
)
rm -rf "$PKG_DIR/node_modules"
cp -a "$DEPS/node_modules" "$PKG_DIR/node_modules"
rm -rf "$PKG_DIR/node_modules/.bin" "$PKG_DIR/node_modules/.package-lock.json"
# 第三方依赖里的类型声明/sourcemap 同样不参与运行。
"$FIND" "$PKG_DIR/node_modules" -type f \( -name '*.map' -o -name '*.d.ts' -o -name '*.d.mts' -o -name '*.d.cts' \) -delete

# ---- 5. 元数据 + 安全校验 --------------------------------------------------
test -f "$PKG_DIR/node_modules/ssh2/package.json" || { echo "错误：ssh2 依赖缺失" >&2; exit 1; }
test -f "$PKG_DIR/node_modules/ws/package.json" || { echo "错误：ws 依赖缺失" >&2; exit 1; }

NODE_ADDONS="$("$FIND" "$PKG_DIR" -type f -name '*.node' -print)"
[ -z "$NODE_ADDONS" ] || { echo "错误：混入原生扩展（Android 无法加载）：" >&2; printf '  %s\n' $NODE_ADDONS >&2; exit 1; }
LINKS="$("$FIND" "$PKG_DIR" -type l -print)"
[ -z "$LINKS" ] || { echo "错误：混入符号链接：" >&2; printf '  %s\n' $LINKS >&2; exit 1; }

# bundle sha256：按相对路径排序后对 "path\0content" 做流式摘要（跨平台、可复现）。
# 在写入 hardssh-bundle.json 之前算，避免元数据自引用。
BUNDLE_SHA="$(node -e '
  const fs=require("fs"), path=require("path"), crypto=require("crypto");
  const root=process.argv[1];
  const files=[];
  (function walk(d){ for(const e of fs.readdirSync(d,{withFileTypes:true})){
    const p=path.join(d,e.name); const r=path.relative(root,p).split(path.sep).join("/");
    if(e.isDirectory()) walk(p); else files.push(r);
  }})(root);
  files.sort();
  const h=crypto.createHash("sha256");
  for(const r of files){ h.update(r); h.update("\0"); h.update(fs.readFileSync(path.join(root,r))); h.update("\0"); }
  process.stdout.write(h.digest("hex"));
' "$PKG_DIR")"

cat > "$PKG_DIR/hardssh-bundle.json" <<EOF
{"name":"@xiaopb/dsh-hardssh","version":"$VERSION","source":"github:XiaoPb/dsh-hardssh","ref":"v$VERSION","npmIntegrity":"$INTEGRITY","tgzUrl":"$TGZ_URL","bundleSha256":"$BUNDLE_SHA"}
EOF

rm -rf "$OUT/hardssh"
cp -a "$PKG_DIR" "$OUT/hardssh"

cat > "$OUT/hardssh.json" <<EOF
{
  "name": "@xiaopb/dsh-hardssh",
  "version": "$VERSION",
  "source": "github:XiaoPb/dsh-hardssh",
  "ref": "v$VERSION",
  "npmIntegrity": "$INTEGRITY",
  "tgzUrl": "$TGZ_URL",
  "bundleSha256": "$BUNDLE_SHA",
  "files": $("$FIND" "$OUT/hardssh" -type f | wc -l | tr -d ' ')
}
EOF

echo "bundle:       $OUT/hardssh ($(du -sh "$OUT/hardssh" | cut -f1))"
echo "metadata:     $OUT/hardssh.json"
echo "bundle sha:   $BUNDLE_SHA"
