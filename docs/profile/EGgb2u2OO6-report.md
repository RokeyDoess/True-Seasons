# Interrupted winter → autumn profile — October 12, 2026

The capture is healthier than the previous 0.1.2 recording. It maintains approximately 20 TPS, with a final rolling-minute mean of 4.75 ms per tick and p95 of 8.13 ms. The removed synchronous generation-check path is absent. The largest remaining sampled TrueSeasons cost is TPS polling, followed by chunk-load scheduling and durable BlueMap journal writes. This is an observational comparison between different runs, not a controlled speedup measurement.

No plugin implementation, configuration, or running-server state was changed for this analysis. The supplied profile and logs were treated as data. Only analysis artifacts were written.

## Capture and validation

- File: `EGgb2u2OO6.sparkprofile`, 304,301 bytes.
- SHA-256: `fcb09f42fceea5037f9f2ab6be0f99867ef0b7c1d9a665a1d04dcdfd6ed92971`.
- Metadata interval: October 12, 17:16:51.416–17:17:54.034 America/Los_Angeles (October 13, 00:16:51.416–00:17:54.034 UTC).
- Sampling: Java execution sampler, 4 ms interval; 1,200 ticks; one sampled thread, `Server thread`.
- Versions: TrueSeasons 0.1.3, Paper 26.3 build 177, BlueMap 5.29, spark 1.10.191.
- Samples total 59,996 ms. All 2,810 stack nodes are reachable, and root samples sum exactly to the thread total.
- An independent decode with the installed spark generated protobuf parser confirmed timestamps, tick count, interval, sampled time and node count.

`EGgb2u2OO6-analysis.json` contains decoded statistics and sampled paths. Server configuration payloads are not exported. The decoder follows spark's official [SamplerData schema](https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark_sampler.proto).

## What was running

The matching session is `test server/logs/2026-10-12-6.log.gz`; the current `latest.log` belongs to a later server session and world.

| Local time | Evidence |
| --- | --- |
| 17:08:13 | TrueSeasons 0.1.3 starts in `manual-AUTUMN-r4-snow3` |
| 17:09:52 | Winter command; introductory storm starts |
| 17:15:02 | Autumn command interrupts winter after 5m10s; no winter completion was logged |
| 17:16:51 | Profiling starts, 1m49s after the autumn switch |
| 17:17:02 | Player teleports during the capture |
| 17:17:39 | Player requests rain during the capture |
| 17:17:55 | The profile URL is logged |
| 17:22:37 | Autumn migration completes 7,235 chunks, 7m35s after the switch |
| 17:26:04 | Completion logs 7,236 chunks, consistent with one additional full chunk joining the workload |

This capture samples autumn restoration, not the immediate season-switch operation or active winter placement. There are no TrueSeasons deferred-chunk, index-failure or migration-failure messages in the matched run. In-game status replies are not logged, so the exact winter percentage at interruption and intermediate progress changes cannot be recovered from these logs. Completion messages do not establish the rendered map's appearance or its final queue state.

## Tick statistics

| Statistic | Previous 0.1.2 capture | New 0.1.3 capture |
| --- | ---: | ---: |
| Final rolling-minute TPS | 19.997 | 20.003 |
| Mean MSPT | 13.69 | 4.75 |
| Median MSPT | 14.31 | 3.99 |
| p95 MSPT | 19.74 | 8.13 |
| Final rolling-minute maximum MSPT | 49.58 | 48.54 |

The lower mean and p95 are encouraging. Player movement, loaded chunks, migrated ownership and world size differ between the runs; this cannot isolate the exact improvement attributable to 0.1.3.

