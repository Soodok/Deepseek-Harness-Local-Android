#!/usr/bin/env bash
# fetch-runtime.sh —— 获取预构建 runtime.zip（android-build 的 fetch-runtime job 用）。
#
# 为什么不再现场编译：runtime 内容只取决于 Termux 仓库包版本 + 本仓库 runtime
# 脚本。此前每次 push/PR/定时都 apt 收集 + 打补丁 + 打包 127MB×2，耗时 5-10 分钟，
# 且 Termux 仓库浮动导致同一 commit 两次构建 sha 可能不同（不可复现）。改造后按优先级
# 取预构建产物：
#
#   ① 滚动 prerelease tag "runtime"（.github/workflows/runtime-build.yml 维护）
#      下载 runtime-<abi>.zip + runtime-meta-<abi>.json，校验 sha256 一致；
#   ② releases/latest（正式 tag 发布自带的 runtime-<abi>.zip；gh 默认排除
#      prerelease/draft，所以不会误取 preview/edge/runtime 这些滚动包）
#      meta 从 zip 内 BUILD.json + 文件哈希重建（scripts/make-runtime-meta.py）；
#   ③ 本地兜底构建（scripts/build-runtime.sh）—— 前两级全缺失时（v1.4.27 之前的
#      历史版本、新 fork bootstrap）保证 CI 不因缺少 runtime 直接红。
#
# 新鲜度（v1.4.36 补）：sha256 校验只能保证「zip 与 meta 同源」，不能保证「这个包
# 是按当前脚本构建的」。若改了 patch-*.py 而 prerelease 还是旧产物，① 会静默命中，
# 直到 APK 打包阶段才被 app/build.gradle.kts 的 PTC 门禁拦下，报错文案还是
# "lacks the PTC patch"（看着像补丁丢了，其实是取到了旧包）。故：
#   --expect-sha <64hex>  比对包内 runtime-inputs.json 的 scriptsSha256
#   --strict              不匹配/缺失即硬失败，禁止降级到 ②③（tag 正式构建用）
# 严格档的意义：同一个 tag 在任何时间构建都必须内嵌同一个 runtime，否则正式发布
# 不可复现 —— 那正是本次改造要消除的问题，不能在滚动通道里复发。
#
# 任一级命中并通过校验即返回；三级全失败才算失败。输出会写进 Step Summary。
# 取货统一走 gh api（api.github.com），不使用 gh release download：后者在本仓库的
# android-build runner 上对 prerelease 标签整体失败，而 decide 用 gh api 能成功。
# 需要：Linux + gh CLI（GH_TOKEN）+ python3 + unzip/sha256sum。
#
# 用法: fetch-runtime.sh --abi arm64-v8a|x86_64 --out runtime.zip --meta runtime-meta.json \
#                        [--expect-sha <64hex>] [--strict]
set -euo pipefail

ABI=""
OUT=""
META=""
EXPECT_SHA=""
STRICT=0

usage() {
  echo "usage: $0 --abi arm64-v8a|x86_64 --out <zip> --meta <json> [--expect-sha <64hex>] [--strict]" >&2
  exit 2
}
while [ $# -gt 0 ]; do
  case "$1" in
    --abi) ABI="${2:?}"; shift 2 ;;
    --out) OUT="${2:?}"; shift 2 ;;
    --meta) META="${2:?}"; shift 2 ;;
    --expect-sha) EXPECT_SHA="${2:?}"; shift 2 ;;
    --strict) STRICT=1; shift ;;
    -h|--help) usage ;;
    *) echo "unknown arg: $1" >&2; usage ;;
  esac
done
[ -n "$ABI" ] && [ -n "$OUT" ] && [ -n "$META" ] || usage
case "$ABI" in
  arm64-v8a|x86_64) ;;
  *) echo "unsupported abi: $ABI (arm64-v8a|x86_64)" >&2; exit 2 ;;
esac
# 指纹是 64 位 hex；格式不对就当场报错，别等比对时才莫名其妙地「一直不匹配」
if [ -n "$EXPECT_SHA" ] && ! printf '%s' "$EXPECT_SHA" | grep -Eq '^[0-9a-f]{64}$'; then
  echo "--expect-sha must be 64 lowercase hex chars, got: $EXPECT_SHA" >&2
  exit 2
fi

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY not set (run inside GitHub Actions)}"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
OUT="$(realpath -m "$OUT")"
META="$(realpath -m "$META")"
WORK="$(mktemp -d)"
# 清理失败不得影响脚本退出码（否则成功路径也会被 trap 带成非 0）
trap 'rm -rf "$WORK" || true' EXIT

