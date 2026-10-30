#!/usr/bin/env python3
"""更新失败分类 / UI 不泄漏技术细节 / fork 身份声明（v1.4.37，§12 §17）。

## 为什么要这条探针

干净虚拟机首装验证时暴露的问题：关于页把异常原文直接怼给用户 ——

    Cannot get update info: HTTP 404 (https://gh-proxy.org/https://github.com/…/update-index.json)

用户看不懂，还顺带泄漏了代理地址。修法是把异常**按类型**归类成人话，原文只进日志。
这条探针锁三件事：

 1. **分类靠类型，不靠字符串**：`SecurityException → 验签失败`、`IOException → 网络`、
    `HTTP 404 → 无索引`。若有人改回 `message.contains("404")`，换 URL 或换文案就会静默失效
    （用户看到"检查更新失败"，日志里却没有分类）。
 2. **UI 不碰 message**：`AboutActivity` 的 UNAVAILABLE 分支只能走 `failureText`；
    一旦有人图省事把 `state.message` 塞回 TextView，URL 会重新出现在界面上。
 3. **fork 构建必须自报身份**：`RepoConfig.IS_FORK` 的上游常量必须是拆开写的，
    否则会被 `apply-fork-links.py` 一起替换成 fork 仓库，判定恒为 false（关于页就不再声明 Fork）。

运行：python scripts/probes/_test_update_failure_kind.py
"""
from __future__ import annotations

import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
UPDATE = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/update")
MODEL = os.path.join(UPDATE, "AppUpdate.kt")
CHECKER = os.path.join(UPDATE, "AppUpdateChecker.kt")
SCHED = os.path.join(UPDATE, "AppUpdateScheduler.kt")
TEXTMAP = os.path.join(UPDATE, "UpdateFailureText.kt")
ABOUT = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/AboutActivity.kt")
TMPL = os.path.join(ROOT, "app/src/main/java/app/dsh/mobile/engine/RepoConfig.kt.tmpl")
RES = os.path.join(ROOT, "app/src/main/res")

FAIL_KEYS = [
    "update_fail_no_index",
    "update_fail_network",
    "update_fail_signature",
    "update_fail_channel",
    "update_fail_too_old",
    "update_fail_no_asset",
    "update_fail_unknown",
]
ENUM_MEMBERS = [
    "None", "NoIndex", "Network", "Signature", "Channel", "TooOld", "NoAsset", "Unknown",
]

failures: list[str] = []


