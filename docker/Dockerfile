# apksim for Linux, headless, for CI: linux/amd64 (APKs' x86_64 native libraries) or linux/arm64 (their arm64-v8a
# ones), each built natively for its CPU. amd64 also runs on Apple Silicon under Rosetta, but arm64 runs there natively
# and is much faster. Build with scripts/docker-build.sh [TAG] [--platform linux/arm64], which stages the context.
FROM eclipse-temurin:24-jdk AS build
RUN apt-get update && apt-get install -y --no-install-recommends gcc libc6-dev unzip zip && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY native native
COPY lib /opt/apksim/work/lib
# Natives: the ELF loader and bionic shim (the window, Metal and VideoToolbox parts are macOS-only).
RUN mkdir -p /opt/apksim/work/runtime/icu && \
    gcc -O2 -shared -fPIC -D_GNU_SOURCE -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
      -o /opt/apksim/work/runtime/libelfloader.so native/elfloader.c native/linux/bionic_linux.c native/linux/jnibridge_linux.c -ldl -lpthread
# Robolectric's native runtime (Android's HWUI, Skia, SQLite for Linux), fonts and ICU data, unpacked once.
RUN cd /opt/apksim/work && ALL="$(ls lib/android-all-instrumented-*.jar)" && DIST="$(ls lib/nativeruntime-dist-compat-*.jar)" && \
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
# snapshot.py (screenshot tests, docs/snapshots.md) and drive.py.
RUN apt-get update && apt-get install -y --no-install-recommends libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri python3 && \
    rm -rf /var/lib/apt/lists/*
ENV EGL_PLATFORM=surfaceless
# State (startup cache, class cache, app data) stays in the image's own work/, also when work/release marks a version.
ENV APKSIM_HOME=/opt/apksim/work
COPY --from=build /opt/apksim /opt/apksim
COPY apksim /opt/apksim/apksim
COPY train.sh instrument.py affected.py snapshot.py drive.py /opt/apksim/scripts/
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
