package dev.trueseasons.snow;

import java.io.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Cosmetic restoration receipts, saved with the blocks; never used to write terrain. */
final class MeltFootprints {
    record Receipt(String version,Map<Integer,GroundCoverColumns.Plant> columns){
        Receipt{Objects.requireNonNull(version);columns=Map.copyOf(columns);}
    }
    private final NamespacedKey key;
    private final Function<String,BlockData> parse;
    private final Map<String,BlockData> parsed=new LinkedHashMap<>(64,.75f,true);
    MeltFootprints(Plugin plugin,Function<String,BlockData> parse){key=new NamespacedKey(plugin,"melt_v1");this.parse=parse;}
    boolean has(Chunk chunk){return chunk.getPersistentDataContainer().has(key);}
    void clear(Chunk chunk){chunk.getPersistentDataContainer().remove(key);}
    Receipt read(Chunk chunk)throws IOException{
        var pdc=chunk.getPersistentDataContainer();
        if(pdc.has(key)&&!pdc.has(key,PersistentDataType.BYTE_ARRAY))throw new IOException("Unexpected melt receipt type");
        return decode(pdc.get(key,PersistentDataType.BYTE_ARRAY));
    }
    void write(Chunk chunk,Receipt receipt){
        if(receipt.columns.isEmpty())clear(chunk);else chunk.getPersistentDataContainer().set(key,PersistentDataType.BYTE_ARRAY,encode(receipt));
    }
    private BlockData data(String text){
        BlockData result=parsed.get(text);if(result!=null)return result;
        result=parse.apply(text);if(parsed.size()>=1024)parsed.remove(parsed.keySet().iterator().next());parsed.put(text,result);return result;
    }
    static boolean support(BlockData a,BlockData b){
        if(a instanceof Snowable){a=a.clone();((Snowable)a).setSnowy(false);}
        if(b instanceof Snowable){b=b.clone();((Snowable)b).setSnowy(false);}
        return a.equals(b);
    }
    SnowOwnershipSnapshots.Preview preview(Chunk chunk,String version,ToIntFunction<BlockData> ids,boolean validate)throws IOException{
        Receipt receipt=read(chunk);Map<Integer,SnowOwnershipSnapshots.PreviewColumn> result=new HashMap<>();
        if(!receipt.version.equals(version))return new SnowOwnershipSnapshots.Preview(version,System.nanoTime(),result,true);
        Set<Integer> invalid=new HashSet<>();
        for(var row:receipt.columns.entrySet()){
            var p=row.getValue();if(p.y()<=chunk.getWorld().getMinHeight()||p.y()>=chunk.getWorld().getMaxHeight()||p.upper()!=null&&p.y()+1>=chunk.getWorld().getMaxHeight())throw new IOException("Melt receipt outside world height");
            try{
                BlockData lower=data(p.lower()),upper=p.upper()==null?null:data(p.upper()),ground=data(p.support());
                if(!lower.getMaterial().isAir()&&!WinterGroundCover.eligible(lower.getMaterial()))throw new IllegalArgumentException("Invalid restored plant");
                if(upper!=null&&(!(lower instanceof Bisected l)||!(upper instanceof Bisected u)||l.getHalf()!=Bisected.Half.BOTTOM||u.getHalf()!=Bisected.Half.TOP||lower.getMaterial()!=upper.getMaterial()))throw new IllegalArgumentException("Invalid restored plant pair");
                if(validate){Block block=chunk.getBlock(row.getKey()&15,p.y(),row.getKey()>>4);
                    if(!block.getBlockData().equals(lower)||upper!=null&&!block.getRelative(BlockFace.UP).getBlockData().equals(upper)
                            ||!support(block.getRelative(BlockFace.DOWN).getBlockData(),ground)
                            ||chunk.getWorld().getHighestBlockAt(block.getX(),block.getZ(),HeightMap.WORLD_SURFACE).getY()
                                >p.y()+(upper!=null?1:lower.getMaterial().isAir()?-1:0)){invalid.add(row.getKey());continue;}
                }
                result.put(row.getKey(),new SnowOwnershipSnapshots.PreviewColumn(p.y(),ids.applyAsInt(ground),ids.applyAsInt(lower),upper==null?-1:ids.applyAsInt(upper)));
            }catch(IllegalArgumentException failure){throw new IOException("Invalid restored snow receipt",failure);}
        }
        if(!invalid.isEmpty()){var retained=new HashMap<>(receipt.columns);invalid.forEach(retained::remove);write(chunk,new Receipt(version,retained));}
        return new SnowOwnershipSnapshots.Preview(version,System.nanoTime(),result,true);
    }
    boolean edited(Block block)throws IOException{
        Chunk chunk=block.getChunk();if(!has(chunk))return false;Receipt receipt=read(chunk);
        int column=(block.getX()&15)|((block.getZ()&15)<<4);var p=receipt.columns.get(column);
        if(p==null||block.getY()<p.y()-1)return false;
        var remaining=new HashMap<>(receipt.columns);remaining.remove(column);write(chunk,new Receipt(receipt.version,remaining));return true;
    }
    static byte[] encode(Receipt receipt){
        if(receipt.columns.size()>256)throw new IllegalArgumentException("Too many melt columns");
        try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);out.writeUTF(receipt.version);
            Map<String,Integer> palette=new LinkedHashMap<>();
            for(var p:receipt.columns.values())for(String text:new String[]{p.lower(),p.upper(),p.support()})if(text!=null)palette.computeIfAbsent(text,ignored->palette.size());
            out.writeShort(palette.size());for(String text:palette.keySet())out.writeUTF(text);
            out.writeShort(receipt.columns.size());
            for(var row:new TreeMap<>(receipt.columns).entrySet()){
                if(row.getKey()<0||row.getKey()>255)throw new IllegalArgumentException("Invalid melt column");
                var p=row.getValue();out.writeByte(row.getKey());out.writeShort(p.y());out.writeShort(palette.get(p.lower()));out.writeShort(p.upper()==null?65535:palette.get(p.upper()));out.writeShort(palette.get(p.support()));
            }
            return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    static Receipt decode(byte[] bytes)throws IOException{
        if(bytes==null)return new Receipt("",Map.of());if(bytes.length>400000)throw new IOException("Oversized melt receipt");
        var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readUnsignedByte()!=1)throw new IOException("Unknown melt receipt schema");
        String version=in.readUTF();if(version.isEmpty()||version.length()>512)throw new IOException("Invalid melt receipt version");
        int size=in.readUnsignedShort();if(size>768)throw new IOException("Oversized melt palette");String[] palette=new String[size];
        for(int i=0;i<size;i++)palette[i]=in.readUTF();int count=in.readUnsignedShort();if(count>256)throw new IOException("Too many melt columns");
        Map<Integer,GroundCoverColumns.Plant> columns=new HashMap<>();
        try{for(int i=0;i<count;i++){
            int column=in.readUnsignedByte(),y=in.readShort(),lower=in.readUnsignedShort(),upper=in.readUnsignedShort(),support=in.readUnsignedShort();
            if(lower>=size||upper!=65535&&upper>=size||support>=size||columns.containsKey(column))throw new IOException("Invalid melt palette entry");
            columns.put(column,new GroundCoverColumns.Plant(y,palette[lower],upper==65535?null:palette[upper],palette[support]));
        }}catch(IllegalArgumentException failure){throw new IOException("Invalid melt receipt",failure);}
        if(in.available()!=0)throw new IOException("Trailing melt data");return new Receipt(version,columns);
    }
}
