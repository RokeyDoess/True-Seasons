package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.*;
import java.util.*;

/** A forced dirty intent permits recoverable final records to share the next durability barrier. */
final class FinalRevisionDurability {
    private final Set<ChunkKey> dirty = new HashSet<>();
    private final Map<ChunkKey, Integer> appendedFinals = new LinkedHashMap<>();
    void restored(RenderLedger ledger) {
        ledger.entries().forEach((key, entry) -> { if (entry.token().isEmpty()) dirty.add(key); });
    }
    boolean forceBeforeMarker(ChunkKey key) { return !dirty.contains(key); }
    void appendedFinal(ChunkKey key, int tick) { dirty.remove(key); appendedFinals.put(key, tick); }
    void forced(RenderLedger ledger, Collection<ChunkKey> keys) {
        // FileChannel.force covers every earlier successfully appended final record, not just this batch.
        appendedFinals.clear();
        for (ChunkKey key : keys) {
            RenderLedger.Entry entry = ledger.entries().get(key);
            if (entry != null && entry.token().isEmpty()) dirty.add(key); else dirty.remove(key);
        }
    }
    List<ChunkKey> due(int tick, int limit) {
        return appendedFinals.entrySet().stream().filter(row -> row.getValue() != tick).limit(limit).map(Map.Entry::getKey).toList();
    }
}
