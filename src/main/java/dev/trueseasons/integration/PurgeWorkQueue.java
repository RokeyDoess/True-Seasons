package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.ChunkKey;
import java.util.*;
import java.util.function.Predicate;

/** One canonical entry per purge, partitioned between chunk work and saved-proof work. */
final class PurgeWorkQueue<V> extends AbstractMap<ChunkKey,V> {
    private final LinkedHashMap<UUID,LinkedHashMap<ChunkKey,V>> ready=new LinkedHashMap<>();
    private final LinkedHashMap<ChunkKey,V> awaiting=new LinkedHashMap<>();
    private int size,version;
    private boolean proofTurn;
    @Override public int size(){return size;}
    @Override public V get(Object key){
        if(!(key instanceof ChunkKey chunk))return null;
        var world=ready.get(chunk.world());V value=world==null?null:world.get(chunk);
        return value==null?awaiting.get(chunk):value;
    }
    @Override public boolean containsKey(Object key){return get(key)!=null;}
    @Override public V put(ChunkKey key,V value){
        Objects.requireNonNull(key);Objects.requireNonNull(value);
        if(awaiting.containsKey(key))return awaiting.put(key,value);
        V before=ready.computeIfAbsent(key.world(),ignored->new LinkedHashMap<>()).put(key,value);
        if(before==null){size++;version++;}return before;
    }
    @Override public V remove(Object key){
        if(!(key instanceof ChunkKey chunk))return null;
        V before=awaiting.remove(chunk);
        if(before==null){var world=ready.get(chunk.world());if(world!=null){before=world.remove(chunk);if(world.isEmpty())ready.remove(chunk.world());}}
        if(before!=null){size--;version++;}return before;
    }
    @Override public void clear(){if(size!=0){ready.clear();awaiting.clear();size=0;version++;}}
    void awaiting(ChunkKey key){
        var world=ready.get(key.world());if(world==null)return;V value=world.remove(key);if(value==null)return;
        awaiting.put(key,value);
        if(world.isEmpty())ready.remove(key.world());else ready.putLast(key.world(),world);
        version++;
    }
    void retry(ChunkKey key){
        V value=awaiting.remove(key);if(value==null)return;
        ready.computeIfAbsent(key.world(),ignored->new LinkedHashMap<>()).put(key,value);version++;
    }
    /** Missing worlds cannot hide runnable work in another world. */
    Map.Entry<ChunkKey,V> nextReady(Predicate<UUID> available){
        for(var world:ready.entrySet())if(available.test(world.getKey())){
            var first=world.getValue().firstEntry();return Map.entry(first.getKey(),first.getValue());
        }
        return null;
    }
    /** Independent proof rotation never moves the head of the chunk-work queue. */
    List<Map.Entry<ChunkKey,V>> probes(int maximum,Predicate<UUID> available){
        if(maximum<0)throw new IllegalArgumentException("Negative probe count");
        var result=new ArrayList<Map.Entry<ChunkKey,V>>(Math.min(maximum,size));
        int proofCount=awaiting.size(),usedProofs=0;
        var candidates=new ArrayList<Map.Entry<ChunkKey,V>>();
        outer:for(var world:ready.entrySet())if(available.test(world.getKey()))for(var row:world.getValue().entrySet()){
            if(candidates.size()==maximum)break outer;candidates.add(Map.entry(row.getKey(),row.getValue()));
        }
        int usedReady=0;
        while(result.size()<maximum&&(usedProofs<proofCount||usedReady<candidates.size())){
            if(usedProofs<proofCount&&(proofTurn||usedReady==candidates.size())){
                ChunkKey key=awaiting.sequencedKeySet().getFirst();V value=awaiting.get(key);
                result.add(Map.entry(key,value));awaiting.putLast(key,value);usedProofs++;version++;
            }else result.add(candidates.get(usedReady++));
            proofTurn=!proofTurn;
        }
        return result;
    }
    private final Set<Map.Entry<ChunkKey,V>> entries=new AbstractSet<>(){
        @Override public int size(){return size;}
        @Override public void clear(){PurgeWorkQueue.this.clear();}
        @Override public Iterator<Map.Entry<ChunkKey,V>> iterator(){
            // Only world buckets are captured; no copy of the chunk keys or values is needed.
            var buckets=new ArrayList<>(ready.values());buckets.add(awaiting);
            return new Iterator<>(){
                int expected=version,index;
                Iterator<Map.Entry<ChunkKey,V>> current=Collections.emptyIterator(),last;
                ChunkKey lastKey;
                LinkedHashMap<ChunkKey,V> bucket,lastBucket;
                private void check(){if(expected!=version)throw new ConcurrentModificationException();}
                public boolean hasNext(){check();while(!current.hasNext()&&index<buckets.size()){bucket=buckets.get(index++);current=bucket.entrySet().iterator();}return current.hasNext();}
                public Map.Entry<ChunkKey,V> next(){
                    if(!hasNext())throw new NoSuchElementException();var entry=current.next();last=current;lastBucket=bucket;lastKey=entry.getKey();
                    return new Map.Entry<>(){
                        public ChunkKey getKey(){return entry.getKey();}
                        public V getValue(){return entry.getValue();}
                        public V setValue(V value){return entry.setValue(Objects.requireNonNull(value));}
                        @Override public boolean equals(Object other){return entry.equals(other);}
                        @Override public int hashCode(){return entry.hashCode();}
                    };
                }
                public void remove(){
                    check();if(last==null)throw new IllegalStateException();last.remove();size--;expected=++version;
                    if(lastBucket!=awaiting&&lastBucket.isEmpty())ready.remove(lastKey.world());last=null;
                }
            };
        }
    };
    @Override public Set<Map.Entry<ChunkKey,V>> entrySet(){return entries;}
}
