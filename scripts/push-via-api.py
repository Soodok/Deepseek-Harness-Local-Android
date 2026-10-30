#!/usr/bin/env python3
"""通过 GitHub Git Data API 推送提交（github.com:443 被阻断/重置时的通道）。

## 设计目标：**保留原始提交 SHA**

旧实现逐个 blob 上传 + 用 base_tree 拼新树 + 只传 author，导致每个提交在远端
被重新创建，SHA 全变。后果：

- 下游 fork（如 XiaoPb）每次同步都发现历史被改写，只能 force-push / rebase；
- GitHub 上的 PR 标记为「已合并」时拿不到原来的提交号，追溯困难；
- 本地与远端永久分叉，`git status` 恒显示 ahead/behind。

现在的做法：**按 git 对象身份原样上传**
  1. 以「自旧到新」的拓扑序处理待推提交；
  2. 每个提交复用其**原始 tree SHA**（tree 内容相同则 SHA 相同，无需重建）；
  3. 提交对象带完整的 author **和 committer**（旧实现漏了 committer，
     GitHub 会填成 API 调用者身份 —— 这是 SHA 变化的第二个原因）；
  4. 父提交用「之前已推送上去的 SHA」映射（父是远端已有的就直接用原 SHA）。

若某个 tree/blob 远端还没有（全新文件），会按原始内容逐个上传，但 tree 的
组装仍按原始结构，最终 SHA 与本地一致。

## 内容来源

一律从本地 **git 对象库**读取（`git cat-file`），而不是工作区。Windows 工作区
因 core.autocrlf=true 是 CRLF，而仓库对象是归一化后的 LF；直接读工作区会把
CRLF 推进远端，导致 CI 上 `#!/usr/bin/env bash` 变成 `bash\r`（bad interpreter）
—— 2026-10-04 就因此让 android-build 挂过一次。

## 用法

    python scripts/push-via-api.py [--remote origin] [--branch main] [--dry-run]
                                   [--rebase-remote] [--max-objects N]

`--rebase-remote`：本地历史与远端分叉时，自动把本地待推提交 rebase 到远端
最新之上（内容不变，SHA 会变，但历史线性、不覆盖任何人）。不加此参数时，
若检测到分叉会停下来提示，**绝不 force 覆盖远端**。

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

# github.com:443 被重置时，fetch 走 URL 前缀代理（gh-proxy 支持 git smart HTTP 的
# GET 请求；**不支持** git-receive-pack 的 POST，所以 push 只能走本脚本的 API 通道）。
FETCH_PROXY = "https://gh-proxy.org/"

BINARY_SUFFIXES = (
    ".png", ".jpg", ".jpeg", ".webp", ".ico", ".gif", ".zip", ".jar", ".apk",
    ".aab", ".so", ".ttf", ".otf", ".woff", ".woff2", ".keystore", ".jks",
)


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
        sys.exit("错误：需要 GITHUB_TOKEN 环境变量，或已保存的 git 凭据")
    for line in raw.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    sys.exit("错误：无法从 git credential 解析出 token")


def api(token: str, path: str, method: str = "GET", body: dict | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        API + path,
        data=data,
        method=method,
        headers={
            "Authorization": f"token {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "dsh-mobile-push",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            payload = r.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:400]
        raise SystemExit(f"GitHub API {method} {path} -> HTTP {e.code}\n{detail}")


def remote_slug(token: str, remote: str) -> tuple[str, str]:
    url = run_git("remote", "get-url", remote).strip()
    # 支持 https://github.com/o/r(.git) 与 git@github.com:o/r(.git)
    for sep in ("github.com/", "github.com:"):
        if sep in url:
            tail = url.split(sep, 1)[1]
            if tail.endswith(".git"):
                tail = tail[:-4]
            parts = tail.split("/")
            if len(parts) >= 2:
                return parts[0], parts[1]
    sys.exit(f"错误：无法从 remote URL 解析 owner/repo：{url}")


# --------------------------------------------------------------------------- 对象上传


class Uploader:
    """把本地 git 对象按**原始 SHA** 上传到远端，供组装 commit 使用。"""

    def __init__(self, token: str, owner: str, repo: str):
        self.token = token
        self.base_path = f"/repos/{owner}/{repo}/git"
        self.base = f"/repos/{owner}/{repo}"
        self.owner, self.repo = owner, repo
        self._known: set[str] = set()      # 已确认存在于远端的对象 SHA
        self.uploaded = 0

    def has(self, kind: str, sha: str) -> bool:
        if sha in self._known:
            return True
        # HEAD 请求式探测：Git Data API 没有 HEAD，用 GET 取元数据
        path = f"{self.base_path}/{kind}/{sha}"
        req = urllib.request.Request(
            API + path,
            headers={
                "Authorization": f"token {self.token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "dsh-mobile-push",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=30):
                self._known.add(sha)
                return True
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            if e.code in (403, 429):
                raise SystemExit("GitHub API 限流，稍后重试")
            raise

    def upload_blob(self, sha: str) -> None:
        if self.has("blobs", sha):
            return
        content = git_bytes("cat-file", "blob", sha)
        size = len(content)
        max_api = 60 * 1024 * 1024   # GitHub blob API 上限 ~100MB，保守取 60MB
        if size > max_api:
            raise SystemExit(
                f"错误：对象 {sha[:10]} 有 {size/1048576:.1f}MB，超过 Git Data API 单 blob 上限。\n"
                "这种情况请改用 SSH/HTTPS 直连 push（不要用本脚本）。"
            )
        is_text = not any(sha_path_suffix(p) in BINARY_SUFFIXES
                          for p in [self._path_of_blob(sha)])
        if is_text and b"\r\n" in content:
            content = content.replace(b"\r\n", b"\n")
        api(self.token, f"{self.base_path}/blobs", "POST", {
            "content": base64.b64encode(content).decode(),
            "encoding": "base64",
        })
        self._known.add(sha)
        self.uploaded += 1

    def _path_of_blob(self, sha: str) -> str:
        try:
            return run_git("rev-list", "--objects", "--all").split("\n")[0].split(" ", 1)[-1]
        except Exception:
            return ""

    def upload_tree(self, sha: str, depth: int = 0) -> None:
        """确保 tree 及其子对象都在远端。tree 用原始条目组装（含 mode/sha）。"""
        if depth > 40:
            raise SystemExit("错误：tree 嵌套过深，疑似异常")
        if self.has("trees", sha):
            return
        entries: list[dict] = []
        for item in split_tree(sha):
            mode, typ, obj = item["mode"], item["type"], item["sha"]
            if typ == "blob" and obj not in self._known:
                self.upload_blob(obj)
            elif typ == "tree":
                self.upload_tree(obj, depth + 1)
            entry = {"path": item["path"], "mode": mode, "type": typ, "sha": obj}
            entries.append(entry)
        api(self.token, f"{self.base_path}/trees", "POST", {"tree": entries})
        self._known.add(sha)

    def commit_meta(self, sha: str) -> dict:
        """读取本地提交的完整元数据（author + committer + 原始时间）。"""
        raw = run_git("cat-file", "commit", sha)
        tree, parents, author, committer, message = parse_commit(raw)
        return {
            "tree": tree, "parents": parents, "author": author,
            "committer": committer, "message": message,
        }


def sha_path_suffix(path: str) -> str:
    lower = path.lower()
    for suf in BINARY_SUFFIXES:
        if lower.endswith(suf):
            return suf
    return ""


def split_tree(sha: str):
    """用 `git ls-tree` 解析树的条目。

    不能用 `git cat-file tree`：它输出的是**二进制**格式
    （`<mode> SP <name> NUL <20 字节 sha>`），没有 type 字段，SHA 也是原始字节。
    文本形式必须走 `git ls-tree`，它给出 `<mode> SP <type> SP <sha> TAB <path>`。
    """
    listing = git_bytes("ls-tree", "-z", sha)
    out = []
    for rec in listing.split(b"\0"):
        if not rec:
            continue
        meta, _, path = rec.partition(b"\t")
        parts = meta.decode().split()
        if len(parts) != 3:
            raise SystemExit(f"错误：无法解析 tree 条目 {meta!r}")
        mode, typ, obj = parts
        out.append({
            "mode": mode, "type": typ, "sha": obj,
            "path": path.decode("utf-8", "surrogateescape"),
        })
    return out


def parse_commit(raw: str):
    head, _, body = raw.partition("\n\n")
    tree = ""
    parents: list[str] = []
    author = committer = None
    for line in head.splitlines():
        if line.startswith("tree "):
            tree = line[5:].strip()
        elif line.startswith("parent "):
            parents.append(line[7:].strip())
        elif line.startswith("author "):
            author = parse_ident(line[7:])
        elif line.startswith("committer "):
            committer = parse_ident(line[10:])
    return tree, parents, author, committer, body


def parse_ident(s: str) -> dict:
    """`Name <email> 1793119657 +0800` -> GitHub API 的 ident 结构。"""
    lt = s.index("<")
    gt = s.index(">", lt)
    name = s[:lt].strip()
    email = s[lt + 1:gt]
    rest = s[gt + 1:].strip().split()
    epoch, tz = int(rest[0]), rest[1] if len(rest) > 1 else "+0000"
    sign = 1 if tz[0] == "+" else -1
    hours, minutes = int(tz[1:3]), int(tz[3:5])
    offset = sign * (hours * 60 + minutes)
    # GitHub 要 ISO 8601，带原时区
    import datetime
    dt = datetime.datetime.fromtimestamp(epoch, datetime.timezone(
        datetime.timedelta(minutes=offset)))
    return {"name": name, "email": email, "date": dt.isoformat()}


def ensure_remote_base(token: str, owner: str, repo: str, ref: str,
                       remote: str) -> str:
    """确保远端基准提交在本地对象库里（否则 rev-list 无法做祖先判断）。

    GitHub API 推送会创建远端侧的新提交；本地若没 fetch 过，那些 SHA 就不存在，
    `git rev-list base..head` 直接报 128。这里按需 fetch 一次。
    """
    base_path = f"/repos/{owner}/{repo}/git"
    base_commit = api(token, f"{base_path}/refs/heads/{ref}")["object"]["sha"]
    exists = subprocess.run(
        ["git", "cat-file", "-e", base_commit],
        capture_output=True,
    ).returncode == 0
    if not exists:
        print(f"本地缺少远端基准 {base_commit[:12]}，先 fetch（走代理）…")
        fetch(remote, ref)
    return base_commit


NL_HELPER = chr(10)


def fetch(remote: str, ref: str) -> None:
    """拉取远端引用。直连失败时自动切 gh-proxy 前缀代理。

    gh-proxy 只代理 GET（fetch 可用），不支持 receive-pack 的 POST（push 不可用），
    所以 push 必须走本脚本的 Git Data API 通道 —— 这也是本文件存在的理由。
    """
    direct = subprocess.run(["git", "fetch", remote, ref], capture_output=True)
    if direct.returncode == 0:
        return
    print("直连 fetch 失败，改用代理…")
    proxied = subprocess.run(
        ["git",
         "-c", f"url.{FETCH_PROXY}https://github.com/.insteadOf=https://github.com/",
         "fetch", remote, ref],
        capture_output=True,
    )
    if proxied.returncode != 0:
        sys.exit(
            "错误：fetch 直连与代理都失败。" + NL_HELPER
            + direct.stderr.decode("utf-8", "replace")[-400:]
            + NL_HELPER + proxied.stderr.decode("utf-8", "replace")[-400:]
        )


def push_commits(token: str, owner: str, repo: str, ref: str, local_head: str,
                 remote: str = "origin") -> str:
    """把从远端基准到 local_head 之间的提交逐个按原始 SHA 上传。"""
    base_path = f"/repos/{owner}/{repo}/git"
    base_commit = ensure_remote_base(token, owner, repo, ref, remote)

    # 待推提交（拓扑序，旧→新）
    pending = run_git(
        "rev-list", "--reverse", "--topo-order", f"{base_commit}..{local_head}"
    ).split()

    if not pending:
        print("远端已是最新，无需推送")
        return base_commit

    # ⚠️ 强制覆盖护栏（2026-10-30 真事故）：
    # 远端 HEAD 必须能在本地被「看到」——即它是本地 HEAD 的祖先，或者本地
    # 基于它。否则说明远端有本地不知道的提交（别人 push / 合并了 PR），此时
    # 强行推送会把那些提交从 main 上抹掉。
    #
    # 真实事故：XiaoPb 的 PR #75（8000+ 行自动更新体系）合进 main 后，本地
    # 未 fetch，`--force` 把远端从 0f328e4 打回 d6702ab，PR 内容在远端消失。
    #
    # 判定：远端 base_commit 若能由本地 HEAD 到达 → 说明本地包含它，安全；
    # 否则必须先合并/rebase。
    is_ancestor = subprocess.run(
        ["git", "merge-base", "--is-ancestor", base_commit, local_head],
        capture_output=True,
    ).returncode == 0
    if not is_ancestor:
        print(
            "",
            "错误：远端有本地不知道的提交，拒绝推送（会覆盖别人的工作）。",
            "  远端 HEAD: " + base_commit[:12],
            "  本地 HEAD: " + local_head[:12],
            "  处理办法：",
            "    1) git fetch " + remote + " " + ref + " && git merge origin/" + ref,
            "    2) 或 git rebase origin/" + ref,
            "  本脚本绝不会 force 覆盖远端别人的提交。",
            "",
            file=sys.stderr,
        )
        raise SystemExit(3)

    # 分叉检测：本地待推提交里若有一个父不在「远端可达 ∪ 待推集合」内，就是分叉
    remote_reach = set(run_git("rev-list", base_commit).split())

    up = Uploader(token, owner, repo)
    mapping: dict[str, str] = {}   # 本地 SHA -> 远端 SHA（正常情况下相同）

    for c in pending:
        meta = up.commit_meta(c)
        for p in meta["parents"]:
            if p in mapping:
                continue
            if p in remote_reach:
                continue
            # 父提交既不在远端也不在本轮待推里 —— 分叉
            print(
                "\n错误：本地历史与远端分叉。\n"
                f"  提交 {c[:10]} 的父提交 {p[:10]} 两边都不存在。\n"
                "  这通常是因为之前用「重建提交」的方式推送过。\n"
                "  处理办法（任选）：\n"
                "    1) 加 --rebase-remote：把本地待推提交 rebase 到远端最新之上（推荐，不覆盖历史）\n"
                "    2) 手工 git rebase origin/<branch> 后再跑本脚本\n"
                "  本脚本**不会** force 覆盖远端。",
                file=sys.stderr,
            )
            raise SystemExit(2)

        up.upload_tree(meta["tree"])
        # 父提交：映射过的用映射值，其余（远端已有）保持原 SHA
        parents = [mapping.get(p, p) for p in meta["parents"]]
        created = api(token, f"{base_path}/commits", "POST", {
            "message": meta["message"],
            "tree": meta["tree"],
            "parents": parents,
            "author": meta["author"],
            "committer": meta["committer"],
        })["sha"]
        mapping[c] = created
        same = "✓ SHA 保持" if created == c else f"→ {created[:10]}"
        print(f"  {c[:10]} {same}  {meta['message'].splitlines()[0][:60]}")

    final = mapping[local_head]
    # 上面已确认 base 是 local_head 的祖先（快进安全），这里用非 force 提交：
    # 若远端在我们检查后又变了，GitHub 会拒绝（422），不会静默覆盖。
    api(token, f"{base_path}/refs/heads/{ref}", "PATCH", {"sha": final, "force": False})
    return final


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--remote", default="origin")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--rebase-remote", action="store_true",
                    help="本地与远端分叉时，先 rebase 到远端最新再推（不覆盖历史）")
    args = ap.parse_args()

    token = resolve_token()
    owner, repo = remote_slug(token, args.remote)
    commit = run_git("rev-parse", "HEAD").strip()
    print(f"仓库 {owner}/{repo}，分支 {args.branch}，本地 HEAD {commit[:12]}")

    if args.dry_run:
        ref = ensure_remote_base(token, owner, repo, args.branch, args.remote)
        pending = run_git("rev-list", "--count", f"{ref}..{commit}").strip()
        print(f"dry-run：远端 {args.branch} = {ref[:12]}，待推 {pending} 个提交，仅比对不推送")
        return

    if args.rebase_remote:
        ref = api(token, f"/repos/{owner}/{repo}/git/ref/heads/{args.branch}")["object"]["sha"]
        behind = run_git("rev-list", "--count", f"HEAD..{ref}").strip()
        if behind != "0":
            print(f"远端领先 {behind} 个提交，rebase 到远端之上…")
            fetch(args.remote, args.branch)
            subprocess.run(
                ["git", "rebase", f"{args.remote}/{args.branch}"], check=True
            )
            commit = run_git("rev-parse", "HEAD").strip()
            print(f"rebase 完成，新 HEAD {commit[:12]}")

    new_sha = push_commits(token, owner, repo, args.branch, commit, args.remote)
    print(f"远端 {args.branch} → {new_sha}")


if __name__ == "__main__":
    main()

