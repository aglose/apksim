#!/bin/bash
# Writes the test app's media samples into app/src/main/assets/media (apksim://media, MediaActivity). Needs an ffmpeg
# with libx264, libx265, libvpx, libsvtav1, libopus and libmp3lame. The outputs are committed: rerun only to change them.
#
# Every video sample has the same 30 frames at 30 fps, so a decoded frame says which one it is: the top half is seven
# vertical stripes, a black one, the frame's index in binary (5 stripes, most significant first, white for 1) and a
# white one; the bottom half is mid grey. That survives scaling, either colour range and lossy coding, and a picture
# upside down or mirrored reads as no frame at all. Audio samples are a 1 kHz sine for 1 s.
set -euo pipefail
cd "$(dirname "$0")"
OUT=../app/src/main/assets/media
rm -rf "$OUT" hls
mkdir -p "$OUT"
F=(ffmpeg -loglevel error -y)
S='floor(X*7/W)'
LUM="if(lt(Y,H/2),if(eq($S,0),16,if(eq($S,6),235,if(mod(floor(N/pow(2,5-$S)),2),235,16))),128)"
src() { echo "color=black:s=$1:r=30:d=1,format=yuv420p,geq=lum='$LUM':cb=128:cr=128"; }
v() { "${F[@]}" -f lavfi -i "$(src "$1")" -frames:v 30 "${@:2}"; }

# Containers, for the player (Media3). The B-frame samples force them (b-adapt=0): on content this plain the encoders
# would otherwise choose none, and nothing would need reordering.
v 320x240 -c:v libx264 -profile:v baseline -pix_fmt yuv420p "$OUT/h264-baseline.mp4"
v 320x240 -c:v libx264 -profile:v high -x264-params bframes=3:b-adapt=0:b-pyramid=normal -pix_fmt yuv420p "$OUT/h264-high-bframes.mp4"
v 640x360 -c:v libx265 -x265-params log-level=error:bframes=3:b-adapt=0 -pix_fmt yuv420p -tag:v hvc1 "$OUT/hevc-main.mp4"
v 320x240 -c:v libx265 -x265-params log-level=error -pix_fmt yuv420p10le -tag:v hvc1 "$OUT/hevc-main10.mp4"
v 320x240 -c:v libvpx -b:v 400k -pix_fmt yuv420p "$OUT/vp8.webm"
v 320x240 -c:v libvpx-vp9 -b:v 400k -pix_fmt yuv420p "$OUT/vp9.webm"
v 320x240 -c:v libsvtav1 -svtav1-params loglevel=0 -pix_fmt yuv420p "$OUT/av1.mp4"
# Elementary streams, for MediaCodec on its own (no extractor): Annex B with access unit delimiters, and IVF. Annex B
# carries no timestamps, and with B-frames decode order is not presentation order, so each comes from an MP4 encode
# with NAME.pts beside it: each access unit's presentation time in microseconds, in decode order, one per line.
annexb() {  # MP4 OUT MUXER BSF
  "${F[@]}" -i "$1" -c:v copy -bsf:v "$4" -f "$3" "$OUT/$2"
  ffprobe -v error -select_streams v -show_entries packet=pts_time -of csv=p=0 "$1" \
    | awk '{ printf "%d\n", $1 * 1000000 + 0.5 }' > "$OUT/$2.pts"
}
T="$(mktemp -d)"
v 320x240 -c:v libx264 -profile:v high -x264-params bframes=3:b-adapt=0:b-pyramid=normal -pix_fmt yuv420p "$T/h264.mp4"
annexb "$T/h264.mp4" h264-high-bframes.h264 h264 h264_mp4toannexb,h264_metadata=aud=insert
v 320x240 -c:v libx265 -x265-params log-level=error:bframes=3:b-adapt=0 -pix_fmt yuv420p -tag:v hvc1 "$T/hevc.mp4"
annexb "$T/hevc.mp4" hevc-main.h265 hevc hevc_mp4toannexb,hevc_metadata=aud=insert
rm -rf "$T"
v 320x240 -c:v libvpx -b:v 400k -pix_fmt yuv420p -f ivf "$OUT/vp8.ivf"
v 320x240 -c:v libvpx-vp9 -b:v 400k -pix_fmt yuv420p -f ivf "$OUT/vp9.ivf"
v 320x240 -c:v libsvtav1 -svtav1-params loglevel=0 -pix_fmt yuv420p -f ivf "$OUT/av1.ivf"

# Audio: a 1 kHz sine, 1 s.
A=(-f lavfi -i sine=frequency=1000:sample_rate=44100:duration=1 -ac 2)
"${F[@]}" "${A[@]}" -c:a aac -b:a 96k "$OUT/aac-lc.m4a"
"${F[@]}" "${A[@]}" -c:a aac -b:a 96k -f adts "$OUT/aac-lc.adts"
"${F[@]}" "${A[@]}" -c:a aac_at -profile:a 4 -b:a 48k "$OUT/aac-he.m4a"
"${F[@]}" "${A[@]}" -c:a libmp3lame -b:a 96k "$OUT/mp3.mp3"
"${F[@]}" -f lavfi -i sine=frequency=1000:sample_rate=48000:duration=1 -ac 2 -c:a libopus -b:a 64k "$OUT/opus.ogg"
"${F[@]}" "${A[@]}" -c:a flac "$OUT/flac.flac"

# HLS (H.264 with B-frames and AAC in MPEG-TS, 2 segments of about half a second): served by the fixture server over the
# network (fixture/), not packaged. With sound: the HLS check verifies it, so a failure here must stop the script.
mkdir -p hls
"${F[@]}" -f lavfi -i "$(src 320x240)" -f lavfi -i sine=frequency=1000:sample_rate=44100:duration=3 -frames:v 30 \
  -c:v libx264 -x264-params bframes=2:b-adapt=0 -g 10 -keyint_min 10 -sc_threshold 0 -pix_fmt yuv420p -c:a aac -b:a 64k -ac 2 \
  -f hls -hls_time 0.34 -hls_list_size 0 -hls_segment_filename 'hls/seg%d.ts' hls/index.m3u8
ls -la "$OUT" hls
