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
# 锁定版本。浮动 latest 曾撞上上游重构（0.1.5-rc.1 时 session-persistence-jsonl
# 从依赖树移除 → 补丁断言失败 → CI 全挂）。
# v1.2.28 升级到 0.2.0-rc.2：已逐包核验补丁目标——session-persistence 的
# fs/promises import 多了 lstat；sandbox-local 的 landlock import 已被上游移除
# （补丁正则自然 no-op）；fs-local 的 linkFile 调用、web-frontend 的 polyfill
# 锚点均未变；koffi/node-pty 改为存在才打桩。
DSH_VERSION="${DSH_VERSION:-0.2.0-rc.2}"
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

# ---- 3.6a D2: openPath/openTextFile Android 化（内联自包含，幂等）----
# 上游默认调原生 opener（Android 无）→ 点击文件引用报
# "path open failed: native path opener is unsupported on android"。
# 补丁脚本单一真源（与本地 build_runtime.py 同源），幂等可重跑。
if command -v python3 >/dev/null 2>&1; then
  SCRIPTS_DIR="$GITHUB_WORKSPACE/scripts"
  [ -d "$SCRIPTS_DIR" ] || SCRIPTS_DIR="$(cd "$(dirname "$0")/.." && pwd)/scripts"
  # ⚠️ 必须传 runtime 根目录：两个 patch 脚本内部会自行拼
  # "lib/node_modules/@deepseek-ai/<pkg>/..."，若传 $NM（已是 lib/node_modules）
  # 会拼成 lib/node_modules/lib/node_modules/... 而静默 WARN 跳过。
  # 该 bug 自 CI 接入起存在，导致 polyfill 从未在 CI 产物中生效
  # （Android 11 旧 WebView 白屏），v1.2.28 修复。
  export DSH_PATCH_TARGET="$ROOT"
  python3 "$SCRIPTS_DIR/patch-apiproxy.py" || echo "WARN: apiproxy patch failed; openPath falls back to native opener"
  python3 "$SCRIPTS_DIR/patch-webview-polyfill.py" || echo "WARN: webview polyfill patch failed"
fi

# [koffi] FFI 库：仅 glibc/x64 预编译。真实消费方只有 dsh-subprocess-local 的
# Win32 进程树强杀（Android 死代码），但其类型注册在模块顶层执行必须不抛错。
K="$NM/koffi"
# 0.2.0 起依赖树可能不含 koffi：set -euo pipefail 下 mv 失败会中断构建，必须守卫。
if [ -e "$K" ] || [ -e "$K.orig" ]; then
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
rm -rf "$K.orig"   # 原件已无用（Android 永不执行真实 FFI），留着只是白占 ~1.6MB
else
  echo "note: koffi absent from dependency tree (0.2.0+), inert stub skipped"
fi

# [node-pty] 缺 android 平台 .node 预编译。App 层已有自研 libdshpty.so，
# M2 将桥接；在此桥接前提供 API 兼容空壳，真实调用时显式报错。
P="$NM/node-pty"
# 同 koffi：依赖树不含 node-pty 时跳过打桩，避免 set -e 中断。
if [ -e "$P" ] || [ -e "$P.orig" ]; then
test -e "$P.orig" || mv "$P" "$P.orig"
mkdir -p "$P/lib"
printf '%s\n' '{"name":"node-pty","version":"0.0.0-android-shim","main":"lib/index.js"}' > "$P/package.json"
cat > "$P/lib/index.js" <<'JSEOF'
// Android shim until libdshpty.so bridge lands (roadmap M2).
module.exports.spawn = function () {
  throw new Error("node-pty unavailable in this Android build; PTY served by app-side libdshpty.so");
};
JSEOF
rm -rf "$P.orig"   # 同上：原包含多平台 prebuilds（~25.6MB），Android 用不到
else
  echo "note: node-pty absent from dependency tree (0.2.0+), shim skipped"
fi

