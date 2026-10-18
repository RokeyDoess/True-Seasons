package dev.trueseasons.migration;

/** React to individual busy ticks before the historical TPS average catches up. */
public final class RecentTickHeadroom {
    private boolean waiting;
    private int healthy;
    public void observe(double tickMillis,double workBudgetMillis){
        double threshold=50-Math.min(10,workBudgetMillis);
        if(!Double.isFinite(tickMillis)||tickMillis<0||tickMillis>=threshold){waiting=true;healthy=0;}
        else if(waiting&&++healthy>=2){waiting=false;healthy=0;}
    }
    public boolean waiting(){return waiting;}
}
