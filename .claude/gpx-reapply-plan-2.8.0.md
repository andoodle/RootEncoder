# GPX fork re-apply onto upstream 2.8.0+ (branch `gpx-2.8`)

Base: `origin/master` @ `9a9ca124f` (pedroSG94 master, 2026-07-30).
Superseded branch: `gpx-master` @ `0802b1120` (base was `49421b686`, 2026-07-20).
Target tag when green: `2.8.0-gpx1`.

40 commits on the old branch collapse to the 14 work items below. Apply top to bottom —
later items depend on earlier ones.

## Checklist

- [x] R1 — CodecUtil `isCBRModeSupported` null guard (debug logs and the copy-paste bug excluded) — `62d7009e8`
- [x] R2 — VideoEncoder forced-VBR bitrate mode + `setTryForceVBRBitrateMode` — `3751dc648`
- [x] R3 — VideoEncoder prepend SPS/PPS to IDR frames (seekable VOD) — `3751dc648`
- [x] R3b — VideoEncoderHelper hvcC csd-0 parsing + start-code ordering bounds check (found during R2; was not in the original item list) — `90b3fc502`
- [x] R4 — StreamBase keyframe on `startStream()` and on `startRecord()` — `cd89bb86b`
- [x] R5 — `takePhoto(width, height, callback)` overload (GlInterface, GlStreamInterface, OpenGlView) — `614488cd2`
- [x] R6 — FlvMuxerRecordController AVCC SPS/PPS fallback — `f594eef93`
- [x] R7 — SRT inbound-silence dead-link detection + `getInboundSilenceMs` — `a7bbe6a63`
- [x] R8 — SRT handshake retransmit with backoff — `a7bbe6a63`
- [x] R9 — Encoder continuous timestamps across stop/start — `c47012f52` (BaseEncoder, VideoEncoder), `68b6a0a6c` (AudioEncoder)
- [x] R10 — Log-noise reduction (ImageStreamObject, SurfaceManager, BaseEncoder cold start) — `5b3d78c6b`; the BaseEncoder part is the `codecStarted` flush gate in `c47012f52`
- [x] R11 — Stream-only overlay plane + live force-render toggle (slate) — `10d264057`
- [x] R12 — Writer-side byte counter, rollover push, visible write errors — `6794feb0a`
- [x] R13 — RtmpSender guard against an incomplete H265/H264 parameter set — `dabdccb17`
- [x] R14 — WHIP/Millicast stack — `9385e391d` (part 1), `9283f343b` (part 2). Smaller than the old commit titles suggest; see the correction below.
- [x] R15 — `warmSources()` seam on StreamBase — `cd89bb86b`
- [x] R16 — `stop()` GL cleanup race fix — `12f5afaaf`
- [x] R16b — GenericStream unsupported-protocol message includes the endpoint — part of `9283f343b`
- [x] R17 — Zero B-frames request with vendor-rejection fallback — `383d55dfa`
- [x] R18 — Per-encoder profile/level + negotiated-format seam — `383d55dfa` (VideoEncoder half; the StreamBase `prepareVideo` parameters are still open)
- [x] R19 — Record codec applied coherently on a prepared encoder (redesign, see below) — `f0ae47678`
- [x] R20 — Build green (`gradlew assembleDebug` plus `test` across all modules)
- [x] R21 — Tag `2.8.0-gpx1`, push branch and tag — `1773819bb`
- [x] R22 — Record the two-line branch policy in this repo and in `gpxstream-app/CLAUDE.md` — `1773819bb`

## After the re-apply

The items above are the 2.7.5-to-2.8.0 migration. Work added to `gpx-2.8` afterwards continues
the same numbering, so a `GPX R<N>` marker in the source and an entry here name the same change.
The marker convention is in `.claude/gpx-branch-policy.md`.

- [x] R23 — Bounded camera open in `Camera2ApiManager` (for `gpxstream-app` S3): a wait with a
      time limit in place of `semaphore.acquireUninterruptibly()`, which parked the calling thread
      for good when a camera never reported itself open. Three things ride with it — the post-wait
      tail is skipped when the attempt is abandoned (it set `isRunning` and reported a camera
      change for a camera that never opened), a generation token lets a late callback recognise
      itself as stale and close its own camera rather than the legitimate one that replaced it,
      and one latch per attempt replaces the shared semaphore that leaked a permit whenever a
      camera opened and later disconnected.
