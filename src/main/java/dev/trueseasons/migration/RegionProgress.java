package dev.trueseasons.migration;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/** Outstanding slots only; completed regions retain no keyed entry. */
final class RegionProgress {
    private static final class Slots {
        long[] bits;
        long compact;
        int word,count;
    }
    private final Long2ObjectOpenHashMap<Slots> regions=new Long2ObjectOpenHashMap<>();
    private Slots spare;
    void update(ChunkPos pos,boolean outstanding){
        long key=key(pos.regionX(),pos.regionZ());Slots slots=regions.get(key);
        if(slots==null){if(!outstanding)return;slots=spare==null?new Slots():spare;spare=null;regions.put(key,slots);}
        int at=((pos.z()&31)<<5)|(pos.x()&31),word=at>>>6;long bit=1L<<(at&63);
        if(slots.bits==null){
            if(slots.count==0)slots.word=word;
            if(slots.word!=word){
                if(!outstanding)return;
                slots.bits=new long[16];slots.bits[slots.word]=slots.compact;slots.compact=0;
            }else{
                boolean previous=(slots.compact&bit)!=0;if(previous==outstanding)return;
                if(outstanding){slots.compact|=bit;slots.count++;}
                else{slots.compact&=~bit;if(--slots.count==0){regions.remove(key);spare=slots;}}
                return;
            }
        }
        boolean previous=(slots.bits[word]&bit)!=0;if(previous==outstanding)return;
        if(outstanding){slots.bits[word]|=bit;slots.count++;}
        else{
            slots.bits[word]&=~bit;slots.count--;
            if(slots.count==1)for(int i=0;i<16;i++)if(slots.bits[i]!=0){slots.word=i;slots.compact=slots.bits[i];slots.bits=null;break;}
        }
    }
    boolean processed(int x,int z){
        long firstX=(long)x*32,firstZ=(long)z*32;
        return firstX>=Integer.MIN_VALUE&&firstX+31<=Integer.MAX_VALUE&&firstZ>=Integer.MIN_VALUE&&firstZ+31<=Integer.MAX_VALUE
            &&!regions.containsKey(key(x,z));
    }
    void clear(){regions.clear();spare=null;}
    private static long key(int x,int z){return ((long)x<<32)|(z&0xffffffffL);}
}
