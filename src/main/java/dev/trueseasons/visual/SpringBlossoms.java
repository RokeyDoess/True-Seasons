package dev.trueseasons.visual;

/** Stable tree selection and scattered, presentation-only flowers within selected crowns. */
public record SpringBlossoms(int treesPercent,int leavesPercent) {
    public static final SpringBlossoms DEFAULT=new SpringBlossoms(20,25);
    public SpringBlossoms {
        if(treesPercent<0||treesPercent>100||leavesPercent<0||leavesPercent>100)
            throw new IllegalArgumentException("Spring blossom percentages must be whole numbers in 0..100");
    }
    boolean tree(long hash){return Long.remainderUnsigned(hash,100)<treesPercent;}
    boolean leaf(long seed,int x,int y,int z){
        return Long.remainderUnsigned(mix(seed^mix(x)^Long.rotateLeft(mix(y),21)^Long.rotateLeft(mix(z),43)^0xe7037ed1a0b428dbL),100)<leavesPercent;
    }
    private static long mix(long value){value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;return value^(value>>>31);}
    public String signature(){return "scattered1/"+treesPercent+"/"+leavesPercent;}
}