# [@deepseek-ai/node-addon-system] 0.2.0 新增的 dsh 原生模块（Landlock 启动器 +
# POSIX flock），平台包只有 linux-x64/glibc+musl。
# flock.js 的 loadBinding() 显式拒绝非 linux/darwin 平台（抛
# ERR_FLOCK_UNSUPPORTED_PLATFORM），并按 `-${platform}-${arch}` 解析平台包 ——
# Android 上两者都不成立。而 dsh-session-persistence-jsonl 在【会话落盘】路径上
# 调用 tryLockExclusive，非 contention 的错误会被直接上抛 → 引擎启动即崩
# （v1.2.28 事故根因：引擎陷入重启循环）。
#
# Android 上改为直接 resolve（视为加锁成功）。安全性依据：
#   1) flock 是【跨进程】advisory 锁，而沙箱内始终只有一个引擎实例
#      （specialUse 前台服务单例，由 EngineSupervisor 保证），无跨进程竞争；
#   2) 调用方在加锁后仍独立比较 fd 与路径的 inode/dev（见 SessionWriteLease
#      获取路径），该检查与 flock 无关，仍能发现"文件被他人替换"。
# 契约保持与上游一致：不打开、不复制、不关闭 fd（所有权归调用方）。
# index.js（landlock-run）无需打桩：launcherPath() 自带 try/catch 回退，
# probe() 在 spawn 失败时返回 'unusable'，均为优雅降级。
NAS_DIR="$NM/@deepseek-ai/node-addon-system/lib"
if [ -d "$NAS_DIR" ]; then
  cat > "$NAS_DIR/flock.js" <<'JSEOF'
/** [dsh-android] Android flock 兼容层（覆盖上游预编译原生实现）。
 *
 * 上游 loadBinding() 仅接受 linux/darwin，且按 platform-arch 解析平台包；
 * Android 上调用即抛 ERR_FLOCK_UNSUPPORTED_PLATFORM。会话落盘路径上的
 * 该错误会直接上抛导致引擎启动崩溃，故此处改为直接 resolve。
 * 依据见 collect-termux-runtime.sh 中本文件的打桩说明（单引擎实例 +
 * 调用方另有 inode/dev 校验）。契约不变：fd 所有权仍归调用方。
 */
export async function tryLockExclusive(fd) {
    void fd;
    return;
}
JSEOF
  echo "node-addon-system/flock patched ok (android no-op; single-engine sandbox)"
else
  echo "note: @deepseek-ai/node-addon-system absent, flock patch skipped"
fi

# [dsh-attachment-local] Android 上 SELinux 禁止 link()，但本包两处 link 调用
# 语义不同，不能像 session-persistence 那样一律 rename（v1.2.56 事故，Issue #7）：
#   1) publishStagedObject: link(staged→target) 后紧跟 unlink(staged)——
#      rename 会移走暂存文件，unlink 必 ENOENT，发布整体被判失败；
#   2) publishImmutableAlias: source 是内容寻址正本 file-objects/<aa>/<sha256>，
#      同一 digest 可派生多个显示名别名——rename 会把正本挪走，其余别名悬空。
# 正确替代是 copyFile（源保留；目标为内容寻址名，覆盖写即同字节）。
# 实现：link as fsLink 导入 + 模块级 link 兜底函数——先试 fsLink（未来平台
# 放行时保持硬链接零拷贝），EACCES/EPERM/ENOTSUP/EXDEV/EMLINK/ENOSYS 退化
# copyFile；EEXIST 及其他错误原样上抛，上游靠 EEXIST 做"目标已存在"的
# digest 校验竞争分支，语义不变。
# 另：ensureDurableHome 的祖先遍历以文件系统根为边界（parse(home).root），
# Android 的 / 一律只读挂载（erofs/dm-verity），syncDirectory 对其 fsync 返回
# EINVAL → 首次存图即 ATTACHMENT_WRITE_FAILED，全部机型命中（Issue #7）。
# 只读挂载上目录项持久性本就无意义，EINVAL 容忍跳过，其余 errno 照旧上抛。
NAL="$NM/@deepseek-ai/dsh-attachment-local/lib/index.js"
if [ -f "$NAL" ]; then
  node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const oldImport = "import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";";
