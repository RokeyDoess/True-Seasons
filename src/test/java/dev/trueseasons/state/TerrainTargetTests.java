package dev.trueseasons.state;

import dev.trueseasons.api.Season;
import java.nio.file.*;
import java.util.Comparator;

public final class TerrainTargetTests {
    private static int checks;
    private static void check(boolean condition,String text){checks++;if(!condition)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("trueseasons-terrain-target-");
        try{
            StateStore state=new StateStore(root);
            state.terrainTarget=TerrainTarget.resolve(null,"spring",false,"rules1");
            String dry=state.terrainTarget.version();
            for(String season:new String[]{"summer","autumn","spring-next-year"})
                state.terrainTarget=TerrainTarget.resolve(state.terrainTarget,season,false,"rules1");
            check(state.terrainTarget.version().equals(dry),"Dry seasons reuse verified terrain without retargeting a world pass");
            state.terrainTarget=TerrainTarget.resolve(state.terrainTarget,"winter",true,"rules1");
            String winter=state.terrainTarget.version();check(!winter.equals(dry),"Winter requires a new physical checkpoint");
            state.winterVersion=winter;state.winterColumns=143;state.lastSeason=Season.WINTER;
            state.terrainTarget=TerrainTarget.resolve(state.terrainTarget,"spring",false,"rules1");
            state.seasonTransition(Season.WINTER,Season.SPRING,state.terrainTarget.version());
            state.meltColumns=91;state.paused=true;state.currentVersion="spring";state.save();
            StateStore resumed=new StateStore(root);
            check(resumed.terrainTarget.equals(state.terrainTarget)&&resumed.meltColumns==91&&resumed.paused,"Restart preserves independent physical target, melt coverage and pause");
            resumed.terrainTarget=TerrainTarget.resolve(resumed.terrainTarget,"summer",false,"rules1");
            resumed.seasonTransition(Season.SPRING,Season.SUMMER,resumed.terrainTarget.version());
            check(resumed.meltVersion.equals(state.meltVersion)&&resumed.meltStartColumns==143&&resumed.meltColumns==91&&resumed.meltOrderVersion.equals(winter),"Changing dry seasons preserves an unfinished melt and its snow order");
            check(!resumed.finishMelt(resumed.terrainTarget.version(),true),"Visible melt coverage must finish before retiring its intent");
            resumed.meltColumns=0;
            check(!resumed.finishMelt(resumed.terrainTarget.version(),false),"Zero visible coverage cannot retire unfinished physical restoration");
            check(resumed.finishMelt(resumed.terrainTarget.version(),true)&&resumed.meltVersion.isEmpty()&&resumed.meltOrderVersion.isEmpty(),"Completed restoration retires melt presentation without another terrain pass");
            resumed.seasonTransition(Season.SUMMER,Season.AUTUMN,resumed.terrainTarget.version());
            check(resumed.meltVersion.isEmpty(),"A later dry season cannot reactivate a completed melt");
            String previous=resumed.terrainTarget.version();
            resumed.terrainTarget=TerrainTarget.resolve(resumed.terrainTarget,"summer",false,"rules2");
            check(!previous.equals(resumed.terrainTarget.version()),"Physical rules invalidate terrain even without a logical season change");
            resumed.terrainTarget=TerrainTarget.resolve(resumed.terrainTarget,"summer",false,"rules1");
            check(!previous.equals(resumed.terrainTarget.version()),"Reverting offline rules cannot reuse an older incompatible checkpoint");
            var legacy=new TerrainTarget("published-winter",true,"rules1");
            check(TerrainTarget.resolve(legacy,"current-winter",true,"rules1")==legacy,"Upgrades adopt published checkpoints without replaying completed winter work");
            resumed.cleanup=true;resumed.seasonTransition(Season.SUMMER,Season.WINTER,winter);
            check(resumed.meltVersion.isEmpty()&&resumed.meltColumns==0,"Cleanup still cancels presentation transitions");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Terrain targets: "+checks+" assertions passed");
    }
}
