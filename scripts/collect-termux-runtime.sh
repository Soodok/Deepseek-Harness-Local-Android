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

# 脚本自身所在目录（绝对路径，必须在任何 cd 之前解析）。
#
# ⚠️ v1.4.17 CI 事故（run 108687827752，两个架构全挂，exit 127）：
#   本脚本第 64/102 行会 cd 进 mktemp 工作区，而 CI 用相对路径调用
#   （./scripts/collect-termux-runtime.sh），此时 $0 也是相对路径。
#   旧代码在内置插件注入处写 `bash "$(dirname "$0")/inject-hardssh-plugin.sh"`，
#   dirname 得到 "./scripts"，按当前 cwd（$WORK/bundle）解析 → 文件不存在 →
#   bash 报 "No such file or directory"（127）。同理，任何在 cd 之后才求值的
#   相对路径都会漂移。
#   因此：所有对同级脚本的引用一律走 SCRIPT_DIR（绝对路径），不得再用 $0。
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[ -d "$SCRIPT_DIR" ] || { echo "错误：无法解析脚本目录（$0）" >&2; exit 1; }

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
# v1.5.0 升级到 0.2.1-rc.5：再逐包核验，**全部补丁目标包的 lib/index.js 与
# rc.2 字节完全相同**（subprocess-local/fs-local/attachment-local/ptc-runtime-node/
# session-persistence-jsonl/sandbox-local/node-addon-system/plugin-manager/app-boot
# 九个包 sha256 一致），依赖清单也是同样的 86 项，仅版本号整体抬升 ——
# 故所有锚点天然仍然命中，无需改动任何补丁。
DSH_VERSION="${DSH_VERSION:-0.2.1-rc.5}"
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
# 补丁脚本目录：一律由 SCRIPT_DIR（绝对路径）派生。
# 不要再回退到 "$GITHUB_WORKSPACE/scripts" 或 `dirname "$0"` —— 此刻 cwd 已是
# $WORK，相对路径必然漂移，而 GITHUB_WORKSPACE 只在 CI 里存在（本地手工跑
# 会静默跳过补丁，再在后面的 fail-fast 断言处报出与真实原因无关的错误）。
SCRIPTS_DIR="$SCRIPT_DIR"
[ -d "$SCRIPTS_DIR" ] || { echo "错误：补丁脚本目录不存在（$SCRIPTS_DIR）" >&2; exit 1; }

if command -v python3 >/dev/null 2>&1; then
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

# [dsh-attachment-local] Android 两个适配点，完整说明见
# scripts/patch-attachment-local.py 顶部（**单一真源**：本地改包与 CI 同用一个脚本）：
#   ① SELinux 禁 link() → `link as fsLink` + 模块级 shim 退化 copyFile（源保留）。
#      早年的 `rename as link` 是错的：rename 会**移走**源文件，publishStagedObject
#      之后的 unlink(staged) 必 ENOENT、publishImmutableAlias 的内容寻址正本被挪走
#      让其余别名悬空（v1.2.56 事故）。
#   ② ensureDurableHome 逐级 sync 的祖先遍历会爬出 app 沙箱：
#      普通模式在 /data/data 撞 EACCES（SELinux 不让 app 域 open 那个目录），
#      root 模式在 / 撞 EINVAL/EROFS（只读 erofs/dm-verity）→ 首次存图即
#      ATTACHMENT_WRITE_FAILED（Issue #7）。这两级的持久性不由我们负责，
#      容忍跳过；其余 errno 照旧上抛。
# fail-fast：形态识别不了必须中止构建 —— 静默漏打补丁等于把「存图即失败」的包发给用户。
python3 "$SCRIPTS_DIR/patch-attachment-local.py" "$ROOT" || {
  echo "错误：dsh-attachment-local 补丁失败（上游形态已变？），中止构建" >&2
  exit 1
}