def check(name: str, got, want) -> None:
    if got == want:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name} — got {got!r}, want {want!r}")
        failures.append(name)


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def strip_comments(src: str) -> str:
    """去掉行注释与块注释：注释里提到 message / 404 不算实现。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


# ── 复刻：AppUpdateChecker.classify（按类型，不按字符串） ──────────────────


class SecurityErr(Exception):
    pass


class IoErr(Exception):
    pass


class HttpErr(Exception):
    pass


class Other(Exception):
    pass


def classify(exc: Exception) -> str:
    """与 Kotlin 同构：沿 cause 链最多看 6 层，先命中先返回。"""
    cur: Exception | None = exc
    for _ in range(6):
        if cur is None:
            break
        if isinstance(cur, SecurityErr):
            return "Signature"
        if isinstance(cur, IoErr):
            return "Network"
        if isinstance(cur, HttpErr) and "HTTP 404" in str(cur):
            return "NoIndex"
        cur = cur.__cause__
    return "Unknown"


def main() -> int:
    model = read(MODEL)
    checker = strip_comments(read(CHECKER))
    sched = read(SCHED)
    textmap = read(TEXTMAP)
    about = strip_comments(read(ABOUT))
    tmpl = read(TMPL)

    print("== 1. 失败分类靠异常类型，不靠字符串 ==")
    check("SecurityException → 验签", classify(SecurityErr("update-index signature mismatch")), "Signature")
    check("IOException → 网络", classify(IoErr("timeout")), "Network")
    check("HTTP 404 → 无索引", classify(HttpErr("HTTP 404 (https://x)")), "NoIndex")
    check("HTTP 500 → 未知（不冒充无索引）", classify(HttpErr("HTTP 500 (https://x)")), "Unknown")
    check("未知异常 → Unknown", classify(Other("boom")), "Unknown")
    # 包装一层（SignedIndex.Unavailable 会把真正原因挂在 cause 上）
    wrapped = Other("unavailable")
    wrapped.__cause__ = SecurityErr("signature mismatch")
    check("Unavailable 包装后仍能识别 cause", classify(wrapped), "Signature")
    check("classify 里有 `is SecurityException`", "is SecurityException" in checker, True)
    check("classify 里有 `is java.io.IOException`", "is java.io.IOException" in checker, True)
    check("classify 里没有按 message 匹配 signature", 'contains("signature")' in checker, False)

    print("== 2. 分类贯通到状态（checker → bus → UI） ==")
    check("enum UpdateFailure 存在", "enum class UpdateFailure" in model, True)
    for m in ENUM_MEMBERS:
        check(f"  member {m}", re.search(rf"^\s*{m},", model, re.M) is not None, True)
    check("UpdateState 带 failure 字段", re.search(r"val failure: UpdateFailure", model) is not None, True)
    check("Result.Failed 带 failure", re.search(r"data class Failed\(\s*val reason: String,\s*val failure", checker) is not None, True)
    check("Scheduler 把 failure 写进 UNAVAILABLE", "failure = result.failure" in sched, True)

    print("== 3. UI 只显示人话（不泄漏 URL / HTTP 码） ==")
    check("AboutActivity 用 failureText", "state.failureText(this)" in about, True)
    # 只约束 UNAVAILABLE 分支：UP_TO_DATE 借 message 传版本名、FAILED 传安装回执，
    # 都是有意为之的数据通道，不算"泄漏技术细节"。
    m = re.search(r"Phase\.UNAVAILABLE -> \{(.*?)\n            \}", about, re.S)
    check("UNAVAILABLE 分支可定位", m is not None, True)
    body = m.group(1) if m else ""
    check("UNAVAILABLE 分支不渲染 state.message", "state.message" in body, False)
    check("UpdateFailureText 覆盖全部枚举", textmap.count("UpdateFailure.") >= len(ENUM_MEMBERS) - 1, True)

    print("== 4. 三语（含 pt 兜底）都有分类文案 ==")
    for loc in ("values", "values-de", "values-zh-rCN", "values-pt-rBR"):
        src = read(os.path.join(RES, loc, "strings.xml"))
        missing = [k for k in FAIL_KEYS if f'name="{k}"' not in src]
        check(f"  {loc} 缺键 {missing}", missing, [])
        check(f"  {loc} 有 about_fork_badge", 'name="about_fork_badge"' in src, True)

    print("== 5. fork 构建自报身份 ==")
    check("RepoConfig 有 IS_FORK", "val IS_FORK" in tmpl, True)
    check(
        "上游 owner/name 拆开写（躲过 apply-fork-links 的全量替换）",
        'UPSTREAM_OWNER = "Soodok"' in tmpl and "UPSTREAM_NAME =" in tmpl,
        True,
    )
    # IS_FORK 那一行不能直接内联完整上游路径，否则脚本一替换就恒等
    is_fork_line = next((ln for ln in tmpl.splitlines() if "val IS_FORK" in ln), "")
    check("IS_FORK 判定不内联完整上游路径", "Soodok/Deepseek-Harness-Local-Android" not in is_fork_line, True)
    check("AboutActivity 用 IS_FORK 控制标记", "RepoConfig.IS_FORK" in about, True)
    check("AboutActivity 仓库地址取自 RepoConfig", "RepoConfig.REPO" in about, True)

    print()
    if failures:
        print(f"FAILED: {len(failures)} 项")
        for f in failures:
            print("  -", f)
        return 1
    print("ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
