#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""Windows 远端目录绑定：路径方言补丁必须真的把 C:\ 收敛成 /C:/ 并大小写不敏感。

## 守什么

dsh-hardssh 0.2.13 的远端路径层是纯 POSIX 假定。用户在手机上给 Windows + OpenSSH
的主机绑定 C:\Users\me 时，绑定在任何 SFTP 动作之前就失败（没有 SFTP、没有命令记录），
因为：

  lib/health-*.js  normalizeRemoteRoot: posix.normalize 后要求 startsWith("/")
  lib/index.js     normalizeRemoteRoot: if (root.includes("\\")) 抛 root-mismatch

而 Win32 OpenSSH 的 SFTP 只认 /C:/Users/me。补丁（scripts/patch-hardssh-windows-paths.py）
做两件事：路径收敛成 /C:/...（P1），以及命令层对 Windows 根明示不支持（P0）。

本探针跑的是**真实补丁脚本 + 0.2.13 原始片段快照**（scripts/probes/fixtures/hardssh-0.2.13）：

  A. 补丁在原始片段上能打上（锚点全部命中；上游改版会让它红）
  B. 收敛语义：C:\x / C:/x / /c:/x 都变 /C:/x；相对路径仍然拒绝
  C. 包含判断在 Windows 根下大小写不敏感，POSIX 根下仍然敏感（不能为了修 Windows 把 POSIX 改松）
  D. 幂等：连打两次不会重复注入 helper
  E. fail-closed：锚点被改坏时必须报错退出，而不是静默跳过

## 反向验证

  删掉 fixture 里的 root.includes("\\") → A/E 红（补丁找不到锚点）
  把补丁里 isInside 的 toLowerCase 去掉 → C1 红
  把 confine 的 windows 分支去掉       → D1 红
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
PATCHER = os.path.join(ROOT, "scripts", "patch-hardssh-windows-paths.py")
FIXTURE = os.path.join(HERE, "fixtures", "hardssh-0.2.13")

PASS = 0
FAIL = 0


def check(ok, label, detail=""):
    global PASS, FAIL
    if ok:
        PASS += 1
        print("  PASS " + label)
    else:
        FAIL += 1
        print("  FAIL " + label + (("  <- " + detail) if detail else ""))


def run_patch(target, *extra):
    return subprocess.run(
        [sys.executable, PATCHER, "--root", target] + list(extra),
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )


def slice_fn(src, pattern, label):
    m = re.search(pattern, src, re.S)
    if m is None:
        raise SystemExit("FAIL 抽取不到 " + label + "（补丁没打上？）")
    return m.group(0)


