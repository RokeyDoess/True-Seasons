# Optional PacketEvents compatibility experiment

This continues the earlier [transport comparison](PACKETEVENTS_TRANSPORT_COMPARISON.md). The current experimental build is `0.3.5-packetevents.2`, based on published 0.3.5. It keeps True Seasons' native packet handling and makes PacketEvents optional.

## Approach

Ordinary PacketEvents sends already enter True Seasons' existing native handler. They arrive as encoded buffers, which the published handler forwards without seasonal processing. That explains why a normal PacketEvents leaf update could turn an autumn leaf green.

The compatibility path recognizes only encoded single-block and section-block updates during gameplay. It decodes those with Paper's native codecs, applies the existing leaf and snow logic, and forwards the result through the normal pipeline. It does not add a transport listener or another channel handler. Native packet objects continue through the existing path without the extra decode/encode round trip used by the first experiment.

- Unrelated encoded packets retain their buffer, data, reader position and ownership.
- Unchanged block updates retain their original buffer. Changed updates get a new buffer; shared input bytes remain untouched.
- The existing presentation caches receive the underlying block states, so later seasonal restoration keeps replacements and does not restore painted leaves as originals.
- Configuration traffic is excluded until a gameplay dimension is known. Reconfiguration and retired presentation also bypass encoded gameplay handling.
- PacketEvents is an optional load-order dependency. Its API is a compile-only dependency for the registry bookkeeping described below, not a required installed plugin.

## Registry bookkeeping

The wider audit found a separate issue: PacketEvents caches synchronized registries globally by protocol version. Without compatibility bookkeeping, client-only seasonal aliases can enter that shared cache. A live palette reload can then update the Minecraft client while the library still exposes the old palette.

A metadata-only compatibility hook handles this during configuration:

1. Before our expanded registry reaches PacketEvents, populate an empty shared biome cache from the real server definitions. Existing library-owned entries are not overwritten.
2. Observe the biome registry already being sent by the native handler and assign the matching seasonal registry to that player.
3. Share one immutable projection across equal joins, replacing the cache when its definitions change. Known-pack negotiation uses materialized original biome definitions.

The hook does not edit, cancel or send packets. Ordinary seasonal updates never pass through its API. It stays attached only for tracked connections and releases its cached projection after retirement and disconnect.

## Verification

Tests use private localhost server copies, existing terrain and whitelisted non-op protocol clients without Grim exemptions. No terrain generation or population is allowed. Downloaded plugins, profiles, raw logs and test worlds remain local.

Tested versions: Paper/client protocol 26.3, PacketEvents 2.14.0, GrimAC 2.3.74-3bc8959, ProtocolLib 5.5.0-SNAPSHOT-d89a5de and ViaVersion 5.12.1. Grim includes a separate relocated PacketEvents copy; True Seasons does not replace or reconfigure it.

The native-path checks cover:

- Startup and seasonal presentation without standalone PacketEvents.
- Grim's bundled copy with no standalone PacketEvents.
- Standalone PacketEvents, both alone and with the full packet-plugin stack.
- Concurrent joins with and without known-pack negotiation, ordinary library block updates, delayed writes and live palette reloads.
- PacketEvents listener edits and cancellation, restoration afterward, and disconnect with a delayed write still queued. These retain their normal library behavior.
- Accumulation, digging acknowledgment, break grace, melt, re-accumulation and visual retirement over a 160-column snow fixture. Physical air and stone remain unchanged.
- Original block restoration, mixed leaf/stone section updates, unrelated buffers and configuration packet-ID collisions in the native packet regression suite.

The four-client full-stack run delivered the seasonal registry twice to each client, including a live palette reload. PacketEvents retained 67 real biomes in its shared cache and 2,881 entries in each player's seasonal registry. The reloaded grass color matched the wire data, and all four players reused the same projection for each palette. The standalone-only run confirmed the same cache separation and reload behavior. Retirement followed by disconnect cleared the tracked connections and cached projection.

The final local build passed all standalone suites and native packet regressions, including 4,990 assertions in the combined seasonal packet suite. Gradle `check jar` also passed. The final full-stack listener-composition run passed against the packaged build.

The wire clients validate received packets, not rendering or Minecraft movement physics. Real-client walking, jumping and collision behavior under Grim still need playtesting. ViaVersion is loaded with native 26.3 clients; older translated clients, proxies and Geyser are not certified.

## Boundaries

This supports ordinary PacketEvents use while preserving the native pipeline. It does not force True Seasons to win against every deliberate packet edit. Listeners that intentionally replace our environmental appearances can still override them. Silent or direct-context sends can bypass the native handler, as they did before; unrelated silent packets are left alone. Fake terrain, custom per-player registries and other competing environmental effects need individual compatibility work when encountered.

The metadata hook uses PacketEvents' registry support and is tested against 2.14.0. That integration needs checking when the library changes. It does not alter PacketEvents' global settings or attempt to manage another plugin's packet listeners.

The shared-cache check starts with a fresh server process. It deliberately leaves an existing shared entry untouched, so it does not repair a cache already contaminated by an earlier plugin instance. Restart the experimental server when changing implementations.

## Performance and status

Normal native packets do not enter the compatibility codec. Unrelated encoded packets take only the packet-ID check. Only relevant encoded updates are decoded, and only changed updates are encoded again. The registry projection is shared and bounded to the current cached definition; there is no per-chunk metadata cache or additional world scan.

A warmed native-codec fixture measured approximately zero allocation per unrelated encoded packet, 120 bytes per unchanged single-block update and 408 bytes per repainted single-block update. These are codec-only allocation measurements over three rounds, using 2,000,000 unrelated packets and 100,000 updates per round. They exclude the seasonal algorithms, networking and server workload; they are not whole-server performance figures.

This is a compatibility change, not a claimed TPS improvement. The first experiment's section-codec allocation figures do not apply to this implementation. Startup and full-server performance conclusions require representative profiling beyond these small fixtures.

The branch remains experimental. Main, the published release and the normal installed test server are unchanged. Use a separate Paper 26.3 server copy to try `TrueSeasons-0.3.5-packetevents.2.jar`; install standalone PacketEvents 2.14.0 only when testing that integration. Grim's bundled copy does not count as the standalone plugin.

For controlled baseline comparisons, `-Dtrueseasons.disable-packet-compatibility=true` disables both compatibility pieces while retaining native seasonal handling. This is an experiment switch, not a normal server setting. The local build script blocks automatic installation into the normal test server.