# [dsh-fs-local] Android 共享存储的权限语义（issue #25 后续：安卓14 LOS
# 「文件资源服务不可用」/ 目录反复刷新）：
#   · probeStats 只容忍 ENOENT/ENOTDIR，其余 errno 直接上抛 —— 而 Android 的
#     FUSE 共享存储即使有 MANAGE_EXTERNAL_STORAGE 也会对某些目录项返回 EACCES
#     （SELinux 域迁移 / volume 重新挂载 / media 属主 uid），于是整条
#     resolve→stat→locateFile 链失败 → 前端显示「文件资源服务不可用」。
#   · listDirectory 对每个子项单独 resolve+probe，一个不可读项就 throw，把
#     「某一项读不了」扩散成「整个目录打不开」。
# 补丁把 EACCES/EPERM 当作「无法观测」（目录列举时降级为 type=other），
# 其余 errno 照旧上抛。详细说明见 scripts/patch-fs-local-android.py 顶部。
python3 "$SCRIPTS_DIR/patch-fs-local-android.py" "$ROOT" || {
  echo "错误：dsh-fs-local Android 补丁失败（上游形态已变？），中止构建" >&2
  exit 1
}

# [dsh-ptc-runtime-node] PTC run_code 子进程保留 LD_LIBRARY_PATH（issue #29）：
# 该包拉子进程时清空继承环境、只留 STARTUP_ENVIRONMENT_NAMES 白名单
# （PATH/PATHEXT/SYSTEMROOT/WINDIR/TEMP/TMP）。Linux 靠 ldconfig 找 libz，
# 不需要 LD_LIBRARY_PATH；Android 的 Termux node 库全在 runtime 的 lib/ 下，
# 一旦被清掉 → `CANNOT LINK EXECUTABLE ... libz.so.1 not found`，主进程正常
# 但所有子进程起不来。补丁把它加入白名单。详见 scripts/patch-ptc-runtime-android.py。
python3 "$SCRIPTS_DIR/patch-ptc-runtime-android.py" "$ROOT" || {
  echo "错误：dsh-ptc-runtime-node Android 补丁失败（上游形态已变？），中止构建" >&2
  exit 1
}

# [node-pty / subprocess-local] Android 终端支持（issue #18）：
#   · node-pty 无 Android 预编译产物 → 换成本仓库实现（转发原生助手 dsh_pty_exec）
#   · subprocess-local 的进程探测器只认 linux/darwin/win32，Termux node 报告的是
#     "android" → 终端 spawn 直接抛 "terminal inspection is unsupported"
# 两处都必须打，否则 Web 终端与持久 bash 工具在设备上不可用。
python3 "$SCRIPTS_DIR/patch-node-pty-android.py" "$ROOT" || {
  echo "错误：node-pty Android 补丁失败（上游形态已变？），中止构建" >&2
  exit 1
}

# [bash SYS_BASHRC] Termux 构建的 bash 把系统 rc 硬编码为
# /data/data/com.termux/files/usr/etc/bash.bashrc —— 编译期宏、无环境变量
# 旁路。真机装有 Termux 时交互式 bash 启动必报
# "Permission denied"（issue #38）。等长 patch 成 /dev/null；同时把
# bin/pkg（Termux apt wrapper，本 runtime 必然失败）换成 pnpm/npm 提示。
python3 "$SCRIPTS_DIR/patch-bash-sysbashrc.py" "$ROOT" || {
  echo "错误：bash SYS_BASHRC 补丁失败（上游形态已变？），中止构建" >&2
  exit 1
}

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

# [dsh-session-persistence-jsonl/worker.cjs] 迁移校验 worker（CJS）自带一份
# defaultFileSystem，其中 `link: node_fs_promises.link` 直取真 link —— ESM 侧的
# 别名补丁覆盖不到 CJS 打包副本。当前 worker 入口只跑 verify()（只读校验），
# prepare/publishCurrentExclusive 未接线，故 link 暂不可达；但上游只要把迁移
# 准备接进 worker 就会当场复活 EACCES 崩溃。此处按"目标已存在不覆盖"的语义
# 兜底：先试真 link，Android 拒绝时退化为 COPYFILE_EXCL 复制（源保留、EEXIST
# 照旧上抛，上游据此做已存在对象的 digest 校验）。
SPW="$NM/@deepseek-ai/dsh-session-persistence-jsonl/lib/worker.cjs"
if [ -f "$SPW" ]; then
  node -e '
