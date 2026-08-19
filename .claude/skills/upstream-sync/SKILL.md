---
name: upstream-sync
description: Bring the GPX RootEncoder fork up to date with pedroSG94/RootEncoder — assess the upstream delta first (is it worth taking, and does it supersede any GPX patch), then fast-forward the master mirror and merge into gpx-2.8 with markers, docs, and build gates intact. Use when asked to sync, update, or catch the fork up with Pedro/upstream.
---

# Upstream sync for the GPX RootEncoder fork

This skill encodes the sync procedure used for R26, R27 and R31. Read
`.claude/gpx-branch-policy.md` first — it is the authority on branches, tags, markers and
who consumes what. The re-apply record lives in `.claude/gpx-reapply-plan-2.8.0.md`.

Two invariants before anything else:

- `master` is a pure mirror of `pedroSG94/RootEncoder` master: fast-forward only, never a
  GPX commit. `gpx-master` (2.7.5 line) is **frozen** — no commits, no pin moves.
- Active GPX work lands on `gpx-2.8` only, in its worktree at
  `.claude/worktrees/gpx-2.8`. Never `checkout -b` in the main checkout.

## 1. Preflight

```bash
git fetch pedro --prune && git fetch origin --prune
git worktree list
for b in master gpx-master gpx-2.8; do git rev-list --left-right --count $b...origin/$b; done
```

Local branches must be 0/0 against origin (andoodle) with clean worktrees and no stashes
before starting. The synced-through baseline is recorded in the branch table of
`.claude/gpx-branch-policy.md` — confirm it matches `git merge-base gpx-2.8 pedro/master`.

## 2. Assess the delta BEFORE merging (the gate)

```bash
git log --oneline --no-merges <synced-through>..pedro/master
git diff --stat <synced-through>..pedro/master
```

Answer two questions, in writing, before any merge:

**Is it worth taking?** Read every non-merge commit against the consumer's actual paths:
GL-surface video + microphone audio through `StreamBase`, Camera2 (never Camera1),
WHIP first and SRT second, the async record controller, H264/H265. Timestamp/PTS, encoder,
GL, record-controller and connection-robustness work usually matters; CameraX / file /
bitmap sources, sample-app and dependabot churn usually does not. If nothing lands on a
path the consumer runs, say so and stop — a sync has to earn its regression risk.

**Does upstream supersede any GPX patch?** (The take-theirs ruling, Andy 2026-08-04:
where upstream now covers one of our patches, drop ours and take theirs — but only where
no functionality is lost.) Mechanically intersect:

```bash
git grep -l "GPX" gpx-2.8 -- "*.kt" "*.java" | sed 's/^gpx-2.8://' | sort > /tmp/ours.txt
git diff --name-only <synced-through>..pedro/master -- "*.kt" "*.java" | sort > /tmp/theirs.txt
comm -12 /tmp/ours.txt /tmp/theirs.txt
```

For each intersecting file, read the upstream diff against the marked regions. A patch
retires only when upstream's version is a behavioural superset (precedent: R1 in R31 —
upstream's `isCBRModeSupported` null-guard was R1 plus an exception guard). Every item
R1–R<latest> gets a per-item verdict recorded in the re-apply plan, including the
"file not touched" bulk rows.

## 3. Fast-forward the mirror

```bash
git fetch . pedro/master:master
git push origin master
```

## 4. Merge into gpx-2.8

In the `gpx-2.8` worktree: `git merge --no-commit --no-ff pedro/master`.

Conflict rules, in order:

- A patch judged superseded in step 2: take upstream whole and remove the GPX marker —
  that is the retirement.
- Pure adjacency (both sides added at the same line): keep both, GPX side positioned per
  its own ordering constraint (e.g. R16's wait stays ahead of anything touching shared GL
  state; R23's generation bump precedes teardown).
- Upstream restructured code a GPX change wraps (e.g. a callback body or signature): keep
  the GPX *shape* (shared callback, added guard, extra parameter) and move its body to
  upstream's new form. Extend the marker comment to say so.

After resolving, re-verify the interactions that never conflict textually: R9's
`forceContinuousTs` against anything touching `firstTimestamp`/PTS rebasing, and R30's
lock coverage against any new `StreamBase` lifecycle path.

## 5. Marker verification

```bash
# Inventory diff: must differ ONLY by deliberate retirements/extensions.
git grep -n "GPX" origin/gpx-2.8 -- "*.kt" "*.java" | sed 's/^origin\/gpx-2.8://' | sed 's/:[0-9]*:/:/' | sort > /tmp/pre.txt
grep -rn "GPX" --include="*.kt" --include="*.java" encoder library srt rtmp rtsp udp whip common extra-sources | sed 's/:[0-9]*:/:/' | sort > /tmp/post.txt
diff /tmp/pre.txt /tmp/post.txt

# Coverage: every file still differing from the merged head carries at least one marker.
git diff --name-only pedro/master -- "*.kt" "*.java" | xargs grep -L "GPX"
```

An unexplained line in the first diff means an auto-resolved hunk silently dropped a
patch — find it before going on.

## 6. Docs in the same commit

- `.claude/gpx-reapply-plan-2.8.0.md`: a new `R<next>` checklist entry in the R27/R31
  format — what arrives (with upstream hashes), the conflicts and how each resolved, the
  per-item outcome table, the marker check result. R numbers are never reused.
- `.claude/gpx-branch-policy.md`: the branch table's synced-through line, the marker
  coverage count and sweep date, and the "Verification status" branch-head paragraph
  (build-verified only; name what the next pin move wants watched on the bench).

## 7. Build gate, commit, push

```bash
./gradlew assembleDebug test   # in the worktree; must pass across every module
```

Commit as `GPX R<next>: merge upstream pedro/master (<headline items>)` in the R27/R31
message style, then push `gpx-2.8`.

## 8. What a sync does NOT do

- **No tag, no pin move.** A `2.8.0-gpx<N>` tag ships only with a consumer-side decision:
  bench-gate the change on the PDT, then tag and move `gpxstream-app`'s pin in the same
  motion. Published tags never move.
- **No new GPX code rides along.** A fork change needs its own reason and its own
  approval — "the fork is open anyway" is never one. Porting a change from another branch
  (e.g. R32 from `teaky-frame-timing`) is its own commit with its own authorization,
  after the merge.
- **No gpx-master or 2.7.5-line changes**, ever, without an explicit unfreeze decision.

## 9. Consumer follow-through

Sweep `gpxstream-app` for claims the sync made stale (its CLAUDE.md fork bullet records
the pin's lineage and synced-through hash). If the sync's content matters to the app,
queue the tag + pin + bench pass as its own piece of work there.
