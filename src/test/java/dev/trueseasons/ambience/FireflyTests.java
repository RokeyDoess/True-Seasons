package dev.trueseasons.ambience;

import dev.trueseasons.config.FireflySettings;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import static dev.trueseasons.ambience.FireflyHabitat.Cell.*;

public final class FireflyTests {
    private static int checks;
    private static void check(boolean value,String text){checks++;if(!value)throw new AssertionError(text);}
    private record Position(int x,int y,int z){}
    private static final class Sample implements FireflyHabitat.Sample {
        final Map<Position,FireflyHabitat.Cell> blocks=new HashMap<>();int sky=15,reads;
        public FireflyHabitat.Cell cell(int x,int y,int z){reads++;return blocks.getOrDefault(new Position(x,y,z),AIR);}
        public int skyLight(int x,int y,int z){return sky;}
        void set(int x,int y,int z,FireflyHabitat.Cell cell){blocks.put(new Position(x,y,z),cell);}
    }
    public static void main(String[] args){
        check(!FireflyHabitat.night(12499)&&FireflyHabitat.night(12500)&&FireflyHabitat.night(22999)&&!FireflyHabitat.night(23000),"Fireflies stay within dusk and night");
        check(FireflyHabitat.night(24000+12500),"World time wraps normally");
        for(String biome:List.of("minecraft:ocean","minecraft:deep_ocean","minecraft:lukewarm_ocean","custom:bright_ocean"))check(FireflyHabitat.ocean(biome),"Ocean habitat excluded: "+biome);
        check(!FireflyHabitat.ocean("minecraft:river"),"River edges remain eligible");
        Sample edge=new Sample();edge.set(0,64,0,SOLID);edge.set(2,63,0,WATER);
        check(FireflyHabitat.suitable(edge,0,64,0),"Loaded land near water is habitat");
        Sample diagonal=new Sample();diagonal.set(0,64,0,SOLID);diagonal.set(2,63,2,WATER);
        check(FireflyHabitat.suitable(diagonal,0,64,0),"Diagonal shorelines qualify without terrain loads");
        Sample canopy=new Sample();canopy.set(0,64,0,SOLID);canopy.set(0,68,0,LEAVES);canopy.sky=10;
        check(FireflyHabitat.suitable(canopy,0,64,0),"Leaves above clear particle space provide sheltered habitat");
        canopy.blocks.clear();canopy.set(0,64,0,SOLID);canopy.set(0,65,0,VEGETATION);
        check(FireflyHabitat.suitable(canopy,0,64,0),"Sheltered vegetation at the particle site counts too");
        Sample open=new Sample();open.set(0,64,0,SOLID);open.set(1,65,0,VEGETATION);
        check(!FireflyHabitat.suitable(open,0,64,0),"Open dry vegetation is not enough");open.sky=11;
        check(FireflyHabitat.suitable(open,0,64,0),"Sheltered vegetation is habitat");open.sky=0;
        check(!FireflyHabitat.suitable(open,0,64,0),"Dark caves are excluded");
        edge.set(0,64,0,WATER);check(!FireflyHabitat.suitable(edge,0,64,0),"Fireflies do not spawn over a water surface");
        edge.set(0,64,0,SOLID);edge.set(0,66,0,SOLID);check(!FireflyHabitat.suitable(edge,0,64,0),"Particle space must be clear");
        Sample unloaded=new Sample();unloaded.set(0,64,0,SOLID);unloaded.sky=10;
        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)if(x!=0||z!=0)unloaded.set(x,64,z,UNAVAILABLE);
        check(!FireflyHabitat.suitable(unloaded,0,64,0)&&unloaded.reads<=96,"Unavailable neighbors never qualify and sample work stays fixed");
        List<Integer> viewers=new ArrayList<>();for(int i=0;i<100;i++)viewers.add(i);Set<Integer> visited=new HashSet<>();int cursor=0;
        for(int cycle=0;cycle<20;cycle++){
            var usage=FireflyBudget.visit(viewers,cursor,16,8,viewer->{visited.add(viewer);return true;});cursor=usage.nextViewer();
            check(usage.attempts()==8&&usage.particles()==8,"Successful spawns honor the shared particle cap");
        }
        check(visited.size()==100,"All viewers receive fair turns across cycles");
        int[] calls={0};var failed=FireflyBudget.visit(viewers,0,16,8,viewer->{calls[0]++;return false;});
        check(calls[0]==16&&failed.attempts()==16&&failed.particles()==0,"Failed habitat samples still consume the work budget");
        check(FireflyBudget.visit(List.of(),0,16,8,viewer->true).attempts()==0,"Empty server performs no samples");
        var defaults=FireflySettings.read(new YamlConfiguration());check(defaults.enabled()&&!defaults.sounds(),"Fireflies default on with sounds off");
        var bad=new YamlConfiguration();bad.set("fireflies.attempts-per-tick",1000);
        try{FireflySettings.read(bad);throw new AssertionError("Unbounded ambient work accepted");}catch(IllegalArgumentException expected){checks++;}
        for(String key:List.of("interval-ticks","attempts-per-tick","max-particles-per-tick","radius-blocks"))for(Object value:List.of("16",1.5,true,Long.MAX_VALUE,4294967312L)){
            var invalid=new YamlConfiguration();invalid.set("fireflies."+key,value);
            try{FireflySettings.read(invalid);throw new AssertionError("Invalid or overflowing allowance accepted: "+key+"="+value);}
            catch(IllegalArgumentException expected){check(expected.getMessage().contains(key),"Invalid allowance identifies its setting");}
        }
        System.out.println("Firefly ambience: "+checks+" assertions passed");
    }
}
