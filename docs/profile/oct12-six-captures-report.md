# Six Spark captures: October 12, 2026

The strongest remaining targets are snow replenishment after winter migration has finished, repeated coverage calculation, and synchronous map journal flushes during migration. Every capture sustains roughly 20 TPS; the four newer captures have final one-minute mean tick times below 5.6 ms and p95 below 8.4 ms. These are useful efficiency findings, not evidence that this test server was overloaded.

This report describes the supplied captures before the new optimizations. It does not claim that subsequent source changes have been benchmarked.

## What was measured

All six files contain **only `Server thread`**, sampled with Spark's built-in Java engine at a 4 ms interval. The logs explicitly say that async-profiler is unsupported on Windows. Each contains approximately 60 seconds of sampled stacks, although metadata start-to-end spans about 62–63 seconds. The profiles include normal server waiting between ticks and time inside blocking I/O. Percentages below mean **share of sampled server-thread wall time**, not CPU utilization or share of tick execution time.

The final one-minute TPS/MSPT statistics are Spark's rolling statistics. They are not exact measurements between the metadata start and end timestamps. Window maximums may include activity before the capture. No individual maximum tick is attributed to a plugin method here.

The Python decoder was independently checked against Spark 1.10.191's installed generated protobuf decoder, using [VerifySparkDecode.java](VerifySparkDecode.java). All six agreed on timestamps, tick count, interval, thread name, sampled duration, and node count. Every decoded node is reachable, and summed root durations equal each thread's sampled duration. The source profiles were neither modified nor executed.

## Capture and log matching

Times below are **October 12 in America/Los_Angeles (PDT, UTC−07:00)**. The later four captures are dated October 13 in UTC. All profiles identify BlueMap 5.29 and contain active TrueSeasons stack paths; none is a plugin-disabled baseline.

| File | Local metadata interval | TrueSeasons | Matched log | Activity during capture |
|---|---|---|---|---|
| `fGwY7OOKVx` | 16:04:55.488–16:05:57.823 | 0.1.2 | `2026-10-12-5.log.gz` | Autumn migration, after the 15:55:27 switch; generated chunk count was still increasing. |
| `EGgb2u2OO6` | 17:16:51.416–17:17:54.034 | 0.1.3 | `2026-10-12-6.log.gz` | Autumn migration after winter was interrupted at 17:15:02. |
| `newnewnew` | 21:28:23.144–21:29:25.891 | 0.1.4 | `2026-10-12-10.log.gz` | Autumn world work already reported complete; map maintenance and drain dominate. |
| `IjK1gxz52X` | 21:38:36.001–21:39:38.628 | 0.1.4 | `2026-10-12-11.log.gz` | Active winter migration and snowstorm. |
| `O57EV1YhdZ` | 23:04:52.563–23:05:55.174 | 0.1.5 | `latest.log` | Completed winter; snow replenishment and map maintenance continue. |
| `K1eHg7Rd6L` | 23:06:57.363–23:07:59.824 | 0.1.5 | `latest.log` | Active autumn migration following the 23:06:15 switch. |

`newnewnew` was renamed after downloading: its timestamp matches the logged profiler start at 21:28:23 and the resulting URL `5b5okmZz4Q` at 21:29:27. The logs contain a second start command at 21:28:26, but the file's own start timestamp matches the first command. The file's metadata, rather than its filename, establishes the match.

Relevant surrounding events, with original log line numbers, are retained in [the selected log evidence](oct12-six-captures-log-evidence.txt):

- `fGw`: autumn switch at 15:55:27; next completion at 16:10:26 for 5,194 chunks, then 5,236 at 16:15:05. The capture occurs during an evolving workload, so it is not a controlled comparison with the later 4,096-chunk runs.
- `EG`: winter starts at 17:09:52, autumn interrupts it at 17:15:02, and autumn completes at 17:22:37 for 7,235 chunks. The capture starts 1 minute 49 seconds after interruption, rather than at the switch itself.
- `newnewnew`: autumn starts at 21:21:15; completions for 4,096 chunks occur at 21:25:24 and 21:26:55, both before profiling.
- `IjK`: winter starts at 21:35:45 and completes at 21:47:02 for 4,096 chunks. Additional completion messages at 21:50:51 and 21:55:49 demonstrate repeated completion reporting. Those messages alone do not prove that all chunks were physically processed again.
- `O57`: winter completion is reported at 23:03:12. Profiling starts 1 minute 40 seconds later, before any autumn switch.
- `K1e`: autumn starts at 23:06:15 and completes at 23:10:22 for 4,096 chunks, a 4 minute 7 second transition. The profile samples only an early minute of that transition.

## Server health and sampled attribution

