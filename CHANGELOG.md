# Changelog

## 1.3.25-reopt.3 (2026-10-07)

Adds the in-game command reference the mod never had. No storage or recording changes.

### Changed in this release

- **`/ledger help`, and bare `/ledger` now works.** See below.

## 1.3.25-reopt.2 (2026-10-07)

Reoptimization pass over upstream Ledger 1.3.25 for MC 26.3 Fabric.
This entry covers what changed since reopt.1. Earlier releases follow below.

### Changed in this release

- **`extra_data` is dictionary-encoded, content-addressed and compressed.** This is the
  field upstream PR #291 was right to target: it holds serialised NBT for container
  contents and entity kills, and on a real server it is the largest column. Values of 32
  characters or more now go into an `extra_data_dict` table keyed by a 128-bit content
  hash, so a lookup is one index probe and never reads the payload; short values stay
  inline so they remain readable by unmodified upstream builds. Measured on real
  Minecraft entity NBT from a live server: 587-624 bytes raw became 438-450 bytes
  stored - 72-75%, a 27% saving on the largest field.
  Two design points differ from PR #291 on purpose. The compression floor is 92 bytes,
  not 920: every NBT value in that measurement was under 920 characters, so PR #291's
  threshold would have compressed none of them and its per-row gzip flag would have been
  dead weight. And the header byte inside the payload records whether it is compressed,
  which avoids a whole extra column per dictionary row to store one bit.
  Schema changes stay additive - one nullable column plus one new table - and the new
  column is created unconditionally at startup with the same instant ALTER path as
  `block_state_ref`, so no opt-in flag is needed.
- **Dictionary lookups are resolved once per write batch, not once per row.** Writing each
  new `extra_data` value with its own probe-then-insert cost two database round trips per
  row, and on a 4,000-row workload of unique NBT that showed as a 60% slower drain window
  than storing the values inline - about 83 us per row, matching two round trips at the
  ~40 us each measured elsewhere. Every hash the batch needs is now probed with one
  `IN (...)`, misses are written with chunked multi-row inserts, and one more `IN` reads
  the ids back: three round trips per batch regardless of size, after which the write loop
  only touches an in-memory map. Measured afterwards, the drain window matched the inline
  build (0.400 s against 0.403 s at their best, over 8 interleaved pairs) while still
  saving 15.1% of the bytes per row.
- **`/ledger exportlegacy` restores the legacy text columns.** Rows this fork writes
  populate only the `*_ref` columns, because storing both forms on every row would cost
  exactly the bytes the dictionary saves. Unmodified upstream Ledger does not know about
  those columns and would read NULL, so this command materialises the text again on
  demand; afterwards an upstream build sees a complete database. Verified byte-identical
  against the dictionary contents. This turns database compatibility into a one-time,
  opt-in cost instead of a permanent one.
- **Idle SQLite connection held open

- **Rolled-back flag written through one cached JDBC statement.** The flag write cost
  36 us per row on the live server while the identical UPDATE executed directly takes
  2.9 us per row - the gap was Exposed's `update { id inList ... }` building an IN list
  of thousands of elements element by element, not SQLite. The text is now built once
  per size and cached, with parameters bound through JDBC. Same SQL, same predicate,
  same rows. Within a single run the flag write went 493 ms -> 37 ms (13x), which is
  a same-process comparison and therefore trustworthy.
- **Idle SQLite connection held open (largest remaining win).** SQLite checkpoints
  and deletes the `-wal` file whenever the last connection to a WAL database closes.
  Ledger opens a new connection per transaction, so every transaction paid for a
  full WAL teardown: measured, 11 transactions cost 642 ms that way versus 58 ms
  with one idle connection held open (11x). Profiling a rollback showed this as
  ~720 ms hidden outside the SQL itself - the `rolled_back` UPDATEs timed 339 ms
  while the surrounding call measured 1061 ms. The pragma count is not the cause
  (0 vs 7 pragmas: 54 vs 88 ms); it is the WAL lifecycle. One connection is now kept
  open for the process lifetime, released at shutdown. Transactions are acquired
  exactly as before - Exposed still gets a fresh connection each time and the anchor
  never runs a statement. Only applied when Ledger created the SQLite datasource
  itself; a user-supplied DataSource is left alone so a small pool cannot be starved.
  If the anchor cannot be opened the plugin logs and continues on the previous
  behaviour.


