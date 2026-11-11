#!/usr/bin/env bash
# 查上游 DSH 引擎有没有新版本（只读，不构建、不发布）
#
# 为什么需要它：本仓的 runtime.zip 由 CI 从 **npm** 的 @deepseek-ai/dsh 安装后打包，
# 版本钉在多处（见下）。而桌面版 DeepSeek Harness 的更新源
# （download.deepseek.com 的 nightly feed）常常**先于 npm** 放出新版本 ——
# 于是会出现「上游明明出了 rc.2，但本仓升不上去」的窗口期。
# 这个脚本把三处版本一次列清楚，避免靠猜。
#
# 用法：bash scripts/check-upstream-dsh.sh
# 退出码：0 = 已是最新；1 = 上游（桌面 feed）更新但 npm 还没有；2 = npm 有更新可升
set -uo pipefail

say() { printf '%s\n' "$*"; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WF="$ROOT/.github/workflows/runtime-build.yml"
FEED="https://download.deepseek.com/dsh-desk/feeds/win-x64/nightly.yml"

PIN="$(grep -oE 'DEFAULT_DSH_VERSION: *"[^"]+"' "$WF" | grep -oE '[0-9][^"]*')"

say "== 本仓所有版本钉（升级时 5 处必须一起改）=="
grep -rn --include=*.sh --include=*.yml -E '0\.[0-9]+\.[0-9]+-(rc|alpha|beta)' \
  "$ROOT/scripts" "$ROOT/.github/workflows" 2>/dev/null \
  | grep -iE 'DSH_VERSION|dsh_version|default: *"0\.2\.' \
  | sed "s|$ROOT/||" | sed 's/^/  /'
say "本仓 pin: ${PIN:-<未找到>}"

NPM_JSON="$(curl -s -m 20 "https://registry.npmjs.org/@deepseek-ai%2Fdsh")"
NPM_LATEST="$(printf '%s' "$NPM_JSON" | python -c "import sys,json;print(json.load(sys.stdin).get('dist-tags',{}).get('latest',''))" 2>/dev/null)"
NPM_NEXT="$(printf '%s' "$NPM_JSON" | python -c "import sys,json;print(json.load(sys.stdin).get('dist-tags',{}).get('next',''))" 2>/dev/null)"
say "npm dist-tags: latest=${NPM_LATEST:-<取不到>} next=${NPM_NEXT:-<取不到>}"

FEED_VER="$(curl -s -m 20 "$FEED" | grep -m1 '^version:' | awk '{print $2}')"
say "桌面版 nightly feed: ${FEED_VER:-<取不到>}"

check_npm() {
  [ -z "${1:-}" ] && return 1
  [ "$(curl -s -o /dev/null -w '%{http_code}' -m 20 "https://registry.npmjs.org/@deepseek-ai%2Fdsh/$1")" = "200" ]
}

say ""
if [ -n "${FEED_VER:-}" ] && [ "$FEED_VER" != "${PIN:-}" ]; then
  if check_npm "$FEED_VER"; then
    say "→ npm 已有 $FEED_VER（本仓 pin ${PIN:-?} 落后），可以升："
    say "  1) 改上面 5 处版本钉  2) 跑 upstream-staging（dsh_version=$FEED_VER）→ runtime-build 自动跟"
    exit 2
  fi
  say "→ 上游桌面 feed 已到 $FEED_VER，但 **npm 还没发布**（registry 对该版本返回 404）。"
  say "  本仓 runtime.zip 只能从 npm 安装，所以现在升不上去。npm 上架后："
  say "  1) 改上面 5 处版本钉  2) 跑 upstream-staging（dsh_version=$FEED_VER）→ runtime-build 自动跟"
  say "  3) 注意 collect-termux-runtime.sh 的 9 个锚点文件字节可能变化，需重新核对 patch markers"
  exit 1
fi
say "→ 已是最新（pin == 桌面 feed == ${PIN:-?}）。"
exit 0
