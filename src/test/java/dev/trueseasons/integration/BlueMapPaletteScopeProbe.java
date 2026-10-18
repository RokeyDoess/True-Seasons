package dev.trueseasons.integration;

import java.lang.reflect.Field;
import java.util.*;

/** Reads native integration state only inside the guarded colour verification clone. */
public final class BlueMapPaletteScopeProbe {
    private BlueMapPaletteScopeProbe(){}
    public static Map<String,String> snapshot(IntegrationManager manager)throws Exception{
        Field adapter=IntegrationManager.class.getDeclaredField("blueMap");adapter.setAccessible(true);
        Object integration=adapter.get(manager);
        Field palettes=BlueMapIntegration.class.getDeclaredField("visualQueue");palettes.setAccessible(true);
        Object queue=palettes.get(integration);Class<?> type=queue.getClass();
        var pending=type.getDeclaredMethod("pending");pending.setAccessible(true);
        if((int)pending.invoke(queue)!=0)throw new AssertionError("Expected a completed palette queue");
        var recorded=type.getDeclaredMethod("recordedMaps");recorded.setAccessible(true);
        var target=type.getDeclaredMethod("target",String.class);target.setAccessible(true);
        var token=type.getDeclaredMethod("receiptToken",String.class);token.setAccessible(true);
        Map<String,String> result=new TreeMap<>();
        @SuppressWarnings("unchecked") var maps=(Set<String>)recorded.invoke(queue);
        for(String map:maps)result.put(map,target.invoke(queue,map)+"/generation="+token.invoke(queue,map));
        if(result.isEmpty())throw new AssertionError("Expected native saved map targets");
        return result;
    }
}
