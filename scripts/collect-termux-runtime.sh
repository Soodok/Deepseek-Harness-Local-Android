#!/usr/bin/env bash
# collect-termux-runtime.sh —— 在 x86_64 Linux（如 GitHub Actions ubuntu runner）上
# 从 Termux 官方 apt 仓库收集 aarch64 Node.js 运行时闭包，产出可被 Android 端
# RuntimeInstaller 解压的 runtime.zip。
#
# 用法: ./collect-termux-runtime.sh <输出zip路径>
#
# 设计说明：
# - 选择 Termux 仓库而非上游官方 Node 二进制：官方 linux-arm64 是 glibc 链接，
#   在 bionic 上无法直接运行；Termux 的 node 为 bionic 交叉编译，开箱即用。
# - 许可证：node (MIT) + 各依赖库（MIT/BSD/ISC/Zlib），允许再分发；
#   刻意不打包任何 GPL 工具链组件。
# - ⚠️ PKGS 清单基于 Termux 主仓库当前已知包名编写，仓库调整时按报错修正。
set -euo pipefail

OUT_ZIP="${1:?用法: $0 <输出zip路径>}"
# 提前转为绝对路径：后面子 shell 会 cd 进 WORK，相对路径会写错位置
OUT_ZIP="$(realpath -m "$OUT_ZIP")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
ARCH="aarch64"
ROOT="$WORK/root"

mkdir -p "$ROOT" "$WORK/lists/partial" "$WORK/cache/archives/partial"

# ---- 1. 配置 apt 源（仅 arm64 架构；trusted 免签仅限 CI 受控环境）----
cat > "$WORK/sources.list" <<EOF
deb [arch=${ARCH} trusted=yes] ${TERMUX_REPO} stable main
EOF
cat > "$WORK/apt.conf" <<EOF
Dir::Etc::sourcelist "${WORK}/sources.list";
Dir::State::lists "${WORK}/lists";
Dir::Cache "${WORK}/cache";
APT::Architecture "${ARCH}";
APT::Architectures {"${ARCH}"};
Acquire::AllowInsecureRepositories "true";
APT::Get::AllowUnauthenticated "true";
Acquire::Languages "none";
EOF

apt-get -c "$WORK/apt.conf" update

# ---- 2. 下载运行时依赖闭包 ----
# 包名已对照 termux-main binary-aarch64 Packages 索引逐个核实：
#   nodejs-lts 24.x Depends = libc++, openssl, c-ares, libicu, libsqlite, zlib
# 注意：没有叫 "icu" 的包（正确名 libicu）；libuv/brotli 已被静态链接进
# Termux node 二进制，不再是硬依赖，保留仅为防未来构建变体回退。
PKGS=(
  nodejs-lts          # node 本体（含 npm）
  openssl c-ares libicu libsqlite zlib libc++   # nodejs-lts 硬依赖闭包
  libuv brotli        # 静态链接兜底
  libandroid-support  # bionic 兼容层辅助
  ca-certificates     # HTTPS 根证书（dsh 调模型 API 必需）
)

apt-get -c "$WORK/apt.conf" download "${PKGS[@]}"

# ---- 3. 解包合并为 usr 树 ----
# 注意：apt-get download 把 .deb 下到【当前目录】而非 Dir::Cache，
# 因此先 cd 进 WORK 再下载，解包时直接 glob WORK 顶层。
cd "$WORK"
apt-get -c "$WORK/apt.conf" download "${PKGS[@]}"
apt-cache -c "$WORK/apt.conf" stats >/dev/null 2>&1 || true

shopt -s nullglob
DEBS=("$WORK"/*.deb)
if [ ${#DEBS[@]} -eq 0 ]; then
  echo "错误：未下载到任何 .deb，请检查 PKGS 清单与 Termux 仓库可达性" >&2
  exit 1
fi
for deb in "${DEBS[@]}"; do
  dpkg-deb -x "$deb" "$ROOT"
done

# ---- 4. 精简：剔除文档/头文件/npm 冗余，控制体积 ----
rm -rf "$ROOT/usr/share/man" "$ROOT/usr/share/doc" "$ROOT/usr/include" \
       "$ROOT/var/cache" "$ROOT/var/log" \
       "$ROOT/usr/lib/node_modules/npm/docs" \
       "$ROOT/usr/lib/node_modules/npm/man" \
       "$ROOT/usr/lib/node_modules/npm/html" 2>/dev/null || true
find "$ROOT" -name "*.a" -delete 2>/dev/null || true
find "$ROOT" -name "*.map" -delete 2>/dev/null || true

# ---- 5. 布局适配：顶层镜像 bin/lib，供 EngineConfig 的 PATH/LD_LIBRARY_PATH 直查 ----
mkdir -p "$ROOT/bin" "$ROOT/lib"
cp -a "$ROOT/usr/bin/." "$ROOT/bin/"
[ -d "$ROOT/usr/lib" ] && cp -a "$ROOT/usr/lib/." "$ROOT/lib/"

# ---- 6. 打 zip ----
( cd "$ROOT" && zip -qr "$OUT_ZIP" . )

echo "runtime.zip 已生成: $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "SHA-256: $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
