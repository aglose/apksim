#!/bin/bash
# Writes the 2 s clips `selftest FILE` checks MediaCodec with into work/media (needs ffmpeg with x264 and x265):
# test pattern video as Annex B H.264 and HEVC with access unit delimiters, VP9 in IVF (libvpx), a 440 Hz tone as ADTS AAC and MP3.
set -euo pipefail
OUT="$(cd "$(dirname "$0")/.." && pwd)/work/media"
mkdir -p "$OUT"
F=(ffmpeg -loglevel error -y)
"${F[@]}" -f lavfi -i testsrc2=size=640x360:rate=30 -t 2 -c:v libx264 -x264-params aud=1 -pix_fmt yuv420p "$OUT/clip.h264"
"${F[@]}" -f lavfi -i testsrc2=size=640x360:rate=30 -t 2 -c:v libx265 -x265-params aud=1:log-level=error -pix_fmt yuv420p "$OUT/clip.h265"
"${F[@]}" -f lavfi -i testsrc2=size=640x360:rate=30 -t 2 -c:v libvpx-vp9 -pix_fmt yuv420p -f ivf "$OUT/clip.ivf"
"${F[@]}" -f lavfi -i sine=frequency=440:duration=2 -ac 2 -c:a aac -b:a 128k "$OUT/clip.aac"
"${F[@]}" -f lavfi -i sine=frequency=440:duration=2 -ac 2 -ar 44100 -c:a libmp3lame -b:a 128k "$OUT/clip.mp3"
ls "$OUT"