def main():
    if not os.path.isdir(FIXTURE):
        print("SKIP 缺 fixture: " + FIXTURE)
        return 0
    work = tempfile.mkdtemp(prefix="hs-win-")
    try:
        pkg = os.path.join(work, "pkg")
        shutil.copytree(FIXTURE, pkg)

        print("[A] 补丁能打在 0.2.13 原始片段上")
        r = run_patch(pkg)
        check(r.returncode == 0, "patch exit 0", (r.stdout + r.stderr).strip()[:300])
        idx_path = os.path.join(pkg, "lib", "index.js")
        hea_path = os.path.join(pkg, "lib", "health-Byv4nAVe.js")
        idx = open(idx_path, encoding="utf-8").read()
        hea = open(hea_path, encoding="utf-8").read()
        check("dsh-patch: windows-paths v1" in idx and "dsh-patch: windows-paths v1" in hea, "两份文件都注入了 helper")

        print("[B-J] 行为断言（node 跑真实打过补丁的片段）")
        helper = slice_fn(idx, r"/\*\* dsh-patch: windows-paths v1.*?function dshIsWindowsRemoteRoot\(root\) \{.*?\n\}", "helper")
        norm = slice_fn(idx, r"function normalizeRemoteRoot\(root\) \{.*?\n\}", "normalizeRemoteRoot")
        inside = slice_fn(idx, r"function isInside\(root, abs\) \{.*?\n\}", "isInside")
        confine = slice_fn(idx, r"\tconfine\(path, code = \"FS_IO_ERROR\"\) \{.*?\n\t\}", "confine")
        contains = slice_fn(idx, r"\tcontains\(parent, child\) \{.*?\n\t\}", "contains")
        hnorm = slice_fn(hea, r"function normalizeRemoteRoot\(raw\) \{.*?\n\}", "health normalize")

        harness = "\n".join([
            "import { posix } from 'node:path'",
            "class BackendError extends Error { constructor(code, msg) { super(msg); this.code = code } }",
            "class FsError extends Error { constructor(msg, code) { super(msg); this.code = code } }",
            helper, norm, inside,
            "const target = {",
            "  confineRoot: undefined,",
            "  processPath(x) { return String(x) },",
            confine + ",",
            contains + ",",
            "}",
            hnorm.replace("function normalizeRemoteRoot(raw)", "function normalizeHealthRoot(raw)"),
            "let n = 0",
            "function eq(label, got, want) { n++; if (got !== want) throw new Error(label + ': got ' + JSON.stringify(got) + ' want ' + JSON.stringify(want)) }",
            "function throws(label, fn, needle) { n++; try { fn() } catch (e) { if (needle && !String(e.message).includes(needle)) throw new Error(label + ': wrong message ' + e.message); return } throw new Error(label + ': did not throw') }",
            "eq('A1 反斜杠盘符', dshCanonicalRemotePath('C:\\\\Users\\\\me'), '/C:/Users/me')",
            "eq('A2 小写正斜杠', dshCanonicalRemotePath('c:/users/me/'), '/C:/users/me')",
            "eq('A3 已带前导斜杠', dshCanonicalRemotePath('/c:/users/me'), '/C:/users/me')",
            "eq('A4 裸盘符', dshCanonicalRemotePath('C:'), '/C:')",
            "eq('A5 盘符根', dshCanonicalRemotePath('C:\\\\'), '/C:')",
            "eq('A6 POSIX 去尾斜杠', dshCanonicalRemotePath('/home/me/'), '/home/me')",
            "eq('A7 相对路径不动', dshCanonicalRemotePath('rel/x'), 'rel/x')",
            "eq('A8 折叠 ..', dshCanonicalRemotePath('/a/../b'), '/b')",
            "eq('B1 绑定根反斜杠', normalizeRemoteRoot('C:\\\\Users\\\\me'), '/C:/Users/me')",
            "eq('B2 绑定根混合', normalizeRemoteRoot('/c:/Users/me/'), '/C:/Users/me')",
            "eq('B3 相对路径拒绝', (() => { try { normalizeRemoteRoot('relative/dir'); return 'no-throw' } catch (e) { return e.code } })(), 'root-mismatch')",
            "eq('B4 POSIX 根不变', normalizeRemoteRoot('/srv/app'), '/srv/app')",
            "eq('B5 空串拒绝', (() => { try { normalizeRemoteRoot(''); return 'no-throw' } catch (e) { return e.code } })(), 'root-mismatch')",
            "eq('C1 Windows 大小写不敏感', isInside('/C:/Users/me', '/c:/users/me/x'), true)",
            "eq('C2 根自身也算在内', isInside('/C:/Users/me', '/C:/Users/me'), true)",
            "eq('C3 兄弟目录不算在内', isInside('/C:/Users/me', '/C:/Users/other'), false)",
            "eq('C4 POSIX 正常包含', isInside('/home/me', '/home/me/x'), true)",
            "eq('C5 POSIX 仍大小写敏感', isInside('/home/me', '/home/MEG/x'), false)",
            "target.confineRoot = '/c:/users/me'",
            "eq('D1 confine 大小写不敏感', target.confine('/C:/Users/Me/a', 'FS_IO_ERROR'), '/C:/Users/Me/a')",
            "throws('D2 confine 越界拒绝', () => target.confine('/C:/Users/other/x', 'FS_IO_ERROR'), 'ssh-outside-root')",
            "target.confineRoot = '/srv/app'",
            "throws('D3 POSIX confine 仍敏感', () => target.confine('/srv/App/x', 'FS_IO_ERROR'), 'ssh-outside-root')",
            "target.confineRoot = '/C:/Users/me/a'",
            "eq('E1 contains 子目录', target.contains('/C:/Users/me/a', '/c:/users/me/a/b'), true)",
            "eq('E2 contains 越界', target.contains('/C:/Users/me/a', '/C:/Users/me/ab'), false)",
            "target.confineRoot = '/srv/app'",
            "eq('E3 contains POSIX 子目录', target.contains('/srv/app', '/srv/app/x'), true)",
            "eq('E4 contains POSIX 越界', target.contains('/srv/app', '/srv/appx'), false)",
            "eq('F1 ledger 归一化也认 Windows', normalizeHealthRoot('C:\\\\Users\\\\me'), '/C:/Users/me')",
            "console.log('ALL OK ' + n)",
        ])
        harness_path = os.path.join(work, "harness.mjs")
        open(harness_path, "w", encoding="utf-8", newline="\n").write(harness)
        node = shutil.which("node")
        if node is None:
            check(False, "找不到 node（行为断言无法执行）")
        else:
            nr = subprocess.run([node, harness_path], capture_output=True, text=True, encoding="utf-8", errors="replace")
            out = (nr.stdout + nr.stderr).strip()
            check(nr.returncode == 0 and "ALL OK" in out, "node 行为断言全过", out[-1200:] if out else "no output")
            if "ALL OK" in out:
                print("        " + out.splitlines()[-1])

        print("[D] 幂等")
        r2 = run_patch(pkg)
        idx2 = open(idx_path, encoding="utf-8").read()
        check(r2.returncode == 0, "第二次 patch 仍 exit 0", (r2.stdout + r2.stderr).strip()[:200])
        check(idx2.count("dsh-patch: windows-paths v1") == 1, "helper 没有重复注入")
        check("function dshCanonicalRemotePath" in idx2 and idx2.count("const DSH_WIN_DRIVE_ABS") == 1, "helper 只出现一次")
        rc = run_patch(pkg, "--check")
        check(rc.returncode == 0, "--check 报已打补丁", (rc.stdout + rc.stderr).strip()[:200])

        print("[E] fail-closed：锚点被改坏必须报错")
        broken = os.path.join(work, "broken")
        shutil.copytree(FIXTURE, broken)
        bp = os.path.join(broken, "lib", "index.js")
        text = open(bp, encoding="utf-8").read()
        text = text.replace('if (root.includes("\\\\") || !posix.isAbsolute(root))', 'if (!posix.isAbsolute(root))', 1)
        open(bp, "w", encoding="utf-8", newline="\n").write(text)
        rb = run_patch(broken)
        check(rb.returncode != 0, "锚点缺失时 exit != 0", "rc=" + str(rb.returncode))

        print("[F] 真实包（若本机存在）")
        real = os.environ.get("HARDSSH_PKG", "")
        if real and os.path.isdir(real):
            rr = run_patch(real, "--check")
            check(rr.returncode == 0, "真实包已打补丁", (rr.stdout + rr.stderr).strip()[:200])
        else:
            print("  SKIP 未设置 HARDSSH_PKG，跳过真实包检查")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print("")
    print("PASS={0} FAIL={1}".format(PASS, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
