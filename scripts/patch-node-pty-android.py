#!/usr/bin/env python3
"""给 dsh 引擎补上 Android 的终端（PTY）支持 —— issue #18 第二层。

## 为什么需要
`@deepseek-ai/dsh-subprocess-local` 是 Web 终端与持久 bash 工具的执行底座，
它在两处不认识 Android：

1. **进程探测器拒收该平台**。`createProcessInspector()` 只认 linux/darwin/win32，
   其它平台直接抛 `terminal inspection is unsupported on platform android`。
   Termux 构建的 node 报告 `process.platform === "android"`（不是 "linux"），
   于是任何终端的 spawn 都在这一步就失败。Android 有完整 procfs，
   上游的 LinuxProcessInspector 本来就是对的实现 —— 只是没被选上。

2. **node-pty 是空壳**。node-pty 无 Android 预编译产物，官方收集脚本把它换成
   「一调用就抛错」的占位实现（见 collect-termux-runtime.sh）。真实 PTY 能力
   由 App 侧原生助手 `dsh_pty_exec` 提供（app/src/main/cpp/dsh_pty_exec.c），
   本脚本把 node-pty 换成转发到该助手的兼容实现。

## 产物
- runner-launch-*.js：探测器认 android（= linux 语义）
- index.js：活动状态判定同样认 android
- node-pty/lib/index.js：真实 Android 实现（转发 dsh_pty_exec）
- node-pty/package.json：版本标记改成 android 实现（幂等判据）

## 用法
    python scripts/patch-node-pty-android.py <runtime 根目录|runtime.zip> [更多...]
    python scripts/patch-node-pty-android.py <runtime.zip> --backup-dir _runtime_bak

幂等：按 node-pty/package.json 的 version 标记 + 补丁后的 JS 内容判断。
以下任一情况都会**非零退出**，不再 WARN 后假装成功（漏打补丁 = 终端对用户不可用）：
  · subprocess-local 包/目录/文件缺失；
  · 两个 Android 锚点任一未命中（上游重构导致形态变化）；
  · node-pty 包缺失，或实现文件无法写入；
  · zip 模式中同样的任一目标缺失。
"""
import json
import os
import shutil
import sys
import zipfile

MARKER_VERSION = "0.0.0-android-ptyexec"

