package dev.trueseasons.migration;

import java.util.*;

/** Large dense worlds must distribute bookkeeping keys without changing coordinate identity. */
public final class ChunkCoordinateHashTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        Set<Integer> hashes=new HashSet<>();Map<ChunkPos,Integer> checkpoints=new HashMap<>();
        for(int x=-256;x<256;x++)for(int z=-200;z<200;z++){
            ChunkPos pos=new ChunkPos(x,z);hashes.add(pos.hashCode());checkpoints.put(pos,x*400+z);
        }
        check(hashes.size()>checkpoints.size()*.99,"Dense 204,800-chunk grid has distributed hashes rather than coordinate bands");
        var saved=Map.copyOf(checkpoints);
        for(int x=-256;x<256;x++)for(int z=-200;z<200;z++)
            check(saved.get(new ChunkPos(x,z))==x*400+z,"Independent coordinate keys retrieve exact saved checkpoints");
        Set<Long> keys=new HashSet<>();
        for(int x:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE})for(int z:new int[]{Integer.MIN_VALUE,-1,0,1,Integer.MAX_VALUE}){
            ChunkPos pos=new ChunkPos(x,z),copy=new ChunkPos((int)(pos.key()>>32),(int)pos.key());
            check(keys.add(pos.key()),"Packed saved keys remain distinct at coordinate limits");
            check(copy.equals(pos)&&copy.hashCode()==pos.hashCode(),"Packed key round trip preserves identity and hash");
        }
        Set<Integer> diagonal=new HashSet<>();
        for(int x=-1000;x<1000;x++)diagonal.add(new ChunkPos(x,-31*x).hashCode());
        check(diagonal.size()>1990,"Old constant-hash diagonal also remains distributed");
        System.out.println("Chunk coordinate hash tests passed: "+checks+" assertions");
    }
}
