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

## Verification status of `2.8.0-gpx1`

`gradlew assembleDebug test` passes across every module and the sample app. No part of this
line has been run on a device or against a live ingest. The WHIP and Millicast behaviour it
carries was originally established by live testing on the `2.7.5-gpx*` line; it is re-derived
here and not re-proven. Device testing of the WHIP path is outstanding.
