package dev.trueseasons.presentation;

import dev.trueseasons.visual.AutumnTreeColors;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

/** Immutable native block-state lookup; state variants preserve leaf properties. */
final class NativeLeafRegistry {
    private final it.unimi.dsi.fastutil.objects.Reference2IntMap<?> nativeIds;
    final Object[] states;
    final AutumnTreeColors.Kind[] kinds;
    final boolean[] persistent;
    private final int[][] variants;
    private final AutumnTreeColors.Appearance[] appearances;
    NativeLeafRegistry() throws ReflectiveOperationException {
        Class<?> block=Class.forName("net.minecraft.world.level.block.Block");
        Object registry=block.getField("BLOCK_STATE_REGISTRY").get(null);
        Method size=registry.getClass().getMethod("size");
        Field ids=Class.forName("net.minecraft.core.IdMapper").getDeclaredField("tToId");ids.setAccessible(true);
        nativeIds=(it.unimi.dsi.fastutil.objects.Reference2IntMap<?>)ids.get(registry);
        states=new Object[(int)size.invoke(registry)];kinds=new AutumnTreeColors.Kind[states.length];variants=new int[states.length][];persistent=new boolean[states.length];appearances=new AutumnTreeColors.Appearance[states.length];
        for(var entry:it.unimi.dsi.fastutil.objects.Reference2IntMaps.fastIterable(nativeIds))states[entry.getIntValue()]=entry.getKey();
        Arrays.fill(kinds,AutumnTreeColors.Kind.OTHER);
        Class<?> holders=Class.forName("net.minecraft.world.level.block.Blocks");
        String[] names={"YELLOW_POPLAR_LEAVES","ORANGE_POPLAR_LEAVES","RED_POPLAR_LEAVES"};Object[] defaults=new Object[names.length];
        for(int i=0;i<names.length;i++){Object leaf=holders.getField(names[i]).get(null);defaults[i]=leaf.getClass().getMethod("defaultBlockState").invoke(leaf);}
        Class<?> property=Class.forName("net.minecraft.world.level.block.state.properties.Property");
        Method set=defaults[0].getClass().getMethod("setValue",property,Comparable.class);
        Class<?> stateType=Class.forName("net.minecraft.world.level.block.state.BlockState");
        Method getValues=stateType.getMethod("getValues");
        Method description=block.getMethod("getDescriptionId");
        Method definition=block.getMethod("getStateDefinition"),possible=Class.forName("net.minecraft.world.level.block.state.StateDefinition").getMethod("getPossibleStates");
        Iterable<?> blocks=(Iterable<?>)Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("BLOCK").get(null);
        // Classify each block once; unrelated state variants need no reflection.
        for(Object owner:blocks){
            String name=(String)description.invoke(owner);
            AutumnTreeColors.Kind kind=AutumnTreeColors.kind(name.startsWith("block.minecraft.")?"minecraft:"+name.substring(16):name);
            if(!kind.leaves()&&!kind.log())continue;
            for(Object state:(Iterable<?>)possible.invoke(definition.invoke(owner))){
                int i=id(state);
                kinds[i]=kind;
                if(!kind.leaves())continue;
                List<?> values;try(Stream<?> stream=(Stream<?>)getValues.invoke(state)){values=stream.toList();}
                for(Object value:values){Object p=PaperBiomePackets.call(value,"property");if(PaperBiomePackets.call(p,"getName").equals("persistent"))persistent[i]=Boolean.TRUE.equals(PaperBiomePackets.call(value,"value"));}
                int[] colors=new int[names.length];
                for(int c=0;c<names.length;c++){
                    Object painted=defaults[c];
                    for(Object value:values){Object p=PaperBiomePackets.call(value,"property"), v=PaperBiomePackets.call(value,"value");painted=set.invoke(painted,p,v);}
                    colors[c]=id(painted);
                }
                variants[i]=colors;
                appearances[colors[0]]=AutumnTreeColors.Appearance.YELLOW;appearances[colors[1]]=AutumnTreeColors.Appearance.ORANGE;appearances[colors[2]]=AutumnTreeColors.Appearance.RED;
            }
        }
    }
    /** Read the native identity table without maintaining a second boxed lookup. */
    int id(Object state){
        int id=nativeIds.getInt(state);
        if(id<0||id>=states.length||states[id]!=state)throw new IllegalArgumentException("Unknown block update state");
        return id;
    }
    AutumnTreeColors.Kind kind(int id){if(id<0||id>=kinds.length)throw new IllegalArgumentException("Unknown native block state");return kinds[id];}
    boolean relevant(int id){var kind=kind(id);return kind.leaves()||kind.log();}
    AutumnTreeColors.Appearance appearance(int id){return id>=0&&id<appearances.length&&appearances[id]!=null?appearances[id]:AutumnTreeColors.Appearance.ORIGINAL;}
    int painted(int original,AutumnTreeColors.Appearance appearance){
        if(appearance==AutumnTreeColors.Appearance.ORIGINAL||variants[original]==null)return original;
        return variants[original][switch(appearance){case YELLOW->0;case ORANGE->1;case RED->2;default->throw new IllegalArgumentException();}];
    }
}
