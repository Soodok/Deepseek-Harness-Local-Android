#!/usr/bin/env bash
# pack-hardssh-plugin.sh —— 把上游源码构建产物打包成 runtime.zip 可部署单元。
#
# 用法: ./scripts/pack-hardssh-plugin.sh <插件构建目录> <输出目录>
#   <插件构建目录>  上游仓库的 packages/dsh-hardssh（已 pnpm build，含 lib/）
#   <输出目录>      产出目录（会整体重建）
#
# 产出（直接对应 runtime 的 lib/node_modules/@tiphareth/dsh-hardssh）：
#   package.json / cordis.patch.yml / LICENSE / lib/*.js / node_modules/{ssh2,ws,...}
#
# 为什么 node_modules 要随包带走：dsh 的 profile 只解析「用户安装」的依赖；
# 内置包位于 engine/lib/node_modules，Node 从包的真实路径向上查找时会命中
# 同目录的 @deepseek-ai/*（引擎自带），所以这里只补插件自己的第三方依赖。
#
# 源码不在本仓库：CI 的 build-hardssh-plugin job 从 GitHub 拉取固定 ref 后执行
# pnpm install + pnpm build，再把产物交给本脚本。
set -euo pipefail

SRC="${1:?用法: $0 <插件构建目录> <输出目录>}"
OUT="${2:?用法: $0 <插件构建目录> <输出目录>}"

# 本地在 Git Bash / Cygwin 下用 Windows 路径调用时，先归一成 POSIX 路径；
# 否则 find/cp 无法遍历 "C:\..." 形式的目标（CI 是 Linux，走不到这里）。
if command -v cygpath >/dev/null 2>&1; then
  SRC="$(cygpath -u "$SRC")"
  OUT="$(cygpath -u "$OUT")"
fi

PKG_NAME="$(node -e 'const fs=require("fs");process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8")).name)' "$SRC/package.json")"
PKG_VERSION="$(node -e 'const fs=require("fs");process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8")).version)' "$SRC/package.json")"
SSH2_RANGE="$(node -e 'const fs=require("fs");process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8")).dependencies?.ssh2 ?? "")' "$SRC/package.json")"
WS_RANGE="$(node -e 'const fs=require("fs");process.stdout.write(JSON.parse(fs.readFileSync(process.argv[1],"utf8")).dependencies?.ws ?? "")' "$SRC/package.json")"

if [ "$PKG_NAME" != "@tiphareth/dsh-hardssh" ]; then
  echo "错误：构建目录不是 @tiphareth/dsh-hardssh（实际 name=$PKG_NAME）" >&2
  exit 1
fi
if [ -z "$SSH2_RANGE" ] || [ -z "$WS_RANGE" ]; then
  echo "错误：package.json 缺少 ssh2 / ws 依赖声明，无法组装运行时依赖" >&2
  exit 1
fi
for f in lib/index.js lib/fs.js lib/subprocess.js lib/client.js cordis.patch.yml; do
  test -f "$SRC/$f" || { echo "错误：构建产物缺少 $f（先执行 pnpm --filter @tiphareth/dsh-hardssh build）" >&2; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT"

# 运行时只需要 package.json + patch + lib；src/tests/README 不进 APK。
cp -a "$SRC/package.json" "$SRC/cordis.patch.yml" "$SRC/LICENSE" "$OUT/"
cp -a "$SRC/lib" "$OUT/lib"
# 与 collect-termux-runtime.sh 的全局裁剪保持一致：sourcemap / 类型声明 / 构建缓存
# 不参与运行，删掉可省 ~1.7MB。
# 用 bash globstar 而不是 find：非登录 Git Bash 的 PATH 里 Windows find.exe 排在
# /usr/bin 前面，它的参数语义不同（-delete 会被静默忽略），本地打包会漏删。
shopt -s globstar nullglob
rm -f "$OUT"/lib/**/*.map "$OUT"/lib/**/*.d.ts "$OUT"/lib/**/*.tsbuildinfo
shopt -u globstar nullglob

# 第三方依赖：独立临时工程安装，避免 npm 读到上游 package.json 的 `catalog:`
# 开发依赖（npm 不支持 pnpm 的 catalog 协议）。
DEPS_DIR="$(mktemp -d)"
trap 'rm -rf "$DEPS_DIR"' EXIT
cat > "$DEPS_DIR/package.json" <<EOF
{
  "name": "dsh-hardssh-runtime-deps",
  "private": true,
  "dependencies": {
    "ssh2": "$SSH2_RANGE",
    "ws": "$WS_RANGE"
  }
}
EOF
(
  cd "$DEPS_DIR"
  # --ignore-scripts: 不跑 install.js（ssh2 的预编译原生加速是可选的）
  # --omit=optional: 不装 cpu-features / nan —— 它们是宿主架构的 .node，
  #                  Android 上必然加载失败；ssh2 有纯 JS 回退（try/catch）。
  npm install --omit=dev --omit=optional --ignore-scripts \
    --no-audit --no-fund --loglevel=error
)
cp -a "$DEPS_DIR/node_modules" "$OUT/node_modules"
rm -f "$OUT/node_modules/.package-lock.json"
rm -rf "$OUT/node_modules/.bin"

test -f "$OUT/node_modules/ssh2/package.json" || { echo "错误：ssh2 依赖缺失" >&2; exit 1; }
test -f "$OUT/node_modules/ws/package.json" || { echo "错误：ws 依赖缺失" >&2; exit 1; }

# 绝不允许宿主架构的 Node 原生扩展混进 APK：CI 跑在 Linux x86_64，装进来的
# .node 会是 ELF x86-64，arm64 Android 上 dlopen 必失败。ssh2 的
# cpu-features / sshcrypto.node 都是可选加速件，缺失时自动回退（见 docs/SSH.md）。
shopt -s globstar nullglob
NODE_ADDONS=("$OUT"/**/*.node)
shopt -u globstar nullglob
if [ ${#NODE_ADDONS[@]} -gt 0 ]; then
  echo "错误：插件包里混入了原生扩展（宿主架构，Android 无法加载）：" >&2
  printf '  %s\n' "${NODE_ADDONS[@]}" >&2
  exit 1
fi

# 供 App/CI 诊断的构建元数据（不参与运行）。
printf '{"name":"%s","version":"%s","source":"github:tiphareth0/dsh-hardssh","ref":"%s"}\n' \
  "$PKG_NAME" "$PKG_VERSION" "${HARDSSH_PLUGIN_REF:-unknown}" > "$OUT/.runtime-bundle.json"

echo "打包完成：$PKG_NAME@$PKG_VERSION -> $OUT ($(du -sh "$OUT" | cut -f1))"
echo "  lib:        $(du -sh "$OUT/lib" | cut -f1)"
echo "  node_modules: $(du -sh "$OUT/node_modules" | cut -f1) (ssh2 $SSH2_RANGE, ws $WS_RANGE)"
