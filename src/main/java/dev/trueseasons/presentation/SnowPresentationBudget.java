package dev.trueseasons.presentation;

import dev.trueseasons.config.SnowPresentationLimits;

/** Shared admission for extra snow packets; no lock is held while inspecting or writing packets. */
final class SnowPresentationBudget {
    private int chunkLimit,blockLimit;
    private int chunks,blocks,tick;
    private long epoch;
    private boolean begun;
    record Usage(int chunks,int blocks){}
    private int usedChunks,usedBlocks;
    private int recentChunks,recentBlocks;
    synchronized Usage usage(){return new Usage(recentChunks,recentBlocks);}
    SnowPresentationBudget(){this(SnowPresentationLimits.DEFAULT.chunks(),SnowPresentationLimits.DEFAULT.blocks());}
    SnowPresentationBudget(int chunks,int blocks){
        if(chunks<1||blocks<1)throw new IllegalArgumentException("Positive presentation budget required");
        chunkLimit=chunks;blockLimit=blocks;
    }
    /** Existing grants keep their accounting; the new allowance begins next tick. */
    synchronized void limits(SnowPresentationLimits limits){chunkLimit=limits.chunks();blockLimit=limits.blocks();}
    synchronized void beginTick(int next){
        if(begun&&tick==next)return;
        recentChunks=usedChunks;recentBlocks=usedBlocks;usedChunks=0;usedBlocks=0;
        tick=next;begun=true;epoch++;chunks=chunkLimit;blocks=blockLimit;
    }
    synchronized Grant reserve(int maximumChunks,int maximumBlocks){
        if(maximumChunks<1||maximumBlocks<1)throw new IllegalArgumentException("Positive grant required");
        if(!begun||chunks==0||blocks==0)return null;
        int c=Math.min(chunks,maximumChunks),b=Math.min(blocks,maximumBlocks);chunks-=c;blocks-=b;
        return new Grant(epoch,c,b);
    }
    final class Grant implements AutoCloseable {
        private final long frame;
        final int chunks,blocks;
        private boolean started,returned;
        private Grant(long frame,int chunks,int blocks){this.frame=frame;this.chunks=chunks;this.blocks=blocks;}
        boolean begin(){synchronized(SnowPresentationBudget.this){
            if(returned||started)return false;
            if(frame!=epoch){returned=true;return false;}
            started=true;return true;
        }}
        void complete(int usedChunks,int usedBlocks){synchronized(SnowPresentationBudget.this){
            if(!started||returned||usedChunks<0||usedChunks>chunks||usedBlocks<0||usedBlocks>blocks)throw new IllegalStateException("Invalid presentation accounting");
            if(frame==epoch){SnowPresentationBudget.this.chunks+=chunks-usedChunks;SnowPresentationBudget.this.blocks+=blocks-usedBlocks;}
            SnowPresentationBudget.this.usedChunks+=usedChunks;SnowPresentationBudget.this.usedBlocks+=usedBlocks;
            returned=true;
        }}
        @Override public void close(){synchronized(SnowPresentationBudget.this){
            if(!returned&&!started&&frame==epoch){SnowPresentationBudget.this.chunks+=chunks;SnowPresentationBudget.this.blocks+=blocks;}
            returned=true; // Failed work that began retains its reservation conservatively.
        }}
    }
}
