#!/usr/bin/env bash
# inject-hardssh-plugin.sh —— 把 pack-hardssh-plugin.sh 的产物装进 runtime 根目录。
#
# 用法: ./scripts/inject-hardssh-plugin.sh <runtime 根目录> <插件目录>
#
# 产物落点固定为 <runtime>/lib/node_modules/@tiphareth/dsh-hardssh：
# App 侧的 EngineConfig.sshPluginDir() 与 CI 闭包校验都按这个路径查找。
#
# 单独拆成脚本而不是内联在 collect-termux-runtime.sh 里，是为了能在不跑
# apt-get / 不下载 Termux 包的情况下单独验证注入逻辑（本地 + CI 都可复用）。
set -euo pipefail

ROOT="${1:?用法: $0 <runtime 根目录> <插件目录>}"
PLUGIN="${2:?用法: $0 <runtime 根目录> <插件目录>}"

# Git Bash / Cygwin 下把 C:\... 归一为 POSIX 路径；Linux 上没有 cygpath。
if command -v cygpath >/dev/null 2>&1; then
  ROOT="$(cygpath -u "$ROOT")"
  PLUGIN="$(cygpath -u "$PLUGIN")"
fi

test -f "$PLUGIN/package.json" || {
  echo "错误：插件目录缺少 package.json（插件目录=$PLUGIN）" >&2
  exit 1
}
PKG_NAME="$(sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$PLUGIN/package.json" | head -n1)"
if [ "$PKG_NAME" != "@tiphareth/dsh-hardssh" ]; then
  echo "错误：插件目录不是 @tiphareth/dsh-hardssh（实际 name=${PKG_NAME:-<读取失败>}）" >&2
  exit 1
fi

for f in cordis.patch.yml lib/index.js lib/fs.js lib/subprocess.js lib/client.js \
         node_modules/ssh2/package.json node_modules/ws/package.json; do
  test -e "$PLUGIN/$f" || {
    echo "错误：内置 SSH 插件缺少 $f（插件目录=$PLUGIN）" >&2
    exit 1
  }
done

DEST="$ROOT/lib/node_modules/@tiphareth/dsh-hardssh"
# 与 runtime 同生命周期：整目录替换，避免旧版本残留文件。
rm -rf "$DEST"
mkdir -p "$(dirname "$DEST")"
cp -a "$PLUGIN" "$DEST"

# 与全局裁剪一致：sourcemap / 类型声明不参与运行。
# 用 bash globstar 而不是 find：非登录 Git Bash 的 PATH 里 Windows find.exe 排在
# /usr/bin 前面，-delete 会被静默忽略。
shopt -s globstar nullglob
rm -f "$DEST"/lib/**/*.map "$DEST"/lib/**/*.d.ts
shopt -u globstar nullglob

# Android 侧按实体文件部署；打包阶段产生的符号链接（如 npm .bin）不允许进包。
FIND=/usr/bin/find
[ -x "$FIND" ] || FIND=find
if "$FIND" "$DEST" -type l -print -quit | grep -q .; then
  echo "错误：内置 SSH 插件含符号链接（打包脚本应已解引用）" >&2
  exit 1
fi

VERSION="$(sed -n 's/.*"version"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$DEST/package.json" | head -n1)"
echo "内置 SSH 插件已集成：@tiphareth/dsh-hardssh@${VERSION:-unknown} ($(du -sh "$DEST" | cut -f1))"