| Capture | Final 1m TPS | Mean MSPT | Median | p95 | Maximum | TrueSeasons wall share | Normal park wall share |
|---|---:|---:|---:|---:|---:|---:|---:|
| `fGw` | 19.997 | 13.694 | 14.308 | 19.740 | 49.585 | 29.72% | 68.78% |
| `EG` | 20.003 | 4.748 | 3.995 | 8.131 | 48.542 | 17.26% | 73.16% |
| `newnewnew` | 19.997 | 3.899 | 3.477 | 6.552 | 29.605 | 11.96% | 69.30% |
| `IjK` | 20.004 | 4.850 | 4.414 | 6.956 | 39.419 | 19.21% | 70.23% |
| `O57` | 20.002 | 4.019 | 3.719 | 5.729 | 40.143 | 22.51% | 68.38% |
| `K1e` | 19.998 | 5.512 | 4.956 | 8.391 | 41.805 | 18.47% | 67.79% |

The TrueSeasons share counts the first plugin frame on each stack, avoiding nested plugin double counting. Normal park time is the deduplicated `Unsafe.park` path, predominantly the server's normal wait between ticks. Neither column should be interpreted as CPU usage. Large plugin wall shares can coexist with low tick times because the sampler and rolling tick metrics describe different denominators and windows.

The following nested paths help prioritize changes. **Rows overlap and must not be added**: journal flushes can be inside map integration, which can be inside migration callbacks. Categories count the first matching frame on each path, so repeated overloads within a category are not counted twice.

| Sampled path/category | fGw | EG | newnewnew | IjK | O57 | K1e |
|---|---:|---:|---:|---:|---:|---:|
| Migration package, including callback descendants | 26.32% | 16.11% | 1.77% | 16.64% | 4.40% | 13.81% |
| BlueMap integration, including journal descendants | 3.45% | 3.05% | 10.03% | 5.47% | 4.55% | 8.01% |
| Snow replenishment tick | — | — | 0.01% | 0.08% | **13.06%** | 0.03% |
| Snow application step | 0.01% | 0.03% | — | 4.09% | — | 0.07% |
| Snow ownership decode | — | 0.01% | — | 0.31% | **5.73%** | 0.01% |
| Coverage update | — | — | — | 1.62% | **2.49%** | — |
| Durable map journal | 0.39% | 2.03% | 0.58% | 2.67% | 0.14% | **3.83%** |
| Plugin-descendant native disk force | 0.18% | 1.81% | 0.01% | **2.21%** | 0.11% | **2.99%** |
| Render ticket scheduled check | 0.05% | 0.13% | 0.98% | 0.47% | **1.35%** | 0.98% |
| Plugin-descendant Bukkit TPS read | 5.39% | 9.58% | — | 0.37% | 0.19% | 0.41% |

An em dash means no matching samples, not proof that a method never ran. These captures differ in season, migration phase, generated chunk count, and loaded chunk count; they are not an A/B benchmark.

## Actionable findings

### 1. Avoid decoding all ownership data on empty replenishment checks

The completed-winter `O57` capture spends 7,836 sampled ms in replenishment tick (13.06%). The refill path accounts for 6,460 ms (10.77%), including 4,664 ms in ownership `read` (7.77%). `SnowColumns.decode` accounts for 3,440 ms (5.73%); insertion into the ownership TreeMap accounts for 1,744 ms (2.91%) on that path. Only 4 ms is sampled in the refill's ownership `write` descendant. This is evidence of expensive repeated reads despite little sampled writing; it is not an exact count of successful or unsuccessful refill calls.

The source confirms that [SeasonalSnowService](../../src/main/java/dev/trueseasons/snow/SeasonalSnowService.java) reads and validates ownership before checking sampled surfaces, then often returns when no placement is possible. A safe first optimization is to inspect surfaces first and decode ownership lazily when a viable target actually needs ownership validation. Cache the snow BlockData used for checks. A decoded ownership cache is another option, provided it is invalidated on ownership writes, cleanup, unload, and version changes. Malformed ownership must still prevent edits; performance changes must preserve that protection.

Do not quote the method aggregate `replenishColumns = 20.32%` as an independent cost. Its nested overloads contribute 10.51% and 9.81% on the same call chain. The outer refill path is 10.77%.

### 2. Stop recomputing completed winter coverage every tick

In the same completed-winter capture, `ChunkMigrationEngine.tick` has a 4.21% path. `checkCompletion` accounts for 1,528 ms (2.55%), with 1,496 ms (2.49%) in `updateCoverage`. Most of that is stream/list construction and summation. This maintenance remains visible after the 23:03:12 completion message, with no migration transition during the capture.

The inspected [engine](../../src/main/java/dev/trueseasons/migration/ChunkMigrationEngine.java) calls `checkCompletion` when all jobs finish, and `checkCompletion` calls `updateCoverage`. Recompute coverage only when progress or the eligible total changes, and skip recalculation once the current winter version is fully covered. Invalidate on season/reset, saved-checkpoint hydration, chunk promotion/discovery, eligibility changes, completion, and partial-column changes. Caching must not retain an old season's coverage or prevent newly generated chunks from joining work.

### 3. Reduce main-thread forced map journal writes while preserving durability

`K1e` samples 1,796 ms (2.99%) in `FileDispatcherImpl.force0` beneath TrueSeasons map journal paths. `IjK` samples 1,324 ms (2.21%); `EG` samples 1,084 ms (1.81%). These are wall-time samples in a disk barrier, not CPU time. `K1e`'s integration `persist` method totals 4.28%, while snow application itself is only 0.07%.

