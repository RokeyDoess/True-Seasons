package dev.trueseasons.snow;

import java.util.*;
import java.util.function.Predicate;

/** Main-thread fresh visibility work, deduplicated across viewers and served in turns. */
final class PreviewPriorityQueue<T> {
    private final int maximum;
    private record Work<T>(UUID viewer,T value,int priority,long sequence){}
    private final Map<UUID,NavigableSet<Work<T>>> owners=new HashMap<>();
    private final LinkedHashSet<UUID> turns=new LinkedHashSet<>();
    private final Map<T,Work<T>> pending=new HashMap<>();
    private long sequence;
    PreviewPriorityQueue(int maximum){if(maximum<1)throw new IllegalArgumentException("Positive capacity required");this.maximum=maximum;}
    boolean offer(UUID viewer,T work){
        return offer(viewer,work,Integer.MAX_VALUE);
    }
    boolean offer(UUID viewer,T work,int priority){
        Objects.requireNonNull(viewer);Objects.requireNonNull(work);
        Work<T> old=pending.get(work);
        if(old!=null){
            if(priority>=old.priority)return false;
            owners.get(old.viewer).remove(old);viewer=old.viewer;
        }else if(pending.size()>=maximum)return false;
        Work<T> entry=new Work<>(viewer,work,priority,old==null?sequence++:old.sequence);
        owners.computeIfAbsent(viewer,ignored->new TreeSet<>(Comparator.<Work<T>>comparingInt(Work::priority).thenComparingLong(Work::sequence))).add(entry);
        pending.put(work,entry);turns.add(viewer);return true;
    }
    T poll(){
        if(turns.isEmpty())return null;
        var turn=turns.iterator();UUID viewer=turn.next();turn.remove();
        var work=owners.get(viewer);var entry=work.pollFirst();T result=entry.value;pending.remove(result);
        if(work.isEmpty())owners.remove(viewer);else turns.add(viewer);
        return result;
    }
    void removeViewer(UUID viewer){var removed=owners.remove(viewer);if(removed!=null)for(var entry:removed)pending.remove(entry.value);turns.remove(viewer);}
    void retain(Predicate<T> keep){
        for(var iterator=owners.entrySet().iterator();iterator.hasNext();){
            var row=iterator.next();row.getValue().removeIf(work->{if(keep.test(work.value))return false;pending.remove(work.value);return true;});
            if(row.getValue().isEmpty()){turns.remove(row.getKey());iterator.remove();}
        }
    }
    boolean isEmpty(){return pending.isEmpty();}
    int size(){return pending.size();}
    void clear(){owners.clear();turns.clear();pending.clear();}
}