source_note() {
  echo "source: $1" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
}

# 严格档失败：给运维可执行的下一步，而不是一句 "check failed"
strict_fail() {
  echo "::error::$1" >&2
  source_note "失败（strict）：$1"
  exit 1
}

verify_pair() {
  # meta.sha256 必须等于 zip 文件哈希，否则视为该级来源不可用
  local zip="$1" meta="$2" want got
  [ -f "$zip" ] && [ -f "$meta" ] || return 1
  want="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["sha256"])' "$meta")"
  got="$(sha256sum "$zip" | cut -d' ' -f1)"
  if [ "$want" != "$got" ]; then
    echo "warn: sha256 mismatch — meta=$want actual=$got" >&2
    return 1
  fi
  return 0
}

# 用 gh api 下载 release 资产（与 runtime-build 的 decide 同一机制，走 api.github.com，
# 而非 release-asset CDN）。gh release download 在本仓库的 android-build runner 上对
# prerelease 标签整体失败（decide 用 gh api 能成功，证明 api 路径可用），故统一走 api。
# $1 = release 标识：latest | tags/<tag>   $2 = 资产名   $3 = 输出文件
api_asset() {
  local rel="$1" name="$2" out="$3" url
  url="$(gh api "repos/${REPO}/releases/${rel}" \
        --jq ".assets[] | select(.name==\"${name}\") | .url" 2>/dev/null || true)"
  [ -n "$url" ] || return 1
  gh api "$url" -H "Accept: application/octet-stream" > "$out" 2>/dev/null || return 1
  [ -s "$out" ] || return 1
  return 0
}

# ① 级产物的新鲜度：scriptsSha256 是否等于当前仓库的脚本指纹。
# 未指定 --expect-sha 时不做判断（兼容旧调用方与本地手测）。
tier1_fresh() {
  if [ -z "$EXPECT_SHA" ]; then
    return 0
  fi
  if [ ! -f "$WORK/runtime-inputs.json" ]; then
    echo "warn: tier 1 无 runtime-inputs.json（v1.4.27 之前的旧 release），无法判断指纹" >&2
    return 1
  fi
  local got
  got="$(python3 -c \
    'import json,sys;print(json.load(open(sys.argv[1])).get("scriptsSha256",""))' \
    "$WORK/runtime-inputs.json" 2>/dev/null || true)"
  if [ "$got" = "$EXPECT_SHA" ]; then
    echo "scripts fingerprint: ${got:0:12}… (match)"
    return 0
  fi
  echo "warn: tier 1 指纹不一致 — 现役 ${got:-none} vs 本地 ${EXPECT_SHA:0:12}…" >&2
  return 1
}

echo "== fetch-runtime =="
echo "abi:    $ABI"
echo "repo:   $REPO"
echo "strict: $STRICT"
[ -n "$EXPECT_SHA" ] && echo "expect: ${EXPECT_SHA:0:12}…"

# ---- ① 滚动 prerelease tag "runtime" ----
echo "-- tier 1: prerelease tag runtime"
if api_asset "tags/runtime" "runtime-${ABI}.zip" "$WORK/runtime-${ABI}.zip" \
   && api_asset "tags/runtime" "runtime-meta-${ABI}.json" "$WORK/runtime-meta-${ABI}.json"; then
  # runtime-inputs.json 是新鲜度判据，老 release 可能没有 → 单独取，缺失不算致命
  api_asset "tags/runtime" "runtime-inputs.json" "$WORK/runtime-inputs.json" || true

  if [ -f "$WORK/runtime-${ABI}.zip" ] && [ -f "$WORK/runtime-meta-${ABI}.json" ]; then
    mv "$WORK/runtime-${ABI}.zip" "$OUT"
    mv "$WORK/runtime-meta-${ABI}.json" "$META"
    if verify_pair "$OUT" "$META"; then
      if tier1_fresh; then
        source_note "runtime prerelease（tag runtime，sha256${EXPECT_SHA:+ + 指纹} 已校验）"
        echo "done (tier 1)"
        exit 0
      fi
      if [ "$STRICT" = "1" ]; then
        rm -f "$OUT" "$META"
        strict_fail "tag/正式构建要求 runtime 与当前脚本指纹一致，但 prerelease 是别的版本。
