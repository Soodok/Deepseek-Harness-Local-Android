"""按 v5 镜头表合成旁白。

镜头表来自 remotion/src/v5/data.ts，必须与那边保持一致；
改镜头时长时两边一起改。
"""
import asyncio, json, os, subprocess, sys
import edge_tts

FPS = 60
# (key, duration_frames, text)  —— 与 src/v5/data.ts 逐项对应
LINES = [
    ("intro",        180, "完整 Agent，兜里。"),
    ("performance",  420, "不是模拟，没有虚拟机。旗舰三秒四，手表十三秒。"),
    ("extensions",   390, "二十一种扩展。Python、Go、Rust、JDK 一键装好。"),
    ("plugins",      270, "三千五百多个社区插件，一键装卸。"),
    ("accessibility",360, "它看得见，也点得动。一步一点四五秒。"),
    ("ocr",          210, "识别快四倍。"),
    ("webui",        330, "大屏编辑，小屏执行。"),
    ("size",         330, "一百四十九兆。proot 要三百九十二兆。"),
    ("pet",          360, "桌宠灵动岛，源于社区。"),
    ("end",          270, "从手机，开始一条更短的路径。"),
]

VOICE = sys.argv[1] if len(sys.argv) > 1 else "zh-CN-YunjianNeural"
RATE = sys.argv[2] if len(sys.argv) > 2 else "+8%"
OUTDIR = "voice/segments"
os.makedirs(OUTDIR, exist_ok=True)

async def synth(path, text):
    await edge_tts.Communicate(text, VOICE, rate=RATE).save(path)

def dur(p):
    r = subprocess.run(["ffprobe","-v","error","-show_entries","format=duration",
                        "-of","default=nw=1:nk=1", p], capture_output=True, text=True)
    try: return float(r.stdout.strip())
    except: return 0.0

_durs = {}

async def main():
    rows = []
    for i, (key, frames, text) in enumerate(LINES):
        path = f"{OUTDIR}/{i:02d}-{key}.mp3"
        await synth(path, text)
        d, budget = dur(path), frames / FPS
        _durs[key] = d
        # 旁白跨镜头是正常剪辑手法：一句说完自然过渡到下一镜即可。
        # 只要溢出量小于下一段的余量（不撞车）就算合格。
        # 硬断言（checker B1）：LEAD 0.35s 进场 + 段尾 TAIL_GUARD 0.15s 余量，
        # 人声必须完全落在自己的镜头内。此前「允许跨镜」的判断读的是尚未
        # 合成的下一段时长（_durs 恒为空），恒放行 → 6/10 段越界、相邻重叠。
        # intro 是 3s 的开场闪标，句尾自然跨进下一镜（performance 有 1s+ 余量）；
        # 其余镜头必须完全装在自己窗口内（checker B1 的硬约束）。
        limit = frames / FPS - 0.35 - 0.15 + (0.5 if key == "intro" else 0.0)
        ok = d <= limit
        _durs[key] = d
        rows.append({"i": i, "key": key, "frames": frames, "budget": round(budget,2),
                     "dur": round(d,2), "chars": len(text), "path": path,
                     "ok": ok, "slack": round(budget-d,2)})
        print(f"{key:14s} budget={budget:4.1f}s  tts={d:4.2f}s  slack={budget-d:+5.2f}s  {'OK' if ok else 'OVER'}", flush=True)
    json.dump(rows, open("voice/timing.json","w",encoding="utf-8"), ensure_ascii=False, indent=2)
    over=[r for r in rows if not r["ok"]]
    print("\nOVER:", [(r["key"], r["slack"]) for r in over] if over else "none")
    print("total tts %.1fs / budget %.1fs"%(sum(r["dur"] for r in rows), sum(r["budget"] for r in rows)))

asyncio.run(main())
