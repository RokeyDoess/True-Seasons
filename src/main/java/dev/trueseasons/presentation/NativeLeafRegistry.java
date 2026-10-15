package dev.trueseasons.presentation;

import dev.trueseasons.visual.AutumnTreeColors;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

/** Immutable native block-state lookup; state variants preserve leaf properties. */
final class NativeLeafRegistry {
    final Object[] states;
    final AutumnTreeColors.Kind[] kinds;
    final boolean[] persistent;
    private final int[][] variants;
    NativeLeafRegistry() throws ReflectiveOperationException {
        Class<?> block=Class.forName("net.minecraft.world.level.block.Block");
        Object registry=block.getField("BLOCK_STATE_REGISTRY").get(null);
        Method size=registry.getClass().getMethod("size"), byId=registry.getClass().getMethod("byId",int.class), getId=registry.getClass().getMethod("getId",Object.class);
        states=new Object[(int)size.invoke(registry)];kinds=new AutumnTreeColors.Kind[states.length];variants=new int[states.length][];persistent=new boolean[states.length];
        Class<?> holders=Class.forName("net.minecraft.world.level.block.Blocks");
        Object[] defaults=new Object[3];String[] names={"YELLOW_POPLAR_LEAVES","ORANGE_POPLAR_LEAVES","RED_POPLAR_LEAVES"};
        for(int i=0;i<3;i++){Object leaf=holders.getField(names[i]).get(null);defaults[i]=leaf.getClass().getMethod("defaultBlockState").invoke(leaf);}
        Class<?> property=Class.forName("net.minecraft.world.level.block.state.properties.Property");
        Method set=defaults[0].getClass().getMethod("setValue",property,Comparable.class);
        IdentityHashMap<Object,AutumnTreeColors.Kind> blocks=new IdentityHashMap<>();
        for(int i=0;i<states.length;i++){
            Object state=states[i]=byId.invoke(registry,i), owner=state.getClass().getMethod("getBlock").invoke(state);
            AutumnTreeColors.Kind kind=blocks.get(owner);
            if(kind==null){String description=(String)owner.getClass().getMethod("getDescriptionId").invoke(owner);
                kind=AutumnTreeColors.kind(description.startsWith("block.minecraft.")?"minecraft:"+description.substring(16):description);blocks.put(owner,kind);}
            kinds[i]=kind;
            if(!kind.leaves())continue;
            List<?> values;try(Stream<?> stream=(Stream<?>)state.getClass().getMethod("getValues").invoke(state)){values=stream.toList();}
            for(Object value:values){Object p=PaperBiomePackets.call(value,"property");if(PaperBiomePackets.call(p,"getName").equals("persistent"))persistent[i]=Boolean.TRUE.equals(PaperBiomePackets.call(value,"value"));}
            int[] colors=new int[3];
            for(int c=0;c<3;c++){
                Object painted=defaults[c];
                for(Object value:values){Object p=PaperBiomePackets.call(value,"property"), v=PaperBiomePackets.call(value,"value");painted=set.invoke(painted,p,v);}
                colors[c]=(int)getId.invoke(registry,painted);
            }
            variants[i]=colors;
        }
    }
    AutumnTreeColors.Kind kind(int id){if(id<0||id>=kinds.length)throw new IllegalArgumentException("Unknown native block state");return kinds[id];}
    boolean relevant(int id){var kind=kind(id);return kind.leaves()||kind.log();}
    int painted(int original,AutumnTreeColors.Appearance appearance){
        if(appearance==AutumnTreeColors.Appearance.ORIGINAL||variants[original]==null)return original;
        return variants[original][switch(appearance){case YELLOW->0;case ORANGE->1;case RED->2;default->throw new IllegalArgumentException();}];
    }
}
