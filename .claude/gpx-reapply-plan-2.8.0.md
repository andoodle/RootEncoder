# GPX fork re-apply onto upstream 2.8.0+ (branch `gpx-master-280`)

Base: `origin/master` @ `9a9ca124f` (pedroSG94 master, 2026-07-30).
Superseded branch: `gpx-master` @ `0802b1120` (base was `49421b686`, 2026-07-20).
Target tag when green: `2.8.0-gpx1`.

40 commits on the old branch collapse to the 14 work items below. Apply top to bottom —
later items depend on earlier ones.

## Checklist

- [x] R1 — CodecUtil `isCBRModeSupported` null guard (debug logs and the copy-paste bug excluded) — `62d7009e8`
- [x] R2 — VideoEncoder forced-VBR bitrate mode + `setTryForceVBRBitrateMode` — `3751dc648`
- [x] R3 — VideoEncoder prepend SPS/PPS to IDR frames (seekable VOD) — `3751dc648`
- [ ] R3b — VideoEncoderHelper hvcC csd-0 parsing + start-code ordering bounds check (found during R2; was not in the original item list)
- [ ] R4 — StreamBase keyframe on `startStream()` and on `startRecord()`
- [ ] R5 — `takePhoto(width, height, callback)` overload (GlInterface, GlStreamInterface, OpenGlView)
- [ ] R6 — FlvMuxerRecordController AVCC SPS/PPS fallback
- [ ] R7 — SRT inbound-silence dead-link detection + `getInboundSilenceMs`
- [ ] R8 — SRT handshake retransmit with backoff
- [ ] R9 — Encoder continuous timestamps across stop/start
- [ ] R10 — Log-noise reduction (ImageStreamObject, SurfaceManager, BaseEncoder cold start)
- [ ] R11 — Stream-only overlay plane + live force-render toggle (slate)
- [ ] R12 — Writer-side byte counter, rollover push, visible write errors
- [ ] R13 — RtmpSender guard against an incomplete H265/H264 parameter set
- [ ] R14 — WHIP/Millicast stack (largest item; see below)
- [ ] R15 — `warmSources()` seam on StreamBase
- [ ] R16 — `stop()` GL cleanup race fix
- [ ] R17 — Zero B-frames request with vendor-rejection fallback
- [ ] R18 — Per-encoder profile/level + negotiated-format seam
- [ ] R19 — Record codec applied coherently on a prepared encoder (redesign, see below)
- [ ] R20 — Build green (`gradlew assembleDebug`)
- [ ] R21 — Tag `2.8.0-gpx1`, push branch and tag

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
