#!/usr/bin/env python3
"""从 dsh-pet-android 的 APK 里抽出素材，打成 DSH Mobile 可导入的桌宠动画包。

这个脚本不生成任何画面，只做三件事：
  1. 从 APK 的 assets/ 里取出 pet-config.json 与 webp/*.webp（上游原始素材）；
  2. 按 App 侧 PetPack / PetPackInstaller 的规则改写 config（见下）；
  3. 打成 zip，并做与导入器同规则的校验。

config 的改写只有三处，全部是为了适配 App 侧语义，不改上游素材与名字：
  - animations.events.workStatus：App 用它映射 6 个工作状态槽位
    （0 思考 1 工作 2 单步完成 3 搜索 4 庆祝 5 出错，见 PetActionController）；
    上游 config 没有这一段时 App 走内置默认值，这里显式写出，避免依赖默认。
  - durations：App 用 duration 决定一次性动画何时回收（PetView.scheduleOneShot）。
    上游 config 全部是常量 10041ms，这里改成从 WebP 里实测的帧时长之和，
    保证「动画放完」与「App 回收」对得上。
  - pets[0].corner/marginX/marginY：沿用上游值，仅确保是 App 认识的顶层字段。

许可：素材来自 PC2005-cloud/dsh-pet，允许开源使用、禁止商用（见包内 NOTICE.md）。
本脚本只做搬运与配置适配。
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
import zipfile
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# PetActionController.playWorkStatus 的六个槽位语义。
# slug 在上游不同版本会改号（v1.8.1 把工作状态挪到 a046-a051），所以按动画名解析，
# 不能写死 slug。
WORK_STATUS_SLOTS = [
    ("思考",),
    ("忙碌",),
    ("清点", "归档", "完成"),
    ("读取", "搜索", "张望", "踱步"),
    ("庆祝", "雀跃"),
    ("垂头", "叹气", "失落", "冒汗"),
]


def resolve_work_status(names: dict, available: set) -> list:
    """从 names 里按语义挑 6 个槽位；找不到的槽位回落到 idle 之外的第一个工作状态。"""
    work = [(slug, name) for slug, name in names.items()
            if "工作状态" in name and slug in available]
    picked, used = [], set()
    for keywords in WORK_STATUS_SLOTS:
        hit = next((s for s, n in work if s not in used and any(k in n for k in keywords)), None)
        if hit:
            picked.append(hit)
            used.add(hit)
        else:
            picked.append(None)
    # 兜底：空槽用还没被占用的工作状态段补，再不行用 idle
    spare = [s for s, _ in work if s not in used]
    idle = next((s for s in names if "待机" in names[s]), None)
    return [slot or (spare.pop(0) if spare else idle) for slot in picked]


def u24(b: bytes) -> int:
    return b[0] | (b[1] << 8) | (b[2] << 16)


def webp_frames(data: bytes):
    """返回 (帧数, 总时长ms, 是否动画, 画布宽高)。"""
    if data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        return 0, 0, False, (0, 0)
    pos, chunks = 12, []
    while pos + 8 <= len(data):
        fourcc = data[pos:pos + 4]
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        chunks.append((fourcc, data[pos + 8:pos + 8 + size]))
        pos += 8 + size + (size & 1)
    names = [c[0] for c in chunks]
    animated = b"ANIM" in names and b"VP8X" in names
    vp8x = dict(chunks).get(b"VP8X")
    wh = (0, 0)
    if vp8x:
        wh = (u24(vp8x[4:7]) + 1, u24(vp8x[7:10]) + 1)
    total = 0
    frames = [c[1] for c in chunks if c[0] == b"ANMF"]
    for fr in frames:
        total += u24(fr[12:15]) + 1
    return len(frames), total, animated, wh


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True, help="dsh-pet-android 的 300p/480p APK")
    ap.add_argument("--out", default="dist")
    ap.add_argument("--label", default=None, help="包名后缀，默认取 APK 文件名里的 XXXp")
    args = ap.parse_args(argv)

    apk = Path(args.apk)
    out_dir = Path(args.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    label = args.label
    if not label:
        label = next((p for p in apk.stem.split("-") if p.endswith("p")), "pack")

    with zipfile.ZipFile(apk) as zf:
        cfg_entry = next((n for n in zf.namelist()
                          if n.endswith("pet-config.json")), None)
        if not cfg_entry:
            print("APK 里没有 pet-config.json", file=sys.stderr)
            return 1
        cfg = json.loads(zf.read(cfg_entry).decode("utf-8"))

        webp_entries = {}
        for n in zf.namelist():
            if not n.lower().endswith(".webp"):
                continue
            parts = n.split("/")
            if len(parts) < 2 or parts[-2] != "webp":
                continue
            webp_entries[parts[-1][:-5]] = n
        if not webp_entries:
            print("APK 里没有 assets/webp/*.webp", file=sys.stderr)
            return 1

        pack_dir = out_dir / f"pet-pack-dsh-pet-{label}"
        webp_dir = pack_dir / "webp"
        webp_dir.mkdir(parents=True, exist_ok=True)

        report, real_durations, canvas = [], {}, None
        for slug in sorted(webp_entries):
            data = zf.read(webp_entries[slug])
            frames, dur, animated, wh = webp_frames(data)
            if not animated:
                print(f"{slug}: 不是动画 WebP（App 会退回占位形象）", file=sys.stderr)
                return 1
            if frames == 0:
                print(f"{slug}: 没有 ANMF 帧", file=sys.stderr)
                return 1
            real_durations[slug] = dur
            canvas = canvas or wh
            if wh != canvas:
                print(f"{slug}: 画布 {wh} 与其他 {canvas} 不一致", file=sys.stderr)
                return 1
            (webp_dir / f"{slug}.webp").write_bytes(data)
            report.append((slug, frames, dur, len(data)))

        # ---- config: 只做三处适配 ----
        if "animations" not in cfg:
            print("config 缺少 animations", file=sys.stderr)
            return 1
        anims = cfg["animations"]
        names = cfg.get("names") or {}
        work = resolve_work_status(names, set(webp_entries))
        if any(s is None for s in work):
            print(f"工作状态槽位解析失败: {work}", file=sys.stderr)
            return 1
        anims.setdefault("events", {})["workStatus"] = work

        old = cfg.get("durations") or {}
        changed = []
        cfg["durations"] = {}
        for slug, dur in sorted(real_durations.items()):
            previous = old.get(slug)
            cfg["durations"][slug] = dur
            if previous != dur:
                changed.append((slug, previous, dur))

        pet = (cfg.get("pets") or [{}])[0]
        pet.setdefault("corner", "top-right")
        pet.setdefault("marginX", 24)
        pet.setdefault("marginY", 100)

        cfg["pack"] = {
            "id": f"dsh-pet-android-{label}",
            "source": "https://github.com/Tony-Chen-Stark/dsh-pet-android",
            "upstream": "https://github.com/PC2005-cloud/dsh-pet",
            "license": "assets: open-source use only, non-commercial; code: MIT",
            "resolution": f"{canvas[0]}x{canvas[1]}",
            "note": "由上游 APK 原样提取；仅改写 events.workStatus 与 durations 以适配 DSH Mobile",
        }

        (pack_dir / "pet-config.json").write_text(
            json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

        # 附带上游许可文本（导入器会忽略非 webp/config 条目）
        for name, src in (("NOTICE.md", ".scratch/upstream/NOTICE.md"),
                          ("LICENSE", ".scratch/upstream/LICENSE")):
            if Path(src).is_file():
                (pack_dir / name).write_text(
                    Path(src).read_text(encoding="utf-8"), encoding="utf-8")

    # ---- 打 zip ----
    zip_path = out_dir / f"pet-pack-dsh-pet-{label}.zip"
    files = [p for p in sorted(pack_dir.rglob("*")) if p.is_file()]
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in files:
            z.write(p, p.relative_to(pack_dir).as_posix())

    total_raw = sum(r[3] for r in report)
    print(f"素材: {len(report)} 段, 画布 {canvas[0]}x{canvas[1]}, "
          f"webp 合计 {total_raw / 1048576:.1f} MB")
    print(f"zip : {zip_path} ({zip_path.stat().st_size / 1048576:.1f} MB)")
    if changed:
        print(f"duration 修正 {len(changed)} 段（上游常量 -> 实测）：")
        for slug, before, after in changed[:8]:
            print(f"  {slug}: {before} -> {after}")
        if len(changed) > 8:
            print(f"  ... 其余 {len(changed) - 8} 段同样修正")

    return validate(zip_path, cfg, real_durations, canvas)


def validate(zip_path: Path, cfg: dict, durations: dict, canvas) -> int:
    """按 App 侧 PetPackInstaller + PetPack 的规则做离线校验。"""
    errors = []
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        if len(names) > 2000:
            errors.append(f"entries {len(names)} > 2000")

        avail = set()
        for raw in names:
            if raw.startswith("/") or ":" in raw or raw.split("/") in ([],):
                errors.append(f"unsafe path: {raw}")
            if any(p in ("..", ".") for p in raw.split("/")):
                errors.append(f"unsafe path: {raw}")
            if raw.endswith("pet-config.json"):
                continue
            if raw.lower().endswith(".webp"):
                parts = raw.split("/")
                if len(parts) < 2 or parts[-2] != "webp":
                    errors.append(f"webp 不在 webp/ 下: {raw}")
                else:
                    avail.add(parts[-1][:-5])

        if sum(1 for n in names if n.endswith("pet-config.json")) != 1:
            errors.append("pet-config.json 数量不是 1")
        if not (set(cfg["animations"].get("idle", [])) & avail):
            errors.append("idle 没有可用 webp（App 会拒绝导入）")
        if not (set(cfg["animations"].get("clicks", [])) & avail):
            errors.append("clicks 没有可用 webp（App 会拒绝导入）")

        for slug in cfg["animations"]["events"]["workStatus"]:
            if slug not in avail:
                errors.append(f"workStatus {slug} 未打包")
        for cat in cfg["animations"].get("categories", []):
            for slug in cat.get("actions", []):
                if slug not in avail:
                    errors.append(f"分类 {cat.get('id')} 的 {slug} 未打包")

        # 一次性动画：App 按 duration 回收，duration 必须覆盖整段
        for slug, dur in durations.items():
            if dur <= 0:
                errors.append(f"{slug}: duration {dur}")
            elif cfg["durations"].get(slug) != dur:
                errors.append(f"{slug}: config duration 与实测不一致")

        b = zf.getinfo("pet-config.json")
        if b.file_size > 1024 * 1024:
            errors.append("pet-config.json > 1MB")

        idle = cfg["animations"].get("idle", [])
        for slug in idle:
            if slug not in avail:
                errors.append(f"idle {slug} 未打包")

    if errors:
        for e in errors:
            print(f"  FAIL {e}")
        return 1
    print(f"  OK 导入器校验通过：{len(avail)} 段 webp，"
          f"idle={idle}，clicks={len(cfg['animations']['clicks'])} 段")
    print(f"     workStatus 槽位 = {cfg['animations']['events']['workStatus']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
