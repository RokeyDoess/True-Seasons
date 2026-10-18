package dev.trueseasons.snow;

import java.io.*;
import java.util.*;

/** Independent schema-one reference plus sparse ownership and malformed-record checks. */
public final class SnowColumnsCodecTests {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static byte[] reference(Map<Integer,Integer> values)throws IOException{
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(1);
        var bitmap=new BitSet(256);values.keySet().forEach(bitmap::set);out.write(Arrays.copyOf(bitmap.toByteArray(),32));
        for(int height:values.values())out.writeShort(height);return bytes.toByteArray();
    }
    private static void rejected(byte[] bytes)throws Exception{
        try{SnowColumns.decode(bytes);throw new AssertionError("Malformed snow metadata accepted");}catch(IOException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        var random=new Random(6271);
        for(int fixture=0;fixture<1000;fixture++){
            var actual=new SnowColumns();var model=new TreeMap<Integer,Integer>();var view=actual.entries();
            for(int step=0;step<100;step++){
                int column=random.nextInt(256),height=(short)random.nextInt(65536);
                if(random.nextInt(4)==0){actual.remove(column);model.remove(column);}else{actual.put(column,height);model.put(column,height);}
                check(Objects.equals(actual.get(column),model.get(column))&&view.equals(model),"Ownership updates keep the existing live read-only map behavior");
            }
            byte[] expected=reference(model);check(Arrays.equals(actual.encode(),expected),"Saved bytes exactly match the existing schema");
            check(SnowColumns.decode(expected).entries().equals(model),"Existing saved records retain every signed height");
            try{view.clear();throw new AssertionError("Mutable ownership view");}catch(UnsupportedOperationException correct){checks++;}
        }
        var all=new SnowColumns();var expected=new TreeMap<Integer,Integer>();
        int[] heights={Short.MIN_VALUE,-64,-1,0,127,128,319,Short.MAX_VALUE};
        for(int column=255;column>=0;column--){int height=heights[column%heights.length];all.put(column,height);expected.put(column,height);}
        byte[] dense=reference(expected);check(dense.length==545&&Arrays.equals(dense,all.encode()),"Every bitmap boundary and signed-height extreme keeps its exact encoding");
        for(int length=0;length<dense.length;length++)rejected(Arrays.copyOf(dense,length));
        rejected(Arrays.copyOf(dense,dense.length+1));
        for(int schema:new int[]{0,2,255})rejected(new byte[]{(byte)schema});
        check(SnowColumns.decode(null).isEmpty(),"Missing ownership remains empty");
        byte[] empty=reference(Map.of());check(Arrays.equals(empty,new SnowColumns().encode())&&SnowColumns.decode(empty).isEmpty(),"An empty stored bitmap stays valid");
        check(all.get(-1)==null&&all.get(256)==null,"Out-of-range lookups remain absent");
        for(int[] invalid:new int[][]{{-1,0},{256,0},{0,Short.MIN_VALUE-1},{0,Short.MAX_VALUE+1}}){try{all.put(invalid[0],invalid[1]);throw new AssertionError("Invalid ownership accepted");}catch(IllegalArgumentException correct){checks++;}}
        System.out.println("Snow ownership codec: "+checks+" assertions passed");
    }
}
