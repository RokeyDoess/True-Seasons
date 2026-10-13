package dev.trueseasons.migration;

final class WinterCoveragePolicy {
    static int budget(int cursor,int shared,int step) { return Math.min(step,Math.max(0,shared-cursor)); }
    static int applicationBudget(int cursor,int shared,int step) { return cursor==256?1:budget(cursor,shared,step); }
    static int nextStage(int shared,int step,boolean remote,int minimumLocal) {
        return remote||minimumLocal<shared||minimumLocal<0?shared:Math.min(256,shared+step);
    }
}
