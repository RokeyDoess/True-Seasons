package dev.trueseasons.migration;

import java.util.concurrent.*;

/** Cancellation interrupts active reads and withdraws queued work before its target becomes obsolete. */
final class IndexScanTask extends FutureTask<Void> {
    private final ExecutorService owner;
    IndexScanTask(ExecutorService owner,Runnable read){super(read,null);this.owner=owner;}
    @Override public boolean cancel(boolean interrupt){
        boolean cancelled=super.cancel(interrupt);
        if(owner instanceof ThreadPoolExecutor pool)pool.remove(this);
        return cancelled;
    }
}
