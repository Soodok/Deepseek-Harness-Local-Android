#!/usr/bin/env python3
"""git 卫生检查：推送前后各跑一次，挡住「悄悄重写历史」这类问题。

用法:
    python scripts/git-hygiene.py            # 检查当前状态
    python scripts/git-hygiene.py --fix-tags # 额外核对 tag 是否指向远端 main

检查项（每项都对应一次真实事故，见 docs/git-workflow.md 第六节）：
  1. **工作区干净** —— 有未提交改动就推送，很容易漏文件或多推临时产物；
  2. **本地含远端最新** —— 2026-10-27 的覆盖事故就是这么发生的；
  3. **不处于 rebase/merge 中间态** —— 半成品历史推上去会让 CI 拿到坏状态；
  4. **无 CRLF 污染的对象** —— 2026-10-04 的事故（shell 脚本变成 bad interpreter）；
  5. **探针目录未被误加** —— `scripts/probes/` 含凭据与大文件，绝不能整体 add。
"""
from __future__ import annotations

import argparse
import glob
import os
import pathlib
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROXY = "https://gh-proxy.org/"
FAIL: list[str] = []
WARN: list[str] = []


def git(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True,
                          check=check, encoding="utf-8", errors="replace")


def fetch_remote() -> str | None:
    """拉远端 main，返回远端 SHA；直连失败自动走代理。"""
    direct = git("fetch", "origin", "main")
    if direct.returncode != 0:
        proxied = git(
            "-c", f"url.{PROXY}https://github.com/.insteadOf=https://github.com/",
            "fetch", "origin", "main",
        )
        if proxied.returncode != 0:
            WARN.append("fetch 失败（直连+代理），跳过远端比对")
            return None
    out = git("rev-parse", "origin/main")
    return out.stdout.strip() if out.returncode == 0 else None


def check_clean() -> None:
    out = git("status", "--porcelain")
    dirty = [l for l in out.stdout.splitlines() if l.strip()]
    # 忽略已知的本地临时产物
    ignored = ("_bench/", "_rel/", "_i5", "_i6", "_n1", "_s1", "_st.png",
               "_island", "_r1", "_r2", "_r3", "_r4", "_c0", "_p1", "_p2", "_sm")
    dirty = [l for l in dirty if not any(k in l for k in ignored)]
    if dirty:
        WARN.append(f"工作区有 {len(dirty)} 个未提交改动（确认不是漏推）")


def check_behind(remote_sha: str | None) -> None:
    if not remote_sha:
        return
    local = git("rev-parse", "HEAD").stdout.strip()
    if local == remote_sha:
        print(f"  ✓ 本地与远端一致 ({local[:10]})")
        return
    anc = git("merge-base", "--is-ancestor", remote_sha, local)
    if anc.returncode == 0:
        behind = git("rev-list", "--count", f"{remote_sha}..{local}").stdout.strip()
        print(f"  ✓ 本地领先远端 {behind} 个提交（可安全快进推送）")
    else:
        ahead = git("rev-list", "--count", f"{remote_sha}..{local}").stdout.strip()
        ONLY = git("rev-list", "--count", f"{local}..{remote_sha}").stdout.strip()
        FAIL.append(
            f"历史分叉：本地独有 {ahead}，远端独有 {ONLY}。\n"
            "    远端有本地缺失的提交 —— **绝对不要直接推**（会覆盖别人）。\n"
            "    处理：git fetch origin && git rebase origin/main（或 --rebase-remote）"
        )


def check_mid_operation() -> None:
    gd = git("rev-parse", "--git-dir").stdout.strip() or ".git"
    for name, label in (("rebase-merge", "rebase"), ("rebase-apply", "rebase"),
                        ("MERGE_HEAD", "merge"), ("CHERRY_PICK_HEAD", "cherry-pick")):
        r = subprocess.run(["git", "rev-parse", "--git-path", name],
                           capture_output=True, text=True)
        path = r.stdout.strip()
        if path and subprocess.run(["test", "-e", path]).returncode == 0:
            FAIL.append(f"处于 {label} 中间态，先完成或 abort")
            return
    print("  ✓ 无 rebase/merge 中间态")


def check_crlf(remote_sha: str | None) -> None:
    """检查待推提交里的文本文件是否含 CRLF（对象库里不该有）。"""
    base = remote_sha or "HEAD~1"
    r = git("diff", "--name-only", f"{base}..HEAD")
    if r.returncode != 0:
        print("  - 跳过 CRLF 检查（无法确定比较基准）")
        return
    names = [n for n in r.stdout.splitlines() if n.strip()]
    BINARY = (".png", ".jpg", ".jpeg", ".webp", ".ico", ".gif", ".zip", ".jar",
              ".apk", ".aab", ".so", ".ttf", ".otf", ".woff", ".woff2",
              ".keystore", ".jks", ".arsc", ".dex")
    bad = []
    for n in names:
        if n.lower().endswith(BINARY):
            continue
        blob = subprocess.run(["git", "show", f"HEAD:{n}"], capture_output=True)
        if blob.returncode == 0 and b"\r\n" in blob.stdout:
            bad.append(n)
    if bad:
        FAIL.append("以下文件在对象库里含 CRLF（CI 上会变成 bad interpreter）：\n    "
                    + "\n    ".join(bad[:8]))
    else:
        print(f"  ✓ {len(names)} 个改动文件无 CRLF 污染")


