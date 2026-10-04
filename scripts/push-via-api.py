#!/usr/bin/env python3
"""通过 GitHub Git Data API 推送本地提交（github.com:443 被重置时的备用通道）。

关键点：内容一律从本地 git 对象库读取（git cat-file），而不是工作区文件。
Windows 工作区因 core.autocrlf=true 是 CRLF，而仓库对象是归一化后的 LF；
直接读工作区会把 CRLF 推进远端，导致 CI 上 `#!/usr/bin/env bash` 变成
`bash\r`（bad interpreter）——2026-10-04 就因此让 android-build 挂过一次。

用法：
    python scripts/push-via-api.py [--remote origin] [--branch main] [--dry-run]

需要环境变量 GITHUB_TOKEN，或本机 git credential 中已存 github.com 凭据。
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

API = "https://api.github.com"


def run_git(*args: str) -> str:
    out = subprocess.run(
        ["git", *args], capture_output=True, check=True, text=False
    ).stdout
    return out.decode("utf-8", "surrogateescape")


def git_bytes(*args: str) -> bytes:
    return subprocess.run(["git", *args], capture_output=True, check=True).stdout


def resolve_token() -> str:
    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if tok:
        return tok.strip()
    try:
        raw = subprocess.run(
            ["git", "credential", "fill"],
            input=b"protocol=https\nhost=github.com\n\n",
            capture_output=True,
            check=True,
        ).stdout.decode("utf-8", "replace")
    except subprocess.CalledProcessError:
        raw = ""
    for line in raw.splitlines():
        if line.startswith("password="):
            return line[len("password=") :].strip()
    sys.exit("找不到 GitHub 凭据：设置 GITHUB_TOKEN 或先让 git 记住 github.com 密码")


def api(token: str, path: str, method: str = "GET", payload: dict | None = None):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:600]
        sys.exit(f"API {method} {path} 失败: HTTP {e.code}\n{detail}")


def remote_slug(token: str, remote: str) -> tuple[str, str]:
    url = run_git("remote", "get-url", remote).strip()
    for prefix in ("https://github.com/", "git@github.com:", "ssh://git@github.com/"):
        if url.startswith(prefix):
            slug = url[len(prefix) :]
            break
    else:
        sys.exit(f"无法从 remote URL 解析 owner/repo: {url}")
    slug = slug.removesuffix(".git").strip("/")
    owner, _, repo = slug.partition("/")
    return owner, repo


BINARY_SUFFIXES = (
    ".png", ".jpg", ".jpeg", ".webp", ".ico", ".gif", ".zip", ".jar", ".apk",
    ".aab", ".so", ".ttf", ".otf", ".woff", ".woff2", ".keystore", ".jks",
)


def push_tree(token: str, owner: str, repo: str, ref: str, local_commit: str) -> str:
    """把本地 commit 的整棵树（含新增/修改/删除）写成远端新提交，返回新 SHA。

    远端基准取 `refs/heads/<ref>` 当前指向的提交（而非本地 HEAD 的父提交），
    这样即使本地历史与远端分叉，也能正确算出差异；新提交的父提交是远端基准。
    """
    base_path = f"/repos/{owner}/{repo}/git"
    ref_info = api(token, f"/repos/{owner}/{repo}/git/ref/heads/{ref}")
    base_commit = ref_info["object"]["sha"]
    base_tree = api(token, f"{base_path}/commits/{base_commit}")["tree"]["sha"]
    base_entries = {
        e["path"]: e
        for e in api(token, f"{base_path}/trees/{base_tree}?recursive=1")["tree"]
        if e["type"] == "blob"
    }

    # 从 git 对象库读取（LF 归一化后），而不是工作区
    listing = git_bytes("ls-tree", "-r", "-z", local_commit)
    local: dict[str, tuple[str, str]] = {}
    for entry in listing.split(b"\0"):
        if not entry:
            continue
        meta, _, path = entry.partition(b"\t")
        mode, _typ, sha = meta.decode().split()
        local[path.decode("utf-8", "surrogateescape")] = (sha, mode)

    changed, removed = [], []
    for path, (sha, mode) in local.items():
        old = base_entries.get(path)
        if old is None or old["sha"] != sha or old["mode"] != mode:
            changed.append((path, sha, mode))
    for path in base_entries:
        if path not in local:
            removed.append(path)

    print(f"远端基准 {base_commit[:12]}：待更新 {len(changed)} 个，待删除 {len(removed)} 个")
    if not changed and not removed:
        print("远端已是最新，无需推送")
        return base_commit

    tree_entries = []
    for path, sha, mode in changed:
        content = git_bytes("cat-file", "blob", sha)
        if b"\r\n" in content and not path.lower().endswith(BINARY_SUFFIXES):
            # 双保险：万一有 CRLF 混进对象库，也在此拦下
            content = content.replace(b"\r\n", b"\n")
        blob = api(
            token,
            f"{base_path}/blobs",
            "POST",
            {"content": base64.b64encode(content).decode(), "encoding": "base64"},
        )
        tree_entries.append({"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]})
    for path in removed:
        tree_entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    # 关键：带 base_tree，未列出的路径沿用远端基准，否则整棵树会被替换成只有变更文件
    new_tree = api(
        token, f"{base_path}/trees", "POST", {"base_tree": base_tree, "tree": tree_entries}
    )["sha"]

    meta_line = run_git("log", "-1", "--format=%an%x00%ae%x00%aI", local_commit).split("\x00")
    name, email, date = meta_line[0], meta_line[1], meta_line[2].strip()
    new_commit = api(
        token,
        f"{base_path}/commits",
        "POST",
        {
            "message": run_git("log", "-1", "--format=%B", local_commit).rstrip(),
            "tree": new_tree,
            "parents": [base_commit],
            "author": {"name": name, "email": email, "date": date},
        },
    )["sha"]

    api(
        token,
        f"/repos/{owner}/{repo}/git/refs/heads/{ref}",
        "PATCH",
        {"sha": new_commit, "force": True},
    )
    return new_commit


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = resolve_token()
    owner, repo = remote_slug(token, args.remote)
    commit = run_git("rev-parse", "HEAD").strip()
    print(f"仓库 {owner}/{repo}，分支 {args.branch}，本地 HEAD {commit[:12]}")

    if args.dry_run:
        ref = api(token, f"/repos/{owner}/{repo}/git/ref/heads/{args.branch}")["object"]["sha"]
        print(f"dry-run：远端 {args.branch} = {ref[:12]}，仅比对，不推送")
        return

    new_sha = push_tree(token, owner, repo, args.branch, commit)
    print(f"远端 {args.branch} → {new_sha}")


if __name__ == "__main__":
    main()