# node-pty 兼容实现（单一真源）。放到独立文件是为了避免与 Kotlin/Python 的
# 多层字符串转义纠缠 —— 内容原样写入 runtime。
NODE_PTY_IMPL = r'''/*
 * node-pty Android 后端（roadmap M2）。
 *
 * 为什么需要这个文件
 * ------------------
 * dsh 的 Web 终端与持久 bash 工具都经 `@deepseek-ai/dsh-subprocess-local`
 * 调 `require("node-pty").spawn(...)` 申请一个**新的**伪终端。node-pty 没有
 * Android 预编译产物，官方 runtime 收集脚本会把它替换成「一调用就抛错」的
 * 空壳（见 scripts/collect-termux-runtime.sh）——于是终端功能在 Android 上
 * 整体不可用（issue #18）。
 *
 * 本文件就是那个空壳的**真实实现**：把 spawn 转发给随 APK 分发的原生助手
 * `dsh_pty_exec`（app/src/main/cpp/dsh_pty_exec.c），由它 fork 出挂 PTY 的
 * shell，并在 PTY 与本进程的管道之间搬运字节。
 *
 * 进程拓扑
 * --------
 *   node 引擎 ── spawn ──> dsh_pty_exec ── fork+PTY ──> bash
 *                stdin/stdout 管道     PTY master↔slave
 *                fd3 = 指令下行（R/K）
 *                pidfile = pid/退出状态上行
 *
 * 维护提示
 * --------
 * 这个文件由 scripts/patch-node-pty-android.py 写入 runtime，**单一真源**是
 * 那个脚本（CI 的 collect-termux-runtime.sh 也调它）。改这里等于只改了一个
 * 副本，下次重建 runtime 会被覆盖。
 */
"use strict";

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const { EventEmitter } = require("events");

/** 助手相对引擎根的位置（EngineConfig 部署到 <engineRoot>/bin/dsh_pty_exec）。 */
const EXEC_NAME = "dsh_pty_exec";

/** pidfile 参数名（与 dsh_pty_exec.c 的解析一致）。 */
const PIDFILE_FLAG = "--dsh-pty-pidfile";

/**
 * 容器包装：把「在宿主命名空间里 exec program」改写成「经 dsh_alpine 进容器再 exec」。
 *
 * ## 为什么必须包
 *
 * `dsh_pty_exec` 是 **bionic（NDK）** 二进制，本身被 loader 判定成
 * `guest_is_bionic()` → 交给宿主侧 cleaner 代跑 → 它 fork 出的子进程
 * **不带容器的 bind 视图**。于是 `spawn('/bin/sh')` 拿到的是宿主的
 * `/system/bin/sh`：实测（模拟器 emulator-5554 / 以 App uid 在容器内跑）
 * `readlink /proc/$$/exe` = `/system/bin/sh`、`[ -d /etc/apk ]` = NO、
 * `PATH` 全是 `/product/bin:/apex/...`。用户症状是「Linux 模式终端里
 * apk/gcc 都用不了」。
 *
 * 包装后再 exec，子进程是 musl 容器里的 shell：同一实测口径下
 * `PWD=/ws`、`cat /etc/alpine-release` = `3.22.2`。
 *
 * ## 参数从哪来
 *
 * Kotlin 侧 `LinuxEngineLauncher.buildTerminalArgv` 构造的是同一套容器 argv；
 * 这里**不重复实现**，而是读它写进环境的四个量（DSH_TERMINAL_*）。
 * 缺任一必需量说明不是容器模式（原生模式、或老版本 App）→ 返回 undefined
 * 由调用方原样直通，行为与之前完全一致。
 *
 * @param {string} file shell 绝对路径（引擎经 resolveShell 得到，如 /bin/sh）
 * @param {string[]} args shell 参数（如 ["-i"]）
 * @returns {{program: string, argv: string[]}|undefined}
 */
function containerWrap(file, args) {
  const runner = process.env.DSH_TERMINAL_RUNNER;
  const rootfs = process.env.DSH_TERMINAL_ROOTFS;
  const interp = process.env.DSH_TERMINAL_INTERP;
  if (!runner || !rootfs || !interp) return undefined;
  // runner 不可执行时不要包：包了只会让终端彻底起不来，退回旧行为至少还能敲宿主命令。
  try {
    fs.accessSync(runner, fs.constants.X_OK);
  } catch (_) {
    return undefined;
  }
  const binds = [];
  const rawBinds = process.env.DSH_TERMINAL_BINDS;
  if (rawBinds) {
    // 格式：每行 "<guest>:<host>"。换行分隔而不是空格，路径里可能带空格。
    for (const line of rawBinds.split("\n")) {
      if (line.length === 0) continue;
      const cut = line.indexOf(":");
      if (cut <= 0) continue;
      binds.push("-m", line);
    }
  }
  // dsh_alpine 的 CLI：dsh_alpine [-m g:h]... <rootfs> <interp> <guest> [args...]
  // loader 把 &argv[base+2] 原样交给 guest，所以 file 与它的参数直接接上即可。
  return { program: runner, argv: binds.concat([rootfs, interp, file], args || []) };
}

/** 定位助手：优先 PREFIX（引擎根，由 Kotlin 注入），再退回相对本模块的推断。 */
function locateHelper() {
  const candidates = [];
  const prefix = process.env.PREFIX;
  if (prefix) candidates.push(path.join(prefix, "bin", EXEC_NAME));
  // node_modules/node-pty/lib/index.js → 上溯到 runtime 根
  candidates.push(path.resolve(__dirname, "..", "..", "..", "bin", EXEC_NAME));
  candidates.push(path.resolve(__dirname, "..", "..", "..", "..", "bin", EXEC_NAME));
  for (const c of candidates) {
    try {
      fs.accessSync(c, fs.constants.X_OK);
      return c;
    } catch (_) {
      /* 继续找下一个候选 */
    }
  }
  return undefined;
}

/** 同步睡眠：只用于 spawn 后取 pid、退出后取状态这两个极短窗口。 */
function sleepMs(ms) {
  const shared = new SharedArrayBuffer(4);
  Atomics.wait(new Int32Array(shared), 0, 0, ms);
}

/** 把 pidfile 读成 { pid } 或 { exitCode, signal }；读不到返回 undefined。 */
function readStatus(pidfile) {
  try {
    const text = fs.readFileSync(pidfile, "utf8").trim();
    if (text.startsWith("EXIT ")) {
      const parts = text.split(/\s+/);
      const code = Number.parseInt(parts[1], 10);
      const sig = Number.parseInt(parts[2], 10);
      return {
        exitCode: Number.isFinite(code) ? code : 0,
        signal: Number.isFinite(sig) && sig > 0 ? sig : undefined,
      };
    }
    const pid = Number.parseInt(text, 10);
    return Number.isFinite(pid) && pid > 0 ? { pid } : undefined;
  } catch (_) {
    return undefined;
  }
}

/** node-pty 兼容终端：接口面 = 上游 LocalTerminalHandle 实际用到的成员。 */
class AndroidTerminal extends EventEmitter {
  constructor(child, pidfile, file, cols, rows) {
    super();
    this.child = child;
    this.pidfile = pidfile;
    this._file = file;
    this._cols = cols;
    this._rows = rows;
    this._killTimer = undefined;
    this._exited = false;
    this._paused = false;

    // ⚠️ pid 必须**同步**可得：LocalTerminalHandle 在构造时就读 terminal.pid。
    // 助手 fork 完才写文件，这里短轮询等它（上限 2s，足够 fork 完成）。
    let status;
    for (let i = 0; i < 200; i++) {
      status = readStatus(pidfile);
      if (status !== undefined && status.pid !== undefined) break;
      sleepMs(10);
    }
    this.pid = status !== undefined && status.pid !== undefined ? status.pid : 0;

    this._onStdout = (buf) => {
      if (!this._paused) this.emit("data", buf.toString("utf8"));
    };
    child.stdout.on("data", this._onStdout);
    child.on("exit", () => this._settle());
    child.on("error", () => this._settle());
  }

  /** 子进程退出：从 pidfile 取真实退出码/信号（字节流已全部走 stdout）。 */
  _settle() {
    if (this._exited) return;
    this._exited = true;
    let exitCode = 0;
    let signal;
    // 助手写完状态才退出：稍等一下确保文件已落盘
    for (let i = 0; i < 100; i++) {
      const st = readStatus(this.pidfile);
      if (st !== undefined && st.exitCode !== undefined) {
        exitCode = st.exitCode;
        signal = st.signal;
        break;
      }
      sleepMs(5);
    }
    try {
      fs.unlinkSync(this.pidfile);
    } catch (_) {
      /* 清理失败无碍 */
    }
    this.emit("exit", { exitCode, signal });
  }

  onData(cb) {
    this.on("data", cb);
    return { dispose: () => this.off("data", cb) };
  }

  onExit(cb) {
    this.on("exit", cb);
    return { dispose: () => this.off("exit", cb) };
  }

  write(data) {
    if (this._exited) return;
    try {
      this.child.stdin.write(data);
    } catch (_) {
      /* 管道已关：忽略，退出事件会随后到达 */
    }
  }

  resize(cols, rows) {
    if (this._exited) return;
    this._cols = cols;
    this._rows = rows;
    this._control(`R ${cols} ${rows}`);
  }

  pause() {
    this._paused = true;
  }

  resume() {
    this._paused = false;
  }

  kill(signal) {
    if (this._exited) return;
    // ⚠️ 必须允许**重复**调用：引擎的清理是 SIGTERM → 等宽限期 → SIGKILL 的升级
    // 序列，而交互式 bash 默认忽略 SIGTERM —— 如果这里只发第一次，升级就被吞掉，
    // 终端关闭后 shell 会作为孤儿残留。实测踩过（"terminal cleanup failed;
    // surviving pid: N"）。
    this._control(`K ${signal === undefined ? "SIGTERM" : signal}`);
    if (this._killTimer === undefined) {
      // 兜底：信号没能收掉子进程组时，直接终止助手本身（它会带走子进程）
      this._killTimer = setTimeout(() => {
        if (!this._exited) {
          try {
            this.child.kill("SIGKILL");
          } catch (_) {
            /* 已退出 */
          }
        }
      }, 5000);
      if (typeof this._killTimer.unref === "function") this._killTimer.unref();
    }
  }

  _control(line) {
    try {
      this.child.stdio[3].write(`${line}\n`);
    } catch (_) {
      /* 控制通道不可用：非致命，退出路径仍能从 pidfile 取状态 */
    }
  }
}

/**
 * 与 node-pty 同形的 spawn。
 * @param {string} file 可执行文件绝对路径（引擎传 shell 路径）
 * @param {string[]} args 参数（不含程序名）
 * @param {{name?:string,cols?:number,rows?:number,cwd?:string,env?:object}} options
 */
function spawnPty(file, args, options) {
  const helper = locateHelper();
  if (helper === undefined) {
    throw new Error(
      "node-pty(android): dsh_pty_exec helper not found; expected $PREFIX/bin/" + EXEC_NAME,
    );
  }
  const cols = options && Number.isFinite(options.cols) ? options.cols : 80;
  const rows = options && Number.isFinite(options.rows) ? options.rows : 24;
  const cwd = options && options.cwd && options.cwd.length > 0 ? options.cwd : os.homedir();
  const tmpBase =
    process.env.TMPDIR && process.env.TMPDIR.length > 0 ? process.env.TMPDIR : os.tmpdir();
  const pidfile = path.join(
    tmpBase,
    "dsh-pty-" + process.pid + "-" + Date.now() + "-" + Math.floor(Math.random() * 1e6) + ".pid",
  );

  // ★容器模式：把 program 换成「dsh_alpine loader + 容器 rootfs/interp + 原 program」。
  //   不包的话 dsh_pty_exec 被 cleaner 代跑，fork 出的 shell 落在宿主 /system/bin/sh
  //   （详见 containerWrap 的说明）。非容器模式返回 undefined → 行为不变。
  const wrapped = containerWrap(file, args);
  const program = wrapped === undefined ? file : wrapped.program;
  const programArgs = wrapped === undefined ? args || [] : wrapped.argv;

  // pidfile 走 argv 末尾（fd 3 是 JS→助手的单向下行通道，不能反向传参）
  const argv = [String(rows), String(cols), cwd, program].concat(programArgs, [
    PIDFILE_FLAG,
    pidfile,
  ]);
  const child = spawn(helper, argv, {
    stdio: ["pipe", "pipe", "pipe", "pipe"],
    env: options && options.env ? options.env : process.env,
    cwd: process.env.HOME && process.env.HOME.length > 0 ? process.env.HOME : cwd,
  });
  // 助手的诊断输出走 stderr：不混入终端内容，只在出错时留给排查
  child.stderr.on("data", (buf) => {
    const text = buf.toString("utf8").trim();
    if (text.length > 0) process.stderr.write("[dsh_pty_exec] " + text + "\n");
  });

  return new AndroidTerminal(child, pidfile, file, cols, rows);
}

module.exports = { spawn: spawnPty };
module.exports.default = module.exports;
'''

