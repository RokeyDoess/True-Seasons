package dev.trueseasons.snow;

import java.util.*;
import java.util.function.Predicate;

/** Main-thread fresh visibility work, deduplicated across viewers and served in turns. */
final class PreviewPriorityQueue<T> {
    private final int maximum;
    private final Map<UUID,LinkedHashSet<T>> owners=new HashMap<>();
    private final LinkedHashSet<UUID> turns=new LinkedHashSet<>();
    private final Set<T> pending=new HashSet<>();
    PreviewPriorityQueue(int maximum){if(maximum<1)throw new IllegalArgumentException("Positive capacity required");this.maximum=maximum;}
    boolean offer(UUID viewer,T work){
        Objects.requireNonNull(viewer);Objects.requireNonNull(work);
        if(pending.size()>=maximum||!pending.add(work))return false;
        owners.computeIfAbsent(viewer,ignored->new LinkedHashSet<>()).add(work);turns.add(viewer);return true;
    }
    T poll(){
        if(turns.isEmpty())return null;
        var turn=turns.iterator();UUID viewer=turn.next();turn.remove();
        var work=owners.get(viewer);var next=work.iterator();T result=next.next();next.remove();pending.remove(result);
        if(work.isEmpty())owners.remove(viewer);else turns.add(viewer);
        return result;
    }
    void removeViewer(UUID viewer){var removed=owners.remove(viewer);if(removed!=null)pending.removeAll(removed);turns.remove(viewer);}
    void retain(Predicate<T> keep){
        for(var iterator=owners.entrySet().iterator();iterator.hasNext();){
            var row=iterator.next();row.getValue().removeIf(work->{if(keep.test(work))return false;pending.remove(work);return true;});
            if(row.getValue().isEmpty()){turns.remove(row.getKey());iterator.remove();}
        }
    }
    boolean isEmpty(){return pending.isEmpty();}
    int size(){return pending.size();}
    void clear(){owners.clear();turns.clear();pending.clear();}
}
