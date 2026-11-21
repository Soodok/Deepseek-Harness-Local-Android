#!/usr/bin/env bash
# 重编 dsh_alpine loader 产物（app/src/main/jniLibs/<abi>/libdsh_alpine.so）。
#
# 为什么需要这个脚本：主仓此前**没有**任何 loader 重编入口，每次都得手抄一长串
# clang 参数（NDK 里的真实路径、-mcmodel=large、--image-base、-nostdlib 全家桶），
# 抄漏一条就得到一份「能编译但跑不起来」或在 App 域被 seccomp 判死的产物。
# 镜像仓 dsh-linux-runtime 里有 build/build.sh，但它输出的是 build/libdsh_alpine.so，
# 路径与本仓的 jniLibs 布局不同（aarch64 → arm64-v8a 还要改名），不能直接搬。
#
# 用法：
#   scripts/build-loader.sh <ndk根目录> [arm64-v8a|x86_64]
#   scripts/build-loader.sh /e/Android/Sdk/ndk/28.2.13676358            # 默认 arm64-v8a
#   scripts/build-loader.sh /e/Android/Sdk/ndk/28.2.13676358 x86_64
#
# 编完必须跑：python scripts/probes/_test_dsh_alpine_source_sync.py
# 该探针会校验两个 ABI 的产物形状（ELFCLASS / e_machine / ET_EXEC / 无 PT_INTERP /
# 不引 libc.so / 无调试残留 / 与另一 ABI 非同一字节流 / 产物不早于源码）。
#
# 为什么参数这么硬：本程序的 BPF 白名单靠比对 seccomp_data.instruction_pointer
# 识别自己写的裸 svc 桩，所以**地址必须每次运行都相同** —— 必须 ET_EXEC + 固定基址
# + 无 PIE。任何一条漏掉，过滤器都会把自己 trap 掉，表现为启动即挂、无任何输出。
set -euo pipefail

NDK="${1:?用法: $0 <ndk根目录> [arm64-v8a|x86_64]}"
ABI="${2:-arm64-v8a}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
SRC="$ROOT/app/src/main/cpp/dsh_alpine.c"

case "$ABI" in
  arm64-v8a) TRIPLE="aarch64"; OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libdsh_alpine.so" ;;
  x86_64)    TRIPLE="x86_64";  OUT="$ROOT/app/src/main/jniLibs/x86_64/libdsh_alpine.so" ;;
  *) echo "错误：不支持的 ABI \"$ABI\"（只认 arm64-v8a / x86_64）" >&2; exit 1 ;;
esac

# Windows 的 NDK 里裸名是真编译器、.cmd 是包装；裸名优先（从 bash 直接调 .cmd 反而
# 多一层 shell 解析，某些 MSYS 版本会丢参数）。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin" ;;
  Darwin)               BIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin" ;;
  *)                    BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin" ;;
esac
CC=""
for c in "$TRIPLE-linux-android28-clang" "$TRIPLE-linux-android28-clang.cmd"; do
  if [ -x "$BIN/$c" ]; then CC="$BIN/$c"; break; fi
done
if [ -z "$CC" ]; then
  echo "错误：$BIN 下找不到 $TRIPLE-linux-android28-clang（NDK 版本或 ABI 不对？）" >&2
  exit 1
fi

# x86_64 必须 -mcmodel=large：小/中代码模型在 --image-base=0x2000000000 下报
# `relocation R_X86_64_32 out of range`（medium 也不行，只有 large 过）。
EXTRA=""
[ "$ABI" = "x86_64" ] && EXTRA="-mcmodel=large"

# shellcheck disable=SC2086
"$CC" \
  -O1 -ffreestanding -fno-builtin -fno-pie -fno-PIE \
  -U_FORTIFY_SOURCE -D_FORTIFY_SOURCE=0 -fno-stack-protector \
  -DPATH_MAX=4096 $EXTRA \
  -static -nostdlib -nostartfiles -nodefaultlibs -no-pie \
  -Wl,-e,_start \
  -Wl,--image-base=0x2000000000 \
  -Wl,-z,max-page-size=0x20000 \
  -Wl,-s \
  -o "$OUT" "$SRC"

echo "ABI:   $ABI"
echo "产物:  $OUT"
echo "大小:  $(wc -c < "$OUT") 字节"
echo "md5:   $(md5sum "$OUT" | cut -d' ' -f1)"

READELF="$(dirname "$CC")/llvm-readelf"
[ -x "$READELF.exe" ] && READELF="$READELF.exe"
[ -x "$READELF" ] || READELF="$(dirname "$CC")/llvm-readelf.exe"
if [ -x "$READELF" ]; then
  "$READELF" -h "$OUT" | grep -E "Class|Type|Machine|Entry point" || true
fi
echo ""
echo "下一步：python scripts/probes/_test_dsh_alpine_source_sync.py"