# ---------------------------------------------------------------------------
# JS 补丁：让探测/活动判定接受 android（procfs 语义与 linux 相同）
# ---------------------------------------------------------------------------
# 0.2.1-rc.2 已核验：runner-launch 的探测器锚点与 0.2.0 完全相同；
# index.js 的活动判定前面新增了 this.exited / managedOwner / root 条件，
# 但下面这个子串仍然唯一命中，替换后 android 走与 linux 相同的 procfs 判定。
PATCHES = [
    (
        "@deepseek-ai/dsh-subprocess-local/lib/runner-launch",
        'if (platform === "linux") return new LinuxProcessInspector(arch, internals);',
        'if (platform === "linux" || platform === "android") return new LinuxProcessInspector(arch, internals);',
        "进程探测器接受 android（Termux node 报告的平台名）",
    ),
    (
        "@deepseek-ai/dsh-subprocess-local/lib/index.js",
        'this.platform === "linux" && observed.complete === true',
        '(this.platform === "linux" || this.platform === "android") && observed.complete === true',
        "终端活动状态判定接受 android",
    ),
]


class PatchError(Exception):
    """必需的包/文件/锚点缺失 —— 绝不能 WARN 后返回成功。"""


def log(msg):
    print(msg, flush=True)


def _failure_report(failures):
    lines = ["patch-node-pty-android: refusing to report success:"]
    lines.extend(f"  - {item}" for item in failures)
    return "\n".join(lines)


