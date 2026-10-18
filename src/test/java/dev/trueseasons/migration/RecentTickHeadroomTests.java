package dev.trueseasons.migration;

public final class RecentTickHeadroomTests {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args){
        var gate=new RecentTickHeadroom();check(!gate.waiting(),"Healthy startup can discover and migrate normally");
        for(int i=0;i<200;i++)gate.observe(8,3);
        check(!gate.waiting(),"Ordinary tick work does not throttle migration");
        gate.observe(320,3);check(gate.waiting(),"A single startup stall stops new background loads immediately");
        gate.observe(5,3);check(gate.waiting(),"One short tick does not bounce back into a burst");
        gate.observe(49,3);gate.observe(5,3);check(gate.waiting(),"Another busy tick restarts the healthy sequence");
        gate.observe(6,3);check(!gate.waiting(),"Two healthy ticks resume automatically without a saved pause");
        gate.observe(47,3);check(gate.waiting(),"The configured work allowance retains headroom below fifty milliseconds");
        gate.observe(44,3);gate.observe(44,3);check(!gate.waiting(),"Useful work resumes on a loaded but healthy server");
        gate.observe(Double.NaN,3);check(gate.waiting(),"An invalid duration cannot admit additional work");
        gate.observe(10,3);gate.observe(10,3);check(!gate.waiting(),"A bad sample cannot permanently wedge migration");
        gate.observe(46,5);check(gate.waiting(),"Larger work budgets reserve more tick time");
        gate.observe(5,50);gate.observe(5,50);check(!gate.waiting(),"A valid maximum work budget cannot create an impossible recovery threshold");
        System.out.println("Recent tick headroom: "+checks+" assertions passed");
    }
}
