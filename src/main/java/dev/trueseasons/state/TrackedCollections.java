package dev.trueseasons.state;

import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Keep derived state correct through direct edits, default map methods and collection views. */
public final class TrackedCollections {
    private TrackedCollections(){}
    @FunctionalInterface public interface Change<K,V>{void changed(K key,V before,V after);}
    public static <K> Set<K> set(Set<K> values,Consumer<K> changed,BooleanSupplier enabled){
        return new AbstractSet<>(){
            @Override public int size(){return values.size();}
            @Override public boolean contains(Object value){return values.contains(value);}
            @Override public boolean add(K value){if(!values.add(Objects.requireNonNull(value)))return false;changed.accept(value);return true;}
            @SuppressWarnings("unchecked") @Override public boolean remove(Object value){if(!values.remove(value))return false;changed.accept((K)value);return true;}
            @Override public void clear(){if(!enabled.getAsBoolean()){values.clear();return;}var iterator=iterator();while(iterator.hasNext()){iterator.next();iterator.remove();}}
            @Override public Iterator<K> iterator(){
                var iterator=values.iterator();return new Iterator<>(){
                    K current;
                    public boolean hasNext(){return iterator.hasNext();}
                    public K next(){return current=iterator.next();}
                    public void remove(){iterator.remove();changed.accept(current);}
                };
            }
        };
    }
    public static <K,V> Map<K,V> map(Map<K,V> values,Change<K,V> changed,BooleanSupplier enabled){
        return new AbstractMap<>(){
            @Override public int size(){return values.size();}
            @Override public boolean containsKey(Object key){return values.containsKey(key);}
            @Override public V get(Object key){return values.get(key);}
            @Override public V put(K key,V value){
                Objects.requireNonNull(key);boolean present=values.containsKey(key);V before=values.put(key,value);
                if(!present||!Objects.equals(before,value))changed.changed(key,before,value);return before;
            }
            @SuppressWarnings("unchecked") @Override public V remove(Object key){
                if(!values.containsKey(key))return null;V before=values.remove(key);changed.changed((K)key,before,null);return before;
            }
            @Override public void clear(){if(!enabled.getAsBoolean()){values.clear();return;}var iterator=entrySet().iterator();while(iterator.hasNext()){iterator.next();iterator.remove();}}
            @Override public Set<Entry<K,V>> entrySet(){
                return new AbstractSet<>(){
                    @Override public int size(){return values.size();}
                    @Override public void clear(){var iterator=iterator();while(iterator.hasNext()){iterator.next();iterator.remove();}}
                    @Override public Iterator<Entry<K,V>> iterator(){
                        var iterator=values.entrySet().iterator();return new Iterator<>(){
                            Entry<K,V> current;
                            public boolean hasNext(){return iterator.hasNext();}
                            public Entry<K,V> next(){
                                var entry=iterator.next();current=entry;
                                return new Entry<>(){
                                    public K getKey(){return entry.getKey();}
                                    public V getValue(){return entry.getValue();}
                                    public V setValue(V value){V before=entry.setValue(value);if(!Objects.equals(before,value))changed.changed(entry.getKey(),before,value);return before;}
                                    @Override public boolean equals(Object value){return entry.equals(value);}
                                    @Override public int hashCode(){return entry.hashCode();}
                                };
                            }
                            public void remove(){if(current==null)throw new IllegalStateException();K key=current.getKey();V before=current.getValue();iterator.remove();current=null;changed.changed(key,before,null);}
                        };
                    }
                };
            }
        };
    }
}
