#!/usr/bin/env bash
# 生成 DSH Mobile 的 GitHub Release 说明（自动读取 git 历史与 APK 产物）。
#
# 用法:
#   bash scripts/generate-release-notes.sh <tag|edge> [输出文件]
#
# 环境变量（GitHub Actions 自动提供大部分）:
#   GITHUB_REF_NAME / GITHUB_SHA / GITHUB_REPOSITORY
#   APK_DIR                APK 目录（默认 apk）
#   RELEASE_CERT_SHA256    正式签名证书指纹（可选，仓库 Variable）
#   HARDSSH_REF            内置 dsh-hardssh 的提交（可选，workflow env）
#
# 规则（参考 combridge-rust 的发布 job）:
#   · tag 模式：变更范围 = 上一个语义化版本 tag..当前 tag；没有旧 tag 时取最近 30 个提交
#   · edge 模式：变更范围 = edge tag..HEAD（即上次 edge 之后的提交）；没有 edge 时取最近 30 个
#   · 按 feat/fix/perf/refactor/build/ci/docs/test/style 分类
#   · 安装包表：文件名 / 架构 / 类型 / 大小 / SHA-256
#   · 版本信息、贡献者（本次更新 + 项目累计 Top 10，含 GitHub 账号链接）、compare 链接；所有内容写入输出文件（供 body_path 使用）
set -euo pipefail

MODE="${1:-tag}"
OUT="${2:-release-notes.md}"
APK_DIR="${APK_DIR:-apk}"
REPO="${GITHUB_REPOSITORY:-local/repo}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD)}"
SHORT="${SHA:0:7}"
REF="${GITHUB_REF_NAME:-$(git rev-parse --abbrev-ref HEAD)}"

VERSION_NAME=$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -1)
VERSION_CODE=$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -1)

RANGE=""
PREV=""
TAG=""
if [ "$MODE" = "tag" ]; then
  TAG="$REF"
  # 只认语义化版本 tag（排除 edge / list 之类的滚动 tag）
  PREV=$(git tag --merged "${TAG}^" --sort=-v:refname 2>/dev/null \
    | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1 || true)
  if [ -n "$PREV" ]; then
    RANGE="${PREV}..${TAG}"
    COMPARE="[${PREV}...${TAG}](https://github.com/${REPO}/compare/${PREV}...${TAG})"
    RANGE_LABEL="\`${PREV}\` → \`${TAG}\`"
  else
    COMPARE="[${TAG}](https://github.com/${REPO}/commits/${TAG})"
    RANGE_LABEL="最近 30 个提交（未找到上一个版本 tag）"
  fi
else
  TAG="edge"
  if git rev-parse -q --verify refs/tags/edge >/dev/null; then
    PREV="edge"
    RANGE="edge..HEAD"
    COMPARE="[edge...${SHORT}](https://github.com/${REPO}/compare/edge...${SHA})"
    RANGE_LABEL="\`edge\` → \`${SHORT}\`"
    # 重跑同一 commit 时 edge 已指向 HEAD，退回最近提交，避免空说明
    if [ "$(git rev-list --count --no-merges edge..HEAD)" -eq 0 ]; then
      RANGE=""
      PREV=""
      COMPARE="[${SHORT}](https://github.com/${REPO}/commits/${SHA})"
      RANGE_LABEL="最近 30 个提交（edge 已指向当前提交）"
    fi
  else
    COMPARE="[${SHORT}](https://github.com/${REPO}/commits/${SHA})"
    RANGE_LABEL="最近 30 个提交（尚无 edge tag）"
  fi
fi

if [ -n "$RANGE" ]; then
  LOG_ARGS=(--no-merges "$RANGE")
  COUNT=$(git rev-list --count --no-merges "$RANGE")
else
  LOG_ARGS=(--no-merges -n 30 HEAD)
  COUNT=$(git rev-list --count --no-merges -n 30 HEAD)
fi

