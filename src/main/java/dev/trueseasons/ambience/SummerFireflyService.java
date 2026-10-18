package dev.trueseasons.ambience;

import dev.trueseasons.TrueSeasonsPlugin;
import dev.trueseasons.api.Season;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Cosmetic, player-local ambience. No tasks, entities, block edits, chunk loads or map updates. */
public final class SummerFireflyService {
    private final TrueSeasonsPlugin plugin;
    private int nextViewer;
    private long nextRun,nextSound;
    private int lastAttempts,lastParticles;
    public SummerFireflyService(TrueSeasonsPlugin plugin){this.plugin=plugin;}
    public int lastAttempts(){return lastAttempts;}
    public int lastParticles(){return lastParticles;}
    public void tick(long tick){
        if(tick<nextRun)return;
        var settings=plugin.settings();var limits=settings.fireflies;
        nextRun=tick+limits.intervalTicks();lastAttempts=lastParticles=0;
        if(!limits.enabled()||!settings.enabled||plugin.currentSeason()!=Season.SUMMER||plugin.state().cleanup||plugin.state().clean||plugin.engine().currentTps()<settings.minTps)return;
        List<? extends Player> players=new ArrayList<>(Bukkit.getOnlinePlayers());if(players.isEmpty())return;
        var usage=FireflyBudget.visit(players,nextViewer,limits.attempts(),limits.particles(),player->emit(player,tick));
        nextViewer=usage.nextViewer();lastAttempts=usage.attempts();lastParticles=usage.particles();
    }
    private boolean emit(Player player,long tick){
            var settings=plugin.settings();var limits=settings.fireflies;var random=ThreadLocalRandom.current();
            World world=player.getWorld();if(!plugin.eligible(world)||!FireflyHabitat.night(world.getTime())||world.hasStorm())return false;
            var position=player.getLocation();
            // Favor visible nearby habitat, while still sampling the whole radius.
            int radius=random.nextInt(4)==0?limits.radius():Math.max(4,(limits.radius()+1)/2);
            int x=position.getBlockX()+random.nextInt(-radius,radius+1),z=position.getBlockZ()+random.nextInt(-radius,radius+1);
            if(!world.isChunkLoaded(x>>4,z>>4))return false;
            int y=world.getHighestBlockYAt(x,z,HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if(Math.abs(y-position.getY())>12||y<world.getMinHeight()||y+2>=world.getMaxHeight())return false;
            String biome=world.getBiome(x,y+1,z).getKey().asString();
            if(FireflyHabitat.ocean(biome)||!settings.climate.applies(Season.SUMMER,biome))return false;
            if(!FireflyHabitat.suitable(new LoadedSample(world),x,y,z))return false;
            var at=new Location(world,x+.5,y+1.25,z+.5);
            player.spawnParticle(Particle.FIREFLY,at,1,.4,.25,.4,0);
            if(limits.sounds()&&tick>=nextSound){player.playSound(at,Sound.BLOCK_FIREFLY_BUSH_IDLE,SoundCategory.AMBIENT,.2f,1);nextSound=tick+600;}
            return true;
    }
    private static final class LoadedSample implements FireflyHabitat.Sample {
        private final World world;
        private final Map<Long,Chunk> chunks=new HashMap<>(4);
        private int lastX,lastZ;
        private Chunk lastChunk;
        private boolean hasLast;
        LoadedSample(World world){this.world=world;}
        private Block block(int x,int y,int z){
            if(y<world.getMinHeight()||y>=world.getMaxHeight())return null;
            int cx=x>>4,cz=z>>4;
            if(!hasLast||cx!=lastX||cz!=lastZ){
                long key=((long)cx<<32)|(cz&0xffffffffL);
                if(!chunks.containsKey(key))chunks.put(key,world.isChunkLoaded(cx,cz)?world.getChunkAt(cx,cz):null);
                lastX=cx;lastZ=cz;lastChunk=chunks.get(key);hasLast=true;
            }
            // A sample is synchronous on the server thread; no terrain can unload mid-sample.
            return lastChunk==null?null:lastChunk.getBlock(x&15,y,z&15);
        }
        public FireflyHabitat.Cell cell(int x,int y,int z){
            Block block=block(x,y,z);if(block==null)return FireflyHabitat.Cell.UNAVAILABLE;
            Material type=block.getType();if(type.isAir())return FireflyHabitat.Cell.AIR;
            if(type==Material.WATER)return FireflyHabitat.Cell.WATER;
            if(type.name().endsWith("_LEAVES"))return FireflyHabitat.Cell.LEAVES;
            if(type==Material.SHORT_GRASS||type==Material.TALL_GRASS||type==Material.FERN||type==Material.LARGE_FERN||type==Material.LEAF_LITTER||type==Material.FIREFLY_BUSH||Tag.FLOWERS.isTagged(type))return FireflyHabitat.Cell.VEGETATION;
            return type.isSolid()?FireflyHabitat.Cell.SOLID:FireflyHabitat.Cell.OTHER;
        }
        public int skyLight(int x,int y,int z){Block block=block(x,y,z);return block==null?0:block.getLightFromSky();}
    }
}
