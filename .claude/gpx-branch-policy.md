# GPX fork branch and tag policy

This fork carries two independent GPX lines. They are not interchangeable, and a consumer
pinned to one cannot be moved to the other without code changes.

## Branches

| Branch | Base | Purpose |
|---|---|---|
| `master` | — | Mirror of `pedroSG94/RootEncoder` master. Fast-forward only, never a GPX commit. |
| `gpx-master` | upstream `49421b686` (2026-07-20) | Frozen. The line `gpxnative-ai` builds against. |
| `gpx-2.8` | upstream `9a9ca124f` (2026-07-30) | Active. The line `gpxstream-app` builds against. |

## Tags and who consumes them

| Tag line | Head tag | Consumer |
|---|---|---|
| `2.7.5-gpx*` | `2.7.5-gpx25` | `gpxnative-ai` |
| `2.8.0-gpx*` | `2.8.0-gpx1` | `gpxstream-app` |

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

**Coverage: all 32 files that differ from upstream `9a9ca124f` carry at least one marker**
(swept 2026-08-03). Two caveats on reading a grep as complete:

- `StreamBase.kt` is a dense grouped rewrite where the marked regions are an index rather than a
  full inventory; its class KDoc says so.
- A marker is a comment, so a change with nothing to attach one to may be unmarked.

`git diff 9a9ca124f -- <file>` remains the authority; the markers are what make the divergence
legible without running it.

## Rules

- **Do not add GPX commits to `gpx-master`.** It is frozen so `gpxnative-ai` keeps a stable
  base. A fix that `gpxnative-ai` genuinely needs is a deliberate decision to unfreeze, made
  with the owner, not a routine commit.
- **New GPX work goes on `gpx-2.8`**, tagged `2.8.0-gpx<N>`.
- **Never reuse or move a published tag.** JitPack caches builds per tag; a moved tag serves
  stale or mismatched artifacts.
- **The next upstream sync** fast-forwards `master`, then branches or rebases the GPX line off
  it. The procedure and the item-by-item record from the 2.7.5 to 2.8.0 move are in
  `.claude/gpx-reapply-plan-2.8.0.md`.

## Verification status of the `2.8.0-gpx*` line

`gradlew assembleDebug test` passes across every module and the sample app at both tags.

**`2.8.0-gpx1` — device-proven over WHIP (2026-08-02).** On a bench PDT-FP1 the WHIP path
reached `Streaming` against the Millicast ingest and video was watched end to end on the
dashboard for the whole test. The stream held across a screen lock for over a minute with the
camera retained, and switching between all three inputs was exercised on the same line. Not
covered: duration, a degrading link, and a moving vehicle — nothing has run for hours or in
adverse conditions.

**Branch head, ahead of `2.8.0-gpx1` and untagged — build-verified only.** It carries R23, the
bounded camera open; R25, frame-rate ranges asked of a named camera; and R26, the merge of upstream
`pedro/master` @ `58af3fb1b`.

R23's changed paths are the ones a healthy open never takes: the timeout, and a framework callback
arriving for an attempt already given up on. Neither has been provoked on hardware, and a normal
open is unaffected by construction (the wait resolves in 13-19 ms against a 3,000 ms bound).

R25 changes every open, so it wants watching on the bench: an external input's capture request now
carries a frame-rate range from that input's own advertised list rather than from the built-in
sensor's. The old behaviour was tolerated by this hardware, so the visible outcome should be no
change; a *difference* in behaviour is the thing to look for.

R26 brings a buffer pool that recycles the arrays every encoded frame is copied into, on both the
stream and the record path. A buffer reused while a frame is still in flight would corrupt picture
or sound rather than crash, which no build can catch — so it joins the bench list.

The tag is deliberately held until the bench pass — see R24 in the re-apply plan, which carries the
checklist.