const newImport = "import { chmod, copyFile, link as fsLink, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";";
if (!s.includes(oldImport)) {
  console.error("attachment-local patch failed: import shape changed");
  process.exit(1);
}
const shim = [
"/* [dsh-android] Android SELinux forbids link(); degrade to copyFile (source kept).",
" * EEXIST is rethrown untouched - upstream verifies the digest of an existing target. */",
"const link = async (source, target) => {",
"\ttry {",
"\t\tawait fsLink(source, target);",
"\t} catch (error) {",
"\t\tconst code = error instanceof Error && \"code\" in error ? error.code : void 0;",
"\t\tif (code === \"EACCES\" || code === \"EPERM\" || code === \"ENOTSUP\" ||",
"\t\t\tcode === \"EXDEV\" || code === \"EMLINK\" || code === \"ENOSYS\") {",
"\t\t\tawait copyFile(source, target);",
"\t\t\treturn;",
"\t\t}",
"\t\tthrow error;",
"\t}",
"};"
].join("\n");
s = s.replace(oldImport, newImport + "\n" + shim);
const T = "\t";
const oldSync = [
T + "const handle = await open(path, constants.O_RDONLY);",
T + "try {",
T + T + "await handle.sync();",
T + "} finally {"
].join("\n");
const newSync = [
T + "const handle = await open(path, constants.O_RDONLY);",
T + "try {",
T + T + "await handle.sync();",
T + "} catch (error) {",
T + T + "/* [dsh-android] The durable-home ancestor walk reaches the filesystem root,",
T + T + " * which Android mounts read-only (erofs); fsync there rejects with EINVAL.",
T + T + " * Entry durability is meaningless on a read-only mount; tolerate EINVAL only. */",
T + T + "if (!(error instanceof Error && \"code\" in error && error.code === \"EINVAL\")) throw error;",
T + "} finally {"
].join("\n");
if (!s.includes(oldSync)) {
  console.error("attachment-local patch failed: syncDirectory shape changed");
  process.exit(1);
}
s = s.replace(oldSync, newSync);
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (!out.includes("const link = async") || !out.includes("error.code === \"EINVAL\"") || out.includes("rename as link")) {
  console.error("attachment-local patch failed: shim/EINVAL not installed");
  process.exit(1);
}
console.log("dsh-attachment-local patched ok: link shim (fsLink->copyFile) + syncDirectory EINVAL tolerance");
' "$NAL"
else
  echo "note: dsh-attachment-local absent, patch skipped"
fi

# [node-addon-require-builtin] 0.2.0 新增：dsh 用它访问 Node 内部模块
# （internal/modules/esm/loader 等），以便安装自定义模块解析拦截
# （dsh-app-boot 的 installRuntimeInterception，用于插件/profile 解析）。
# 上游实现是 node-addon-native-custom-loader 的预编译原生 addon，仅
# linux-x64-gnu 预编译；Android 上 createRequire(...)("node-addon-require-builtin")
# 会解析失败 —— 且 dsh-app-boot 的 internalModules() 【没有 try/catch】，
# 启动即抛错（与 flock 同属 v1.2.28 引擎重启事故的根因）。
#
# 改为纯 JS 兼容层：引擎启动已带 --expose-internals（EngineProcess.kt），
# 因此可直接 createRequire require 内部模块 ID —— 与上游 web bundle 里
# `if (execArgv.includes('--expose-internals')) return req(id)` 的 fallback 同源。
NRB_DIR="$NM/node-addon-require-builtin/lib"
if [ -d "$NRB_DIR" ]; then
  cat > "$NRB_DIR/index.js" <<'JSEOF'
"use strict";
/** [dsh-android] Android 兼容层：以纯 JS 替代预编译原生 addon。
 *
 * 上游经 node-addon-native-custom-loader 加载原生模块访问 Node 内部模块，
 * 仅 linux-x64 预编译，Android 无法加载。引擎启动已带 --expose-internals
 * （见 app 侧 EngineProcess.kt 的 node 参数），故直接用 createRequire
 * require 内部模块 ID，与上游 web 侧 fallback 逻辑一致。
 */
const { createRequire } = require("node:module");
const req = createRequire(__filename);
function requireBuiltin(moduleId) {
    return req(moduleId);
}
function isAllowedInternalId(moduleId) {
    return typeof moduleId === "string" && moduleId.startsWith("internal/");
}
function getBindingInfo() {
    return { backend: "android-js-fallback", via: "--expose-internals" };
}
exports.requireBuiltin = requireBuiltin;
exports.isAllowedInternalId = isAllowedInternalId;
exports.getBindingInfo = getBindingInfo;
exports.default = { requireBuiltin, isAllowedInternalId, getBindingInfo };
JSEOF
  echo "node-addon-require-builtin patched ok (pure-JS via --expose-internals)"
