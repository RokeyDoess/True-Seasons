package dev.trueseasons.migration;

import dev.trueseasons.state.TrackedCollections;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Typed migration views share collection mutation accounting with render progress. */
final class MigrationStateCollections {
    private MigrationStateCollections(){}
    @FunctionalInterface interface Change<V>{void changed(ChunkPos pos,V before,V after);}
    static Set<ChunkPos> set(Set<ChunkPos> values,Consumer<ChunkPos> changed,BooleanSupplier enabled){
        return TrackedCollections.set(values,changed,enabled);
    }
    static <V> Map<ChunkPos,V> map(Map<ChunkPos,V> values,Change<V> changed,BooleanSupplier enabled){
        return TrackedCollections.map(values,changed::changed,enabled);
    }
}