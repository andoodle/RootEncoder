# GPX fork branch and tag policy

This fork carries two independent GPX lines. They are not interchangeable, and a consumer
pinned to one cannot be moved to the other without code changes.

## Branches

| Branch | Base | Purpose |
|---|---|---|
| `master` | — | Mirror of `pedroSG94/RootEncoder` master. Fast-forward only, never a GPX commit. |
| `gpx-master` | upstream `49421b686` (2026-07-20) | Frozen. The line `gpxnative-ai` builds against. |
| `gpx-2.8` | upstream `9a9ca124f` (2026-07-30) | Active. The line `gpxstream-app` builds against. Synced forward since; latest merged upstream is `300d99fe1` (R31, 2026-08-19). |

## Tags and who consumes them

| Tag line | Head tag | Consumer |
|---|---|---|
| `2.7.5-gpx*` | `2.7.5-gpx25` | `gpxnative-ai` |
| `2.8.0-gpx*` | `2.8.0-gpx3` | `gpxstream-app` |

JitPack builds per tag, so a pin resolves the tagged commit regardless of what any branch
does afterwards. Moving `gpx-2.8` cannot affect a consumer pinned to `2.7.5-gpx25`.

## Why the two lines cannot be swapped

Upstream changed `BaseEncoder.type` from a MIME `String` to a `com.pedro.common.Codec` enum
and deleted `getType()` and `setType()`. Any consumer that sets an encoder's codec compiles
against one form or the other, not both. Upstream also changed several `SdpBody` factory
signatures and moved parsers into `com.pedro.common`.

## Marking GPX changes in the source

**Every GPX edit carries a `GPX` marker comment at each place it changes.** One marker per
contiguous changed region — on the KDoc of an added member, or as the first line of a changed
block. The enumeration command is:

```
git grep -n "GPX " -- "*.kt" "*.java"
```

This exists because the alternative does not survive a rebase. A GPX change was previously
findable only from its commit message and this repo's re-apply checklist, and the next upstream
sync rewrites the commits and starts a new checklist — so at exactly the moment someone needs to
know "what did we change here, and why", the answer has moved out of the file.

**Two marker forms.** `GPX R<N>` where the change maps to a re-apply item
(`.claude/gpx-reapply-plan-2.8.0.md`), so a marker and a checklist entry name the same work; the
number is never reused across upstream syncs, and a re-applied change keeps the number it was
given. `GPX patch` where no item covers it — several small changes were made alongside a larger
item and were never enumerated separately. A bare `GPX patch` is preferred over guessing a number,
because a wrong attribution is worse than an absent one.

**Coverage: all 31 files that differ from the merged upstream head carry at least one marker**
(swept 2026-08-19 against `pedro/master` @ `300d99fe1`; the count dropped from 32 because R1's
file, `CodecUtil.java`, no longer differs — upstream absorbed the change and R1 retired). The baseline is the *merged* upstream head,
not the branch point: since R26 and R27 brought upstream commits in, a diff against the original
`9a9ca124f` base also lists files upstream changed, which carry no GPX work and never will. Two
caveats on reading a grep as complete:

- `StreamBase.kt` is a dense grouped rewrite where the marked regions are an index rather than a
  full inventory; its class KDoc says so.
- A marker is a comment, so a change with nothing to attach one to may be unmarked.

`git diff pedro/master -- <file>` remains the authority; the markers are what make the divergence
legible without running it.

## Rules

- **Do not add GPX commits to `gpx-master`.** It is frozen so `gpxnative-ai` keeps a stable
  base. A fix that `gpxnative-ai` genuinely needs is a deliberate decision to unfreeze, made
  with the owner, not a routine commit.
- **New GPX work goes on `gpx-2.8`**, tagged `2.8.0-gpx<N>`. Each GPX change needs its own
  reason and its own approval from the consumer side — "the fork is open anyway" is never one
  (the gpxstream-app fork-edit rule).
- **Built as R28 — per-encoder re-prepare (authorized at the gpxstream-app S8 gate, F2, Andy
  2026-08-12; shipped as `2.8.0-gpx2`).** `StreamBase.applyVideoStreamConfig` and
  `StreamBase.applyVideoRecConfig`: a scoped re-prepare that rebuilds one video encoder while
  the other encoder and the muxer keep running, stream side and record side each; honours the
  existing invariants (the record/stream aspect-ratio equality check, the record parameter
  set and its `recordCodecPrepared` claim discipline, B-frame suppression and the
  negotiated-format listener surviving a re-prepare). Device-proven on the consumer's bench
  PDT (gpxstream-app #78, 2026-08-14): a stream resolution change and a codec change ran with
  the recording rolling unbroken, and two H265 encoders allocated concurrently — R-STR-14's
  named risk, cleared. Design home: `gpxstream-app/docs/design/S8_recording_vod.md`
  (F2), build placement in its implementation plan (WP9).
- **Never reuse or move a published tag.** JitPack caches builds per tag; a moved tag serves
  stale or mismatched artifacts.
- **The next upstream sync** fast-forwards `master`, then branches or rebases the GPX line off
  it. The procedure and the item-by-item record from the 2.7.5 to 2.8.0 move are in
  `.claude/gpx-reapply-plan-2.8.0.md`.

## Verification status of the `2.8.0-gpx*` line

`gradlew assembleDebug test` passes across every module and the sample app at every tag and at
the branch head.

**`2.8.0-gpx1` — device-proven over WHIP (2026-08-02).** On a bench PDT-FP1 the WHIP path
reached `Streaming` against the Millicast ingest and video was watched end to end on the
dashboard for the whole test. The stream held across a screen lock for over a minute with the
camera retained, and switching between all three inputs was exercised on the same line. Not
covered: duration, a degrading link, and a moving vehicle — nothing has run for hours or in
adverse conditions.

**`2.8.0-gpx2` (R23, R25, R26, R27, R28) — shipped 2026-08-12 with the consumer's pin move
(gpxstream-app #46), superseding R24's tag hold.** On the bench PDT the consumer's #78 pass
(2026-08-14) exercised R28 directly: a stream resolution change and a codec change ran with the
recording rolling unbroken, and two H265 encoders allocated concurrently. The item-specific
provocations R24's bench checklist named for R23/R25/R26/R27 have no recorded pass and stay open
as watch items — the ordinary paths they sit on have run on the bench since without incident.

**`2.8.0-gpx3` (R29, R30) — shipped and pinned; concurrency coverage is build-verified.** Both
changes rode the consumer's #78 bench build. Their specific interleavings (a wedged muxer write,
a stopRecord racing a stopStream) are not reproducible on a bench, so device proof is of the
unbroken ordinary paths, not the races. `gpxstream-app`'s pin is `2.8.0-gpx3`.

**Branch head, ahead of `2.8.0-gpx3` and untagged — build-verified only.** It carries R31, the
merge of upstream `pedro/master` @ `300d99fe1` (surface-PTS rebase onto the shared start, the
GL timestamp rework, a timestamp-based fps limiter — all on the live video path, so the next
consumer pin move wants a bench watch for A/V sync and frame pacing), and R32, the encoded-frame
timing seam ported from `teaky-frame-timing` (inert until a listener is set).