[BlueMapIntegration](../../src/main/java/dev/trueseasons/integration/BlueMapIntegration.java) can force persistence for dirty intent before edits and again when issuing a completed revision. [DurableRenderJournal.putAll](../../src/main/java/dev/trueseasons/integration/DurableRenderJournal.java) calls `channel.force(false)` when forced. The profile cannot count the number of flush calls or prove every chunk caused exactly two flushes. The combination of sampled stacks and source makes the batching opportunity concrete.

Batch pre-write intents for chunks that are about to be edited, with one durability barrier covering the batch before any of those edits. Completed receipts may be grouped when their crash/recovery semantics permit it. Removing the barrier or moving it after physical edits would weaken recovery guarantees. This is more valuable during active migration than optimizing snow placement in the autumn capture.

### 4. Cache and batch BlueMap maintenance lookups

After autumn world work is already complete, `newnewnew` samples `BlueMapIntegration.tick` at 10.01% and `purgeChunks` at 4.97%. The purge path includes 1,932 ms under `World.getChunkAtAsync` out of 2,980 ms in that path. This records request/callback execution on the main thread, not the lifetime or worker CPU cost of asynchronous disk loads.

In completed-winter `O57`, `BlueMapRenderBridge.scheduled` accounts for 812 ms (1.35%), including 124 ms under reflection lookup, 544 ms under invocation, and 116 ms under list membership checks. [The bridge](../../src/main/java/dev/trueseasons/integration/BlueMapRenderBridge.java) retrieves the scheduled task list for each checked ticket. Cache reflection methods and retrieve the scheduled ticket set once per poll, rather than retrieving/copying a list repeatedly for multiple tickets.

The `O57` methods also sample `hasLoadedChunk` at 0.37%; total `CraftWorld.getLoadedChunks` is 0.71%, shared with other callers. Cache loaded regions once per relevant poll and reuse them. Source inspection suggests additional opportunities to reuse region grouping and world-folder mappings, but these are lower-confidence attribution targets than the explicitly sampled paths above.

### 5. Earlier generated checks and TPS polling are no longer the dominant targets

The older `fGw` capture has 19.14% under `CraftWorld.isChunkGenerated`. That path has no matching samples in any newer capture. Plugin-descendant Bukkit TPS reads account for 5.39% in `fGw` and 9.58% in `EG`, compared with 0–0.41% in the four newer captures. This supports the direction of the saved-index and TPS-cache changes, but differing workloads prevent assigning an exact speedup to either change.

The aggregate `MinecraftServer.getTPS` can count nested overloads twice; for example, `EG`'s method aggregate is 19.10% while the deduplicated Bukkit TPS path is 9.58%. Use the latter to characterize that call chain.

## What the captures cannot establish

- No BlueMap renderer worker, map storage worker, background indexer, or JVM compiler/GC thread was included. Worker CPU, worker wait time, total map rendering cost, and contention between those workers cannot be quantified.
- The logs do not record per-region render submission, cancellation, and completion identities. A shutdown message says region `(-1, -1)` was still rendering at 22:00:08 in log 11; it does not establish repeated or duplicate rendering. Expensive polling and a lingering render are different observations.
- Sampling is not a call counter. There is no measured number of ownership decodes, forced journal writes, loaded chunks, or placements derived from sampled stack frequency.
- A one-minute capture does not measure an entire migration or extrapolate its total CPU cost. The measured samples are not multiplied by the full transition duration.
- Repeated completion messages establish repeated reporting, not a full chunk replay. Establish replay from checkpoint/version/queue evidence or instrumented counts.

## Verification after optimization

Repeat the same 4,096-chunk world in three phases: completed winter with storm and no player changes, winter migration, and autumn migration from that winter. Record plugin artifact/version and settings, loaded chunk count, season-switch time, world completion time, and map completion time. Capture all threads when studying BlueMap workers, with the same sampling interval and duration. Include a plugin-disabled baseline only after confirming the plugin is absent from that capture's active paths.

Expected evidence is a reduction in completed-winter ownership decode and coverage-maintenance paths, fewer durable barriers for an equal amount of migrated work, and fewer repeated task-list retrievals. Validate saved progress after restart, player-broken snow cooldown, protection-listener cancellation, ownership recovery, and map completion receipts separately: a faster profile alone cannot establish those correctness properties. To verify duplicate-render prevention, collect region/revision task identities and native submission/completion counts; another server-thread-only profile will not settle it.

## Data artifacts

[Batch summary](oct12-six-captures-summary.json) contains timestamps, SHA-256 hashes, source versions, rolling statistics, and deduplicated path categories. Each complete decoded capture is stored as `<profile>-oct12-analysis.json` in this directory. [Selected log evidence](oct12-six-captures-log-evidence.txt) preserves relevant events with original line numbers and removes the player's name. Earlier individual reports for [fGw](fGwY7OOKVx-report.md) and [EG](EGgb2u2OO6-report.md) remain available for context.