- [x] R25 — Frame-rate ranges asked of a **named camera** rather than of a facing
      (`gpxstream-app` S3). `Facing` has two values and Android has three: an external camera — a
      capture card, a USB camera — reports `LENS_FACING_EXTERNAL`, which this class squeezes into
      front-or-back in three places and gets three different answers (`openCameraId`'s tail says
      BACK, `getFacingByCameraId` says FRONT, `getCameraIdForFacing` matches neither and falls
      through to the first id). So `adaptFpsRange` resolved a facing back to an id and always
      landed on the built-in sensor: every external input had its capture request's frame-rate
      range negotiated from the phone's own camera. Adds a `getSupportedFps(size, cameraId)`
      overload, points `adaptFpsRange` at it, and removes the hardcoded `cameraId == "1"` guess in
      `adaptFpsRangeDynamic` that worked around the same thing. Authorised as a deliberate second
      S3 fork change (Andy, 2026-08-03).
- [x] R26 — Merge upstream `pedro/master` @ `58af3fb1b` (10 commits past the `9a9ca124f` base).
      Carries no new GPX code, so it adds no `GPX R26` marker; the one hand-resolved line keeps its
      existing `GPX patch` marker in `SrtClient.kt`. What arrives:
      - **A buffer pool on both the send and record paths** (`6561adfec`, `0cf2ec988`). Encoded
        frames were copied into a freshly allocated array each time; those arrays are now recycled.
        Upstream measured roughly 750 KB/s of short-lived allocation at 6 Mbps. `BaseSender.queue`
        became private and `sendMediaFrame` changed signature — every call site is upstream's own,
        the fork adds none.
      - **A source fallback in `changeVideoSource`/`changeAudioSource`** (`ec949a59c`). Unreachable
        from `gpxstream-app`, which drives `Camera2ApiManager` directly and never calls either
        method (S3 design decision 3).
      - **`UrlParser` rewritten** (`3e0819a92`, `e00bcada9`) — query split off before `URI` parsing,
        raw rather than decoded paths. Verified output-identical to the old parser for the real
        Millicast SRT, RTMP and WHIP endpoints.
      - **An autofocus fix** (`c1a5bad0e`). Lands in `Camera2ApiManager.kt` alongside R23 and R25
        but inside `enableAutoFocus`/`disableAutoFocus`, which nothing calls internally and
        `gpxstream-app` never calls.

      One textual conflict, on the `SrtClient.kt` streamid line, resolved in favour of the GPX
      patch — upstream's own fix drops the Millicast token. The reason is recorded at that line.
