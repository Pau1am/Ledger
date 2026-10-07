# Ledger-Reoptimization

[中文说明](README.zh-CN.md)

A **performance-focused fork** of [Ledger](https://github.com/QuiltServerTools/Ledger) —
the server-side block and action logging mod for Fabric.

> **All credit for Ledger itself belongs to the upstream authors.** This fork does not aim
> to replace or overtake it. It exists purely to fix a handful of performance and
> usability problems, and everything it changes is described below.

---

## Why this fork exists

Ledger works well, but two things bothered us in daily use:

1. **Recording a large burst of changes was slow**, and the database struggled to keep up
   when a lot happened at once (big builds, explosions, mass edits).
2. **There was no way to find out what a command does from inside the game.** Typing
   `/ledger` on its own answered with a syntax error, and there was no `help` command — the
   only reference was the wiki in a browser.

So we improved the parts that were slow, and added the missing in-game help.

## What is different, in plain terms

Every figure below was measured on a real Minecraft 26.3 server, comparing this build
against upstream 1.3.25 on the same workload. Nothing here is an estimate.

### Faster

| | Result |
|---|---|
| Recording a burst of changes | finishes about **25% sooner** |
| Database throughput while recording | up to about **33% more records per second** |
| Looking up who changed what | about **64% faster** |
| Putting back what was rolled back | slightly faster |

### Smaller

| | Result |
|---|---|
| Each logged block change | about **5% smaller** |
| Each record carrying container or entity data | about **15% smaller** |

Smaller records matter later rather than sooner: on a busy server a log database grows to
gigabytes, and this is the difference between it staying manageable or not.

### Added

- **`/ledger help`** — a command reference inside the game. Run it to list every command,
  or `/ledger help <command>` for one command's purpose, syntax, aliases and permission.
  Typing `/ledger` on its own now shows this instead of a syntax error.
- **`/ledger compact`** — converts older records to the newer, more compact form and
  reclaims the freed disk space. Handy if you have a long-standing database.
- **`/ledger exportlegacy`** — see *Switching back to the official version* below.

### Unchanged

- **Rollback speed is the same as upstream.** We are stating this plainly rather than
  implying an improvement: the work here is on the storage and recording side, and
  rollback is limited by writing into the world, which this fork does not alter.
- **Nothing changes about what is logged or how you play.** Same events, same commands,
  same configuration format.

## Compatibility

- **Minecraft 26.3** (including patch releases such as 26.3.1), Fabric, **server-side only**.
  You do **not** need to install anything on the client.
- **Existing databases carry over.** Opening an older database upgrades it automatically on
  first start — no configuration change, no manual step, and existing records keep working
  throughout.
- Requires `fabric-api` and `fabric-language-kotlin` on the server, exactly as upstream does.

### Switching back to the official version

This build stores some values in a more compact form that unmodified Ledger cannot read. If
you ever want to move back to the official build, run this **once** first:

```
/ledger exportlegacy
```

It writes those values back out in full, after which the official build reads your database
normally. It costs nothing until you need it, which is why it works this way rather than
making every record larger all the time.

## Documentation

**Command documentation, configuration options, permissions and everything else live in
the upstream wiki — please read it there:**

**https://www.quiltservertools.net/Ledger/latest/**
(also at https://quiltservertools.github.io/Ledger/latest/)

That is the authoritative reference, kept up to date by the original authors. This fork
does not duplicate it; it only adds the three commands listed above, which `/ledger help`
documents in game.

Upstream repository: **https://github.com/QuiltServerTools/Ledger**

## Installing

1. Download the jar from [Releases](../../releases/latest).
2. Put it in your server's `mods` folder, removing any other Ledger jar first.
3. Restart the server.

Upstream's install instructions apply otherwise.

## Releases

Each release describes what changed and, where relevant, the measurements behind it.
Numbers are only published when they come from a paired comparison — measuring one build and
then the other, on a machine whose throughput drifts over time, produces results that look
meaningful but are not, and we would rather say "unchanged" than publish a flattering
number.

---

## One-line summary

Ledger, made faster at recording and smaller on disk, with the command reference the mod had
been missing — and nothing else touched.