# GitHub API 用的范围：有 baseline 时走 compare，否则取最近的提交列表
API_BASE=""
API_HEAD=""
if [ "$MODE" = "tag" ]; then API_HEAD="$TAG"; else API_HEAD="$SHA"; fi
if [ -n "$PREV" ]; then API_BASE="$PREV"; fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 贡献者：本次更新（按提交数降序）+ 全项目累计 Top 10。
# 有 GH_TOKEN + gh 时从 GitHub API 取账号/链接；否则回退纯 git 统计（无链接）。
declare -A LOGIN_BY_NAME=()
: > "$TMP/contrib-update.md"
: > "$TMP/contrib-top.md"

if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
  # 本次范围：commit name -> GitHub login
  if [ -n "$API_BASE" ]; then
    PAIRS=$(gh api "repos/${REPO}/compare/${API_BASE}...${API_HEAD}" \
      --jq '.commits[] | select(.author != null) | [.commit.author.name, .author.login] | @tsv' 2>/dev/null || true)
  else
    PAIRS=$(gh api "repos/${REPO}/commits?sha=${API_HEAD}&per_page=30" \
      --jq '.[] | select(.author != null) | [.commit.author.name, .author.login] | @tsv' 2>/dev/null || true)
  fi
  while IFS=$'\t' read -r name login; do
    [ -n "$name" ] && [ -n "$login" ] && LOGIN_BY_NAME["$name"]="$login"
  done <<< "$PAIRS"

  # 全项目 Top 10：GitHub contributors API（已按提交数降序，带 profile URL）
  TOP=$(gh api "repos/${REPO}/contributors?per_page=100" \
    --jq '.[] | [.login, .contributions, .html_url] | @tsv' 2>/dev/null || true)
  if [ -n "$TOP" ]; then
    rank=0
    printf '%s\n' "$TOP" | head -10 | while IFS=$'\t' read -r login contributions url; do
      rank=$((rank + 1))
      printf '| %d | [@%s](%s) | %s |\n' "$rank" "$login" "$url" "$contributions" >> "$TMP/contrib-top.md"
    done
  fi
fi

# 本次更新（能映射到 GitHub 账号时附链接）
git log "${LOG_ARGS[@]}" --pretty='%an' | sed '/^[[:space:]]*$/d' | sort | uniq -c \
  | sort -k1,1nr -k2,2 | while read -r n author; do
      login="${LOGIN_BY_NAME[$author]:-}"
      if [ -n "$login" ]; then
        printf -- '- [%s](https://github.com/%s)（%s 次提交）\n' "$author" "$login" "$n" >> "$TMP/contrib-update.md"
      else
        printf -- '- %s（%s 次提交）\n' "$author" "$n" >> "$TMP/contrib-update.md"
      fi
    done

# API 不可用（本地测试 / 无 token）时回退 git 统计
if [ ! -s "$TMP/contrib-top.md" ]; then
  rank=0
  git log --no-merges --pretty='%an' HEAD | sed '/^[[:space:]]*$/d' | sort | uniq -c \
    | sort -k1,1nr -k2,2 | head -10 | while read -r n author; do
        rank=$((rank + 1))
        printf '| %d | %s | %s |\n' "$rank" "$author" "$n" >> "$TMP/contrib-top.md"
      done
fi


for key in feat fix perf refactor build ci docs test style other; do
  : > "$TMP/$key.md"
done

# conventional commit 分类（feat(scope): / fix!: 等）
while IFS=$'\t' read -r short subject; do
  [ -z "${subject:-}" ] && continue
  type=$(printf '%s' "$subject" | sed -nE 's/^([a-z]+)(\([^)]*\))?!?:.*/\1/p')
  case "$type" in
    feat|fix|perf|refactor|build|ci|docs|test|style) ;;
    *) type=other ;;
  esac
  printf -- '- %s (`%s`)\n' "$subject" "$short" >> "$TMP/$type.md"
done < <(git log "${LOG_ARGS[@]}" --pretty=format:'%h%x09%s%n')

# 安装包清单：文件名 / 架构 / 类型 / 大小 / SHA-256
: > "$TMP/assets.md"
if [ -d "$APK_DIR" ]; then
  while IFS= read -r file; do
    name=$(basename "$file")
    abi=unknown
    case "$name" in
      *-arm64-v8a-*) abi=arm64-v8a ;;
      *-x86_64-*) abi=x86_64 ;;
    esac
    variant=unknown
    case "$name" in
      *-release.apk) variant=release ;;
      *-debug.apk) variant=debug ;;
    esac
    size=$(du -h "$file" | cut -f1)
    sha=$(sha256sum "$file" | cut -d' ' -f1 | sed 's/^\\//')
    printf '| `%s` | %s | %s | %s | `%s` |\n' "$name" "$abi" "$variant" "$size" "$sha" >> "$TMP/assets.md"
  done < <(find "$APK_DIR" -maxdepth 1 -type f -name '*.apk' | sort)
