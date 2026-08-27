#!/usr/bin/env bash
# collect-termux-runtime.sh —— 在 x86_64 Linux（如 GitHub Actions ubuntu runner）上
# 从 Termux 官方 apt 仓库收集 aarch64 Node.js 运行时闭包，产出可被 Android 端
# RuntimeInstaller 解压的 runtime.zip。
#
# 用法: ./collect-termux-runtime.sh <输出zip路径> [架构]（默认 aarch64）
#
# 设计说明：
# - 选择 Termux 仓库而非上游官方 Node 二进制：官方 linux-arm64 是 glibc 链接，
#   在 bionic 上无法直接运行；Termux 的 node 为 bionic 交叉编译，开箱即用。
# - 许可证：node (MIT) + 各依赖库（MIT/BSD/ISC/Zlib），允许再分发；
#   刻意不打包任何 GPL 工具链组件。
# - ⚠️ PKGS 清单基于 Termux 主仓库当前已知包名编写，仓库调整时按报错修正。
set -euo pipefail

OUT_ZIP="${1:?用法: $0 <输出zip路径> [架构]}"
ARCH="${2:-aarch64}"
case "$ARCH" in aarch64|x86_64) ;; *) echo "不支持的架构: $ARCH" >&2; exit 1;; esac
# 提前转为绝对路径：后面子 shell 会 cd 进 WORK，相对路径会写错位置
OUT_ZIP="$(realpath -m "$OUT_ZIP")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

TERMUX_REPO="https://packages.termux.dev/apt/termux-main"
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
# bash 的依赖不能依赖 apt-get download 自动递归：bash -> readline -> ncurses，
# 另有 libiconv/termux-tools；ripgrep -> pcre2，全部显式列出以形成可审计闭包。
PKGS=(
  nodejs-lts          # node 本体（含 npm）
  bash readline ncurses libiconv termux-tools  # bash 执行闭包
  ripgrep pcre2       # Android/bionic 原生 rg 及其正则库
  openssl c-ares libicu libsqlite zlib libc++   # nodejs-lts 硬依赖闭包
  libuv brotli        # 静态链接兜底
  libandroid-support  # bionic 兼容层辅助
  ca-certificates     # HTTPS 根证书（dsh 调模型 API 必需）
)

# ---- 3. 解包合并 ----
# 注意：apt-get download 把 .deb 下到【当前目录】而非 Dir::Cache，
# 因此先 cd 进 WORK 再下载。
cd "$WORK"
apt-get -c "$WORK/apt.conf" download "${PKGS[@]}"

