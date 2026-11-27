#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
工作区注册表「同 path 多认领」现场修复脚本（issue 应急版）。

用途：不用等新 APK，直接从电脑上把手机里那张坏掉的 `storages/workspace.json`
修好。适用于报错：

    workspace domain is inconsistent: path '<P>' is claimed by both
      workspace '<A>' and workspace '<B>'

为什么必须这么修：上游 `validateStoredState` 把「一个 path 只能一个 workspace」
当硬约束，抛在 `[cordis.init]` 里 → workspace / storage-domain /
session-persistence 全 failed → workspaceRegistry 缺失 → session-controller /
workspace-controller / ui-deliverables 全部 pending。用户看到「会话全没了」，
而磁盘上会话文件一个都没少，坏的只是这张表。

用法（两种，任选）：

  1) 直接在手机上跑（设备已 root，且已装 python3 扩展）：
       adb push fix_workspace_registry.py /data/local/tmp/
       adb shell "python3 /data/local/tmp/fix_workspace_registry.py --apply"

  2) 拉回电脑改好再推回去（需要 adb root）：
       adb root
       adb pull /data/user/0/app.dsh.mobile/files/dsh-home/storages/workspace.json ws.json
       python fix_workspace_registry.py --file ws.json --apply     # 原地备份后改写
       adb push ws.json /data/local/tmp/ws.json
       adb shell "cp /data/local/tmp/ws.json /data/user/0/app.dsh.mobile/files/dsh-home/storages/workspace.json"
       adb shell "chown \\$(stat -c '%u:%g' /data/user/0/app.dsh.mobile/files/dsh-home/storages) /data/user/0/app.dsh.mobile/files/dsh-home/storages/workspace.json"

**执行顺序铁律**：先 `adb shell am force-stop app.dsh.mobile`，再改文件，最后启动
App。引擎把这张表当独占文件 —— 运行期间改会被它的旧内存状态整份覆盖掉（这是
v1.4.7 用户实测过的「恢复成功、重启后全没了」）。

规则与 App 内 `WorkspaceDedupe.kt` 完全一致（赢家=顺序表靠前、会话取并集、
时间戳取极值、path 原样不变）。
"""

import argparse
import io
import json
import os
import shutil
import sys
import time


def norm_path(p):
    s = (p or "").strip().replace("\\", "/")
    while len(s) > 1 and s.endswith("/"):
        s = s[:-1]
    while "//" in s:
        s = s.replace("//", "/")
    return s


def earlier(a, b):
    if not a:
        return b
    if not b:
        return a
    return b if b < a else a


def later(a, b):
    if not a:
        return b
    if not b:
        return a
    return b if b > a else a


def plan(order, records):
    """返回 (keep_by_id, merges, new_order)。records 是 {id: dict}。"""
    visit = []
    for i in order:
        if i in records and i not in visit:
            visit.append(i)
    for i in records:
        if i not in visit:
            visit.append(i)

    winner_by_path = {}
    merged = {}
    merges = []
    dropped = set()

    for i in visit:
        src = records[i]
        key = norm_path(src.get("path", ""))
        w = winner_by_path.get(key)
        if w is None:
            winner_by_path[key] = i
            merged[i] = dict(src)
            merged[i]["sessionIds"] = [x for x in (src.get("sessionIds") or []) if x]
            continue
        cur = merged[w]
        have = set(cur.get("sessionIds") or [])
        extra = [x for x in (src.get("sessionIds") or []) if x and x not in have]
        cur["sessionIds"] = (cur.get("sessionIds") or []) + extra
        if not (cur.get("title") or ""):
            cur["title"] = src.get("title") or ""
        cur["createdAt"] = earlier(cur.get("createdAt") or "", src.get("createdAt") or "")
        cur["updatedAt"] = later(cur.get("updatedAt") or "", src.get("updatedAt") or "")
        merges.append((w, i, cur.get("path")))
        dropped.add(i)

    keep = {}
    for i in order:
        if i in dropped or i not in merged:
            continue
        keep[i] = merged.pop(i)
    for i, r in merged.items():
        keep[i] = r

    return keep, merges, [i for i in order if i not in dropped]


def fix(root, apply=False):
    global_ = root.get("global")
    if not isinstance(global_, dict):
        print("no global section; nothing to do")
        return 0
    tables = (root.get("tables") or {}).get("workspaces")
    if not isinstance(tables, dict):
        print("no workspaces table; nothing to do")
        return 0

    order = [x for x in (global_.get("workspaceIds") or []) if x]
    keep, merges, new_order = plan(order, tables)
    if not merges:
        print("registry is clean (no duplicate path); nothing to do")
        return 0

    for w, l, p in merges:
        print("merge: %s -> %s  (same path %s)" % (l, w, p))
    if not apply:
        print()
        print("dry-run only; %d merge(s) would be applied. Re-run with --apply." % len(merges))
        return len(merges)

    for w, l, _ in merges:
        root["tables"]["workspaces"].pop(l, None)
    for i, r in keep.items():
        root["tables"]["workspaces"][i] = r
    global_["workspaceIds"] = new_order
    print("applied %d merge(s); %d workspace(s) remain" % (len(merges), len(keep)))
    return len(merges)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", default=os.environ.get("WS_REGISTRY") or (
        "/data/user/0/app.dsh.mobile/files/dsh-home/storages/workspace.json"))
    ap.add_argument("--apply", action="store_true", help="真的改写（默认只预览）")
    a = ap.parse_args()

    if not os.path.isfile(a.file):
        print("not found: %s" % a.file)
        return 2
    with io.open(a.file, encoding="utf-8") as f:
        text = f.read()
    try:
        root = json.loads(text)
    except Exception as e:
        print("unparseable JSON (%s); refusing to touch it" % e)
        return 2

    changed = fix(root, apply=a.apply)
    if changed and a.apply:
        backup = a.file + ".before-dedupe-" + time.strftime("%Y%m%d-%H%M%S")
        shutil.copy2(a.file, backup)
        print("backup: %s" % backup)
        tmp = a.file + ".dedupe-tmp"
        with io.open(tmp, "w", encoding="utf-8") as f:
            f.write(json.dumps(root, indent=2, ensure_ascii=False) + "\n")
        os.replace(tmp, a.file)
        print("written: %s" % a.file)
        print()
        print("NEXT: force-stop the app, then start it. If the app was running while")
        print("      you wrote this file, the engine will overwrite it from memory.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