def patch_js(text, old, new):
    if new in text:
        return text, "already"
    if old not in text:
        return text, "missing"
    return text.replace(old, new, 1), "patched"


def patch_dir(root):
    """在已解压的 runtime 目录上打补丁。返回改动条数。

    先校验所有必需目标与锚点，再统一落盘 —— 任一项缺失都抛 PatchError，
    不会留下"打了一半"的 runtime。
    """
    nm = os.path.join(root, "lib", "node_modules")
    failures = []
    writes = []  # (path, new_text, label)

    # 1) 探测器 / 活动判定
    for rel, old, new, why in PATCHES:
        target_dir = os.path.join(nm, os.path.dirname(rel))
        if not os.path.isdir(target_dir):
            failures.append(f"missing directory {target_dir} (target {rel}; {why})")
            continue
        base = os.path.basename(rel)
        names = sorted(
            name
            for name in os.listdir(target_dir)
            if name.startswith(base) and name.endswith(".js")
        )
        if not names:
            failures.append(f"no {base}*.js under {target_dir} (target {rel}; {why})")
            continue
        for name in names:
            p = os.path.join(target_dir, name)
            try:
                with open(p, encoding="utf-8") as f:
                    text = f.read()
            except (OSError, UnicodeDecodeError) as error:
                failures.append(f"cannot read {p}: {error}")
                continue
            out, state = patch_js(text, old, new)
            if state == "patched":
                writes.append((p, out, f"{rel}{name[len(base):]}: {why}"))
            elif state == "already":
                log(f"  already patched {rel}{name[len(base):]}: {why}")
            else:
                failures.append(
                    f"anchor not found in {p} (target {rel}; expected {old!r}); "
                    "upstream shape changed?"
                )

    # 2) node-pty 真实实现
    pkg_dir = os.path.join(nm, "node-pty")
    impl = os.path.join(pkg_dir, "lib", "index.js")
    pkg_json = os.path.join(pkg_dir, "package.json")
    pty_write = None
    if not os.path.isdir(pkg_dir):
        failures.append(f"node-pty package missing at {pkg_dir} (terminal PTY backend)")
    else:
        cur = ""
        if os.path.isfile(impl):
            with open(impl, encoding="utf-8") as f:
                cur = f.read()
        impl_ok = cur == NODE_PTY_IMPL
        pkg_ok = False
        if os.path.isfile(pkg_json):
            try:
                with open(pkg_json, encoding="utf-8") as f:
                    meta = json.load(f)
                pkg_ok = (
                    meta.get("version") == MARKER_VERSION
                    and meta.get("main") == "lib/index.js"
                )
            except (OSError, ValueError):
                pkg_ok = False
        if impl_ok and pkg_ok:
            log("  node-pty: already current Android implementation, skipped")
        else:
            # 包目录存在即可修复（例如上游只缺 lib/index.js 或 package.json），
            # 只有整个包缺失才是硬失败。
            pty_write = (impl, pkg_json, not impl_ok, not pkg_ok)

    if failures:
        raise PatchError(_failure_report(failures))

    changed = 0
    for p, out, label in writes:
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(out)
        log(f"  patched {label}")
        changed += 1

    if pty_write is not None:
        impl_path, pkg_path, write_impl, write_pkg = pty_write
        os.makedirs(os.path.dirname(impl_path), exist_ok=True)
        if write_impl:
            with open(impl_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(NODE_PTY_IMPL)
        if write_pkg:
            with open(pkg_path, "w", encoding="utf-8", newline="\n") as f:
                json.dump(
                    {"name": "node-pty", "version": MARKER_VERSION, "main": "lib/index.js"},
                    f,
                    separators=(",", ":"),
                )
                f.write("\n")
        log("  node-pty: wrote Android PTY implementation (forwards dsh_pty_exec)")
        changed += 1

    return changed


def _zip_rel(name):
    """zip 内条目名归一（Info-ZIP 可能带 "./" 前缀）。"""
    return name[2:] if name.startswith("./") else name


def _zip_find(names, target):
    for name in names:
        if _zip_rel(name) == target:
            return name
    return None


def patch_zip(zip_path, backup_dir=None):
    """在 runtime.zip 上原地打补丁（保持压缩级别与顺序）。

    与目录模式同样严格：任一必需的 subprocess-local 锚点或 node-pty 目标缺失都
    抛 PatchError，且在全部校验通过前不写回 zip。
    """
    with zipfile.ZipFile(zip_path) as z:
        infos = {i.filename: i for i in z.infolist()}
        names = list(infos)
        blobs = {n: z.read(n) for n in names}

    entry_prefix = "./" if any(n.startswith("./") for n in names) else ""
    nm = "lib/node_modules/"
    failures = []
    pending = {}  # original/new entry name -> bytes
    new_entries = []
    patched_logs = []  # (entry, reason) — logged only after all anchors validated

    def add_entry(target, data):
        existing = _zip_find(names, target)
        if existing is None:
            existing = entry_prefix + target
            infos[existing] = zipfile.ZipInfo(existing)
            new_entries.append(existing)
        pending[existing] = data

    # 1) 探测器 / 活动判定
    for rel, old, new, why in PATCHES:
        matches = [
            n for n in names if _zip_rel(n).startswith(nm + rel) and _zip_rel(n).endswith(".js")
        ]
        if not matches:
            failures.append(f"no {nm}{rel}*.js in {zip_path} (target {rel}; {why})")
            continue
        for n in matches:
            try:
                text = blobs[n].decode("utf-8")
            except UnicodeDecodeError as error:
                failures.append(f"cannot decode {n} as UTF-8: {error}")
                continue
            out, state = patch_js(text, old, new)
            if state == "patched":
                pending[n] = out.encode("utf-8")
                patched_logs.append((n, why))
            elif state == "already":
                log(f"  already patched {n}: {why}")
            else:
                failures.append(
                    f"anchor not found in {n} (target {rel}; expected {old!r}); "
                    "upstream shape changed?"
                )

    # 2) node-pty 真实实现
    pkg_prefix = nm + "node-pty/"
    pkg_entries = [n for n in names if _zip_rel(n).startswith(pkg_prefix)]
    impl_name = _zip_find(names, pkg_prefix + "lib/index.js")
    pkg_json = _zip_find(names, pkg_prefix + "package.json")
    pty_pending = False
    if not pkg_entries:
        failures.append(f"node-pty package missing from {zip_path} (terminal PTY backend)")
    else:
        cur = blobs.get(impl_name, b"").decode("utf-8", errors="replace") if impl_name else ""
        impl_ok = cur == NODE_PTY_IMPL
        pkg_ok = False
        if pkg_json is not None:
            try:
                meta = json.loads(blobs[pkg_json].decode("utf-8"))
                pkg_ok = (
                    meta.get("version") == MARKER_VERSION
                    and meta.get("main") == "lib/index.js"
                )
            except (UnicodeDecodeError, ValueError):
                pkg_ok = False
        if impl_ok and pkg_ok:
            log("  node-pty: already current Android implementation, skipped")
        else:
            if not impl_ok:
                add_entry(pkg_prefix + "lib/index.js", NODE_PTY_IMPL.encode("utf-8"))
            if not pkg_ok:
                add_entry(
                    pkg_prefix + "package.json",
                    (
                        json.dumps(
                            {
                                "name": "node-pty",
                                "version": MARKER_VERSION,
                                "main": "lib/index.js",
                            },
                            separators=(",", ":"),
                        )
                        + "\n"
                    ).encode("utf-8"),
                )
            pty_pending = True

    if failures:
        raise PatchError(_failure_report(failures))

    for entry, why in patched_logs:
        log(f"  patched {entry}: {why}")
    if pty_pending:
        log("  node-pty: wrote Android PTY implementation (forwards dsh_pty_exec)")

    if not pending:
        log("nothing to do (补丁已存在)")
        return 0

    if backup_dir:
        os.makedirs(backup_dir, exist_ok=True)
        stamp = os.path.basename(zip_path) + ".pre-pty"
        dst = os.path.join(backup_dir, stamp)
        if not os.path.exists(dst):
            shutil.copy2(zip_path, dst)
            log(f"backup -> {dst}")

    # 新条目必须追加到写入顺序里；旧实现只遍历 names，会丢掉新建的
    # node-pty/lib/index.js（当上游包没有该文件时）。
    ordered = names + new_entries
    tmp = zip_path + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as out:
        for n in ordered:
            info = infos[n]
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, pending[n] if n in pending else blobs[n])
    os.replace(tmp, zip_path)
    log(f"patched {len(pending)} file(s) -> {zip_path}")
    return 0


def main(argv):
    if len(argv) < 2:
        log(__doc__)
        return 2
    targets = []
    backup_dir = None
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--backup-dir":
            i += 1
            backup_dir = argv[i] if i < len(argv) else None
        else:
            targets.append(a)
        i += 1

    if not targets:
        log("ERROR: 未给出 runtime 目录或 zip")
        return 2

    rc = 0
    for t in targets:
        log(f"== {t}")
        if os.path.isdir(t):
            try:
                n = patch_dir(t)
            except PatchError as error:
                print(f"ERROR: {error}", file=sys.stderr, flush=True)
                rc = 1
                continue
            log(f"   {n} 处改动")
        elif os.path.isfile(t) and t.endswith(".zip"):
            try:
                patch_zip(t, backup_dir)
            except PatchError as error:
                print(f"ERROR: {error}", file=sys.stderr, flush=True)
                rc = 1
                continue
        else:
            log(f"ERROR: 无法识别 {t}")
            rc = 1
    return rc


if __name__ == "__main__":
    sys.exit(main(sys.argv))
