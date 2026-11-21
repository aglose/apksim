# Spike S1: shared surfaces (2026-11-20)

Question: can a host app on the same Mac show a headless instance's screen from the instance's own IOSurfaces,
with a small "frame N ready" message per present, instead of decoding the H.264 stream (`stream ... h264`,
`FrameStream`)? How do the two processes meet under the instance's sandbox profile, and what does it save?

Answer: yes, and both hand-offs tried work under today's default and beam profiles with no profile change.
Present-to-host latency drops from ~6.8 ms (p50) to ~0.22 ms, the instance stops spending ~5 ms of encoder time
per frame, and every frame arrives (no lossy quantisation, no keyframe waits). Recommended mechanism: Mach send
rights (`IOSurfaceCreateMachPort`, or the same thing wrapped as `IOSurfaceCreateXPCObject`) sent to a Mach service
the host owns, not global IOSurface IDs.

## What was built (opt-in, nothing changes unless a host asks)

- `surfacewatch global` / `surfacewatch mach NAME` on the control channel (`SharedSurface.java`,
  `native/sharedsurface.m` → `libsharedsurface.dylib`). The connection becomes a feed: on the raster thread, right
  after `SimWindow.present`, the frame (already BGRA in a direct buffer from HWUI's ImageReader, headless or not) is
  copied with one `memcpy` into the next of three BGRA IOSurfaces, and the connection gets
  `f SEQ INDEX PRESENT_EPOCH_US COPY_US`. When the ring is made (first frame, a size change) the line before it is
  `s GEN W H` and, for `global`, the three surface IDs; for `mach`, the three surfaces' Mach send rights have just gone
  in one complex Mach message to the bootstrap service NAME (`bootstrap_look_up` in the instance).
- Headless today has no IOSurface at all: `SimWindow.present` returns at once and the pixels live only in the
  ImageReader's `Image` (`HwRenderer.rasterLoop`). The copy into the ring is the one extra step; it is the same copy
  `simwindow.m`'s `nativePresent` makes into its window surfaces, so a windowed instance could share those instead.
- `scripts/spike-s1/surfacehost.m`: a stand-in host (command line). `global` looks the surfaces up by ID
  (`IOSurfaceLookup`), `mach` checks in `dev.apksim.spike.surfaces.PID` (`bootstrap_check_in`, which launchd allows
  for a name no plist declares) and maps the ports (`IOSurfaceLookupFromMachPort`), `h264` reads today's stream and
  decodes it with VideoToolbox into IOSurface-backed BGRA buffers. Each frame it reads 256 pixels along the
  diagonal (proof the pixels are there and change; `DUMP.ppm` writes the last frame, which showed the Shader tab
  correctly). `scripts/spike-s1/measure.py` runs the modes interleaved; `scripts/spike-s1/s1probe.m` and
  `profiles.sh` run the two hand-offs alone with the instance's half inside each sandbox profile.

## Sandbox: which mechanism works where

Instance half run inside the profile (the launcher's own profile through `APKSIM_SANDBOX_TEST`, others with
`sandbox-exec`), host half outside; checked that the default and beam profiles were applied (a write to the home
folder and, under beam, a read of `~/Downloads` were denied). The full JVM path was also run sandboxed (cold launch,
`sandbox_check` = 1) and from a warm standby (instance re-parented to launchd): both modes worked.

| Instance profile | Mach port to host's service | Global ID + `IOSurfaceLookup` |
| --- | --- | --- |
| default (`allow default`, file and network denies) | works | works |
| beam (adds loopback, unix-socket, signal and pasteboard denies) | works | works |
| `(deny mach-lookup)` | **fails**: `bootstrap_look_up` → 1100 permission denied | works |
| that plus `(allow mach-lookup (global-name-prefix "dev.apksim.spike."))` | works | works |
| `(deny iokit-open (iokit-user-client-class "IOSurfaceRootUserClient"))` | fails (no IOSurface at all) | fails |

So no change is needed today: both profiles are `allow default` and neither denies Mach lookups. If the profile
is ever tightened to deny lookups, the one rule the Mach route
needs is `(allow mach-lookup (global-name "NAME"))` (or a prefix) for the host's service. Not tried: the host under
the App Sandbox, and an XPC listener: `xpc_connection_create_mach_service`
as a listener needs the name in a launchd plist (an `SMAppService` LaunchAgent, or the app's bootstrap-inherited
name), whereas `bootstrap_check_in` of a dynamic name worked from a plain process here. Over XPC the payload would
be `IOSurfaceCreateXPCObject`, which wraps the same Mach right.

Why not global IDs even though they work: `kIOSurfaceIsGlobal` and `IOSurfaceLookup` are deprecated, and a global
surface can be looked up by *any* process of the user that guesses its small integer ID (they were 20-60 here):
every other app could read the device screen. A Mach right is a capability only the host receives.

## Numbers

Apple M4 Pro, macOS 26.1, JDK 24, this branch built, no AOT cache trained (does not matter for steady state), the
test app headless (cold launch, default sandbox) on the Shader tab (120 fps unthrottled shader; `frames`: 120.6 fps).
`measure.py 7471 3 10`: three interleaved rounds of none / global / mach / h264, 10 s each. The machine was busy
(load average ~5, memory pressure level 2), so tails are noisy. Latency = host wall clock when the frame is
readable in the host (surface: its pixels sampled; H.264: the decoder's output callback, synchronous decode) minus
the instance's present time (epoch µs taken right after `SimWindow.present`, the same clock `FrameStream` stamps
its header with). Same machine, same clock.

| Path | fps received | present→host p50 | p90 | p99 | instance work per frame | instance CPU (one core) | host CPU |
| --- | --- | --- | --- | --- | --- | --- | --- |
| none attached | | | | | | 20-23% | |
| global IDs | 119.7-120.1, 0 gaps | 0.21-0.23 ms | 0.24-0.26 | 0.28-0.63 | 0.16-0.17 ms copy | 22-23% | 0.3% |
| Mach ports | 119.7-120.1, 0 gaps | 0.21-0.24 ms | 0.24-0.45 | 0.29-0.87 | 0.16-0.19 ms copy | 21-26% | 0.3-0.4% |
| H.264 (4000 kbps) | 120.9-121.2 | 6.72-7.35 ms | 7.14-9.89 | 10.3-19.1 | 5.1-5.9 ms encode (own thread) | 29-32% | 1.4-1.8% |

Maxima: ~5-6 ms on the surface paths (scheduling hiccups), 90-115 ms for H.264 (the first keyframe and the
encoder opening). The surface paths' 0.2 ms is the control-line write plus the host's wake-up; the ring copy itself
is 0.16 ms for 822x1782 (5.9 MB) and runs on the raster thread before the line is sent. What was *not* measured:
present to the photons on the host's screen (both paths would add the same Core Animation commit and display
latency; with the surface path the host sets `layer.contents` to the IOSurface, so no further copy), a host display
link sending `vsync` back, the beam sandbox with the real JVM (only the probe), a windowed instance, several
instances at once, and larger displays (a 2560x1600 tablet is ~16 MB a copy, ~0.45 ms by bandwidth, not run).