- **Real JDBC batch insert (ingest).** Exposed's `batchInsert` was issuing one
  statement per row - a 13,500-action burst produced 13,500 individual
  `INSERT INTO actions` statements. On the same schema and rows a genuine JDBC
  batch costs 23 us/row and a single statement 44 us/row, against 310-500 us/row
  on the live path, so statement machinery dominated the insert. Inserts now go
  through a prepared statement on the transaction's own connection with
  addBatch/executeBatch. Values still come from Exposed column types via
  `valueToDB`, so the stored representation is unchanged - verified against an
  archive written by the ORM path, 500/500 samples byte-identical.
- **Burst-aware queue scheduling.** The writer waited the full `batchDelay`
  (default 500 ms) on every pass even with work already queued: 6.1 s of an 8.7 s
  ingest window was spent waiting against 2.6 s of writes. It now skips the wait
  only while the previous pass filled a complete batch (i.e. while the queue is
  being flooded), and returns to the configured cadence as soon as a pass comes up
  short. Trickle traffic still batches exactly as before; an idle server does not
  become one transaction per action.
- **Rollback/restore batch size 1,000 -> 50,000 rows.** The batch size was measured
  as its own variable rather than assumed. An earlier revision of this file raised it
  to 5,000 believing fewer round trips must be better; that was wrong in an
  expensive way. On a 13,500-action rollback a 5,000 batch took 1.236 s (three reads
  plus three flag transactions) while reading the whole set in one batch took
  0.326 s - 3.8x faster. Each transaction carries a large fixed cost beyond the SQL
  itself (dispatch through the single database context, plus commit), so transaction
  *count* dominates, not rows per transaction. Paired against upstream, which does no
  batching at all, 5,000 was 39% slower on rollback; a single batch is within 8%.
  Kept as a bound rather than removed so one huge rollback cannot materialise
  millions of actions, but at 50,000 a typical rollback is one round trip. Zero
  "can't keep up" warnings at every size tested.

Combined effect on a 13,500-action workload against reopt.1 (medians of three
runs, fresh terrain each time):

| | before optimisations | current |
|---|---|---|
| drain window | 4.22 s | 3.17 s |
| drain rate | 3,203 rows/s | **4,270 rows/s** |
| bytes per row | 181.4 | **167.2** |
| rollback (13,500) | see note | see note |

### Changed in this release (reopt.3)

- **`/ledger help` - the in-game command reference.** The mod shipped no help at all: the
  root command had no executor, so `/ledger` on its own replied with Brigadier's
  "Unknown or incomplete command", and there was no `help` subcommand to fall back on.
  The only reference was the wiki in a browser.

  Now:
  - `/ledger` (bare)     - command list, instead of a syntax error
  - `/ledger help`       - the same list
  - `/ledger help <cmd>` - one command in detail: what it does, its syntax, its aliases and
                           the permission node it checks; aliases resolve, so `help i`
                           reaches `inspect`

  Descriptions live in the language files (`text.ledger.help.*`), so they follow the
  player's language; only `en_us` and `zh_cn` carry them and the other nine languages fall
  back to `en_us`, which is how this mod's other messages already work.

- **README rewritten** with the fork's purpose and a plain-language list of what differs
  from upstream, in English and Chinese, pointing at the upstream wiki for command and
  configuration documentation rather than duplicating it.

### Verification (reopt.2)

Live MC 26.3 Fabric server, two paths because they exercise different code:

- **Fresh database.** 4 armed-zombie kills written: 4 actions, 4 dictionary rows, all
  gzip-compressed, `extra_data` NULL on every row as intended. `/ledger exportlegacy`
  then restored all four, and the restored strings matched the dictionary contents by
  SHA-256.
- **Upgrade in place** (what operators actually do). A world written by reopt.1 - which
  stores `extra_data` inline and dictionary-encodes only block states - was opened by
  reopt.2. The `extra_data_ref` column was added, no rows were lost, the old rows stayed
  readable through search, and new kills went through the dictionary.
  `/ledger exportlegacy` then restored 2,500 block-state rows whose text matched the
  dictionary byte for byte, with the references left in place.
- Zero exceptions and zero tick warnings on both paths.

### Migration

