package dev.trueseasons.migration;

import java.util.*;

/** Compare incremental readiness with the full current-state definition after arbitrary edits. */
public final class RegionProgressTests {
    private static int checks;
    public static void main(String[] args){
        var job=new ChunkMigrationEngine.Job(null);job.scanned=true;
        List<ChunkPos> positions=new ArrayList<>();
        for(int z=-16;z<16;z++)for(int x=-16;x<16;x++)positions.add(new ChunkPos(x,z));
        positions.add(new ChunkPos(Integer.MIN_VALUE,Integer.MIN_VALUE));positions.add(new ChunkPos(Integer.MAX_VALUE,Integer.MAX_VALUE));
        Random random=new Random(483921);
        for(int step=0;step<12000;step++){
            ChunkPos pos=positions.get(random.nextInt(positions.size()));
            switch(random.nextInt(20)){
                case 0 -> job.observeIndexed(pos,random.nextBoolean());
                case 1 -> toggle(job.known,pos);
                case 2 -> toggle(job.unavailable,pos);
                case 3 -> toggle(job.done,pos);
                case 4 -> toggle(job.queued,pos);
                case 5 -> toggle(job.flight,pos);
                case 6 -> {if(random.nextBoolean())job.failures.put(pos,1L);else job.failures.remove(pos);}
                case 7 -> {if(random.nextBoolean())job.nextStep.put(pos,7);else job.nextStep.remove(pos);}
                case 8 -> job.columns.put(pos,random.nextInt(257));
                case 9 -> job.columns.remove(pos);
                case 10 -> {var iterator=job.columns.entrySet().iterator();if(iterator.hasNext())iterator.next().setValue(random.nextInt(257));}
                case 11 -> {var iterator=job.columns.entrySet().iterator();if(iterator.hasNext()){iterator.next();iterator.remove();}}
                case 12 -> job.columns.keySet().remove(pos);
                case 13 -> job.columns.values().remove(random.nextInt(257));
                case 14 -> {if(step%17==0)job.columns.clear();else job.columns.compute(pos,(key,value)->value==null?19:(value+1)%257);}
                case 15 -> {if(step%19==0)job.failures.entrySet().clear();else job.nextStep.keySet().removeIf(key->key.equals(pos));}
                case 16 -> {var iterator=job.known.iterator();if(iterator.hasNext()){iterator.next();iterator.remove();}}
                case 17 -> {job.done.removeIf(value->value.equals(pos));job.unavailable.removeAll(List.of(pos));}
                case 18 -> job.markComplete(pos);
                case 19 -> {if(step%23==0){job.queued.clear();job.flight.clear();}else job.observeFull(pos);}
            }
            for(int z=-1;z<=0;z++)for(int x=-1;x<=0;x++)check(job.regionProcessed(x,z)==slow(job,x,z),"Incremental region readiness follows every collection edit");
            check(job.regionProcessed(pos.regionX(),pos.regionZ())==slow(job,pos.regionX(),pos.regionZ()),"Extreme and ordinary coordinates retain exact readiness");
            long expected=job.done.size()*256L;for(int value:job.columns.values())expected+=value;
            check(job.coveredColumns()==expected,"Partial coverage follows replacements, views, iterators and removals");
        }
        job.reset();check(!job.scanned&&!job.regionProcessed(0,0)&&job.coveredColumns()==0,"A restart discards derived progress before hydration");
        var complete=new SavedChunkState(true,"winter",null,false,0,true,false,null);
        for(ChunkPos pos:List.copyOf(job.known))job.observeCheckpoint(pos,complete,"winter",false,true,false);
        job.scanned=true;
        for(int z=-1;z<=0;z++)for(int x=-1;x<=0;x++)check(job.regionProcessed(x,z)==slow(job,x,z),"Saved hydration rebuilds progress while retaining in-flight obligations");
        var columns=job.columns.entrySet().iterator();boolean failed=false;
        try{columns.remove();}catch(IllegalStateException expected){failed=true;}
        check(failed,"Tracked iterators preserve the remove-before-next contract");
        // Clearing through views must notify progress just like direct operations.
        ChunkPos pos=new ChunkPos(0,0);job.known.add(pos);job.done.add(pos);job.queued.clear();job.flight.clear();job.failures.clear();job.nextStep.clear();
        job.columns.put(pos,256);check(!job.regionProcessed(0,0),"A terminal cursor still blocks rendering before final metadata");
        job.columns.entrySet().clear();check(job.coveredColumns()==job.done.size()*256L,"Entry-set clear retires the partial coverage total");
        check(job.regionProcessed(0,0)==slow(job,0,0),"Entry-set clear updates region readiness");
        var restarting=new ChunkMigrationEngine.Job(null);restarting.scanned=true;
        restarting.observeIndexed(pos,true);restarting.flight.add(pos);restarting.reset();
        restarting.observeFull(pos);restarting.observeCheckpoint(pos,complete,"winter",false,true,false);restarting.scanned=true;
        check(!restarting.regionProcessed(0,0),"A restart retains an old callback's blocker even when live observations supersede saved checkpoints");
        restarting.flight.clear();check(!restarting.regionProcessed(0,0),"Retiring a callback cannot silently complete its unfinished terrain");
        restarting.markComplete(pos);check(restarting.regionProcessed(0,0),"Current-generation completion opens the restarted region");
        System.out.println("Incremental region progress: "+checks+" assertions passed");
    }
    private static boolean slow(ChunkMigrationEngine.Job job,int x,int z){
        for(ChunkPos pos:job.known)if(pos.regionX()==x&&pos.regionZ()==z&&!job.unavailable.contains(pos)
                &&(!job.done.contains(pos)||job.flight.contains(pos)||job.failures.containsKey(pos)||job.nextStep.containsKey(pos)||job.columns.containsKey(pos)||job.queued.contains(pos)))return false;
        return true;
    }
    private static void toggle(Set<ChunkPos> values,ChunkPos pos){if(!values.remove(pos))values.add(pos);}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