else
  echo "note: node-addon-require-builtin absent, patch skipped"
fi

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
if [ ! -f "$SP" ]; then
  echo "WARN: dsh-session-persistence-jsonl not in dependency tree (upstream restructure) - patch skipped"
else
node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const oldImport = "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";";
// rename 以 link 别名导入：0.2.0 里 link 的引用点不止一处 —— 既有
// link(tmp,finalPath) 调用，也有 defaultFileSystem 的 shorthand 属性 `link,`。
// 逐个调用点替换会漏（v1.2.29 内测踩坑：漏掉 shorthand → "link is not
// defined" → session-persistence-jsonl 导入失败 → 7 个插件 pending）。
// 别名导入让所有 link 引用自动获得 rename 行为；硬链接在 Android 上本被
// SELinux 禁止（EACCES），rename 的原子发布语义是合法替代。
const newImport = "import { rename as link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";";
if (!s.includes(oldImport)) {
  console.error("session persistence patch failed: fs/promises import shape changed");
  process.exit(1);
}
s = s.replace(oldImport, newImport);
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (!out.includes("rename as link")) {
  console.error("session persistence patch failed: rename-as-link not installed");
  process.exit(1);
}
console.log("session persistence patched ok: link -> rename (import alias)");
' "$SP"
fi

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
# corepack 家族（corepack/npm/npx/pnpm/pnpx/yarn/yarnpkg）：dist/*.js 的 shebang 硬编码
# Termux 绝对路径（#!/data/data/com.termux/files/usr/bin/env node）→ 直接调用报
# "No such file or directory"。
#
# ⚠️ 不能靠改 dist/*.js 的 shebang 解决（实测两种写法都错）：
#   · 在 shebang 后插 shell 注释/exec 行 → node 从第 2 行开始解析，报 SyntaxError
#     （shebang 只被内核识别，JS 解析器不看它）。
#   · 把 shebang 改成构建期绝对路径（$ROOT/bin/node）→ 该路径是 CI 临时目录，
#     runtime.zip 在设备上解压到别处，路径失效（且本文件不含任何构建期绝对路径）。
# 正解与 bin/pnpm 同款：生成**静态 shell wrapper**，用 $(dirname "$0") 自推导 node，
# 不含任何绝对路径，构建期/运行期路径无关。
make_corepack_wrapper() {
  local name="$1" entry="$2"   # entry = dist 下的 js 文件名
  local js="$ROOT/lib/node_modules/corepack/dist/$entry"
  [ -f "$js" ] || return 1
  cat > "$ROOT/bin/$name" <<SHEOF
#!/system/bin/sh
# Android corepack $name wrapper (shebang-safe, PATH-independent)
exec "\$(dirname "\$0")/node" "\$(dirname "\$0")/../lib/node_modules/corepack/dist/$entry" "\$@"
SHEOF
  chmod 0755 "$ROOT/bin/$name"
}
if [ -f "$ROOT/lib/node_modules/corepack/dist/corepack.js" ]; then
  for pair in "corepack:corepack.js" "npm:npm.js" "npx:npx.js" \
              "pnpm:pnpm.js" "pnpx:pnpx.js" "yarn:yarn.js" "yarnpkg:yarnpkg.js"; do
    make_corepack_wrapper "${pair%%:*}" "${pair##*:}" && echo "added bin/${pair%%:*} wrapper"
  done
  # shims/*：真 shell 脚本，shebang 换 /system/bin/sh 即可（脚本体走 basedir 自推导，
  # 本身是 PATH 无关的；只有 shebang 指向 Termux 会挂）
  shims_fixed=0
  for f in "$ROOT/lib/node_modules/corepack/shims"/*; do
    [ -f "$f" ] || continue
    case "$f" in *.cmd|*.ps1) continue ;; esac
    case "$(head -n 1 "$f" 2>/dev/null)" in
      *com.termux*) sed -i '1s|.*|#!/system/bin/sh|' "$f" && chmod 0755 "$f" && shims_fixed=$((shims_fixed+1)) ;;
    esac
  done
  echo "corepack shims shebang fixed: $shims_fixed"
else
  echo "错误：corepack/dist/corepack.js 不存在" >&2
  exit 1
fi

# curl：系统 /system/bin/curl 链接旧 OpenSSL（缺 EVP_MD_CTX_CREATE）不可用；
# node fetch 垫片，sh 侧解析参数 + 环境变量传值（node 不接触原始 argv）。
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
    // 二进制安全（v1.2.51 修复）：必须用 arrayBuffer + Buffer 写出。
    // 旧实现用 r.text()：按 UTF-8 解码响应，非 UTF-8 字节被替换为 U+FFFD，
    // 再编码写回时每字符 3 字节 → 13MB 的 jar 下成 26.8MB 且含 466 万个 U+FFFD
    // （Agent 实测：用 curl -o 取 android.jar / d8.jar 后文件损坏不可用）。
    //
    // ⚠️ -s 语义修正（v1.2.52，Agent 审计 N2）：真实 curl 的 -s 只静默**进度条**，
    // 响应体照常写 stdout。旧实现把它当成"整段不回显"→ `curl -s URL` 返回 0 字节，
    // 而种子文档教的正是这个写法（`curl -s http://127.0.0.1:3083/ext/list`）→ agent
    // 会误判"扩展中心没响应"。现 -s 只抑制 stderr 错误输出，响应体始终输出。
    if(out){
      require("fs").writeFileSync(out,Buffer.from(await r.arrayBuffer()));
    }else{
      process.stdout.write(Buffer.from(await r.arrayBuffer()));
    }
    process.exit(r.ok?0:1);
  }catch(e){if(!process.env.CURL_SILENT)console.error(e.message);process.exit(2)}
})()
'
SHEOF
chmod 0755 "$ROOT/bin/curl"
echo "added bin/curl wrapper -> node fetch (env-passing)"

# ---- 3.9 engine/bin 里 Termux shim 的 shebang 归一（v1.2.52，Agent 审计 N6）----
# Termux 在 bin/ 放了一批"清环境后 exec 系统命令"的 shim（pm/settings/getprop/logcat/
# df/ping/su/termux-* 等 30 个），内容正确且必要（unset LD_LIBRARY_PATH 防引擎的
# bionic 库污染系统命令），但 shebang 指向 /data/data/com.termux/files/usr/bin/sh ——
# 该路径在扩展/normal 模式下不存在（Termux 数据目录不可读），报
# "bad interpreter: Permission denied"（exit 126）。
#
# ⚠️ 不能简单删掉这些 shim：它们遮蔽 /system/bin 同名命令是**有意的**（PATH 里 engine/bin
# 优先），删了会让系统命令继承引擎的 LD_LIBRARY_PATH → 加载错误版本的 libc 而崩溃。
# 正解：只把 shebang 改指 /system/bin/sh（toybox，这些脚本只用 POSIX 基本语法，
# 不需要 bash），其余内容一字不动。
fixed_bins=0
for f in "$ROOT/bin"/*; do
  [ -f "$f" ] || continue
  case "$f" in *.exe|*.dll) continue ;; esac
  head -c 2 "$f" 2>/dev/null | grep -q '#!' || continue
  case "$(head -n 1 "$f" 2>/dev/null)" in
    *com.termux*)
      sed -i '1s|.*|#!/system/bin/sh|' "$f" && chmod 0755 "$f" && fixed_bins=$((fixed_bins+1)) ;;
  esac
done
echo "engine/bin Termux shebangs fixed: $fixed_bins"

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

# 平台专用二进制清理：CI 跑在 Ubuntu x86_64，npm 会按【该平台】安装
# optionalDependencies，于是包里混进了 linux-x64 的 .node/.so ——
# 这些是 ELF x86-64，Android arm64 上根本无法 dlopen，属纯浪费（~50MB）。
# sharp 在 Android 走同 scope 下的 sharp-wasm32 回退（已保留），故删除安全；
# sherpa-onnx 属实验性语音输入，移动端本就不加载。
rm -rf "$ROOT/lib/node_modules/sherpa-onnx-linux-x64" \
       "$ROOT/lib/node_modules/@img/sharp-linux-x64" \
       "$ROOT/lib/node_modules/@img/sharp-libvips-linux-x64" \
       "$ROOT/lib/node_modules/node-addon-require-builtin-linux-x64-gnu" 2>/dev/null || true

# ---- 5. 打 zip ----
( cd "$ROOT" && zip -qr "$OUT_ZIP" . )

echo "runtime.zip 已生成: $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "SHA-256: $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