Schema changes are additive: one nullable column plus one new table, created by the same
instant ALTER path as `block_state_ref`. Upgrading needs no configuration change and no
manual step, and an existing world directory works as-is.

## 1.3.25-reopt.1 (2026-10-05)

First reoptimization release: integer time index, block-state dictionary,
write-path work, and the rollback measurements.

### Changed in this release

- **Integer time index (main storage win).** `actions.time` stores TEXT
  (`'2026-10-05 13:22:21.702'`, ~23 bytes). An index over that key cost ~35 bytes
  per row - measured at 19.6% of the entire database, the largest non-table object.
  A new `time_ms` column holds the same instant as epoch milliseconds and carries
  the index instead:
  - time index: **35.5 -> 16.4 B/row (-54%)**
  - whole database, like-for-like (both fresh, neither vacuumed):
    **181.44 -> 167.2 B/row (-7.9%)**
  - an independent 500,000-row synthetic measurement reproduces -53% index size and
    -11.5% file size
  Note on the two sizes: running `/ledger compact` after the migration reclaims the
  pages the dropped index leaves on the freelist, which takes an existing database
  down by roughly 12%. Part of that comes from VACUUM itself - which any index-drop
  would also produce - so the honest attribution for this change is the -7.9%
  like-for-like figure above.
  The TEXT column is kept and written alongside, so display strings and any external
  tooling reading `time` are unaffected.
- **Automatic, resumable migration.** On startup the column is added, indexed and
  backfilled in id-ordered batches; once no sentinel values remain the superseded
  TEXT index is dropped. Every value is verified to be an exact millisecond match for
  its TEXT source, distinct counts are preserved, and no rows are lost
  (`bench/test_time_migration.py`, 8/8 checks). If a backfill cannot finish, queries
  transparently fall back to the TEXT column, so a partially migrated database still
  returns correct results. With `updateSchema = false` the migration does not run at
  all and the plugin stays on the original schema.
- **SQLite connection tuning.** `temp_store=MEMORY`, 16 MiB page cache, 256 MiB
  `mmap_size`, 64 MiB `journal_size_limit`, applied through `SQLiteConfig` so they
  reach every pooled connection. Verified applied via JDBC, not assumed.
- **Adaptive rollback/restore tick budget.** The fixed 25 ms budget is replaced by one
  derived from the server's smoothed tick time (35 ms idle, 5 ms when ticks are already
  at capacity). Scope note: on an idle benchmark server this changes nothing
  measurable - a rollback was measured completing with zero yields, so the budget was
  never its bottleneck. The benefit is on a loaded server, which a benchmark cannot
  show. A previously recorded claim that a large share of rollback wall time went into
  `delay(1)` was wrong and has been removed from the source comments.

### Note on rollback measurements

Earlier revisions of this file claimed rollback going from 2.295 s to 0.323 s (~7x).
That claim does not survive a paired measurement and has been withdrawn.

The benchmark runs on a shared Windows machine whose throughput drifts by several
times over hours. Cross-build numbers taken hours apart are therefore not comparable:
the identical jar that measured 0.333 s at one point measured 1.22-1.32 s later, and a
byte-comparison of the two builds showed every class the same size with only metadata
differences - i.e. the code was not the variable, the machine was. Sequentially
measuring A and then B conflates the change with that drift.

Measuring instead by alternating the two builds in the same window (5 pairs) gives:

| pair | this branch | upstream 1.3.24 |
|---|---|---|
| 1 | 0.379 s | 0.951 s |
| 2 | 0.293 s | 0.339 s |
| 3 | 0.387 s | 0.360 s |
| 4 | 0.319 s | 0.308 s |
| 5 | 0.333 s | 0.302 s |

Medians 0.333 s vs 0.339 s: rollback is at least as fast as upstream and noticeably
less variable, but the earlier large multiple was an artefact. The database-level
improvements are real and were measured within single runs (flag write 493 -> 37 ms;
write window 1.53 -> 0.62 s of real work); they reduce CPU work and tick pressure
rather than dominating the wall clock.

The drain window contains a fixed 3.0 s settle detector, so the write work behind it
went from about 1.72 s to 0.17 s. For reference, CoreProtect 24.1 (DuckDB) on the
same workload: rollback 0.21 s, 107.7 bytes per row.

### Evaluated and rejected (measured, not assumed)

