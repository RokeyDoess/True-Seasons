package dev.trueseasons.snow;

import java.io.IOException;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.IdentityHashMap;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Snow;

/** Main-thread, one-pass terrain reads. All edits remain on the Bukkit path. */
interface ColumnReader {
    int height(int column)throws IOException;
    Material material(int column,int y)throws IOException;
    String biome(int column,int y)throws IOException;
    String text(int column,int y)throws IOException;
    boolean singleSnow(int column,int y)throws IOException;
    boolean canSnow(int column,int y,BlockData snow)throws IOException;
    boolean snowLight(int column,int y)throws IOException;
    int stateId(int column,int y,java.util.function.ToIntFunction<BlockData> ids)throws IOException;
    default boolean nativeAccess(){return false;}

    static ColumnReader open(Chunk chunk)throws IOException{
        World world=chunk.getWorld();
        if(world.getClass().getName().startsWith("org.bukkit.craftbukkit.")){
            Handles handles=NativeHandles.INSTANCE;
            if(handles!=null&&handles.worldType.isInstance(world)){
                if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Terrain reads require the server thread");
                try{
                    Object level=handles.world.invokeExact((Object)world);
                    Object loaded=handles.loaded.invokeExact(level,chunk.getX(),chunk.getZ());
                    if(loaded==null)throw new IOException("Seasonal terrain chunk is no longer loaded");
                    return new Native(chunk,level,loaded,handles);
                }catch(Throwable failure){throw io(failure);}
            }
        }
        return fallback(chunk);
    }
    static ColumnReader fallback(Chunk chunk){
        return new ColumnReader(){
            final World world=chunk.getWorld();final int x=chunk.getX()*16,z=chunk.getZ()*16;
            public int height(int column){return world.getHighestBlockAt(x+(column&15),z+(column>>4),HeightMap.WORLD_SURFACE).getY();}
            public Material material(int column,int y){return chunk.getBlock(column&15,y,column>>4).getType();}
            public String biome(int column,int y){return chunk.getBlock(column&15,y,column>>4).getBiome().getKey().toString();}
            public String text(int column,int y){return chunk.getBlock(column&15,y,column>>4).getBlockData().getAsString();}
            public boolean singleSnow(int column,int y){return chunk.getBlock(column&15,y,column>>4).getBlockData() instanceof Snow snow&&snow.getLayers()==1;}
            public boolean canSnow(int column,int y,BlockData snow){return chunk.getBlock(column&15,y,column>>4).canPlace(snow);}
            public boolean snowLight(int column,int y){var block=chunk.getBlock(column&15,y,column>>4);return block.getLightFromSky()==15&&block.getLightFromBlocks()<10;}
            public int stateId(int column,int y,java.util.function.ToIntFunction<BlockData> ids){return ids.applyAsInt(chunk.getBlock(column&15,y,column>>4).getBlockData());}
        };
    }
    private static IOException io(Throwable failure){
        if(failure instanceof Error error)throw error;
        return failure instanceof IOException io?io:new IOException("Could not inspect loaded seasonal terrain",failure);
    }
    final class NativeHandles{
        static Throwable failure;
        static final Handles INSTANCE=create();
        private static Handles create(){
            try{return new Handles();}
            catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){failure=unavailable;return null;}
        }
    }
    final class Handles{
        final Class<?> worldType;
        final MethodHandle world,loaded,height,state,material,noise,key,identifier,position,setPosition,survive,data,asString,skyLight,blockLight,id;
        final IdentityHashMap<Object,String> textCache=new IdentityHashMap<>();
        final Object snow;
        Handles()throws ReflectiveOperationException{
            ClassLoader loader=ColumnReader.class.getClassLoader();
            worldType=Class.forName("org.bukkit.craftbukkit.CraftWorld",false,loader);
            Class<?> level=Class.forName("net.minecraft.server.level.ServerLevel",false,loader);
            Class<?> chunk=Class.forName("net.minecraft.world.level.chunk.LevelChunk",false,loader);
            Class<?> stateType=Class.forName("net.minecraft.world.level.block.state.BlockState",false,loader);
            Class<?> holder=Class.forName("net.minecraft.core.Holder",false,loader);
            Class<?> resource=Class.forName("net.minecraft.resources.ResourceKey",false,loader);
            Class<?> types=Class.forName("net.minecraft.world.level.levelgen.Heightmap$Types",false,loader);
            Class<?> blockPos=Class.forName("net.minecraft.core.BlockPos",false,loader);
            Class<?> mutable=Class.forName("net.minecraft.core.BlockPos$MutableBlockPos",false,loader);
            Class<?> reader=Class.forName("net.minecraft.world.level.LevelReader",false,loader);
            MethodHandles.Lookup lookup=MethodHandles.publicLookup();
            Class<?> accessor=Class.forName("net.minecraft.world.level.LevelAccessor",false,loader);
            Class<?> light=Class.forName("net.minecraft.world.level.LightLayer",false,loader);
            MethodHandle brightness=lookup.unreflect(accessor.getMethod("getBrightness",light,blockPos));
            skyLight=MethodHandles.insertArguments(brightness,1,light.getField("SKY").get(null)).asType(MethodType.methodType(int.class,Object.class,Object.class));
            blockLight=MethodHandles.insertArguments(brightness,1,light.getField("BLOCK").get(null)).asType(MethodType.methodType(int.class,Object.class,Object.class));
            Class<?> block=Class.forName("net.minecraft.world.level.block.Block",false,loader);
            id=method(lookup,block.getMethod("getId",stateType),int.class,Object.class);
            world=method(lookup,worldType.getMethod("getHandle"),Object.class,Object.class);
            loaded=method(lookup,level.getMethod("getChunkIfLoaded",int.class,int.class),Object.class,Object.class,int.class,int.class);
            MethodHandle heights=lookup.unreflect(chunk.getMethod("getHeight",types,int.class,int.class));
            height=MethodHandles.insertArguments(heights,1,types.getField("WORLD_SURFACE").get(null)).asType(MethodType.methodType(int.class,Object.class,int.class,int.class));
            state=method(lookup,chunk.getMethod("getBlockState",int.class,int.class,int.class),Object.class,Object.class,int.class,int.class,int.class);
            material=method(lookup,stateType.getMethod("getBukkitMaterial"),Material.class,Object.class);
            noise=method(lookup,chunk.getMethod("getNoiseBiome",int.class,int.class,int.class),Object.class,Object.class,int.class,int.class,int.class);
            key=method(lookup,holder.getMethod("unwrapKey"),Optional.class,Object.class);
            identifier=method(lookup,resource.getMethod("identifier"),Object.class,Object.class);
            position=lookup.unreflectConstructor(mutable.getConstructor()).asType(MethodType.methodType(Object.class));
            setPosition=method(lookup,mutable.getMethod("set",int.class,int.class,int.class),void.class,Object.class,int.class,int.class,int.class);
            survive=method(lookup,stateType.getMethod("canSurvive",reader,blockPos),boolean.class,Object.class,Object.class,Object.class);
            Class<?> blocks=Class.forName("net.minecraft.world.level.block.Blocks",false,loader);
            Object snowBlock=blocks.getField("SNOW").get(null);
            snow=snowBlock.getClass().getMethod("defaultBlockState").invoke(snowBlock);
            Object layer=Class.forName("net.minecraft.world.level.block.SnowLayerBlock",false,loader).getField("LAYERS").get(null);
            // Loaded vanilla block states are canonical. Extra snow properties would
            // invalidate identity-only layer recognition, so retain Bukkit in that case.
            var properties=(java.util.Collection<?>)stateType.getMethod("getProperties").invoke(snow);
            Class<?> property=Class.forName("net.minecraft.world.level.block.state.properties.Property",false,loader);
            if(properties.size()!=1||!properties.contains(layer)||!Integer.valueOf(1).equals(stateType.getMethod("getValue",property).invoke(snow,layer)))
                throw new IllegalStateException("Unsupported snow state properties");
            Class<?> craftData=Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData",false,loader);
            data=method(lookup,craftData.getMethod("createData",stateType),Object.class,Object.class);
            asString=method(lookup,BlockData.class.getMethod("getAsString"),String.class,Object.class);
        }
        private static MethodHandle method(MethodHandles.Lookup lookup,Method method,Class<?> result,Class<?>...arguments)throws IllegalAccessException{
            return lookup.unreflect(method).asType(MethodType.methodType(result,arguments));
        }
    }
    final class Native implements ColumnReader{
        private final Object level,chunk,pos;
        private final Handles handles;
        private final int x,z;
        private Object lastBiome;
        private String lastKey;
        Native(Chunk bukkit,Object level,Object chunk,Handles handles)throws Throwable{
            this.level=level;this.chunk=chunk;this.handles=handles;pos=handles.position.invokeExact();x=bukkit.getX()*16;z=bukkit.getZ()*16;
        }
        public boolean nativeAccess(){return true;}
        public boolean snowLight(int column,int y)throws IOException{
            try{
                handles.setPosition.invokeExact(pos,x+(column&15),y,z+(column>>4));
                return (int)handles.skyLight.invokeExact(level,pos)==15&&(int)handles.blockLight.invokeExact(level,pos)<10;
            }catch(Throwable failure){throw io(failure);}
        }
        public int stateId(int column,int y,java.util.function.ToIntFunction<BlockData> ignored)throws IOException{
            try{return (int)handles.id.invokeExact(state(column,y));}catch(Throwable failure){throw io(failure);}
        }
        public int height(int column)throws IOException{
            try{return (int)handles.height.invokeExact(chunk,column&15,column>>4);}catch(Throwable failure){throw io(failure);}
        }
        private Object state(int column,int y)throws Throwable{return handles.state.invokeExact(chunk,column&15,y,column>>4);}
        public Material material(int column,int y)throws IOException{
            try{return (Material)handles.material.invokeExact(state(column,y));}catch(Throwable failure){throw io(failure);}
        }
        public String biome(int column,int y)throws IOException{
            try{
                Object biome=handles.noise.invokeExact(chunk,(x+(column&15))>>2,y>>2,(z+(column>>4))>>2);
                if(biome==lastBiome)return lastKey;
                Optional<?> key=(Optional<?>)handles.key.invokeExact(biome);
                Object id=handles.identifier.invokeExact((Object)key.orElseThrow());
                String value=id.toString();lastBiome=biome;lastKey=value;return value;
            }catch(Throwable failure){throw io(failure);}
        }
        public boolean singleSnow(int column,int y)throws IOException{
            try{return state(column,y)==handles.snow;}
            catch(Throwable failure){throw io(failure);}
        }
        public String text(int column,int y)throws IOException{
            try{
                Object state=state(column,y);String cached=handles.textCache.get(state);if(cached!=null)return cached;
                Object data=handles.data.invokeExact(state);String text=(String)handles.asString.invokeExact(data);
                if(handles.textCache.size()>=1024)handles.textCache.clear();handles.textCache.put(state,text);return text;
            }catch(Throwable failure){throw io(failure);}
        }
        public boolean canSnow(int column,int y,BlockData ignored)throws IOException{
            try{handles.setPosition.invokeExact(pos,x+(column&15),y,z+(column>>4));return (boolean)handles.survive.invokeExact(handles.snow,level,pos);}
            catch(Throwable failure){throw io(failure);}
        }
    }
}