- [x] R27 — Merge upstream `pedro/master` @ `02b8e9cce` (18 commits, 14 of them non-merge, past the
      `58af3fb1b` R26 base). Carries no new GPX code and adds no `GPX R27` marker. What arrives:
      - **Per-second streaming statistics** (`beabe65f7`, `fa94f2450`, `412ef4321`, `8aa5d20bf`,
        `575302c18`, `a3937a883`, `0c3ce57c7`, `ce56cb794`). `BitrateChecker` gains a default
        `onStreamingStats(StreamingStatsReport)`; a new `StreamingStatsMonitor` classifies the send
        queue's byte trend over a three-sample window as `SUFFICIENT` / `INSUFFICIENT` / `UNKNOWN`
        and reports a queue-congestion percentage. `BaseSender.start()` became `suspend` and
        cancels-and-joins any previous send job before starting. `hasCongestion` is re-expressed
        through the same percentage and keeps its old meaning. `gpxstream-app` implements
        `ConnectChecker` but overrides none of this, and the callback is a default method, so the
        app compiles and behaves unchanged; the visible cost is one no-op main-thread post per
        second per stream.
      - **An encoder crash-recovery budget** (`d27a7d2f8`). `BaseEncoder` now allows at most three
        resets in a 30-second window and stops the encoder rather than looping; `codec` and
        `executorService` became `volatile`; the API-below-23 read loop hands recovery to its own
        thread; `getDataFromEncoder` null-checks the codec. The new "stop instead of reset" branch
        runs on the codec callback thread, but so did the pre-existing `reset()` branch — which
        also calls `stop(false)` — so it is the same shape the fork already guards against in
        `releaseCallbackThread`, not a new hazard.
      - **Color-format selection prefers YUV420PLANAR** (`d27a7d2f8`) over whichever format the
        vendor happens to list first. Reached only when `formatVideoEncoder` is `YUV420Dynamical`;
        `gpxstream-app` encodes from a GL surface and never sets that, so this is unreachable here.
      - **Camera2 white-balance lock and tap-to-meter** (`af42307cc`, `402b08c57`, `06ff1138b`,
        `daaeae0eb`). New AWB-lock and tap-to-meter AE/AWB entry points, metering regions cleared
        to a zero-size `METERING_WEIGHT_DONT_CARE` rect before an auto mode is re-applied, and
        `closeCamera` resets the two lock flags. Purely additive; the app calls none of it.
      - **ktor 3.5.2** (`27ec9a918`).

      Six conflicts, every one resolved by keeping the GPX change and taking upstream's beside it.
      Three of them (`GenericStreamClient`, `SrtStreamClient`, `StreamBaseClient`) are pure
      adjacency — R7's `getInboundSilenceMs` and upstream's `getQueueBytesOut` were added at the
      same line. `Camera2ApiManager` is the same story: R23's generation bump and upstream's lock
      resets both sit at the top of `closeCamera`, and the generation bump stays first because it
      must precede the teardown. The two substantive ones are in `BaseEncoder.start(long)`, where
      R9's conditional timestamp baseline now sits above upstream's three reset-budget
      initialisers, and in the field block, where R19's `volatile prepared` sits beside upstream's
      two new counters.

      **One GPX line dropped in favour of upstream's:** `AudioEncoder.start(boolean)` no longer
      carries `shouldReset = resetTs`. Upstream deleted that line from both `AudioEncoder` and
      `VideoEncoder` and moved the assignment into `BaseEncoder.start(long)`, because with a reset
      budget a `restart()` must not quietly clear the flag. The line was never a GPX change — R9
      only added the `forceContinuousTs` guard on the `tsBuffer` reset immediately above it — so
      nothing of ours is lost. The `VideoEncoder` half merged with no conflict at all.
