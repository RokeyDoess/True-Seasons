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
        if(sky<15)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0){
            Cell nearby=sample.cell(x+dx,groundY+1,z+dz);
            if(nearby==Cell.VEGETATION||nearby==Cell.LEAVES)return true;
        }
        for(int distance=1;distance<=3;distance++)for(int axis=0;axis<4;axis++){
            int nx=x+(axis==0?distance:axis==1?-distance:0),nz=z+(axis==2?distance:axis==3?-distance:0);
            if(sample.cell(nx,groundY,nz)==Cell.WATER||sample.cell(nx,groundY-1,nz)==Cell.WATER)return true;
        }
        return false;
    }
}
