Profile analysis: winter → autumn, 12 October 2026

In the captured TrueSeasons 0.1.2 build, the dominant migration cost is checking whether an unloaded chunk is generated on the server thread. Snow removal and journal persistence are small in this recording. The reported 100% → approximately 50% progress regression also has a concrete cause in that build: an unchanged index rescan reintroduces previously rejected protochunks into the progress denominator. The current 0.1.3 source addresses both behaviors; no post-change spark benchmark is available yet.

The supplied profile is 326,977 bytes, SHA-256 `2043e2444ac6cff85df23ad11caba6c21cc681a31b7d2f9858b2da2ea8bfbd1b`. It records 23:04:55.488–23:05:57.823 UTC, TrueSeasons 0.1.2, BlueMap 5.29, Paper 26.3-177, and spark 1.10.191. Its Java execution sampler used a 4 ms interval and covered 1,200 ticks. Recorded last-minute TPS was 19.997; mean tick duration was 13.69 ms, median 14.31 ms, 95th percentile 19.74 ms, and maximum 49.58 ms. This minute shows increased tick cost and spikes close to the 50 ms tick allowance, while its average TPS remained approximately 20.

Only the Server thread is present. Its samples total 59,988 ms. Inclusive values contain descendants and overlap; parked or waiting time is included, so these are sampled main-thread wall-time shares, not CPU percentages. Execution samples use milliseconds according to [spark's SamplerMode](https://github.com/lucko/spark/blob/master/spark-common/src/main/java/me/lucko/spark/common/sampler/SamplerMode.java).

| Sampled path | Inclusive share | Interpretation |
| --- | ---: | --- |
| All topmost TrueSeasons callbacks | 29.72% | Includes native waiting invoked by the plugin |
| ChunkMigrationEngine.tick | 25.99% | Main scheduled migration loop |
| engine.load → CraftWorld.isChunkGenerated | 19.14% | Synchronous chunk availability checking reaches native chunk scheduling, managedBlock, waitForTasks, and park |
| engine.lowTps → CraftServer.getTPS | 5.39% | Repeated TPS checks appear as a separate expensive sampled path |
| BlueMapIntegration.tick | 3.24% | Maintenance and render coordination |
| BlueMapIntegration.purgeChunks | 2.05% | 1.20% self time plus async-load scheduling; scans already processed purge entries each tick |
| DurableRenderJournal.putAll | 0.39% | Current small journal updates are not the leading cost |
| SeasonalSnowService.applyStep | 0.013% | Direct snow edits were scarcely sampled |
| NativeSnowPresentation.tick | 0.107% | Small in this recording |
| WeatherService.tick | 0.013% | Small in this recording |

The captured 0.1.2 engine performs `isChunkGenerated` before requesting its asynchronous, generation-disabled load, and repeats that synchronous check for null callbacks. Its application budget measures `apply`, while these availability checks happen outside that measurement. One blocking check can therefore exceed the intended edit budget. The current 0.1.3 source removes both synchronous availability checks, keeps generation-disabled asynchronous loading, and caches the TPS result. Its worker-built index snapshot classifies saved FULL chunks separately from protochunks. The captured integration samples also suggest that an explicit pending purge-load queue could avoid repeated scans of entries already waiting for their saved purge proof; this recording does not measure that possible change.

The progress regression follows this sequence in the captured 0.1.2 behavior:

1. `Job.total()` is `known.size() - unavailable.size()`.
2. The index reads region location headers, which contain both full chunks and saved protochunks.
3. An index scan calls `add` for every entry. For an already known unavailable entry, `add` removes it from `unavailable` and enqueues it again.
4. The denominator grows immediately, before generation-disabled checks reject those entries again. A world with 2,569 completed full chunks and 5,479 header entries would move from 100% to 46.9%, then back toward 100%, even if no additional chunks had become full. Those numbers illustrate the previously verified clone; this profile itself does not contain a whole-world index count.

The current 0.1.3 worker snapshot distinguishes saved FULL/proto status, and unchanged rescans preserve the eligible denominator instead of reintroducing rejected protochunks. Newly full or genuinely new chunks can still join the job. These are implementation findings, not measured post-change performance results.

Ordinary BlueMap marker purging does not reset completed seasonal chunks: `bluemap_revision` is excluded from snow `hasMetadata`, `hasPending`, and seasonal version checks. The engine's ChunkLoadEvent listener requeues a completed chunk only if its seasonal version/pending state requires work, or if restoration metadata remains. Purge loads can discover genuinely new or newly full chunks, but the old unchanged-index rescan behavior is the direct explanation for a large repeatable denominator reset.

The standard-library decoder follows the official [SamplerData schema](https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark_sampler.proto), [statistics schema](https://github.com/lucko/spark/blob/master/spark-common/src/main/proto/spark/spark.proto), and [window time encoding](https://github.com/lucko/spark/blob/master/spark-common/src/main/java/me/lucko/spark/common/sampler/window/ProtoTimeEncoder.java). An independent decode using the installed spark JAR confirmed the exact start/end timestamps, 1,200 ticks, 4,000 µs interval, Server thread's 59,988 ms, and 2,775 stack nodes. Every node is reachable from the thread roots, and root times sum exactly to the thread total. The JSON output includes method totals and plugin call paths; server configuration contents are deliberately omitted.

Reproduction tools are `analyze_spark.py` and `VerifySparkDecode.java` in this directory. No plugin source or running server was changed for this analysis.
