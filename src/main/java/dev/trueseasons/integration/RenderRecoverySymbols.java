package dev.trueseasons.integration;

import dev.trueseasons.integration.RenderLedger.ChunkKey;
import java.nio.file.Path;
import java.util.*;

/** Short-lived, bounded sharing of immutable identifiers while restoring a queue. */
final class RenderRecoverySymbols {
    private static final int LIMIT=256;
    private static final int MAX_NAME_CHARS=1024;
    private final Map<String,UUID> worlds=new HashMap<>();
    private final Map<String,String> maps=new HashMap<>();
    private final Map<String,Path> folders=new HashMap<>();
    private String previousPrefix;
    private UUID previousWorld;
    ChunkKey key(String id){
        int first=id.indexOf('/'),second=id.indexOf('/',first+1),end=id.indexOf('/',second+1);
        UUID world=previousWorld;
        if(previousPrefix==null||first!=previousPrefix.length()||!id.startsWith(previousPrefix)){
            String prefix=id.substring(0,first);world=worlds.get(prefix);
            if(world==null){world=UUID.fromString(prefix);if(worlds.size()<LIMIT)worlds.put(prefix,world);}
            previousPrefix=prefix;previousWorld=world;
        }
        return new ChunkKey(world,Integer.parseInt(id,first+1,second,10),Integer.parseInt(id,second+1,end<0?id.length():end,10));
    }
    String map(String id){
        String known=maps.get(id);if(known!=null)return known;
        if(maps.size()<LIMIT&&id.length()<=MAX_NAME_CHARS)maps.put(id,id);return id;
    }
    Path folder(String value){
        Path known=folders.get(value);if(known!=null)return known;
        Path path=Path.of(value);if(folders.size()<LIMIT&&value.length()<=MAX_NAME_CHARS)folders.put(value,path);return path;
    }
}
