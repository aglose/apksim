# apksim for Linux, headless, for CI: linux/amd64 (APKs' x86_64 native libraries) or linux/arm64 (their arm64-v8a
# ones), each built natively for its CPU. amd64 also runs on Apple Silicon under Rosetta, but arm64 runs there natively
# and is much faster. Build with scripts/docker-build.sh [TAG] [--platform linux/arm64], which stages the context.
FROM eclipse-temurin:24-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev unzip zip libopenh264-dev && rm -rf /var/lib/apt/lists/*
# H.264 decoding for MediaCodec (native/linux/avdecoder_linux.c): FFmpeg's libavcodec with only the decoders named in
# VIDEO_DECODERS and libswscale, built from the pinned release's source and linked statically into libvtdecoder.so below
# (LGPL 2.1+, see THIRD-PARTY-NOTICES.md). Before COPY native so a change to apksim's own code does not rebuild it.
# nasm is for x86_64's assembly (arm64's is assembled by gcc); pthreads is the C library's.
# h264 by default: the published image ships no HEVC decoder (its patent licensing is the less settled of the two).
# `--build-arg VIDEO_DECODERS=h264,hevc,vp9` adds HEVC and VP9 to an image you build yourself; MediaCodec lists what is there.
ARG VIDEO_DECODERS=h264
ARG FFMPEG_VERSION=7.1.5
ARG FFMPEG_SHA256=de668509caf9e35e3cd162473441fdb29538c6d96ed080292b3cf9e6fc5d558f
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates xz-utils make $([ "$(uname -m)" = x86_64 ] && echo nasm) && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir /tmp/ffmpeg && cd /tmp/ffmpeg && \
    curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz" && \
    echo "$FFMPEG_SHA256  ffmpeg.tar.xz" | sha256sum -c - && \
    tar xf ffmpeg.tar.xz && cd "ffmpeg-$FFMPEG_VERSION" && \
    ./configure --prefix=/opt/ffmpeg --disable-everything --disable-programs --disable-doc --disable-network \
      --disable-autodetect --disable-debug --disable-avdevice --disable-avformat --disable-avfilter --disable-swresample \
      --enable-pic --enable-static --disable-shared --enable-pthreads \
      --enable-decoder="$VIDEO_DECODERS" --enable-parser="$VIDEO_DECODERS" --enable-bsf=vp9_superframe_split --enable-swscale && \
    make -j"$(nproc)" && make install && \
    cd / && rm -rf /tmp/ffmpeg
WORKDIR /src
COPY native native
COPY lib /opt/apksim/work/lib
# Natives: the ELF loader and bionic shim (the window, Metal and VideoToolbox parts are macOS-only).
RUN mkdir -p /opt/apksim/work/runtime/icu && \
    gcc -O2 -shared -fPIC -D_GNU_SOURCE -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
      -o /opt/apksim/work/runtime/libelfloader.so native/elfloader.c native/linux/bionic_linux.c native/linux/jnibridge_linux.c native/jnigraphics.c -ldl -lpthread && \
    # apksim serve's H.264 stream: OpenH264, opened at run time (libopenh264-7 below; without it the stream stays JPEG).
    gcc -O2 -shared -fPIC -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
      -o /opt/apksim/work/runtime/libh264enc.so native/linux/h264enc_linux.c -ldl
# MediaCodec's video decoders: libvtdecoder.so (dev.apksim.video.VideoToolbox's natives on libavcodec). FFmpeg's archives
# are linked in with their symbols private (an APK may bring its own FFmpeg), so only the JNI functions are exported.
# The decoder is checked here on a clip per codec (B-frames and reordering, pts, size, colours, a reset), which is not
# shipped; a codec left out of VIDEO_DECODERS must not open.
COPY media /tmp/media
RUN FF=/opt/ffmpeg && L=/opt/apksim/work/runtime/libvtdecoder.so && \
    gcc -O2 -shared -fPIC -fvisibility=hidden -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I$FF/include \
      -o $L native/linux/avdecoder_linux.c \
      -Wl,--exclude-libs,ALL -Wl,-Bsymbolic -Wl,--gc-sections -Wl,-z,defs -Wl,-z,noexecstack \
      $FF/lib/libavcodec.a $FF/lib/libswscale.a $FF/lib/libavutil.a -lm -lpthread && \
    strip --strip-unneeded $L && \
    gcc -O2 -Wall -I$FF/include -o /tmp/avdecoder_check native/linux/avdecoder_check.c \
      $FF/lib/libavcodec.a $FF/lib/libswscale.a $FF/lib/libavutil.a -lm -lpthread && \
    /tmp/avdecoder_check /tmp/media/clip.h264 h264 640 360 && \
    case ",$VIDEO_DECODERS," in \
      *,hevc,*) /tmp/avdecoder_check /tmp/media/clip.h265 h265 640 360 ;; \
      *) /tmp/avdecoder_check --absent h265 ;; \
    esac && \
    # the checks must be able to fail: a wrong size is a failure, and H.264 is not absent
    ! /tmp/avdecoder_check --absent h264 && \
    ! /tmp/avdecoder_check /tmp/media/clip.h264 h264 641 360 2>/dev/null && \
    ! ldd $L | grep -E 'libav|libsw' && \
    ! nm -D --defined-only $L | awk '{print $3}' | grep -vE '^(Java_dev_apksim_video_VideoToolbox_|_init$|_fini$|_edata$|_end$|__bss_start$)' && \
    ls -l $L && rm -rf /tmp/media /tmp/avdecoder_check
# Robolectric's native runtime (Android's HWUI, Skia, SQLite for Linux), fonts and ICU data, unpacked once.
# The newest of each, should an older one be left in work/lib; the others go.
RUN cd /opt/apksim/work && ALL="$(ls lib/android-all-instrumented-*.jar | sort -V | tail -1)" && \
    DIST="$(ls lib/nativeruntime-dist-compat-*.jar | sort -V | tail -1)" && \
    for J in lib/nativeruntime-dist-compat-*.jar; do [ "$J" = "$DIST" ] || rm "$J"; done && \
    # Robolectric's runtime for this CPU (x86_64 or aarch64; aarch64 from nativeruntime-dist-compat 1.0.20) must be there.
    test -n "$(unzip -l "$DIST" "native/linux/$(uname -m)/*" | grep nativeruntime)" && \
    for JAR in "$ALL" "$DIST"; do \
      unzip -q -o "$JAR" 'fonts/*' 'hyphen-data/*' -d runtime 2>/dev/null || true; \
      unzip -q -o -j "$JAR" "native/linux/$(uname -m)/*" -d runtime 2>/dev/null || true; \
    done && \
    unzip -q -o -p "$ALL" "$(unzip -l "$ALL" | awk '{print $4}' | grep '^icu/icudt' | head -1)" > runtime/icu/icudt.dat && \
    # Now unpacked, they need not ship twice: the compat jar is not on the classpath, and android-all keeps its classes
    # and framework resources but drops natives for other platforms, fonts and ICU data.
    rm "$DIST" && zip -q -d "$ALL" 'native/*' 'fonts/*' 'icu/*' 'hyphen-data/*'
# Android 15+ frameworks run on android-all's own libandroid_runtime, and 16-robolectric-13921718-i7 has none for
# linux/aarch64: an arm64 build takes the one from its respin, which docker-build.sh puts in linux-arm64/ (see
# build.gradle.kts, linuxArm64Runtime). Either way both Robolectric libraries must be there for this CPU.
COPY linux-arm64 /tmp/linux-arm64
RUN cd /opt/apksim/work/runtime && \
    if [ ! -e libandroid_runtime.so ] && [ "$(uname -m)" = aarch64 ]; then cp /tmp/linux-arm64/libandroid_runtime.so .; fi && \
    test -e libandroid_runtime.so && test -e librobolectric-nativeruntime.so

FROM eclipse-temurin:24-jre
LABEL org.opencontainers.image.source=https://github.com/aglose/apksim \
      org.opencontainers.image.description="apksim: run Android APKs and their instrumented tests headless, no emulator" \
      org.opencontainers.image.licenses=Apache-2.0
# GLES for test runs that render offscreen (dev.apksim.gl): Mesa's software EGL and GLES, on EGL's surfaceless platform
# (no display server), where pbuffers work. Python for scripts/instrument.py (a whole suite in one container),
# snapshot.py (screenshot tests, docs/snapshots.md), drive.py and journey.py (docs/replay.md). OpenH264 for `apksim serve`'s H.264 stream.
RUN apt-get update && apt-get install -y --no-install-recommends libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri python3 libopenh264-7 && \
    rm -rf /var/lib/apt/lists/*
ENV EGL_PLATFORM=surfaceless
# State (startup cache, class cache, app data) stays in the image's own work/, also when work/release marks a version.
ENV APKSIM_HOME=/opt/apksim/work
COPY --from=build /opt/apksim /opt/apksim
COPY apksim /opt/apksim/apksim
COPY LICENSE NOTICE THIRD-PARTY-NOTICES.md /opt/apksim/
COPY train.sh instrument.py affected.py coverage.py snapshot.py crawl.py drive.py journey.py flake-hunt.py /opt/apksim/scripts/
COPY apksim.jar /opt/apksim/work/apksim.jar
# The JVM's ahead-of-time cache holds the framework for any APK; it must be built by this JVM, in this folder.
ARG TRAIN=1
RUN --mount=type=bind,source=train.apk,target=/tmp/train.apk \
    if [ "$TRAIN" = 1 ]; then /opt/apksim/scripts/train.sh /tmp/train.apk; fi && \
    rm -rf /opt/apksim/work/tmp/* /opt/apksim/work/cache /opt/apksim/work/train-*.log /opt/apksim/work/app.aotconf
# A release build names its version (`apksim --version` and the label); set after training, which it does not change.
ARG VERSION=
LABEL org.opencontainers.image.version=$VERSION
RUN if [ -n "$VERSION" ]; then echo "$VERSION" > /opt/apksim/work/release; fi
ENV PATH=/opt/apksim:/opt/apksim/scripts:$PATH
WORKDIR /work
ENTRYPOINT ["apksim"]
