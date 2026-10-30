#!/usr/bin/env bash
# 一条命令出正式成片：混旁白 + 生成带字幕版本。
# 用法: ./voice/finalize.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PORTRAIT=remotion/out/dsh-mobile-portrait-v5.mp4
LANDSCAPE=remotion/out/dsh-mobile-landscape-v5.mp4
OUT=remotion/out

mix() {
  local in="$1" out="$2"
  [ -f "$in" ] || { echo "跳过（不存在）: $in"; return 0; }
  echo "混流 $in -> $out"
  ffmpeg -y -v error -i "$in" -i voice/narration.wav \
    -map 0:v:0 -map 1:a:0 -c:v copy -c:a aac -b:a 192k \
    -shortest -movflags +faststart "$out"
  ffprobe -v error -show_entries format=duration,size -of default=nw=1 "$out"
}

mix "$PORTRAIT"  "$OUT/dsh-mobile-portrait-v5-narrated.mp4"
mix "$LANDSCAPE" "$OUT/dsh-mobile-landscape-v5-narrated.mp4"

# 硬字幕版：剪映里可直接用，也可当审核对照
if [ -f "$OUT/dsh-mobile-portrait-v5-narrated.mp4" ]; then
  ffmpeg -y -v error -i "$OUT/dsh-mobile-portrait-v5-narrated.mp4" \
    -vf "subtitles=voice/subtitles.srt:force_style='FontName=Microsoft YaHei,FontSize=20,PrimaryColour=&H00FFFFFF,BorderStyle=1,OutlineColour=&H00000000,Outline=2,Shadow=0,MarginV=80'" \
    -c:v libx264 -crf 20 -preset medium -c:a copy \
    -movflags +faststart "$OUT/dsh-mobile-portrait-v5-subbed.mp4"
  echo "字幕版 -> $OUT/dsh-mobile-portrait-v5-subbed.mp4"
fi

ls -lh "$OUT"/*v5*.mp4 2>/dev/null || true