## Recommendation

Use Mach rights to a host-owned service for the surfaces and keep the control connection (or that same Mach
channel) for the per-frame `f` message. It is the lowest latency and cost measured, needs no profile change today,
and needs one explicit `mach-lookup` allow if the profile is ever made default-deny. Keep H.264 for remote viewers
(serve, beam's browser tabs), where a surface cannot go.

## What a real implementation needs

- Nothing that can block on the raster thread: the prototype writes each `f` line and retries a failed Mach send
  there, so a host that stops reading (or dies with its socket open) stalls or thrashes the instance's drawing. Lines
  go through a queue on a thread of their own, as `FrameStream` does, and a failed send ends the watch.

- Back-pressure: the host must tell the instance when it is done with a surface (or the instance must check
  `IOSurfaceIsInUse` / a use count) before reusing it. The prototype assumes the host reads within two frames;
  a host holding a frame on screen for a long time, or a 3-deep ring under load, will tear. A "released N" reply
  line, or more buffers, fixes it.
- Ring changes: free the old ring only after the host has acknowledged the new one; resend the ring to a host that
  attaches later; size changes and rotation (the ring is remade at a new size; done), and the skin/watch-mask
  handling `FrameStream` has.
- Sharing the window's own three surfaces when there is a window (`simwindow.m`), so a hosted windowed instance
  copies nothing extra; headless keeps the one copy (HWUI renders into Robolectric's ImageReader, not an
  IOSurface; rendering straight into the ring would remove that copy too, and is the bigger change).
- The service: an `SMAppService` LaunchAgent or XPC service of the app's for `xpc_connection_create_mach_service`,
  or keep `bootstrap_check_in` of a per-process name and pass the name in the launch (as here). The name must be
  unguessable or checked (`audit_token` of the sender against the instance PID the app launched), since any process
  of the user may look it up.
- Per-frame message over Mach (a small message on the same port) instead of TCP if the 0.2 ms matters; and the
  host's display link feeding `vsync` back as serve's page does.
- `libsharedsurface` packaged in releases (`package-release.sh` copies `work/runtime`) and a Linux stub (none
  needed: the command answers "Mac only").

## Files

`runtime/src/main/java/dev/apksim/SharedSurface.java`, `native/sharedsurface.m` (built by `build-native.sh`), the
`surfacewatch` branch in `Control.java`, the one call in `HwRenderer.rasterLoop`; `scripts/spike-s1/` (host,
measurement and profile probes; build lines are at the top of each `.m`).