const fs = require("fs");
const p = process.argv[1];
let s = fs.readFileSync(p, "utf8");
const target = "\tlink: node_fs_promises.link,";
if (!s.includes(target)) {
  console.error("jsonl worker.cjs patch failed: defaultFileSystem link property changed");
  process.exit(1);
}
const repl = [
"\tlink: async (source, target) => {",
"\t\t/* [dsh-android] SELinux forbids link(); COPYFILE_EXCL keeps no-clobber (EEXIST) semantics. */",
"\t\ttry {",
"\t\t\tawait node_fs_promises.link(source, target);",
"\t\t} catch (error) {",
"\t\t\tconst code = error instanceof Error && \"code\" in error ? error.code : void 0;",
"\t\t\tif (code === \"EACCES\" || code === \"EPERM\" || code === \"ENOTSUP\" ||",
"\t\t\t\tcode === \"EXDEV\" || code === \"EMLINK\" || code === \"ENOSYS\") {",
"\t\t\t\tawait node_fs_promises.copyFile(source, target, node_fs.constants.COPYFILE_EXCL);",
"\t\t\t\treturn;",
"\t\t\t}",
"\t\t\tthrow error;",
"\t\t}",
"\t},"
].join("\n");
// worker.cjs 顶部 require("node:fs/promises") 无 node:fs；补一个 require 供 constants
if (!s.includes("require(\"node:fs\")")) {
  s = s.replace("let node_fs_promises = require(\"node:fs/promises\");",
                "let node_fs_promises = require(\"node:fs/promises\");\nlet node_fs = require(\"node:fs\");");
}
s = s.replace(target, repl);
fs.writeFileSync(p, s);
const out = fs.readFileSync(p, "utf8");
if (!out.includes("COPYFILE_EXCL") || !out.includes("let node_fs = require")) {
  console.error("jsonl worker.cjs patch failed: link shim not installed");
  process.exit(1);
}
console.log("jsonl worker.cjs patched ok: link shim (COPYFILE_EXCL fallback)");
' "$SPW"
else
  echo "note: jsonl worker.cjs absent, patch skipped"
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
#
# ⚠️⚠️ 必须先 rm 再写（v1.4.9，用户实测「corepack.js 被改成 shell 脚本」）：
#
# Termux 的 npm 用**硬链接**把 bin/<name> 链到 dist/<name>.js（同一 inode）。
# 本函数原来直接 `cat > "$ROOT/bin/$name"`，而 shell 的 `>` 是 **truncate 已存在
# 的文件**，不是新建 —— 硬链接下 truncate 写的是**共享 inode**，于是 bin/corepack
# 的 wrapper 内容被同时写进了 lib/node_modules/corepack/dist/corepack.js。
#
# 后果（用户报告）：dist/corepack.js 不再是 JS，而是一段 exec 自己的 shell 脚本
#   · bin/corepack 用 node 执行 → SyntaxError
#   · 直接执行 → exec 目标是自己 → 无限自循环
# 而 npm/npx/pnpm 等其余 6 个没坏 —— 因为它们的 bin 条目和 dist 条目不共享 inode，
# 唯独 corepack 这一对是硬链接。这也解释了为什么只有它一个坏。
#
# 修法：先 `rm -f` 断开目录项（inode 引用计数减一，dist/*.js 保留原内容），
# 再创建全新的普通文件。写完校验：目标必须是普通文件、源 JS 必须仍是 JS。
make_corepack_wrapper() {
  local name="$1" entry="$2"   # entry = dist 下的 js 文件名
  local js="$ROOT/lib/node_modules/corepack/dist/$entry"
  local dst="$ROOT/bin/$name"
  [ -f "$js" ] || return 1
  # 断链：确保 dst 是独立 inode（对普通文件是无害的 no-op）
  rm -f "$dst"
  cat > "$dst" <<SHEOF
#!/system/bin/sh
# Android corepack $name wrapper (shebang-safe, PATH-independent)
exec "\$(dirname "\$0")/node" "\$(dirname "\$0")/../lib/node_modules/corepack/dist/$entry" "\$@"
SHEOF
  chmod 0755 "$dst"
  # 写后校验：源 JS 不得被这次写入污染（硬链接事故的回归探针）。
  # 正常情况下 dist/$entry 的第一行是 Termux/node 的 shebang 或 JS 代码；
  # 若变成 /system/bin/sh，说明这次写入又写穿了 -- 立即中止构建，别把坏包发出去。
  if [ "$(head -n 1 "$js" 2>/dev/null)" = "#!/system/bin/sh" ]; then
    echo "错误：写入 bin/$name 时污染了 dist/$entry（硬链接写穿）。" >&2
    echo "      该文件现在是 shell 脚本，node 无法执行它。" >&2
    exit 1
  fi
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
TIMEOUT=""
INCLUDE=""
FAIL=""
for word in "$@"; do
  if [ -n "$nextval" ]; then
    case "$nextval" in
      -o|--output) OUT="$word" ;;
      -X|--request) METHOD="$word" ;;
      -H|--header) HDRS="$HDRS$word\n" ;;
      -d|--data|--data-raw|--data-binary) DATA="$word" ;;
      -m|--max-time|--connect-timeout) TIMEOUT="$word" ;;
      -w|--write-out|--retry|--retry-delay|-A|--user-agent|-e|--referer|-b|--cookie|-u|--user|--proxy) : ;;
    esac
    nextval=""
    continue
  fi
  case "$word" in
    -o|--output|-X|--request|-H|--header|-d|--data|--data-raw|--data-binary) nextval="$word" ;;
    -m|--max-time|--connect-timeout) nextval="$word" ;;
    -w|--write-out|--retry|--retry-delay|-A|--user-agent|-e|--referer|-b|--cookie|-u|--user|--proxy) nextval="$word" ;;
    -s|-sS|-S|--silent) SILENT=1 ;;
    -i|--include) INCLUDE=1 ;;
    -f|--fail) FAIL=1 ;;
    -L|--location|-k|--insecure|--compressed|-g|--globoff) : ;;
    -*) : ;;
    *) if [ -z "$URL" ]; then URL="$word"; fi ;;
  esac
