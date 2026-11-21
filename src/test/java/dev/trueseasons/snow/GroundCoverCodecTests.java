package dev.trueseasons.snow;

import java.io.*;
import java.util.*;

/** Compatibility with existing schema-one plant snapshots, including Java's modified UTF. */
public final class GroundCoverCodecTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static byte[] reference(Map<Integer,GroundCoverColumns.Plant> rows)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);out.writeShort(rows.size());
        for(var row:rows.entrySet()){
            var p=row.getValue();out.writeByte(row.getKey());out.writeShort(p.y());out.writeUTF(p.lower());out.writeBoolean(p.upper()!=null);
            if(p.upper()!=null)out.writeUTF(p.upper());out.writeUTF(p.support());
        }
        return bytes.toByteArray();
    }
    private static void rejected(byte[] bytes)throws Exception{
        try{GroundCoverColumns.decode(bytes);throw new AssertionError("Malformed plant metadata accepted");}catch(IOException correct){checks++;}
    }
    public static void main(String[] args)throws Exception{
        var samples=List.of(new GroundCoverColumns.Plant(-64,"minecraft:flower[prop="+(char)0+"é€🌸]",null,"minecraft:grass_block[snowy=false]"),
            new GroundCoverColumns.Plant(128,"minecraft:tall_grass[half=lower]","minecraft:tall_grass[half=upper]","minecraft:grass_block[snowy=true]"),
            new GroundCoverColumns.Plant(Short.MAX_VALUE-1,"minecraft:short_grass",null,"minecraft:dirt"));
        var random=new Random(6271);
        for(int fixture=0;fixture<600;fixture++){
            var model=new TreeMap<Integer,GroundCoverColumns.Plant>();int count=random.nextInt(257);
            for(int n=0;n<count;n++)model.put(random.nextInt(256),samples.get(random.nextInt(samples.size())));
            byte[] bytes=reference(model);var decoded=GroundCoverColumns.decode(bytes);
            check(decoded.entries().equals(model),"Existing snapshots preserve exact heights, support and both plant halves");
            check(Arrays.equals(decoded.encode(),bytes),"Existing modified-UTF bytes remain canonical and unchanged");
            for(int length:new int[]{0,1,2,bytes.length-1})if(length<bytes.length)rejected(Arrays.copyOf(bytes,length));
            rejected(Arrays.copyOf(bytes,bytes.length+1));
        }
        var unordered=new LinkedHashMap<Integer,GroundCoverColumns.Plant>();unordered.put(255,samples.get(0));unordered.put(0,samples.get(1));
        check(GroundCoverColumns.decode(reference(unordered)).entries().equals(new TreeMap<>(unordered)),"Previously accepted unordered records remain readable");
        byte[] one=reference(Map.of(0,samples.get(0)));for(int length=0;length<one.length;length++)rejected(Arrays.copyOf(one,length));
        byte[] malformed=one.clone();malformed[8]=(byte)0xf0;rejected(malformed);
        var duplicateBytes=new ByteArrayOutputStream();var duplicate=new DataOutputStream(duplicateBytes);duplicate.writeByte(1);duplicate.writeShort(2);
        for(int i=0;i<2;i++){var p=samples.get(1);duplicate.writeByte(3);duplicate.writeShort(p.y());duplicate.writeUTF(p.lower());duplicate.writeBoolean(true);duplicate.writeUTF(p.upper());duplicate.writeUTF(p.support());}
        rejected(duplicateBytes.toByteArray());rejected(new byte[]{1,1,1});rejected(new byte[]{2});rejected(new byte[524289]);
        var collision=new TreeMap<Integer,GroundCoverColumns.Plant>();
        collision.put(0,new GroundCoverColumns.Plant(64,"minecraft:Aa",null,"minecraft:dirt"));
        collision.put(1,new GroundCoverColumns.Plant(64,"minecraft:BB",null,"minecraft:dirt"));
        collision.put(2,collision.get(0));
        check(GroundCoverColumns.decode(reference(collision)).entries().equals(collision),"UTF hash collisions still compare exact encoded bytes");
        check(GroundCoverColumns.decode(null).isEmpty()&&GroundCoverColumns.decode(reference(Map.of())).isEmpty(),"Missing and empty plant backups stay empty");
        var mutable=new GroundCoverColumns();var view=mutable.entries();
        check(view.isEmpty(),"Empty backup view starts empty");
        mutable.put(255,samples.get(0));mutable.put(0,samples.get(1));mutable.put(127,samples.get(2));
        check(view.size()==3&&new ArrayList<>(view.keySet()).equals(List.of(0,127,255)),"Existing views follow edits in column order");
        mutable.put(127,samples.get(0));
        check(view.size()==3&&view.get(127).equals(samples.get(0)),"Replacing a backup keeps its column count");
        try{view.clear();throw new AssertionError("Backup view was mutable");}catch(UnsupportedOperationException correct){checks++;}
        byte[] saved=mutable.encode();byte[] expected=saved.clone();
        Arrays.fill(saved,(byte)0);
        check(Arrays.equals(mutable.encode(),expected),"Encoded records do not expose the shared UTF cache");
        for(int column=mutable.nextColumn(0);column<256;column=mutable.nextColumn(column+1))mutable.remove(column);
        check(mutable.isEmpty()&&view.isEmpty()&&mutable.nextColumn(0)==256,"Restoration can remove each current column without skipping sparse backups");
        check(Arrays.equals(mutable.encode(),reference(Map.of())),"Removing every backup writes the original empty format");
        mutable.put(128,samples.get(2));
        check(view.size()==1&&view.get(128).equals(samples.get(2)),"Backup grids can be reused after restoration");
        System.out.println("Plant snapshot codec: "+checks+" assertions passed");
    }
}
