# apksim for Linux x86_64: headless, for CI. Runs on x86_64 runners natively and on Apple Silicon under
# Rosetta (Colima/Docker with Rosetta enabled). Build with scripts/docker-build.sh, which stages the context.
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
    for JAR in "$ALL" "$DIST"; do \
      unzip -q -o "$JAR" 'fonts/*' 'hyphen-data/*' -d runtime 2>/dev/null || true; \
      unzip -q -o -j "$JAR" 'native/linux/x86_64/*' -d runtime 2>/dev/null || true; \
    done && \
    unzip -q -o -p "$ALL" "$(unzip -l "$ALL" | awk '{print $4}' | grep '^icu/icudt' | head -1)" > runtime/icu/icudt.dat && \
    # Now unpacked, they need not ship twice: the compat jar is not on the classpath, and android-all keeps its classes
    # and framework resources but drops natives for other platforms, fonts and ICU data.
    rm "$DIST" && zip -q -d "$ALL" 'native/*' 'fonts/*' 'icu/*' 'hyphen-data/*'

FROM eclipse-temurin:24-jre
LABEL org.opencontainers.image.source=https://github.com/aglose/apksim \
      org.opencontainers.image.description="apksim: run Android APKs and their instrumented tests headless, no emulator" \
      org.opencontainers.image.licenses=Apache-2.0
# GLES for test runs that render offscreen (dev.apksim.gl): Mesa's software EGL and GLES, on EGL's surfaceless platform
# (no display server), where pbuffers work. Python for scripts/instrument.py (a whole suite in one container) and
# drive.py.
RUN apt-get update && apt-get install -y --no-install-recommends libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri python3 && \
    rm -rf /var/lib/apt/lists/*
ENV EGL_PLATFORM=surfaceless
# State (startup cache, class cache, app data) stays in the image's own work/, also when work/release marks a version.
ENV APKSIM_HOME=/opt/apksim/work
COPY --from=build /opt/apksim /opt/apksim
COPY apksim /opt/apksim/apksim
COPY train.sh instrument.py affected.py drive.py /opt/apksim/scripts/
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