- [x] R28 — Per-encoder re-prepare (`gpxstream-app` S8 gate, F2, Andy 2026-08-12; ships as
      `2.8.0-gpx2`). `prepareVideo` refuses unless stream, record and preview are all stopped, so
      a stream-side parameter change while a recording rolls would force the recording down —
      which the consumer's R-STR-12 forbids. Adds two scoped re-prepares to `StreamBase`, each
      rebuilding one video encoder while the other encoder, the sources and the muxer keep
      running: `applyVideoStreamConfig` (stream stopped, recording may roll) and
      `applyVideoRecConfig` (record stopped, stream may roll — the full-parameter generalization
      of R19's `applyVideoRecCodec`, same claim-and-label discipline). Fps and rotation stay
      full-rebuild-only (shared-engine facts); the shared video source is not re-inited, so a
      size above the capture upscales until the next full rebuild. The concurrent-hardware-
      encoder risk on the restart path is the consumer's bench gate.
- [x] R29 — Bounded muxer join in `AsyncBaseRecordController.stopRecord` (`gpxstream-app` S8
      post-merge review, F1/CRITICAL; ships in the `2.8.0-gpx3` line). The `runBlocking { muxerJob?.join() }`
      never returns if the muxer coroutine is parked in a non-cancellable disk write (a wedged or
      full card) — `cancel()` cannot interrupt it. `StreamBase.release()` calls `stopRecord()` from
      the engine thread, so a permanently-parked join takes the whole stream down with the recording,
      the exact R-STR-12 outcome the consumer's record lane exists to prevent. Bounds the join with
      `withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS = 3_000L)`; on timeout the already-cancelled job is
      abandoned (a straggler on `Dispatchers.IO` frees itself when the stuck write errors), the
      stalled file is lost regardless, and the caller survives. Self-contained; no other file touched.
- [x] R30 — Lifecycle mutual exclusion in `StreamBase` (`gpxstream-app` S8 post-merge review,
      F2/HIGH; ships in the `2.8.0-gpx3` line). With the consumer's tier flip, two threads drive the
      shared source/encoder lifecycle — the engine thread (`startStream`/`stopStream`,
      `applyVideoStreamConfig`/`applyVideoRecConfig`, `prepareEncoders`, `release`) and the record
      lane (`startRecord`/`stopRecord`). Those methods are plain volatile check-then-act built for
      one driving thread, so a concurrent pair could double-start an encoder (`startSources` racing
      itself → `videoEncoder.start()` twice → IllegalStateException) or reconfigure a MediaCodec
      under a starting stream (an uncatchable native crash). Adds one reentrant monitor
      (`lifecycleLock`) guarding the source/encoder mutations: `startSources`, `stopSources`,
      `stopSourcesImp`, `prepareEncoders`, `applyVideoStreamConfig`, `applyVideoRecConfig`.
      **Deliberately NOT held across the muxer join** (`recordController.stopRecord()` in
      `stopRecord`/`release`): a lock held across that join would let a record lane parked in a
      non-cancellable write (R29's case) block the engine thread that wants the lock — so only the
      source/encoder sub-methods those paths call *after* the join take it, never the join itself.
      A single reentrant monitor has no lock-ordering cycle and is never held across the join, so it
      cannot deadlock. The concurrent-hardware behaviour is the consumer's bench gate.
      **Adversarial-review follow-up (F2 lens):** locking only the six sub-methods left the
      `stopRecord`/`stopStream` *tails* racing — `if (!isStreaming) { stopSources(); prepareEncoders() }`
      is check-then-act, so the engine thread's `startStream` could set `isStreaming` and start the
      sources between the record lane's read and its `stopSources()`, tearing a live stream's sources
      down. Fixed: each tail is now one `synchronized(lifecycleLock)` block (it runs *after* the join,
      so it still wraps no join), and `isStreaming` is `@Volatile` (read cross-lane). Known residual
      (review MED, generic-fork surface unused here): on a bounded-join timeout `stopRecordImp()`
      still runs against possibly-wedged storage — survivable for this app (MPEG-TS close throws into
      a caught path, RecordCapture installs a fresh controller per start), tracked on #15.
### Per-item outcome under the take-theirs ruling (Andy, 2026-08-04)

The ruling: where upstream has improved the library so one of our patches is no longer necessary,
drop ours and take theirs — but only where no functionality is lost. Every item R1–R26 was checked
against the nine files this sync touches that also carry a `GPX` marker.

**Nothing retires, and nothing narrows.** Upstream's three areas of work this round — send-queue
statistics, an encoder reset budget, and camera white-balance controls — do not overlap any GPX
patch, so every item is *keep and re-apply*. What was checked, and why each stayed:

| Item | Nearest upstream work this sync | Why it stays |
|---|---|---|
| R7 SRT inbound-silence | `StreamingStatsMonitor`'s `INSUFFICIENT` verdict | A different fault. R7 reports the link *dead* — a UDP blackhole where `sendto` keeps succeeding and nothing comes back — and fails the connection. Upstream's verdict is advisory and reads the *send queue*, which also grows on a merely congested but healthy link. |
| R12 writer-side byte counter | `getQueueBytesOut`, `totalBytesOut` | Different path. R12 counts bytes reaching the *recording file* through the muxer and makes a write failure visible; upstream counts bytes queued on the *send* path. `Mpeg2TsMuxerRecordController` and `AsyncBaseRecordController` are untouched this sync. |
| R9 continuous timestamps | `BaseEncoder.start(long)` rework | Upstream still rebases `presentTimeUs` to the supplied value on every start. No equivalent. |
| R10 `codecStarted` flush gate | `stop()` rework | Upstream's `stop()` still flushes on `codec != null` alone, so a Configured-but-never-Executing codec still logs and throws. No equivalent. |
| R19 `volatile prepared` / `isPrepared()` | `volatile codec`, `volatile executorService` | Upstream made two neighbouring fields volatile but not `prepared`, and its new budget-exhausted branch writes `prepared` from the callback thread — which strengthens the original reason rather than removing it. |
| `GPX patch` — `releaseCallbackThread` | `stop()` awaits executor termination | Upstream still never nulls `handlerThread` and still has no way to retire the thread outside the full `stop()` path, which is what `VideoEncoder`'s configure-retry needs. |
| R23 bounded camera open, R25 named-camera fps | `Camera2ApiManager` AWB lock, tap-to-meter | Upstream's camera work is in the AE/AWB request builders and `closeCamera`. `openCameraId`, `adaptFpsRange` and `getSupportedFps` are untouched. |
| R1–R6, R8, R11, R13–R18, R26 | — | Their files are not in this sync's 28 changed files at all. |

Marker check: the `GPX` marker inventory is byte-identical before and after the merge — 24 distinct
tags, same counts — so no patch was silently dropped by an auto-resolved hunk.
- [ ] R24 — Tag `2.8.0-gpx2` and bump the pin in `gpxstream-app`'s CLAUDE.md and
      `docs/PHASE_3_PLAN.md`. **Deliberately deferred** (owner ruling 2026-08-03): a published tag
      cannot be moved and JitPack caches per tag, so the tag is cut once the bench PDT has exercised
      the items below rather than while they are build-verified only. `gpxstream-app` builds against
      this branch meanwhile through the `rootencoder.local` composite-build switch, so nothing is
      blocked. The pin stays at `2.8.0-gpx1` until then.

      Bench checklist before the tag:
      - [ ] **R23, the bounded camera open.** Its changed paths are the ones a healthy open never
            takes — the timeout, and a framework callback arriving for an abandoned attempt. Provoke
            them rather than waiting for them.
      - [ ] **R25, frame-rate ranges from a named camera.** This changes every open. The old
            behaviour was tolerated by this hardware, so the expected outcome is no visible change;
            a *difference* is the thing to look for.
      - [ ] **R26, the buffer pool.** The reason this is on the list rather than trusted to the
            build: it touches every encoded frame on both the stream and the recording path, and a
            buffer recycled while still in flight corrupts picture or sound instead of crashing.
            Upstream ships unit tests for the pool and guards against a double release, but nothing
            covers the in-flight case on real hardware. Watch a stream and a recording long enough
            for the pool to reach steady state, and check the recording plays back clean — a
            corrupt frame is far easier to see in the file than on a live viewer.
      - [ ] **R27, the send-job restart.** `BaseSender.start()` now cancels and joins any previous
            job before starting, on every `startStream`. Expected outcome is no visible change; a
            connect that hangs or a reconnect that does not resume is the thing to look for.

## Correction to R14's scope

The original item list described R14 from the old branch's commit titles, which overstated
what survives. Three of those changes are not in the old branch's final tree at all:

- **HTTPS with Bearer auth and the `?auth=` token parameter** reached upstream independently.
  `whip/webrtc/CommandsManager.kt` on `origin/master` already sends
  `Authorization: Bearer $token`, identically to `gpx-master`. The old branch's own commit
  `5d3148119` is titled "WHIP minimal Millicast fix + pedro upstream auth", which is that
  handover.
- **`transport-cc` and MID RTP header extensions** appear in commit `84d19931c` but a grep for
  `extmap` or `transport-cc` across `gpx-master`'s `rtsp/`, `whip/` and `library/` trees returns
  nothing outside a code comment. A later commit removed them.
- **H264 `profile-level-id`, `msid` and port 9** (commit `74eb7c2e5`) are likewise absent from
  the old branch's final `CommandsManager.kt`.

What actually survives, and is therefore what was re-applied: the DTLS client role and
`DtlsClient.kt`, the ICE binding-check and nomination retransmits, the held ICE socket closed on
disconnect, `a=setup` parsing into `SdpInfo.setupRole`, `lastOrNull` in `SdpParser`, the
1200-byte packet cap, `a=msid-semantic: WMS` without the wildcard, and the offer-SDP log.

## Verification status

`gradlew assembleDebug test` passes across every module and the sample app. Nothing in this
branch has been run on a device or against a live ingest. The WHIP behaviour above was
originally established by testing against Dolby and Millicast; it is re-derived here and not
re-proven.

File-coverage check: every file the old branch changed (excluding its session-recap HTML) is
also changed on this branch, and this branch changes no other files.

## Two plan revisions made while applying R1–R11

**The StreamBase items are one block, not six.** R4 (keyframes on start), R9 (continuous
timestamps), R15 (`warmSources`), R18 (per-encoder profile/level) and R19 (record codec) all
live inside `library/src/main/java/com/pedro/library/base/StreamBase.kt` and reference each
other's fields. The old branch also carried three further StreamBase changes that were not on
the original item list: a `sourcesRunning` flag that makes `startSources`/`stopSources`
idempotent and transactional, an `isOnPreview` ordering fix in `startPreview`, and a
stream-encoder `MediaFormat` listener (`setStreamVideoFormatListener` /
`getLastStreamVideoFormat`). These are applied as one grouped set of commits against that file
rather than as separate re-applies.

**Comments are compressed as they are re-applied.** The old branch's StreamBase carries roughly
200 lines of doc comment that narrate the fork's own history — "Until gpxnative-ai#282 there
was...", "previously both startStream's catch...", "Moved AFTER the reset" — and cite session
and issue numbers that cannot be looked up from this repository. The durable facts inside them
are kept: that `MediaCodecInfo.CodecProfileLevel` constants are codec-namespaced so one pair
cannot serve two codecs, why each field is `@Volatile`, why the muxer label is advanced only
after the prepare that realises it, and why a replay may clear but never set the
prepared-codec claim. The narration around those facts is dropped.

## Old commits deliberately not re-applied

| Old commits | Reason |
|---|---|
| `891740643` + `b0c0332eb` | TextStreamObject log fix, then its revert. Net zero. |
| `20e3856cb` + `6be0e4f19` | WHIP in-band SPS level attempt, then its revert. Net zero. |
| `c125f4d1b` + `2d1855a90` | Added `?token=` then removed it. Collapses into R14 as `?auth=` only. |
| `f8f74f373` | Fixes bugs the legacy bundle introduced. Folded into R1/R5 rather than re-applied. |
| `668ccc4f0`, `25ac918b3`, `72bd535bf` | Session-recap HTML files; not code. |
| `b5426a617`, `cd1d3d5f3`, `1c9897787`, `8a837948e`, `b7d21328a` | Merge commits. |
| `2fefe4c0e` as one unit | The legacy bundle is decomposed into R1–R6. |

## Upstream changes that force a re-derivation rather than a textual re-apply

**`BaseEncoder.type` is now `com.pedro.common.Codec` (public field), not a `String` MIME.**
`getType()` and `setType()` were deleted. This affects R9, R17, R18 and R19.

**R19 redesign.** The old commits `021290036`, `c7a109a5c`, `3a5d075ed` and `0802b1120`
exist to mutate the record encoder's MIME string coherently on an already-prepared
encoder, and to stay exception-safe across that mutation. With an enum field the
assignment cannot throw partway, so the exception-safety wrapper is dropped. What is kept:
the ordering requirement that the record codec is applied *before* the muxer is prepared
(required by `docs/spec/recording-vod.md` MB-VOD-13 in gpxstream-app), the `volatile`
`prepared` flag, and the single source-lifecycle flag from `0802b1120`.

**R14 WHIP.** Upstream's `refactor-sdp` work moved parsers to `com.pedro.common`, changed
`SdpBody.createG711Body` / `createOpusBody` / `createAV1Body` signatures, deleted the
`spsString` / `ppsString` / `vpsString` accessors on `whip/webrtc/CommandsManager.kt`, and
added VP8/VP9 branches. None of the GPX WHIP work reached upstream — upstream still emits
`a=setup:actpass` and no `extmap` lines. The GPX behaviour to reproduce: HTTPS + Bearer
auth, `?auth=` token parameter, ICE retransmit, `a=setup:passive` with the DTLS client role
handshaker, socket teardown on failure, H264 `profile-level-id`, `msid`, port 9,
`transport-cc` and MID RTP header extensions, and the 1200-byte packet cap.

**R6 FLV.** Upstream changed `FlvMuxerRecordController.kt` in 8 commits (record-controller
codec checks, FLV fallback in `Camera1Base`, VP8/VP9 FLV packets). The AVCC SPS/PPS
fallback is re-derived against the new file, not patched in textually.

**R12 recording.** Upstream changed `Mpeg2TsMuxerRecordController.kt` (4 commits) and
`AsyncBaseRecordController.kt` (1 commit).

**R13 RtmpSender.** Upstream replaced the `else ->` branch with an explicit
`VideoCodec.H264 ->` branch and added VP8/VP9 and HE-AAC branches. The parameter-set guard
is re-placed inside the new branch structure.

## Verification

Build only (`gradlew assembleDebug`) plus the upstream unit tests. There is no device
verification available in this repository, and the WHIP/Millicast behaviour in R14 was
originally established by live testing against Dolby/Millicast ingest — that behaviour is
re-derived here but not re-proven.
