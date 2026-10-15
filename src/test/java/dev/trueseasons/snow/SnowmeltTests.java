package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import dev.trueseasons.state.StateStore;
import java.nio.file.*;
import java.util.*;

public final class SnowmeltTests {
    private static int checks;
    public static void main(String[]args)throws Exception{
        Map<Integer,GroundCoverColumns.Plant> columns=new HashMap<>();
        for(int i=0;i<256;i++)columns.put(i,new GroundCoverColumns.Plant(-20,"minecraft:air",null,"minecraft:stone"));
        var receipt=new MeltFootprints.Receipt("winter",columns);byte[] encoded=MeltFootprints.encode(receipt);
        check(encoded.length<2500,"Dense flat melt receipts share their block-state palette");
        check(MeltFootprints.decode(encoded).equals(receipt),"All negative heights and columns survive receipt serialization");
        for(int length:new int[]{0,1,4,encoded.length-1}){
            try{MeltFootprints.decode(Arrays.copyOf(encoded,length));throw new AssertionError("Truncated receipt accepted");}catch(java.io.IOException expected){checks++;}
        }
        byte[] unknown=encoded.clone();unknown[0]=99;try{MeltFootprints.decode(unknown);throw new AssertionError("Unknown schema accepted");}catch(java.io.IOException expected){checks++;}
        Path root=Files.createTempDirectory("trueseasons-melt-");
        try{
            StateStore state=new StateStore(root);state.winterVersion="winter-pattern";state.winterColumns=64;
            state.seasonTransition(Season.WINTER,Season.SPRING,"spring");
            check(state.meltStartColumns==64&&state.meltColumns==64&&state.meltOrderVersion.equals("winter-pattern"),"A new melt preserves winter coverage and its exact scattering pattern");
            state.meltColumns=31;state.save();state=new StateStore(root);state.seasonTransition(state.lastSeason,Season.SPRING,"spring");
            check(state.meltColumns==31&&state.meltVersion.equals("spring"),"Restarting the same transition resumes persisted coverage");
            state.seasonTransition(Season.SPRING,Season.AUTUMN,"autumn");
            check(state.meltColumns==31&&state.meltVersion.equals("autumn")&&state.meltOrderVersion.equals("winter-pattern"),"Out-of-sequence nonwinter changes preserve remaining melt without a restart");
            state.seasonTransition(Season.AUTUMN,Season.WINTER,"winter-two");
            check(state.meltColumns==0&&state.meltVersion.isEmpty(),"Reentering winter retires the previous melt");
            state.winterColumns=256;state.seasonTransition(Season.WINTER,Season.SUMMER,"summer");state.cleanup=true;
            state.seasonTransition(Season.SUMMER,Season.SUMMER,"cleanup");
            check(state.meltColumns==0&&state.meltVersion.isEmpty(),"Cleanup never waits for a presentation fade");
            state.cleanup=false;state.clean=true;state.seasonTransition(Season.WINTER,Season.SPRING,"restored");
            check(state.meltColumns==0,"Restored mode cannot start another fade");
            Files.writeString(root.resolve("jobs.yml"),"schema: 1\nsnowmelt:\n  remaining-columns: 257\n");
            try{new StateStore(root);throw new AssertionError("Invalid melt progress accepted");}catch(java.io.IOException expected){checks++;}
            Files.writeString(root.resolve("jobs.yml"),"schema: 1\n");state=new StateStore(root);
            check(state.lastSeason==null&&state.meltVersion.isEmpty()&&state.meltColumns==0,"Existing installations acquire no invented winter history");
        }finally{
            Path allowed=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if(!root.toRealPath().startsWith(allowed)||root.toRealPath().equals(allowed))throw new IllegalStateException("Unsafe test cleanup");
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
        }
        System.out.println("Snowmelt tests passed: "+checks+" assertions");
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
