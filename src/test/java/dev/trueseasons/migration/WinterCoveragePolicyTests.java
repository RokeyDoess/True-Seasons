package dev.trueseasons.migration;

import java.util.Arrays;

public final class WinterCoveragePolicyTests {
    private static int assertions;
    private static void check(boolean value,String label) { assertions++; if(!value)throw new AssertionError(label); }
    public static void main(String[] args) {
        check(WinterCoveragePolicy.budget(0,0,16)==0,"No local placement before shared coverage advances");
        check(WinterCoveragePolicy.budget(120,128,16)==8,"Last batch stops exactly at the shared cap");
        check(WinterCoveragePolicy.budget(128,128,16)==0,"Caught-up chunks cannot run ahead");
        check(WinterCoveragePolicy.budget(200,128,16)==0,"Existing older coverage cannot cause new placement beyond cap");
        check(WinterCoveragePolicy.budget(250,256,16)==6,"Final batch reaches full coverage without overflow");
        check(WinterCoveragePolicy.applicationBudget(256,256,16)==1,"A completed pending cursor can finalize metadata without placing another column");
        check(WinterCoveragePolicy.applicationBudget(256,128,16)==1,"Metadata finalization does not wait for a lower placement cap");
        check(WinterCoveragePolicy.nextStage(128,16,true,128)==128,"Remote work controls the shared coverage while present");
        check(WinterCoveragePolicy.nextStage(128,16,false,127)==128,"A lagging local chunk holds the shared stage");
        check(WinterCoveragePolicy.nextStage(128,16,false,-1)==128,"An empty local job does not advance coverage");
        int shared=0; int[] cursors={0,0,0,0}; int turns=0;
        while(Arrays.stream(cursors).anyMatch(n -> n<256)&&turns++<200) {
            shared=WinterCoveragePolicy.nextStage(shared,16,false,Arrays.stream(cursors).min().orElse(-1));
            int chunk=turns%4;
            cursors[chunk]+=WinterCoveragePolicy.budget(cursors[chunk],shared,16);
            for(int cursor:cursors)if(cursor>shared)throw new AssertionError("Local coverage ran ahead of shared coverage");
        }
        check(shared==256&&Arrays.stream(cursors).allMatch(n -> n==256),"All-local worlds finish without a coverage deadlock");
        for(int cap=0;cap<=256;cap++)for(int cursor=0;cursor<=256;cursor++) {
            int budget=WinterCoveragePolicy.budget(cursor,cap,16);
            if(budget<0||budget>16||budget>0&&cursor+budget>cap)throw new AssertionError("Cap invariant violated");
        }
        check(true,"Every valid cursor and cap obeys the local placement limit");
        System.out.println("Winter coverage policy tests passed: "+assertions+" assertions");
    }
}
