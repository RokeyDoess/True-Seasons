package dev.trueseasons.ambience;

/** Fixed-size read-only sample; unavailable terrain never counts as habitat. */
public final class FireflyHabitat {
    private FireflyHabitat(){}
    public enum Cell { UNAVAILABLE, AIR, VEGETATION, LEAVES, WATER, SOLID, OTHER }
    public interface Sample {
        Cell cell(int x,int y,int z);
        int skyLight(int x,int y,int z);
    }
    public static boolean night(long time){long value=Math.floorMod(time,24000);return value>=12500&&value<23000;}
    public static boolean ocean(String biome){return biome.toLowerCase(java.util.Locale.ROOT).contains("ocean");}
    public static boolean suitable(Sample sample,int x,int groundY,int z){
        Cell support=sample.cell(x,groundY,z),space=sample.cell(x,groundY+1,z);
        if(support!=Cell.SOLID||space!=Cell.AIR&&space!=Cell.VEGETATION||sample.cell(x,groundY+2,z)!=Cell.AIR)return false;
        int sky=sample.skyLight(x,groundY+1,z);if(sky<=0)return false;
        if(sky<15){
            if(space==Cell.VEGETATION)return true;
            for(int height=3;height<=6;height++)if(sample.cell(x,groundY+height,z)==Cell.LEAVES)return true;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0){
                Cell nearby=sample.cell(x+dx,groundY+1,z+dz);
                if(nearby==Cell.VEGETATION||nearby==Cell.LEAVES||sample.cell(x+dx,groundY+3,z+dz)==Cell.LEAVES)return true;
            }
        }
        // The complete small disc includes diagonal banks. Four rays miss many
        // natural shorelines even when water is only a couple of blocks away.
        for(int distance=1;distance<=3;distance++)for(int dx=-distance;dx<=distance;dx++)for(int dz=-distance;dz<=distance;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))!=distance||dx*dx+dz*dz>9)continue;
            if(sample.cell(x+dx,groundY,z+dz)==Cell.WATER||sample.cell(x+dx,groundY-1,z+dz)==Cell.WATER)return true;
        }
        return false;
    }
}