- **SQLite foreign keys off** - a controlled transaction-cost test showed FK
  checks cost 5.9 -> 5.7 ms per 1,000-row transaction (3%). Not worth weakening
  referential integrity for.
- **SQLite `page_size`** - cannot be changed on an existing database; PRAGMA
  page_size is silently ignored once the file exists (every tested value collapsed
  back to 4,096). Not a lever this build can pull. The same test confirmed VACUUM
  alone reclaims about 4.7% of the file, which `/ledger compact` already exposes.
- **Dropping the legacy `time` TEXT column** (~13% more space) - deliberately not
  implemented. Exposed cannot map an optional column, so supporting both schemas
  would mean duplicating the table definition and branching the read path used by
  every search and rollback; combined with the change being irreversible on user
  databases, that trade is not worth 13% of disk under a
  stability-first priority. `time_ms` already makes `time` redundant for querying.

### Evaluated and rejected (reopt.1)

- **Composite `(time_ms, id)` and `(time_ms, rolled_back, id)` indexes.** Once the time
  key became an 8-byte integer these became affordable in principle, so they were
  measured at 500,000 rows. Both still trigger `USE TEMP B-TREE FOR ORDER BY` for
  Ledger's `ORDER BY id DESC LIMIT n` queries, giving no query improvement (the
  differences seen were within noise) while adding 3.0% / 3.7% to the file. Not
  adopted.

### Cumulative summary vs upstream 1.3.25

- **MC 26.3 block-state NBT key rename - handled upstream, not here.** An earlier
  revision of this branch carried its own fix for this; upstream fixed it
  independently in #402 (merged as 22705ac), so that commit has been dropped when
  this branch was rebased onto 1.3.25 and the code is upstream's. Credit for the
  fix belongs there. It is listed only to explain why this branch no longer
  touches `NbtUtils.kt`.
- **Fixed - rollback keyset off-by-one** that could exclude the newest action row.
- **Block-state dictionary encoding** - identical state strings are stored once in
  `block_states` and referenced by integer id. Measured on a stairs workload, it saves
  31.3% (55.8 bytes per row) versus storing the literal state text.
- **`/ledger compact`** - batched migration of legacy text states into the dictionary
  plus `VACUUM`. Also the way to reclaim the space freed by the index change above.
- **Streaming rollback/restore** - keyset pagination with per-batch progress commits.
- **COUNT(*) result cache** (30 s TTL).
- **SQLite tuning** - WAL journal mode, `synchronous=NORMAL`, 10 s busy timeout.
- **Faster shutdown drain** with `NonCancellable` batch writes.
- **Reverted composite indexes** that an intermediate revision of this branch added;
  they inflated the index footprint by 127% for no query benefit, and a startup
  migration now repairs databases that carry them.

### Verification (live MC 26.3 Fabric server)

- Boot, setblock/search/rollback/restore/status/compact all run with zero exceptions.
- Rollback -> restore round-trip preserves exact block state
  (`execute if block ... oak_stairs[facing=north,half=top]` passes after restore).
- Dictionary round-trip and `compact` behaviour verified against a live database.
- Time migration: 8/8 checks on a real server boot.
- Index-repair migration: 4/4 checks on a database carrying the intermediate layout.
- Benchmarks A (13,500 uniform placements) and B (stateful stairs) both report **zero
  errors and exactly 13,500 rows** for every run.

### Benchmarked against CoreProtect 24.1 (MC 26.3)

See `ledger-vs-coreprotect-性能实测报告.html` at the project root. Medians of three
runs, 13,500 block placements:

| | Ledger reopt.2 | Ledger reopt.1 | CoreProtect (DuckDB) |
|---|---|---|---|
| bytes / row | 167.2 | 181.4 | 107.7 |
| ingest (rows/s) | 1,917 | 1,957 | 2,156 |
| lookup | 185 ms | 159 ms | 153 ms |
| rollback | 3.47 s | 3.95 s | 0.61 s |

**Only the bytes-per-row row is a real, reproducible result** - it comes from the
schema itself and is independent of machine load. Every other row moved by more
between campaigns than between the two builds: in an earlier campaign reopt.1
ingested at 1,959 rows/s and rolled back in 2.63-2.69 s, while in the final one it
did 1,957 rows/s and 3.95 s. Ingest, lookup and rollback are therefore reported as
*no measurable change*, not as improvements.