shopt -s nullglob
DEBS=("$WORK"/*.deb)
if [ ${#DEBS[@]} -eq 0 ]; then
  echo "错误：未下载到任何 .deb，请检查 PKGS 清单与 Termux 仓库可达性" >&2
  exit 1
fi
for deb in "${DEBS[@]}"; do
  dpkg-deb -x "$deb" "$ROOT"
done

# Termux deb 按【绝对路径】打包：文件位于 data/data/com.termux/files/usr/…
# （而不是 usr/）。定位真实 usr 后整体平铺为 zip 根 —— 与 Android 端
# EngineConfig 的 PATH(bin)/LD_LIBRARY_PATH(lib)/dshEntry(lib/node_modules)
# 以及 Termux 惯例 $PREFIX/etc/tls/cert.pem 证书路径完全对齐。
USR_DIR="$(find "$ROOT" -type d -path '*com.termux/files/usr' | head -n1)"
if [ -z "$USR_DIR" ]; then
  echo "错误：解包后未找到 com.termux/files/usr，Termux 打包布局可能已变更" >&2
  find "$ROOT" -maxdepth 6 -type d >&2 || true
  exit 1
fi
cp -a "$USR_DIR/." "$ROOT/"
rm -rf "$ROOT/data"

# ---- 3.5 集成 dsh 引擎（平台无关 JS 依赖闭包）----
# 用 runner 自带 npm 在 x64 环境解析整棵依赖树（JS 文件与运行架构无关）；
# --ignore-scripts 禁掉 postinstall，防Linux-x64 native 构建/二进制混入。
# 若未来引入真 native 依赖（如 better-sqlite3），需针对 bionic 十字编译，
# 到时按报错在此处做平台裁剪或替换实现。
DSH_VERSION="${DSH_VERSION:-latest}"   # 可 pinned，如 DSH_VERSION=0.1.1-rc.2
mkdir -p "$WORK/bundle" && cd "$WORK/bundle"
printf '{"name":"dsh-runtime","private":true,"dependencies":{"@deepseek-ai/dsh":"%s"}}' \
  "$DSH_VERSION" > package.json
# dsh 依赖闭包巨大，npm 理想树解析默认堆会 OOM（SIGABRT/134），放开到 5.5GB
export NODE_OPTIONS="${NODE_OPTIONS:+$NODE_OPTIONS }--max-old-space-size=5632"
npm install --omit=dev --ignore-scripts --no-audit --no-fund --loglevel=error
mkdir -p "$ROOT/lib/node_modules"
cp -a "$WORK/bundle/node_modules/." "$ROOT/lib/node_modules/"

# 体积修剪：README/TS 类型/sourcemap 可安全删除；LICENSE 一律保留（分发合规）
find "$ROOT/lib/node_modules" \( -name '*.md' -o -name '*.map' \) -not -iname 'LICENSE*' -delete 2>/dev/null || true
find "$ROOT/lib/node_modules" -type f -name '*.d.ts' -delete 2>/dev/null || true

# ---- 3.6 Android (bionic) 兼容补丁 —— 唯一允许触碰上游的位置，逐条注明理由 ----
NM="$ROOT/lib/node_modules"

# [koffi] FFI 库：仅 glibc/x64 预编译。真实消费方只有 dsh-subprocess-local 的
# Win32 进程树强杀（Android 死代码），但其类型注册在模块顶层执行必须不抛错。
K="$NM/koffi"
test -e "$K.orig" || mv "$K" "$K.orig"
mkdir -p "$K"
printf '%s\n' '{"name":"koffi","version":"0.0.0-android-inert","main":"index.js"}' > "$K/package.json"
cat > "$K/index.js" <<'JSEOF'
// Android inert koffi: type REGISTRATION must not throw (win32-only helpers
// run it at module top level). Real FFI calls never happen on Android.
function makeInert(name) {
  const fn = function () { return inertProxy(name); };
  return fn;
}
function inertProxy(tag) {
  return new Proxy(makeInert(tag), {
    get(t, p) {
      if (p === "__esModule") return false;
      if (p === "then") return undefined;
      if (!t[p]) t[p] = makeInert(tag + "." + String(p));
      return t[p];
    },
    construct() { return {}; },
    apply() { return inertProxy(tag); },
  });
}
module.exports = inertProxy("koffi");
module.exports.default = module.exports;
JSEOF

# [node-pty] 缺 android 平台 .node 预编译。App 层已有自研 libdshpty.so，
# M2 将桥接；在此桥接前提供 API 兼容空壳，真实调用时显式报错。
P="$NM/node-pty"
test -e "$P.orig" || mv "$P" "$P.orig"
mkdir -p "$P/lib"
printf '%s\n' '{"name":"node-pty","version":"0.0.0-android-shim","main":"lib/index.js"}' > "$P/package.json"
cat > "$P/lib/index.js" <<'JSEOF'
// Android shim until libdshpty.so bridge lands (roadmap M2).
module.exports.spawn = function () {
  throw new Error("node-pty unavailable in this Android build; PTY served by app-side libdshpty.so");
};
JSEOF

# [dsh-sandbox-local] 外科手术：仅摘除两行 glibc-only native import
#   (node-addon-landlock-run / dsh-sandbox-windows-acl)，其余源码保持上游原样。
# bwrap/landlock 在 Android 内核上本就不存在，受限模式会经原版 fail-closed
# 路径抛 SANDBOX_UNAVAILABLE（诚实失败）；danger-full-access 显式放行。
SL="$NM/@deepseek-ai/dsh-sandbox-local/lib/index.js"
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
s = s.replace(
  /^import\s*\{[^}]*\}\s*from\s*"@deepseek-ai\/node-addon-landlock-run";?\s*$/m,
  `const LAUNCHER_BIN = "";
const LAUNCHER_FAILURE_EXIT = 126;
const grantArgs = () => [];
const launcherPath = () => "";
const probe = () => ({ usable: false });`
);
s = s.replace(
  /^import\s*\{[^}]*\}\s*from\s*"@deepseek-ai\/dsh-sandbox-windows-acl";?\s*$/m,
  `const AclWriteGrant = null;
const assertTempRootOutsideWorkspace = () => {};
const tempWriteSid = () => "";
const workspaceWriteSid = () => "";`
);
fs.writeFileSync(p, s);
// 只断言【import 语句】消失；Windows-only 分支里的 import.meta.resolve
// 字符串引用保留（永不执行于 Android，属上游原件）
const out = fs.readFileSync(p, "utf8");
if (/^import\s*\{[^}]*\}\s*from\s*"[^"]*(node-addon-landlock-run|dsh-sandbox-windows-acl)/m.test(out)) {
  console.error("patch failed: native imports still present");
  process.exit(1);
}
console.log("sandbox-local patched ok");
' "$SL"

# [dsh-session-persistence-jsonl] Android 禁止普通 App 创建硬链接（EACCES）。
# 首次会话落盘原本使用 fs.promises.link(tmp, finalPath) 做原子发布；临时文件
# 与目标文件同目录时，rename 同样具备原子发布语义，且是 Android 允许的普通操作。
SP="$NM/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js"
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const oldImport = "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";";
const newImport = "import { mkdir, mkdtemp, open, readFile, readdir, realpath, rename, rm, stat, truncate } from \"node:fs/promises\";";
if (!s.includes(oldImport)) {
  console.error("session persistence patch failed: fs/promises import shape changed");
  process.exit(1);
}
s = s.replace(oldImport, newImport);
const oldCall = "await link(tmp, finalPath);";
if ((s.match(/await link\(tmp, finalPath\);/g) || []).length !== 1) {
  console.error("session persistence patch failed: expected one link(tmp, finalPath) call");
  process.exit(1);
}
s = s.replace(oldCall, "await rename(tmp, finalPath);");
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (out.includes("await link(tmp, finalPath);") || !out.includes("await rename(tmp, finalPath);")) {
  console.error("session persistence patch failed: rename call not installed");
  process.exit(1);
}
console.log("session persistence patched ok: link -> rename");
' "$SP"

# [@vscode/ripgrep] npm 在 Ubuntu runner 上会选择 linux-x64 optional binary，
# 不适用于 Android。m1.7 重构：不再对上游 index.js 做文本块替换（上游改版即碎，
# 曾先后在本地构建与 CI 两次翻车），改为【注入平台包】：
#   lib/node_modules/@vscode/ripgrep-android-<arch>/bin/rg  ← Termux bionic rg 副本
# 上游 resolver 在 android 平台 require.resolve("@vscode/ripgrep-android-arm64/bin/rg")
# 时沿 node_modules 目录查找天然命中，零代码补丁，对上游 shape 免疫。
NODE_ARCH=""
case "$ARCH" in aarch64) NODE_ARCH=arm64 ;; x86_64) NODE_ARCH=x64 ;; esac
RGP="$NM/@vscode/ripgrep-android-$NODE_ARCH"
mkdir -p "$RGP/bin"
cp "$ROOT/bin/rg" "$RGP/bin/rg"
chmod 0755 "$RGP/bin/rg"
printf '%s\n' "{\"name\":\"@vscode/ripgrep-android-$NODE_ARCH\",\"version\":\"1.0.0-android-native\"}" > "$RGP/package.json"
test -x "$RGP/bin/rg" || { echo "错误：ripgrep 平台包注入失败" >&2; exit 1; }
echo "ripgrep 平台包注入 ok: @vscode/ripgrep-android-$NODE_ARCH/bin/rg"

# [dsh-fs-local] Android SELinux 禁止普通 App 创建硬链接（link() → EACCES）。
# write 工位 createIfAbsent 的原子发布走 fs.promises.link → 真机 EACCES。
# Android 分支改为 lstat 预检 + rename 原子发布，保留原 FS_NOT_OBSERVED /
# FS_NOT_REGULAR_FILE 语义（同 build_runtime.py 真机验证过的实现）。
FSL="$NM/@deepseek-ai/dsh-fs-local/lib/index.js"
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const pat = /[ \t]*await linkFile\(tempPath, absolutePath\);/;
if (!pat.test(s)) {
  console.error("dsh-fs-local patch failed: linkFile call not found");
  process.exit(1);
}
const andr = [
"\t\t\tif (process.platform === \"android\") {",
"\t\t\t\t// Android SELinux forbids hard links (EACCES). Keep the",
"\t\t\t\t// no-overwrite-create semantics with an lstat guard + atomic rename.",
"\t\t\t\tlet existing;",
"\t\t\t\ttry {",
"\t\t\t\t\texisting = await inspectPublicationTarget(absolutePath);",
"\t\t\t\t} catch (metadataError) {",
"\t\t\t\t\tif (!isENOENT(metadataError) && !isENOTDIR(metadataError)) throw new FsError(`cannot write \"${createIfAbsent.displayPath}\": ${errorMessage(metadataError)}`, \"FS_IO_ERROR\", { cause: metadataError });",
"\t\t\t\t}",
"\t\t\t\tif (existing !== void 0) {",
"\t\t\t\t\tif (!existing.isFile()) throw new FsError(`cannot write \"${createIfAbsent.displayPath}\": not a regular file`, \"FS_NOT_REGULAR_FILE\");",
"\t\t\t\t\tthrow new FsError(`cannot overwrite existing \"${createIfAbsent.displayPath}\" without reading it first`, \"FS_NOT_OBSERVED\");",
"\t\t\t\t}",
"\t\t\t\tawait rename(tempPath, absolutePath);",
"\t\t\t} else {",
"\t\t\t\tawait linkFile(tempPath, absolutePath);",
"\t\t\t}",
].join("\n");
s = s.replace(pat, andr);
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (!out.includes("await rename(tempPath, absolutePath);")) {
  console.error("dsh-fs-local patch failed: android branch not installed");
  process.exit(1);
}
console.log("dsh-fs-local patched ok: createIfAbsent -> lstat+rename");
' "$FSL"

# ---- 3.7 SONAME 别名副本（真机 m1.5 事故：bash 报 CANNOT LINK libreadline.so.8）----
# Android linker 按 NEEDED 里记录的 SONAME 精确文件名查找；deb 只带完整版本号
# 文件（如 .8.3）。Android SELinux 禁 symlink/link()，必须 cp 出普通文件别名。
copy_soname() {
  dst="$ROOT/lib/$2"
  [ -e "$dst" ] && return 0
  for s in "$ROOT"/lib/"$1"; do
    [ -f "$s" ] || continue
    cp -p "$s" "$dst"
    echo "soname alias $(basename "$s") -> $2"
    return 0
  done
  echo "错误：SONAME 别名 $2 无源文件（glob: lib/$1）" >&2
  exit 1
}
copy_soname 'libreadline.so.[0-9]*'  libreadline.so.8
copy_soname 'libhistory.so.[0-9]*'   libhistory.so.8
copy_soname 'libncursesw.so.[0-9]*'  libncursesw.so.6
copy_soname 'libncursesw.so.[0-9]*'  libncurses.so.6
copy_soname 'libncursesw.so.[0-9]*'  libncurses.so

# ---- 3.8 bin 工具 wrapper（真机 m1.6.7/8 验证版，与 build_runtime.py 对齐）----
# pnpm：corepack 的 shebang 指向 Termux 绝对路径且依赖 $PREFIX；
# wrapper 用 $(dirname "$0") 自推导 node 与 pnpm.js，环境无关。
# curl：系统 /system/bin/curl 链接旧 OpenSSL（缺 EVP_MD_CTX_CREATE）不可用；
# node fetch 垫片，sh 侧解析参数 + 环境变量传值（node 不接触原始 argv）。
if [ -f "$ROOT/lib/node_modules/corepack/dist/pnpm.js" ]; then
  cat > "$ROOT/bin/pnpm" <<'SHEOF'
#!/system/bin/sh
# Android corepack pnpm wrapper (shebang-safe, PATH-independent)
exec "$(dirname "$0")/node" "$(dirname "$0")/../lib/node_modules/corepack/dist/pnpm.js" "$@"
SHEOF
  chmod 0755 "$ROOT/bin/pnpm"
  echo "added bin/pnpm wrapper -> corepack dist pnpm.js"
else
  echo "错误：corepack/dist/pnpm.js 不存在，pnpm wrapper 未生成" >&2
  exit 1
fi

cat > "$ROOT/bin/curl" <<'SHEOF'
#!/system/bin/sh
# Android curl -> node fetch (system curl cannot link due to broken system OpenSSL).
# http(s) only, first bare arg = URL. Options: -s/-sS silent, -o FILE,
#   -X METHOD, -H "K: V" (repeatable), -d DATA, --max-time/-w accepted-and-ignored.
URL="" METHOD="" OUT="" SILENT="" DATA=""
HDRS=""
nextval=""
for word in "$@"; do
  if [ -n "$nextval" ]; then
    case "$nextval" in
      -o|--output) OUT="$word" ;;
      -X|--request) METHOD="$word" ;;
      -H|--header) HDRS="$HDRS$word\n" ;;
      -d|--data|--data-raw) DATA="$word" ;;
    esac
    nextval=""
    continue
  fi
  case "$word" in
    -o|--output|-X|--request|-H|--header|-d|--data|--data-raw|--max-time|-w) nextval="$word" ;;
    -s|-sS|-S|--silent) SILENT=1 ;;
    -*) : ;;
    *) if [ -z "$URL" ]; then URL="$word"; fi ;;
  esac
done
export CURL_URL="$URL" CURL_METHOD="$METHOD" CURL_OUT="$OUT" \
  CURL_SILENT="$SILENT" CURL_DATA="$DATA" CURL_HDRS="$HDRS"
exec "$(dirname "$0")/node" -e '
(async()=>{
  try{
    const url=process.env.CURL_URL.trim();
    const h={};
    // sh 双引号内 \n 是字面反斜杠+n，故 JS 侧也按字面 \\n 切分
    (process.env.CURL_HDRS||"").split("\\n").filter(Boolean).forEach(l=>{const c=l.indexOf(":");if(c>0)h[l.slice(0,c).trim()]=l.slice(c+1).trim()});
    const d=process.env.CURL_DATA||null;
    const m=(d&&!process.env.CURL_METHOD)?"POST":(process.env.CURL_METHOD||"GET");
    const r=await fetch(url,{method:m,headers:h,body:d||void 0});
    const out=process.env.CURL_OUT;
    if(out){require("fs").writeFileSync(out,await r.text())}else if(!process.env.CURL_SILENT){process.stdout.write(await r.text())}
    process.exit(r.ok?0:1);
  }catch(e){if(!process.env.CURL_SILENT)console.error(e.message);process.exit(2)}
})()
'
SHEOF
chmod 0755 "$ROOT/bin/curl"
echo "added bin/curl wrapper -> node fetch (env-passing)"

# usr/bin 必须真实存在（EngineConfig PATH 声明了它；空目录会被 zip 丢弃）
mkdir -p "$ROOT/usr/bin"
printf 'keep engine/usr/bin on PATH\n' > "$ROOT/usr/bin/.keep"

# 打包前闭包校验：防止 bash/rg 在 CI 产出后才于真机失败。
test -x "$ROOT/bin/bash" || { echo "错误：缺少可执行 bin/bash" >&2; exit 1; }
test -x "$ROOT/bin/rg" || { echo "错误：缺少可执行 bin/rg（Termux ripgrep）" >&2; exit 1; }
# SONAME 精确文件名（Android linker 按 NEEDED 逐字查找，模糊存在不算数）
for so in libreadline.so.8 libhistory.so.8 libncursesw.so.6 libncurses.so.6 \
          libiconv.so libpcre2-8.so; do
  test -e "$ROOT/lib/$so" || { echo "错误：SONAME 库 lib/$so 缺失" >&2; exit 1; }
done
# 工具 wrapper
test -x "$ROOT/bin/pnpm" || { echo "错误：bin/pnpm wrapper 缺失" >&2; exit 1; }
test -x "$ROOT/bin/curl" || { echo "错误：bin/curl wrapper 缺失" >&2; exit 1; }
test -e "$ROOT/usr/bin/.keep" || { echo "错误：usr/bin/.keep 缺失" >&2; exit 1; }
# ripgrep 平台包（android resolver 的 require.resolve 目标）
NODE_ARCH=""
case "$ARCH" in aarch64) NODE_ARCH=arm64 ;; x86_64) NODE_ARCH=x64 ;; esac
test -x "$NM/@vscode/ripgrep-android-$NODE_ARCH/bin/rg" || {
  echo "错误：ripgrep 平台包 @vscode/ripgrep-android-$NODE_ARCH/bin/rg 缺失" >&2; exit 1;
}
find "$ROOT" -type f -name 'libreadline.so*' -print -quit | grep -q . || {
  echo "错误：bash 依赖 libreadline.so* 未打包" >&2; exit 1;
}
# 删除 npm 根据 Ubuntu runner 拉入的宿主 Linux rg 二进制，保留 JS 解析器；
# 上面的 Android 分支会将 rgPath 指向 Termux 的 $ROOT/bin/rg。
find "$NM/@vscode" -maxdepth 1 -type d -name 'ripgrep-linux-*' -exec rm -rf {} + 2>/dev/null || true

# 打包前闭包校验：禁止宿主 Linux rg 残留，要求 Android 原生 rg 到位。
if find "$ROOT" -type f -path '*@vscode/ripgrep-linux-*/*/rg' -print -quit | grep -q .; then
  echo "错误：runtime 混入宿主 Linux ripgrep" >&2
  exit 1
fi

echo "Android 补丁完成：koffi/inert, node-pty/shim, sandbox-local/source-patch, session-persistence/rename, dsh-fs-local/rename, ripgrep/平台包注入, soname-aliases, pnpm+curl wrapper"
echo "dsh 引擎已集成：$(du -sh "$ROOT/lib/node_modules" | cut -f1)，样例 $(ls "$ROOT/lib/node_modules/@deepseek-ai" 2>/dev/null | head -n4 | tr '\n' ' ')"

# ---- 4. 精简：剔除文档/头文件/npm 冗余，控制体积 ----
rm -rf "$ROOT/share/man" "$ROOT/share/doc" "$ROOT/include" \
       "$ROOT/var/cache" "$ROOT/var/log" \
       "$ROOT/lib/node_modules/npm/docs" \
       "$ROOT/lib/node_modules/npm/man" \
       "$ROOT/lib/node_modules/npm/html" 2>/dev/null || true
find "$ROOT" \( -name "*.a" -o -name "*.map" \) -delete 2>/dev/null || true

# ---- 5. 打 zip ----
( cd "$ROOT" && zip -qr "$OUT_ZIP" . )

echo "runtime.zip 已生成: $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "SHA-256: $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