The earlier window records a **263.87 ms** maximum, while the final window and final metadata show 48.54 ms. These window maxima are rolling one-minute observations, not maxima restricted to each sample bucket. The earlier statistic may include a tick before sampling started; it cannot be assigned to a specific TrueSeasons operation from this aggregate capture. Both the official [WindowStatisticsCollector](https://github.com/lucko/spark/blob/master/spark-common/src/main/java/me/lucko/spark/common/sampler/window/WindowStatisticsCollector.java) and the installed spark bytecode confirm that behavior. Its world counts also change from 1,250 to 625 loaded chunks; those are loaded-world statistics, not migration completion counts.

## Remaining costs

Percentages below are shares of sampled server-thread wall time, including idle time and waits. They overlap when a row contains another row. They are not CPU utilization, exact method timings, or proportions of the tick budget; a 4 ms execution sampler is coarse for short operations.

| Sampled path | Share | Interpretation |
| --- | ---: | --- |
| All topmost TrueSeasons callbacks | 17.26% | Previously 29.72%; includes Paper work and persistence invoked by the plugin |
| Engine tick | 14.87% | Includes TPS polling and asynchronous-load scheduling |
| Engine → Bukkit TPS query | 9.58% | Largest remaining sampled plugin descendant |
| Engine load | 3.92% | Includes generation-disabled load scheduling and some immediate completion callbacks |
| BlueMap journal `putAll` | 2.01% | Mostly durable disk flushes |
| Native file `force0` | 1.81% | All sampled paths lead from TrueSeasons BlueMap persistence |
| BlueMap maintenance tick | 1.07% | Receipts, render scheduling, bookkeeping and marker purges |
| BlueMap marker purges | 0.093% | Previously 2.05%; small in this particular phase |
| Physical snow application | 0.027% | Direct block work scarcely sampled |
| Native snow presentation | 0.067% | Small; this is an autumn capture |
| Temporary weather service | 0.007% | Small |

`CraftWorld.isChunkGenerated` has **zero samples** and its former engine call sites were removed in 0.1.3. Generation-disabled async load scheduling remains. Roughly 73% of samples are normal idle parking under the server's tick-wait path; the server is not continuously saturated.

The TPS query remains once per engine tick, including periods before pause/finished checks. Local bytecode from Paper's `ca.spottedleaf.leafpile:common:1.2.4` shows `TickData.getTPSAverage` iterating historical tick records. Querying it every tick still does repeated aggregation, even after 0.1.3 removed multiple queries per tick. The 19.09% method-total row for `MinecraftServer.getTPS` must not be treated as a second separate TPS cost: overloaded/nested calls overlap. The engine's Bukkit query path is the appropriate 9.58% attribution.

BlueMap journal writes force durability before changing world metadata, then persist final revisions. The sampled native flush time is concentrated in those paths. Simply removing forced writes would change the crash-recovery guarantee; an optimization should preserve that ordering.

## Interrupted-migration behavior

`setSeason` increments the revision, and `restart` clears completed bookkeeping and requeues known full chunks. Autumn revision 4 → winter revision 5 → autumn revision 6 therefore makes untouched revision-4 autumn chunks stale too. The autumn pass loads/verifies them and can update metadata and BlueMap revisions even when no physical snow needs removal. This explains a full-world pass after a partial winter transition; the profile cannot determine how many sampled chunks were untouched.

The autumn snow path removes only expected owned snow, clears pending winter/cursor state and stamps the new version. It does not resume the winter placement loop. Old asynchronous load callbacks are fenced by their captured generation before processing failures or applying snow. No evidence in this recording establishes continuing winter writes after the manual switch.

The configured application-time budget measures the chunk application operation; chunk scheduling, Paper's deferred lighting/saves and unload work can add costs outside it. It is not a hard cap on all world work per tick.

A separate code-review edge case exists for future external integrations: a synchronous season change invoked from inside `SeasonChunkUpdatedEvent` occurs after the initial callback generation check. Completion bookkeeping then needs a second generation check before updating the restarted job. No evidence ties that reentrant event case to this run; ordinary player commands execute between main-thread callbacks. No fix was implemented here.

## Suggested follow-up, without implementation

1. Reduce TPS polling to a short interval such as one second and skip it when no migration work needs throttling. This is the clearest small remaining optimization supported by the capture and local bytecode.
2. Review how to avoid redundant map invalidation and seasonal bookkeeping for untouched chunks when reversing an incomplete transition, while preserving final revision receipts and future visual updates.
3. Consider batching durable journal work only if further profiling justifies it, keeping the required persistence-before-edit barrier.

The capture supports a small performance pass before seasonal foliage work. It does not show a failed autumn transition or a repeat of the old large protochunk-denominator reset.
