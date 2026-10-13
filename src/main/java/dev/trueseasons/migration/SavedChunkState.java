package dev.trueseasons.migration;

/** Saved chunk PDC is the restart checkpoint; counters alone cannot prove edits survived. */
public record SavedChunkState(boolean full, String version, String pendingVersion, boolean pending, int columns,
                              boolean seasonMetadata, boolean mapMarker, String mapRevision) {
    public boolean matches(String target, boolean cleanup) {
        return full && (cleanup ? !seasonMetadata && !mapMarker : target.equals(version) && !pending);
    }
}