def check_probes_not_staged() -> None:
    staged = git("diff", "--cached", "--name-only").stdout.splitlines()
    risky = [s for s in staged
             if s.startswith("scripts/probes/") and not s.startswith("scripts/probes/_test_")
             and not s.endswith(".md")]
    if risky:
        WARN.append("暂存区里有 scripts/probes/ 下的非测试文件（可能含凭据/大文件）：\n    "
                    + "\n    ".join(risky[:8]))
    else:
        print("  ✓ probes 目录未误加")


def check_stale_branches() -> None:
    """已合入 main 的本地分支应立即删除（2026-10-31 一次清出 10 个陈年分支）。"""
    merged = git("branch", "--merged", "main").stdout.splitlines()
    stale = []
    for line in merged:
        name = line.strip().lstrip("*+ ")
        if not name or name == "main" or line.strip().startswith("*"):
            continue
        stale.append(name)
    if stale:
        WARN.append(
            f"有 {len(stale)} 个已合入 main 的本地分支未删（内容已在 main，删了不丢东西）：\n    "
            + "\n    ".join(stale[:10])
            + f"\n    清理：git branch -d {' '.join(stale)}"
        )
    else:
        print("  ✓ 无已合并的残留分支")


def check_stale_worktrees() -> None:
    """孤儿 worktree 会占数 GB 且容易改错目录（2026-10-31 一次清出 7 个）。"""
    out = git("worktree", "list", "--porcelain").stdout
    paths = [l.split(" ", 1)[1] for l in out.splitlines() if l.startswith("worktree ")]
    extras = paths[1:]
    missing = [p for p in extras if not os.path.isdir(p)]
    alive = [p for p in extras if os.path.isdir(p)]
    if missing:
        WARN.append(
            f"{len(missing)} 个 worktree 目录已不存在但元数据还在（git worktree prune 即可）：\n    "
            + "\n    ".join(missing[:6])
        )
    if alive:
        WARN.append(
            f"还有 {len(alive)} 个额外 worktree（确认是否仍在用，用完 git worktree remove）：\n    "
            + "\n    ".join(alive[:6])
        )
    if not missing and not alive:
        print("  ✓ 只有主 worktree")


def check_residue() -> None:
    """仓库根目录的本地产物：不提交，但会越攒越多，超阈值就提醒清。"""
    rules = [("dsh-android-*.apk", "手打 APK", 60),
             ("_*.png", "临时截图", 30),
             ("hs_err_pid*.log", "JVM 崩溃日志", 1)]
    hits: list[str] = []
    for pattern, label, limit in rules:
        found = glob.glob(os.path.join(ROOT, pattern))
        if len(found) > limit:
            hits.append(f"{label} {len(found)} 个（阈值 {limit}）")
    for sub, label, gb in (("_runtime_dl", "runtime 下载缓存", 1.0),
                           ("_rel", "dedup 产物", 0.5)):
        p = os.path.join(ROOT, sub)
        if os.path.isdir(p):
            size = sum(f.stat().st_size for f in pathlib.Path(p).rglob("*") if f.is_file())
            if size > gb * 1024**3:
                hits.append(f"{label} {size / 1024**3:.1f}G（阈值 {gb}G）")
    if hits:
        WARN.append("仓库根目录堆了本地产物（不提交，但会越攒越多）：\n    "
                    + "\n    ".join(hits))
    else:
        print("  ✓ 无超标本地产物")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ci", action="store_true",
                    help="CI 模式：不做网络操作；工作区脏只提示不告警")
    args = ap.parse_args()

    print("git 卫生检查\n" + "-" * 44)
    if not args.ci:
        check_clean()
    remote_sha = None if args.ci else fetch_remote()
    if remote_sha:
        check_behind(remote_sha)
    elif args.ci:
        print("  - CI 模式：跳过远端比对与工作区检查")
    check_mid_operation()
    check_crlf(remote_sha)
    check_probes_not_staged()
    check_stale_branches()
    check_stale_worktrees()
    check_residue()

    print("-" * 44)
    for w in WARN:
        print(f"WARN  {w}")
    for f in FAIL:
        print(f"FAIL  {f}")
    if FAIL:
        print(f"\n{len(FAIL)} 项阻断性问题，先修再推")
        return 1
    print("通过" + (f"（{len(WARN)} 条提醒）" if WARN else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