下一步：Actions → runtime-build → Run workflow（勾 force）发布新运行时，成功后再重跑本构建。
不允许降级到 releases/latest 或现场编译 —— 否则同一个 tag 会内嵌不同的 runtime。"
      fi
      echo "warn: tier 1 非当前脚本构建，降级到 tier 2" >&2
    else
      echo "warn: tier 1 产物不一致，降级到 tier 2" >&2
    fi
  else
    echo "warn: tier 1 资产不全（缺 zip 或 meta），降级到 tier 2" >&2
  fi
else
  echo "warn: tier 1 不可用（无 runtime release 或下载失败），降级到 tier 2" >&2
fi
# runtime-inputs.json 必须一起清掉：tier 2 会复用同一个路径下载自己的指纹文件，
# 而正式发布（如 v1.5.0）往往没有这个资产，api_asset 失败后 [ -f ] 就会命中
# tier 1 留下的残留，把上一级的指纹当成 latest 的指纹，误判「tier 2 指纹不一致」
# 从而永久降级到 tier 3 现场编译。
rm -f "$OUT" "$META" "$WORK/runtime-inputs.json"

# 严格档到这里说明 ① 级没给出可用的定版产物，②③ 都是「浮动/现场编译」，
# 会破坏发布可复现性，直接失败而不是悄悄换个来源。
if [ "$STRICT" = "1" ]; then
  strict_fail "tier 1 未提供与当前脚本一致的 runtime（strict 档禁止降级）。
下一步：Actions → runtime-build → Run workflow（勾 force）发布运行时后重跑。"
fi

# ---- ② releases/latest（正式发布自带 runtime zip）----
echo "-- tier 2: releases/latest"
if api_asset "latest" "runtime-${ABI}.zip" "$WORK/runtime-${ABI}.zip" \
   && [ -f "$WORK/runtime-${ABI}.zip" ]; then
  mv "$WORK/runtime-${ABI}.zip" "$OUT"
  python3 "$SCRIPT_DIR/make-runtime-meta.py" "$OUT" "$META"
  if verify_pair "$OUT" "$META"; then
    # 新鲜度（仅指定 --expect-sha 时生效）：stable 发布若含 runtime-inputs.json 且与
    # 当前脚本不符，不允许静默用旧包 —— strict 档硬失败，非 strict 降级到 tier3 本地
    # 重建（重建产物与当前脚本一致，比用旧包更正确）。
    if [ -n "$EXPECT_SHA" ]; then
      api_asset "latest" "runtime-inputs.json" "$WORK/runtime-inputs.json" || true
      if [ -f "$WORK/runtime-inputs.json" ]; then
        got="$(python3 -c \
          'import json,sys;print(json.load(open(sys.argv[1])).get("scriptsSha256",""))' \
          "$WORK/runtime-inputs.json" 2>/dev/null || true)"
        if [ -n "$got" ] && [ "$got" != "$EXPECT_SHA" ]; then
          if [ "$STRICT" = "1" ]; then
            rm -f "$OUT" "$META"
            strict_fail "tag/正式构建要求 runtime 与当前脚本指纹一致，但 releases/latest 是别的版本。
下一步：Actions → runtime-build → Run workflow（勾 force）发布新运行时，成功后再重跑本构建。"
          fi
          echo "warn: tier 2 指纹不一致（${got:-none} vs ${EXPECT_SHA:0:12}…），降级到 tier 3 本地重建" >&2
          rm -f "$OUT" "$META"
        fi
      fi
    fi
    if [ -f "$OUT" ]; then
      source_note "releases/latest（meta 由 zip 内 BUILD.json 重建${EXPECT_SHA:+，指纹已校验}）"
      echo "done (tier 2)"
      exit 0
    fi
  else
    echo "warn: tier 2 重建 meta 校验失败，降级到 tier 3" >&2
  fi
else
  echo "warn: tier 2 无 runtime-${ABI}.zip，降级到 tier 3" >&2
fi
rm -f "$OUT" "$META"

# ---- ③ 本地兜底构建 ----
echo "-- tier 3: 本地兜底构建（build-runtime.sh）"
chmod +x "$SCRIPT_DIR/build-runtime.sh" "$SCRIPT_DIR/collect-termux-runtime.sh"
"$SCRIPT_DIR/build-runtime.sh" --abi "$ABI" --out "$OUT"
mv "${OUT%.zip}.json" "$META"
verify_pair "$OUT" "$META" || { echo "error: 兜底构建产物与 meta 不一致" >&2; exit 1; }
source_note "本地兜底构建（前两级来源均缺失，如新 fork bootstrap）"
echo "done (tier 3)"
