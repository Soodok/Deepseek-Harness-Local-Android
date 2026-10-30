"""把分段旁白按镜头起点拼成整条音轨。

镜头起点来自 remotion/src/v4/data.ts（v5 若改了时长，改这里的 SHOTS 即可）。
每段人声在其镜头内**前 0.35 秒后**开始，避免与转场撞在一起。
"""
import json, os, subprocess, sys

# v5 时间轴，与 remotion/src/v5/data.ts 逐项对应
FPS = 60
SHOTS = [
    ("intro", 0, 180), ("performance", 180, 420), ("extensions", 600, 390),
    ("plugins", 990, 270), ("accessibility", 1260, 360), ("ocr", 1620, 210),
    ("webui", 1830, 330), ("size", 2160, 330), ("pet", 2490, 360),
    ("end", 2850, 270),
]
TOTAL_FRAMES = 3120
LEAD = 0.35          # 每段前留白（秒）
# intro 是 3s 开场闪标，第一帧就该出声（幕前留白会让开场空半秒）。
INTRO_LEAD = 0.05
# 收尾镜头不留 LEAD —— 旁白要早一点起，才能在片尾留出余韵
LAST_LEAD = 0.05
TAIL_GUARD = 0.15    # 段尾最低余量

timing = {r["key"]: r for r in json.load(open("voice/timing.json", encoding="utf-8"))}

def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print("FAILED:", " ".join(cmd)); print(r.stderr[-1500:]); sys.exit(1)

total = TOTAL_FRAMES / FPS
cover = 0.0
parts = []
for key, start, dur in SHOTS:
    t0 = start / FPS + (INTRO_LEAD if key == "intro" else (LAST_LEAD if key == "end" else LEAD))
    seg = timing[key]["path"]
    parts.append((seg, t0))
    cover = max(cover, t0 + timing[key]["dur"])

print(f"track length {total:.2f}s, last voice ends {cover:.2f}s, tail {total-cover:.2f}s")

# ── 硬断言（checker B1）───────────────────────────────────────────────
# 每段人声必须完全落在自己的镜头内：起点 = 镜头起点 + LEAD，
# 终点 = 起点 + dur，要求 终点 <= 镜头终点 - TAIL_GUARD。
# amix 用 normalize=0，重叠处两条人声会直接相加（不闪避）→ 必须前置拦住。
violations = []
for key, start, dur in SHOTS:
    lead = INTRO_LEAD if key == "intro" else (LAST_LEAD if key == "end" else LEAD)
    slot_end = (start + dur) / FPS
    voice_end = start / FPS + lead + timing[key]["dur"]
    room = slot_end - TAIL_GUARD
    if voice_end > room:
        violations.append(
            f"{key}: 人声止于 {voice_end:.2f}s，镜头留给它的上限是 {room:.2f}s"
            f"（超出 {voice_end - room:+.2f}s）"
        )
if violations:
    print("")
    print("❌ 旁白越界 —— 会与相邻镜头的人声重叠：")
    for v in violations:
        print("   " + v)
    print("   修法：python voice/tts.py 里对应句子改短或加语速，然后重跑本脚本。")
    sys.exit(1)
print("✅ 全部人声落在各自镜头内（含 %.2fs 段尾余量）" % TAIL_GUARD)

# 用 adelay + amix 精确摆位，输出 48kHz 立体声（成片通用）
inputs = []
for seg, _ in parts:
    inputs += ["-i", seg]

filters = []
for i, (seg, t0) in enumerate(parts):
    ms = int(round(t0 * 1000))
    filters.append(f"[{i}:a]adelay={ms}|{ms},aformat=sample_rates=48000:channel_layouts=stereo[v{i}]")

mix_in = "".join(f"[v{i}]" for i in range(len(parts)))
# apad 必须在 filtergraph 内补齐：amix 会在最后一个输入结束时收尾，
# 不补的话片尾比画面短，混流后音视频长度不一致。
# （-af 不能和 -filter_complex 同时用，必须写成链式过滤）
filters.append(f"{mix_in}amix=inputs={len(parts)}:normalize=0:dropout_transition=0,apad[out]")

cmd = ["ffmpeg", "-y", "-v", "error"] + inputs + [
    "-filter_complex", ";".join(filters),
    "-map", "[out]",
    "-t", f"{total:.3f}",
    "-c:a", "pcm_s16le", "voice/narration-raw.wav",
]
run(cmd)

# 响度标准化到 -16 LUFS（网络视频旁白通用目标），限幅 -1.5 dBTP 防削顶。
# TTS 原始输出 mean -28dB 太小，混进成片会被音乐完全盖住。
# 单独一步做：-af 不能和 -filter_complex 同时用在同一趟里。
run(["ffmpeg", "-y", "-v", "error", "-i", "voice/narration-raw.wav",
     "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
     "-c:a", "pcm_s16le", "voice/narration.wav"])

# 顺带导一条 mp3 便于试听
run(["ffmpeg", "-y", "-v", "error", "-i", "voice/narration.wav",
     "-c:a", "libmp3lame", "-b:a", "192k", "voice/narration.mp3"])

for p in ("voice/narration.wav", "voice/narration.mp3"):
    print(p, f"{os.path.getsize(p)/1e6:.2f} MB")
