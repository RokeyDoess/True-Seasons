> Historical first experiment, commit `12da4d6`. The current branch uses native handling with optional compatibility; see [current findings](PACKETEVENTS_EXPERIMENT.md). The follow-up established that ordinary encoded sends reach the native handler but bypassed its seasonal processing.

# PacketEvents experiment

This branch tests whether moving seasonal packet handling to PacketEvents would improve compatibility and reduce maintenance. It is based on True Seasons 0.3.5. It does not change the published release.

## Findings

**There is a concrete compatibility benefit, but no demonstrated overall performance improvement.**

The existing transport worked with the tested GrimAC, ProtocolLib and ViaVersion stack. It is not generally incompatible with those plugins. However, an ordinary block update sent through PacketEvents' public API bypassed the existing native packet handler. Sending a birch leaf update during autumn made that leaf green again. The experimental transport caught the same update and kept the autumn appearance. Grim tracked the block state that reached the client in both cases.

That matters for plugins which send their own block appearances. It does not prove that every plugin using PacketEvents conflicts today, or that this experiment fixes every possible conflict. Sends that deliberately bypass listeners can still bypass our changes. Later listeners can also replace an earlier listener's work.

**Adding standalone PacketEvents does not put Grim and True Seasons into one shared listener order.** The tested Grim build bundles a separate, relocated copy of PacketEvents. Both packet handlers appeared in the connection pipeline. The ordering between those two copies still needs testing.

## What the prototype changes

- Seasonal packets enter through a PacketEvents listener instead of True Seasons' own Netty handler.
- Existing leaf, biome and snow logic remains in use, including its work limits and restoration behavior.
- Configuration updates keep PacketEvents' per-player biome registry consistent with the expanded registry sent to the client. The shared vanilla registry cache is left alone.
- Generated updates retain their identity while writes are delayed, preventing our own already-painted packets from being processed again.
- Closing seasonal presentation restores appearances and keeps the necessary biome-width adapter until the connection closes.
- An optional second experiment reads and writes chunk sections using PacketEvents' palette classes.

This is a transport prototype, **not a completed removal of native Minecraft packet code**. It decodes packets into the existing native helpers and encodes changed packets again. That extra work is a reason to avoid treating this branch as the final implementation or using its timings to predict an optimized wrapper port.

## Tested stack

| Component | Version |
| --- | --- |
| Paper / client protocol | 26.3 |
| True Seasons baseline | 0.3.5 |
| Experimental build | 0.3.5-packetevents.1 |
| Standalone PacketEvents | 2.14.0 |
| GrimAC | 2.3.74-3bc8959 |
| PacketEvents bundled inside that Grim build | 2.14.1+c11273e-SNAPSHOT |
| ProtocolLib | 5.5.0-SNAPSHOT-d89a5de |
| ViaVersion | 5.12.1 |

Tests used isolated localhost server copies, existing terrain and non-op clients without Grim exemptions. The installed test server and main checkout were not changed. Downloaded plugins, server copies, profiles and raw logs remain local.

## Results

| Check | Result |
| --- | --- |
| Published native transport with Grim | Passed native wire checks and sampled Grim terrain checks |
| Published native transport with all four packet plugins | Passed ordinary joins and autumn → summer → autumn updates |
| Public PacketEvents leaf update during autumn | Native transport reproduced the green-leaf issue; prototype preserved the autumn leaf |
| Four concurrent clients, full stack | Passed with and without known-pack negotiation; each received 213 visible chunks |
| Delayed writes during a season change | Passed; queued changes reached the expected final appearance |
| Earlier wrapper edits, later overrides and cancellation | Actual wire state followed the intended listener order; cancellation preserved the previous appearance |
| Disconnect with a delayed write still queued | Connection bindings and generated-message tracking both returned to zero |
| Live palette reload | All four clients reconfigured, received the changed palette and returned to the full view |
| Biome registry after join and palette reload | Client and standalone PacketEvents both had all 2,881 entries |
| Snow preview and melt over 160 eligible columns | Passed accumulation, digging, melt, re-accumulation and presentation retirement |
| Preview digging | Received the native acknowledgment; the broken preview stayed clear during its grace period |
| Physical blocks beneath packet snow | Fixture remained unchanged throughout the snow-flow check |
| Existing standalone regression suites | Passed, including the combined seasonal packet suite |
| PacketEvents section adapter | Preserved block states, block/fluid counts, original biomes and all 1,536 mapped biome cells in two 24-section fixtures |

Wire clients verify received protocol data. They do not render Minecraft or reproduce its movement physics. These checks therefore do not certify walking, jumping, collision prediction or every anticheat check. Brief differences while a bounded season refresh is still being delivered are expected; settled states are the meaningful comparison.

ViaVersion was loaded with native 26.3 clients. Older translated clients, proxies, Geyser and arbitrary packet-plugin combinations were not tested. BlueMap rendering, full terrain migrations and durability were not re-benchmarked in this transport experiment; their underlying code was not changed.

## Performance

The registry-aware section adapter was compared with the existing codec using the same input and outputs, including capture of original biomes for later restoration. A warmed fixture contained 24 sections, mixed block palettes and biome borders. Four alternating rounds encoded 2,000 chunks per implementation.

| Codec | Allocated per round | Thread CPU per round |
| --- | --- | --- |
| Existing codec | About 379 MiB | 109–125 ms |
| PacketEvents section adapter | About 260–261 MiB | 156–219 ms |

In this fixture the adapter allocated about 31% less memory but used more CPU. This measures only the section codec. It excludes networking, lighting, block entities and whole-server workload. It is not a TPS benchmark, and it does not justify enabling the section adapter by default. Short server tests also showed variable startup and join spikes; they are correctness checks, not evidence of a speedup.

PacketEvents does not remove tree selection, snow planning, migration or BlueMap work. The current bridge also adds packet decoding and encoding. A production port would need to avoid that conversion round trip before making performance claims.

Keeping the library's registry view correct also adds per-player registry parsing and storage in this prototype. That cost needs measuring at realistic player counts; the section-codec allocation result does not account for it.

## Trying the branch

Use a separate server copy with Paper 26.3 and standalone PacketEvents 2.14.0. This branch declares PacketEvents as a required plugin. Grim's bundled copy does not satisfy that requirement. The experimental jar is named `TrueSeasons-0.3.5-packetevents.1.jar`.

The default selects PacketEvents transport and retains the current section codec. For controlled comparisons only:

- `-Dtrueseasons.packet-backend=native` selects the original transport. PacketEvents is still required by this branch's plugin declaration.
- `-Dtrueseasons.packetevents-sections=true` enables the optional section adapter. Leave it off when measuring transport alone.

The local build script deliberately rejects automatic installation into the normal test server. There is no release tag or release asset for this experiment.

## Recommendation

Keep this separate from the release branch for now. PacketEvents is promising for compatibility and packet maintenance; requiring it solely for better TPS or solely for Grim would not be supported by these results.

Before adopting it, replace the conversion bridge with direct packet adapters, profile that implementation on a representative workload, and test real-client movement and digging under Grim. Expand the listener-composition and queued-disconnect checks to other packet types and plugin combinations. Also test native bundle delivery and translated clients if those are to be supported. Keep the existing restoration and world-preservation rules throughout that work.

Upstream references: [PacketEvents 2.14.0](https://github.com/retrooper/packetevents/releases/tag/v2.14.0), [PacketEvents development setup](https://docs.packetevents.com/development-setup), [Grim source used for inspection](https://github.com/GrimAnticheat/Grim/tree/3bc895910193212efd92d5eea9995334efe22388).