done
export CURL_URL="$URL" CURL_METHOD="$METHOD" CURL_OUT="$OUT" \
  CURL_SILENT="$SILENT" CURL_DATA="$DATA" CURL_HDRS="$HDRS" \
  CURL_TIMEOUT="$TIMEOUT" CURL_INCLUDE="$INCLUDE" CURL_FAIL="$FAIL"
PREFIX="$(cd "$(dirname "$0")/.." && pwd)"
# node 是动态链接的 bionic 二进制：缺 LD_LIBRARY_PATH 时连 libz.so.1 都找不到
# （v1.2.100 实测：独立调用 curl 时报 CANNOT LINK ... libz.so.1 not found）。
# 引擎子进程本来就有这个变量，但闸门脚本/独立调用者不一定有 —— 这里自己补。
export LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$(dirname "$0")/node" -e '
(async()=>{
  try{
    const url=process.env.CURL_URL.trim();
    const h={};
    // sh 双引号内 \n 是字面反斜杠+n，故 JS 侧也按字面 \\n 切分
    (process.env.CURL_HDRS||"").split("\\n").filter(Boolean).forEach(l=>{const c=l.indexOf(":");if(c>0)h[l.slice(0,c).trim()]=l.slice(c+1).trim()});
    const d=process.env.CURL_DATA||null;
    const m=(d&&!process.env.CURL_METHOD)?"POST":(process.env.CURL_METHOD||"GET");
    // 超时（v1.2.100）：-m / --connect-timeout 映射到这里（真 curl 默认无限等，
    // 但 agent 常写 `-m 5` 防挂死，此前该参数被忽略 → 挂死时无法自保）
    const tmo=parseInt(process.env.CURL_TIMEOUT||"",10);
    const ac=new AbortController();
    if(tmo>0)setTimeout(()=>ac.abort(),tmo*1000);
    const r=await fetch(url,{method:m,headers:h,body:d||void 0,signal:ac.signal});
    const buf=Buffer.from(await r.arrayBuffer());
    // -i：响应头 + 空行 + 正文（与真 curl 形状一致，v1.2.100）
    const body=process.env.CURL_INCLUDE
      ? Buffer.concat([
          Buffer.from("HTTP/"+r.status+" "+r.statusText+"\n"+
            [...r.headers.entries()].map(([k,v])=>k+": "+v).join("\n")+"\n\n","utf8"),
          buf,
        ])
      : buf;
    // ⚠️ v1.2.100：`out` 是 **node 侧未定义变量**（其值在环境变量 CURL_OUT 里，
    // 由外层 sh 解析后 export）。旧写法 `if(out)` 触发 ReferenceError → 被 catch
    // → 恒 exit 2，即 `curl -o 文件 URL` 必然失败。独立审查发现 collect 版漏改
    // （patch-curl-wrapper.py 里是正确的）——而 CI 只跑 collect，故此 bug 会随
    // 每次发布流向用户。两处必须保持一致。
    const outFile = process.env.CURL_OUT || "";
    if(outFile){
      require("fs").writeFileSync(outFile,body);
    }else{
      process.stdout.write(body);
    }
    // -f：HTTP >=400 以 22 退出（与 curl 一致）；不带 -f 时 >=400 退 1（保持原语义）
    process.exit((process.env.CURL_FAIL&&!r.ok)?22:(r.ok?0:1));
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

# ---- 4.5 内置 SSH 远程工作区插件（v1.4.20 起已解耦）----
# 插件不再进入 runtime.zip：运行时会被云端单独更新/重装，而重装会清 engine/lib，
# 插件若在里面就会随运行时更新被删（或反之把运行时绑死在插件版本上）。
# 现在由 scripts/pack-hardssh.sh 单独组装 APK 的 assets/hardssh/，App 启动时
# 由 HardsshInstaller 补装。这里只保留一条提醒，避免有人误把注入逻辑加回来。
echo "note: hardssh 已与 runtime 解耦（APK assets/hardssh/，见 scripts/pack-hardssh.sh）"

# ---- 5. 打 zip ----
#
# ⚠️ 出包前**硬断言 node 架构**（v1.3.3，issue #23）。
# 一旦把 x86_64 的 node 打进 arm64 的 APK，症状是「装完就是坏的」：
# 引擎 execve 被 linker 拒绝（EXEC format error）→ 无限重启 → 用户只能卸载。
# 而 App 侧完整性探针看不到架构，会把它当「安装完整」，永远不自我修复。
# 这类错误一旦发出去就是确定性事故，所以在这里挡住，不许静默通过。
NODE_BIN="$ROOT/bin/node"
if [ ! -f "$NODE_BIN" ]; then
  echo "错误：runtime 内缺少 bin/node，中止打包" >&2
  exit 1
fi
# ELF e_machine 位于文件偏移 18，小端 2 字节
NODE_MACHINE=$(od -An -t u2 -j 18 -N 2 "$NODE_BIN" 2>/dev/null | tr -d ' \n')
case "$ARCH" in
  aarch64) EXPECT_MACHINE=183 ;;
  x86_64)  EXPECT_MACHINE=62  ;;
  *)       EXPECT_MACHINE=""   ;;
esac
if [ -n "$EXPECT_MACHINE" ] && [ "$NODE_MACHINE" != "$EXPECT_MACHINE" ]; then
  echo "错误：bin/node 架构不匹配 —— ARCH=$ARCH, e_machine=$NODE_MACHINE (期望 $EXPECT_MACHINE)" >&2
  echo "      （183=AArch64, 62=x86_64；发出去会导致该架构设备完全无法启动）" >&2
  exit 1
fi
echo "架构校验通过：ARCH=$ARCH, bin/node e_machine=$NODE_MACHINE"

( cd "$ROOT" && zip -qr "$OUT_ZIP" . )

echo "runtime.zip 已生成: $OUT_ZIP ($(du -h "$OUT_ZIP" | cut -f1))"
echo "SHA-256: $(sha256sum "$OUT_ZIP" | cut -d' ' -f1)"
