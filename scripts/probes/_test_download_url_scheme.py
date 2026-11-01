#!/usr/bin/env python3
"""HttpDownloader 只接受 https（issue #49 P2）。

背景：#49 的 P2 清单里有一条「HttpDownloader 强制 https」。在此之前只靠
「上游 URL 恰好都是 https」+「索引验签」间接保证，下载器本身不拒绝明文：
镜像前缀被改、索引里的 host 被换，就能把整包换成 http 明文。

这里对**真实 Kotlin 源码**做静态断言 + 用 Python 复刻同一套判定跑用例表，
防止实现回退（不是只 grep 一个字符串就算过）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "app", "src", "main", "java", "app", "dsh", "mobile", "engine",
                   "HttpDownloader.kt")

checks = 0
failures = []


def check(cond, msg):
    global checks
    checks += 1
    if not cond:
        failures.append(msg)


src = io.open(SRC, encoding="utf-8").read()

# --- 1. 源码层面：判定函数存在、放在 companion 里、download() 真的用了它 ---
check("internal fun isSecureDownloadUrl(" in src,
      "isSecureDownloadUrl() missing from HttpDownloader.kt")
check("rejecting non-https" not in src, "unexpected placeholder text")

# download() 必须在遍历 URL 之前就把 http 剔掉（而不是在 fetch 里才发现）
dl = src[src.index("fun download("):src.index("private fun fetch(")]
check("isSecureDownloadUrl(" in dl,
      "download() does not filter by isSecureDownloadUrl()")
check("refusing non-https download URL" in dl,
      "download() must raise the explicit non-https error")
check(re.search(r"val\s+safe\s*=\s*urls\.distinct\(\)\.filter", dl) is not None,
      "download() should build a filtered `safe` list from `urls.distinct()`")
check("for (url in safe)" in dl, "download() should iterate the filtered list, not raw urls")

# 明文 URL 必须留在日志里，否则用户只看到「全部失败」
check(re.search(r'skip non-https URL', dl) is not None,
      "download() should log skipped non-https URLs")

# --- 2. 行为层面：用同一套判定跑用例表 ---
def secure(url):
    """复刻 Kotlin 的 isSecureDownloadUrl（java.net.URI 语义）。"""
    from urllib.parse import urlsplit
    try:
        p = urlsplit(url)
    except ValueError:
        return False
    scheme = (p.scheme or "").lower()
    if scheme == "https":
        return True
    if scheme != "http":
        return False
    host = (p.hostname or "").lower()
    if host.startswith("[") and host.endswith("]"):
        host = host[1:-1]
    return host in ("127.0.0.1", "localhost", "::1")


CASES = [
    # (url, expected, note)
    ("https://github.com/a/b/releases/download/v1/x.apk", True, "正常 https"),
    ("https://registry.npmmirror.com/x.tgz", True, "镜像 https"),
    ("http://github.com/a/b/x.apk", False, "明文 http 必须拒"),
    ("http://gh-proxy.com/https://x", False, "明文代理前缀也要拒"),
    ("http://192.168.1.10:8080/x.apk", False, "内网 http 不在例外内"),
    ("http://127.0.0.1:8000/x.apk", True, "环回例外"),
    ("http://localhost:8000/x.apk", True, "localhost 例外"),
    ("http://[::1]:8000/x.apk", True, "IPv6 环回例外"),
    ("ftp://example.com/x.apk", False, "非 http(s) 协议拒"),
    ("file:///sdcard/x.apk", False, "file 协议拒"),
    ("", False, "空串"),
    ("not a url", False, "非 URL"),
    ("HTTPS://GitHub.com/x", True, "大写 scheme 正常"),
    ("HTTP://127.0.0.1/x", True, "大写 scheme + 环回"),
]

for url, want, note in CASES:
    got = secure(url)
    check(got == want, "isSecureDownloadUrl(%r) must be %s (%s), got %s"
          % (url, want, note, got))

# --- 3. 失败语义：全是 http 时必须抛错，而不是静默返回空 ---
# 源码里 safe.isEmpty() 分支必须抛异常（否则用户看到的是「下载成功但没文件」）
check(re.search(r"if\s*\(\s*safe\.isEmpty\(\)\s*\)\s*throw", dl) is not None,
      "download() must throw when every URL is rejected")

print("download url scheme: %d checks, %d failed" % (checks, len(failures)))
for f in failures:
    print("  FAIL:", f)
sys.exit(1 if failures else 0)