fi

BUILD_TIME=$(date -u '+%Y-%m-%d %H:%M UTC')
CERT="${RELEASE_CERT_SHA256:-}"
PLUGIN_REF="${HARDSSH_REF:-}"

{
  if [ "$MODE" != "tag" ]; then
    echo "> ⚠️ 自动构建（\`main\` @ \`${SHORT}\`），未经过正式版本验证。"
    echo ""
  fi
  echo "## 版本信息"
  echo ""
  echo "| 项目 | 值 |"
  echo "| --- | --- |"
  echo "| 版本 | \`${TAG}\`（\`${VERSION_NAME}\`） |"
  echo "| versionCode | \`${VERSION_CODE}\` |"
  echo "| 提交 | \`${SHORT}\` |"
  echo "| 变更范围 | ${RANGE_LABEL} |"
  echo "| 提交数量 | ${COUNT} |"
  echo "| 构建时间 | ${BUILD_TIME} |"
  echo "| 架构 | arm64-v8a（真机）/ x86_64（模拟器） |"
  echo "| 签名 | 正式签名（release）/ debug 签名（debug） |"
  if [ -n "$PLUGIN_REF" ]; then
    echo "| 内置 SSH 插件 | \`${PLUGIN_REF}\` |"
  fi
  echo ""
  echo "## 安装包"
  echo ""
  if [ -s "$TMP/assets.md" ]; then
    echo "| 文件 | 架构 | 类型 | 大小 | SHA-256 |"
    echo "| --- | --- | --- | --- | --- |"
    cat "$TMP/assets.md"
  else
    echo "未找到构建产物。"
  fi
  echo ""
  echo "## 变更内容"
  echo ""
  for key in feat fix perf refactor build ci docs test style other; do
    if [ -s "$TMP/$key.md" ]; then
      case "$key" in
        feat) title="新功能" ;;
        fix) title="问题修复" ;;
        perf) title="性能优化" ;;
        refactor) title="重构" ;;
        build) title="构建与依赖" ;;
        ci) title="CI/CD" ;;
        docs) title="文档" ;;
        test) title="测试" ;;
        style) title="代码风格" ;;
        *) title="其他改动" ;;
      esac
      echo "### ${title}"
      echo ""
      cat "$TMP/$key.md"
      echo ""
    fi
  done
  echo "## 安装说明"
  echo ""
  echo "- \`*-release.apk\`：**正式签名**，可以覆盖升级旧版本。"
  echo "- \`*-debug.apk\`：debug 签名，仅用于测试；与正式包证书不同，**不能覆盖正式版**。"
  echo "- \`arm64-v8a\` 用于真机，\`x86_64\` 用于模拟器。"
  echo "- 若从旧签名证书版本升级失败：先导出数据，再卸载重装（签名不同无法覆盖）。"
  echo ""
  if [ -n "$CERT" ]; then
    echo "## 签名"
    echo ""
    echo "- 证书 SHA-256：\`${CERT}\`"
    echo "- 校验：\`apksigner verify --print-certs <file>\`"
    echo ""
  fi
  echo "## 贡献者"
  echo ""
  echo "### 本次更新"
  echo ""
  if [ -s "$TMP/contrib-update.md" ]; then
    cat "$TMP/contrib-update.md"
  else
    echo "—"
  fi
  echo ""
  echo "### 项目累计 Top 10"
  echo ""
  if [ -s "$TMP/contrib-top.md" ]; then
    echo "| # | 贡献者 | 提交数 |"
    echo "| --- | --- | --- |"
    cat "$TMP/contrib-top.md"
  else
    echo "—"
  fi
  echo ""
  echo "## 完整对比"
  echo ""
  echo "${COMPARE}"
} > "$OUT"

echo "----- ${OUT} -----"
cat "$OUT"
