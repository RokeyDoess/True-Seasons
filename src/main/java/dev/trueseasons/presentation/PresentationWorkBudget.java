package dev.trueseasons.presentation;

/** Shared admission for extra seasonal packets; no lock is held while processing packets.
 * Units are inspected foliage states or changed snow blocks, in separate pools. */
class PresentationWorkBudget {
    private int chunkLimit,unitLimit;
    private int chunks,units,tick;
    private long epoch;
    private boolean begun;
    record Usage(int chunks,int units){}
    private int usedChunks,usedUnits,recentChunks,recentUnits;
    synchronized Usage usage(){return new Usage(recentChunks,recentUnits);}
    PresentationWorkBudget(int chunks,int units){limits(chunks,units);}
    /** Existing grants retain their accounting; the new allowance begins next tick. */
    synchronized void limits(int chunks,int units){
        if(chunks<1||units<1)throw new IllegalArgumentException("Positive presentation budget required");
        chunkLimit=chunks;unitLimit=units;
    }
    synchronized void beginTick(int next){
        if(begun&&tick==next)return;
        recentChunks=usedChunks;recentUnits=usedUnits;usedChunks=0;usedUnits=0;
        tick=next;begun=true;epoch++;chunks=chunkLimit;units=unitLimit;
    }
    synchronized Grant reserve(int maximumChunks,int maximumUnits){
        if(maximumChunks<1||maximumUnits<1)throw new IllegalArgumentException("Positive grant required");
        if(!begun||chunks==0||units==0)return null;
        int c=Math.min(chunks,maximumChunks),u=Math.min(units,maximumUnits);chunks-=c;units-=u;
        return new Grant(epoch,c,u);
    }
    final class Grant implements AutoCloseable {
        private final long frame;
        final int chunks,units;
        private boolean started,returned;
        private Grant(long frame,int chunks,int units){this.frame=frame;this.chunks=chunks;this.units=units;}
        boolean begin(){synchronized(PresentationWorkBudget.this){
            if(returned||started)return false;
            if(frame!=epoch){returned=true;return false;}
            started=true;return true;
        }}
        void complete(int usedChunks,int usedUnits){synchronized(PresentationWorkBudget.this){
            if(!started||returned||usedChunks<0||usedChunks>chunks||usedUnits<0||usedUnits>units)throw new IllegalStateException("Invalid presentation accounting");
            if(frame==epoch){PresentationWorkBudget.this.chunks+=chunks-usedChunks;PresentationWorkBudget.this.units+=units-usedUnits;}
            PresentationWorkBudget.this.usedChunks+=usedChunks;PresentationWorkBudget.this.usedUnits+=usedUnits;
            returned=true;
        }}
        @Override public void close(){synchronized(PresentationWorkBudget.this){
            if(!returned&&!started&&frame==epoch){PresentationWorkBudget.this.chunks+=chunks;PresentationWorkBudget.this.units+=units;}
            returned=true; // Failed work that began retains its reservation conservatively.
        }}
    }
}
