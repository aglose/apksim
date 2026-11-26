# Linux and Docker

Each release publishes a headless Linux image, `ghcr.io/aglose/apksim:VERSION`, for x86_64 and arm64 under the
one tag (Docker pulls the host's), with `:latest` following the newest release. It's about 565 MB and holds:

- the launcher and a trained startup cache;
- Mesa, for offscreen GLES;
- `instrument.py`, `coverage.py`, `flake-hunt.py`, `snapshot.py`, `crawl.py`, `journey.py` and `drive.py` on the `PATH`.

Pin a version in CI: a new release can change how apps are translated. On Linux there is no window, Metal or
VideoToolbox: rendering and shaders run on the CPU, and MediaCodec's H.264, AAC and MP3 decoders are libavcodec (the published image
has no HEVC or VP9 decoder; an image you build can add them). Nothing is played aloud there, but an app's sound reaches an
`apksim serve` tab. APKs need native libraries for the host's CPU there: x86_64, or
arm64-v8a on arm64 (4 KB-aligned ones load). On an Apple Silicon Mac or an arm64 runner the arm64 image runs without
emulation, so it is the faster choice there. There is no sandbox inside the container,
so use `docker run --network none` to keep an app off the network. Building the image and
running it on a Mac are covered in [docs/linux-docker.md](linux-docker.md).
