package dev.trueseasons.migration;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Distinguishes saved full terrain from generation scaffolding using actual region/NBT fixtures. */
public final class GeneratedChunkSnapshotTests {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private record Tag(int type, String name, Object value) {}
    private static Tag text(String name, String value) { return new Tag(8, name, value); }
    private static Tag integer(String name, int value) { return new Tag(3, name, value); }
    private static void tag(DataOutputStream data, Tag tag) throws IOException {
        data.writeByte(tag.type()); data.writeUTF(tag.name());
        switch (tag.type()) {
            case 3 -> data.writeInt((Integer) tag.value());
            case 8 -> data.writeUTF((String) tag.value());
            case 7 -> { byte[] bytes = (byte[]) tag.value(); data.writeInt(bytes.length); data.write(bytes); }
            case 10 -> { for (Tag child : (Tag[]) tag.value()) tag(data, child); data.writeByte(0); }
            default -> throw new AssertionError("Unsupported fixture tag " + tag.type());
        }
    }
    private static byte[] status(String status, boolean legacy, Tag... metadata) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(10); data.writeUTF("");
        if (legacy) { data.writeByte(10); data.writeUTF("Level"); }
        data.writeByte(8); data.writeUTF("Status"); data.writeUTF(status);
        if (metadata.length > 0) tag(data, new Tag(10, "ChunkBukkitValues", metadata));
        data.writeByte(0);
        if (legacy) data.writeByte(0);
        return bytes.toByteArray();
    }
    private static byte[] payload(byte[] nbt, int compression) throws IOException {
        if (compression == 3) return nbt;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream stream = compression == 1 ? new GZIPOutputStream(bytes) : new DeflaterOutputStream(bytes)) { stream.write(nbt); }
        return bytes.toByteArray();
    }
    private static byte[] wrongStatusType() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(bytes);
        data.writeByte(10); data.writeUTF("");
        data.writeByte(3); data.writeUTF("Status"); data.writeInt(1); data.writeByte(0);
        return bytes.toByteArray();
    }
    private static void chunk(RandomAccessFile file, int slot, int sector, byte[] nbt, int compression) throws IOException {
        byte[] bytes = payload(nbt, compression);
        if (bytes.length + 5 > 4096) throw new AssertionError("Fixture exceeded one sector");
        file.seek(slot * 4L); file.writeInt((sector << 8) | 1);
        file.seek(sector * 4096L); file.writeInt(bytes.length + 1); file.writeByte(compression); file.write(bytes);
    }
    private static void checkpoints(Path parent) throws IOException {
        Path directory = Files.createDirectory(parent.resolve("checkpoints"));
        Path region = directory.resolve("r.0.0.mca");
        String version = "manual-WINTER-r12-snow3";
        String versionKey = "trueseasons:season_version", pendingKey = "trueseasons:winter_pending", cursorKey = "trueseasons:winter_cursor";
        byte[] owned = new byte[33]; owned[0] = 1; // Valid empty ownership bitmap; metadata still requires cleanup.
        byte[][] fixtures = {
            status("minecraft:full", false, text(versionKey,version), new Tag(7,"trueseasons:snow_v1",owned)),
            status("full", true, text(versionKey,version)),
            status("minecraft:full", false, text(versionKey,version), text(pendingKey,version), integer(cursorKey,128)),
            status("minecraft:full", false, text(versionKey,version), integer(pendingKey,128)),
            status("minecraft:full", false, text(versionKey,version), text(cursorKey,"malformed")),
            status("minecraft:full", false, text(versionKey,version), integer(cursorKey,0)),
            status("minecraft:full", false, integer(versionKey,12)),
            status("minecraft:full", false, new Tag(7,"trueseasons:snow_v1",owned)),
            status("minecraft:full", false, text("trueseasons:bluemap_revision","map-token")),
            status("minecraft:full", false),
            status("minecraft:full", false, text("otherplugin:season_version",version), integer("otherplugin:winter_cursor",128),
                    new Tag(10,"otherplugin:data",new Tag[]{text(versionKey,version),text("trueseasons:bluemap_revision","nested") })),
            status("minecraft:full", false, text(versionKey,version), text("trueseasons:bluemap_revision","map-token")),
            status("full", true, text(versionKey,version), text(pendingKey,version), integer(cursorKey,64)),
            status("minecraft:full", false, integer("trueseasons:bluemap_revision",123)),
            status("minecraft:full", false, new Tag(7,"trueseasons:ground_cover_v1",new byte[]{1,0,0})),
            status("minecraft:full", false, integer("trueseasons:ground_cover_v1",123)),
            status("minecraft:full", false, integer("trueseasons:winter_order",1)),
            status("minecraft:full", false, text("trueseasons:winter_order","malformed")),
            status("full", true, text(versionKey,version),integer("trueseasons:winter_order",1))
        };
        try (RandomAccessFile file = new RandomAccessFile(region.toFile(),"rw")) {
            file.setLength((fixtures.length + 2L) * 4096);
            for (int slot = 0; slot < fixtures.length; slot++) chunk(file,slot,slot+2,fixtures[slot],slot==1?1:2);
        }
        byte[] before = Files.readAllBytes(region);
        GeneratedChunkIndex index = new GeneratedChunkIndex();
        var snapshot = index.scanSnapshot(directory);
        var states = snapshot.states();
        check(!states.get(new ChunkPos(14,0)).matches(version,true), "Saved plant backups prevent cleanup completion without loading/restoration");
        check(!states.get(new ChunkPos(15,0)).matches(version,true), "Malformed plant backup presence remains visible to cleanup recovery");
        check(states.get(new ChunkPos(16,0)).seasonMetadata()&&!states.get(new ChunkPos(16,0)).matches(version,true),
                "An orphan winter order marker must be cleaned before plugin removal is safe");
        check(states.get(new ChunkPos(17,0)).seasonMetadata()&&!states.get(new ChunkPos(17,0)).matches(version,true),
                "Malformed orphan winter order metadata remains visible for safe live validation");
        check(states.get(new ChunkPos(18,0)).matches(version,false)&&!states.get(new ChunkPos(18,0)).matches(version,true),
                "A leftover order marker does not restart a completed season but still requires cleanup");
        check(states.size() == fixtures.length && snapshot.unreadable().isEmpty(), "Every readable FULL fixture supplies a saved metadata checkpoint");
        SavedChunkState modern = states.get(new ChunkPos(0,0));
        check(modern.full() && version.equals(modern.version()) && !modern.pending() && modern.matches(version,false),
                "Modern ChunkBukkitValues can hydrate a completed matching season");
        check(!modern.matches("next-winter",false), "A previous revision cannot hydrate a new migration");
        check(!modern.matches(version,true), "A seasonal version or owned snow prevents saved cleanup completion");
        SavedChunkState legacy = states.get(new ChunkPos(1,0));
        check(version.equals(legacy.version()) && legacy.matches(version,false), "Legacy Level.ChunkBukkitValues hydrates the matching season");
        SavedChunkState partial = states.get(new ChunkPos(2,0));
        check(partial.pending() && partial.columns()==128 && !partial.matches(version,false), "A matching stamp with a partial cursor cannot hydrate completed work");
        check(!partial.matches(version,true), "Pending snow remains an explicit cleanup obligation");
        check(states.get(new ChunkPos(3,0)).pending() && !states.get(new ChunkPos(3,0)).matches(version,false),
                "A malformed pending type cannot hide behind a matching version");
        check(states.get(new ChunkPos(4,0)).pending() && !states.get(new ChunkPos(4,0)).matches(version,false),
                "A malformed cursor type cannot hide behind a matching version");
        check(states.get(new ChunkPos(5,0)).pending() && !states.get(new ChunkPos(5,0)).matches(version,false),
                "An orphan zero cursor still prevents premature completion hydration");
        SavedChunkState malformedVersion = states.get(new ChunkPos(6,0));
        check(malformedVersion.version()==null && malformedVersion.seasonMetadata() && !malformedVersion.matches(version,false)
                && !malformedVersion.matches(version,true), "Malformed version metadata must be revalidated rather than skipped");
        check(states.get(new ChunkPos(7,0)).seasonMetadata() && !states.get(new ChunkPos(7,0)).matches(version,true),
                "Even an empty retained snow ownership tag prevents saved cleanup completion");
        SavedChunkState mapOnly = states.get(new ChunkPos(8,0));
        check(!mapOnly.seasonMetadata() && mapOnly.mapMarker() && !mapOnly.matches(version,true), "A temporary map marker prevents cleanup completion after snow metadata is gone");
        SavedChunkState restored = states.get(new ChunkPos(9,0));
        check(!restored.seasonMetadata() && !restored.mapMarker() && restored.matches(version,true) && !restored.matches(version,false),
                "A fully restored chunk can hydrate cleanup but cannot hydrate winter placement");
        SavedChunkState unrelated = states.get(new ChunkPos(10,0));
        check(unrelated.version()==null && !unrelated.pending() && !unrelated.seasonMetadata() && !unrelated.mapMarker()
                && unrelated.matches(version,true), "Unrelated plugin metadata and nested similarly named tags are ignored");
        SavedChunkState mappedComplete = states.get(new ChunkPos(11,0));
        check(mappedComplete.matches(version,false) && !mappedComplete.matches(version,true), "A map receipt does not invalidate completed terrain but still prevents cleanup hydration");
        SavedChunkState legacyPartial = states.get(new ChunkPos(12,0));
        check(legacyPartial.pending() && legacyPartial.columns()==64 && !legacyPartial.matches(version,false), "Legacy pending cursors survive snapshot reading without becoming completed terrain");
        check(states.get(new ChunkPos(13,0)).mapMarker() && !states.get(new ChunkPos(13,0)).matches(version,true),
                "A malformed map marker still requires cleanup instead of being ignored");
        check(Arrays.equals(before,Files.readAllBytes(region)), "Saved checkpoint hydration checks never edit world data");
        check(index.scanSnapshot(directory).states().equals(states), "Saved metadata checkpoints are stable across repeated reads");
    }
    private static void sharedRegion(Path parent)throws IOException{
        Path directory=Files.createDirectory(parent.resolve("shared-region"));
        Path region=directory.resolve("r.-1.-1.mca"),external=directory.resolve("c.-28.-32.mcc");
        try(RandomAccessFile file=new RandomAccessFile(region.toFile(),"rw")){
            file.setLength(10L*4096);
            chunk(file,0,2,status("minecraft:full",false),2);
            chunk(file,1,3,new byte[]{10,0},2);
            chunk(file,2,4,status("full",true),1);
            chunk(file,3,5,status("minecraft:full",false),3);
            for(int slot=4;slot<=5;slot++){
                file.seek(slot*4L);file.writeInt(((slot+2)<<8)|1);
                file.seek((slot+2L)*4096);file.writeInt(1);file.writeByte(128|2);
            }
            Files.write(external,payload(status("minecraft:full",false),2));
            chunk(file,6,8,status("minecraft:full",false),99);
            chunk(file,7,9,status("minecraft:full",false),2);
        }
        byte[] before=Files.readAllBytes(region),externalBefore=Files.readAllBytes(external);
        var index=new GeneratedChunkIndex();var result=index.scanSnapshot(directory);
        check(result.generated().equals(List.of(new ChunkPos(-32,-32),new ChunkPos(-30,-32),new ChunkPos(-29,-32),
            new ChunkPos(-28,-32),new ChunkPos(-25,-32))),"Shared region reader handles zlib, gzip, raw, external and final readable chunks");
        check(result.unreadable().keySet().equals(Set.of(new ChunkPos(-31,-32),new ChunkPos(-27,-32),new ChunkPos(-26,-32))),
            "Malformed NBT, missing external payload and unknown compression defer only their own chunks");
        check(Arrays.equals(before,Files.readAllBytes(region))&&Arrays.equals(externalBefore,Files.readAllBytes(external)),
            "Shared reader changes neither regions nor external chunk payloads");
        Thread.currentThread().interrupt();
        try{index.scanSnapshot(directory);throw new AssertionError("Cancelled scan continued");}
        catch(IOException expected){check(expected.getMessage().contains("cancelled"),"Cancellation stops a scan without publishing partial completion");}
        finally{Thread.interrupted();}
        // A captured location must not become an accepted checkpoint after relocation.
        try(RandomAccessFile writer=new RandomAccessFile(region.toFile(),"rw")){
            writer.seek(0);writer.writeInt((5<<8)|1);
        }
        try(RandomAccessFile reader=new RandomAccessFile(region.toFile(),"r")){
            try{SavedChunkStatus.read(reader,directory,new ChunkPos(-32,-32),(2<<8)|1,new byte[5]);
                throw new AssertionError("Moved chunk accepted");}
            catch(IOException expected){check(expected.getMessage().contains("moved"),"Region handle reuse still rejects a chunk whose saved location changed");}
        }
        try(RandomAccessFile writer=new RandomAccessFile(region.toFile(),"rw")){
            writer.seek(0);writer.writeInt((2<<8)|1);
        }
        check(index.scanSnapshot(directory).generated().equals(result.generated()),"Closed failed reads leave subsequent region scans usable");
        Path tooLarge=directory.resolve("c.-27.-32.mcc");
        try(RandomAccessFile file=new RandomAccessFile(tooLarge.toFile(),"rw")){file.setLength(16L*1024*1024+1);}
        var oversized=index.scanSnapshot(directory);
        check(oversized.unreadable().get(new ChunkPos(-27,-32)).contains("exceeds"),"Oversized external payload is bounded and cannot hide behind a full checkpoint");
    }
    private static void decompressionLimit(Path parent)throws IOException{
        Path directory=Files.createDirectory(parent.resolve("decompression-limit"));
        byte[] oversized=payload(status("minecraft:full",false,new Tag(7,"trueseasons:snow_v1",new byte[16*1024*1024-1])),2);
        int sectors=(oversized.length+5+4095)/4096;
        try(RandomAccessFile file=new RandomAccessFile(directory.resolve("r.0.0.mca").toFile(),"rw")){
            file.setLength((2L+sectors)*4096);file.writeInt((2<<8)|sectors);
            file.seek(2L*4096);file.writeInt(oversized.length+1);file.writeByte(2);file.write(oversized);
        }
        var result=new GeneratedChunkIndex().scanSnapshot(directory);
        check(result.generated().isEmpty()&&result.unreadable().get(new ChunkPos(0,0)).contains("exceeds"),
            "Buffered parsing still enforces the total decompressed limit after a valid FULL tag");
    }
    private static void ignoredUtf()throws Exception{
        var method=SavedChunkStatus.class.getDeclaredMethod("skipUtf",DataInputStream.class,byte[].class);method.setAccessible(true);
        List<byte[]> fixtures=new ArrayList<>();
        for(String value:List.of("","ASCII", "\u0000é中\uD83D\uDE03","a".repeat(20000)+"中")){
            var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeUTF(value);fixtures.add(bytes.toByteArray());
        }
        var random=new Random(8276231728L);
        for(int sample=0;sample<6000;sample++){
            int length=sample<256?1:random.nextInt(16);byte[] text=new byte[length];random.nextBytes(text);if(sample<256)text[0]=(byte)sample;
            var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);data.writeShort(length+(sample%7==0?1:0));data.write(text);fixtures.add(bytes.toByteArray());
        }
        for(byte[] bytes:fixtures){
            boolean expected;
            try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){input.readUTF();expected=true;}catch(IOException invalid){expected=false;}
            for(int size:new int[]{5,8192}){
                boolean actual;
                try(var input=new DataInputStream(new ByteArrayInputStream(bytes))){method.invoke(null,input,new byte[size]);actual=true;check(input.available()==0,"Ignored UTF consumes the exact encoded payload");}
                catch(java.lang.reflect.InvocationTargetException invalid){if(!(invalid.getCause() instanceof IOException))throw invalid;actual=false;}
                check(actual==expected,"Ignored UTF validates exactly like DataInputStream, including malformed/truncated data");
            }
        }
    }
    private static byte[] ignoredPayload(int child,int count,boolean truncated,boolean malformedKey)throws IOException{
        var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);data.writeByte(10);data.writeUTF("");tag(data,text("Status","minecraft:full"));
        data.writeByte(10);data.writeUTF("ignored");
        if(malformedKey){data.writeByte(8);data.writeShort(1);data.writeByte(0x80);data.writeUTF("value");}
        else{data.writeByte(9);data.writeUTF("values");data.writeByte(child);data.writeInt(count);int width=child==1?1:child==2?2:child==3||child==5?4:8;data.write(new byte[count*width-(truncated?1:0)]);}
        if(!truncated){data.writeByte(0);data.writeByte(0);}return bytes.toByteArray();
    }
    private static void ignoredPayloads(Path parent)throws IOException{
        Path directory=Files.createDirectory(parent.resolve("ignored-payloads"));int slot=0;
        try(var file=new RandomAccessFile(directory.resolve("r.0.0.mca").toFile(),"rw")){
            file.setLength(20*4096L);
            for(int child=1;child<=6;child++){
                chunk(file,slot,slot+2,ignoredPayload(child,1000,false,false),2);slot++;
                chunk(file,slot,slot+2,ignoredPayload(child,1000,true,false),2);slot++;
            }
            chunk(file,slot,slot+2,ignoredPayload(1,1,false,true),2);slot++;
            chunk(file,slot,slot+2,status("minecraft:full",false,new Tag(8,"ignored-text","a".repeat(20000)+"\u0000é中")),2);
        }
        var snapshot=new GeneratedChunkIndex().scanSnapshot(directory);
        check(snapshot.generated().size()==7,"All primitive-list types and long ignored UTF remain readable");
        check(snapshot.unreadable().size()==7,"Truncated primitive lists and malformed nested names remain unreadable despite a valid FULL tag");
    }
    private static void reusedEncodedBuffers(Path parent)throws IOException{
        Path directory=Files.createDirectory(parent.resolve("reused-encoded")),region=directory.resolve("r.0.0.mca");
        byte[] large=new byte[24000];new Random(81273).nextBytes(large);
        byte[][] values={status("full",false,text("trueseasons:season_version","first"),new Tag(7,"ignored",Arrays.copyOf(large,12000))),
            status("full",false,text("trueseasons:season_version","second")),status("features",true),status("full",false),
            status("full",false,text("trueseasons:season_version","third"),new Tag(7,"ignored",large)),
            status("full",false),status("full",false,text("trueseasons:season_version","external"))};
        values[3]=Arrays.copyOf(values[3],values[3].length-1); // Truncation must not consume bytes left by a previous large read.
        int[] formats={3,2,1,3,2,3,2},locations=new int[values.length];
        try(var file=new RandomAccessFile(region.toFile(),"rw")){
            file.setLength(32*4096L);int sector=2;
            for(int slot=0;slot<values.length;slot++){
                byte[] encoded=payload(values[slot],formats[slot]);boolean external=slot==6;
                int sectors=external?1:(encoded.length+5+4095)/4096;locations[slot]=(sector<<8)|sectors;
                file.seek(slot*4L);file.writeInt(locations[slot]);file.seek(sector*4096L);
                file.writeInt(external?1:encoded.length+1);file.writeByte(formats[slot]|(external?128:0));
                if(external)Files.write(directory.resolve("c.6.0.mcc"),encoded);else file.write(encoded);
                sector+=sectors;
            }
        }
        byte[] before=Files.readAllBytes(region);var buffers=new SavedChunkStatus.ReadBuffers();
        try(var file=new RandomAccessFile(region.toFile(),"r")){
            var first=SavedChunkStatus.read(file,directory,new ChunkPos(0,0),locations[0],buffers);
            for(int round=0;round<3;round++)for(int slot=0;slot<values.length;slot++){
                if(slot==3){try{SavedChunkStatus.read(file,directory,new ChunkPos(slot,0),locations[slot],buffers);throw new AssertionError("Truncated raw data must not read stale buffer bytes");}
                    catch(IOException expected){check(true,"A shorter truncated record remains unreadable after a large valid record");}}
                else check(SavedChunkStatus.read(file,directory,new ChunkPos(slot,0),locations[slot],buffers).equals(SavedChunkStatus.read(directory,new ChunkPos(slot,0))),
                    "Reused buffers match independent reads across sizes, compression formats and external records");
            }
            check("first".equals(first.version()),"Later buffer reuse leaves previously decoded checkpoints intact");
        }
        var snapshot=new GeneratedChunkIndex().scanSnapshot(directory);
        check(snapshot.generated().size()==5&&snapshot.unfinished().size()==1&&snapshot.unreadable().size()==1,"Full snapshot preserves mixed reused-buffer classifications");
        check(Arrays.equals(before,Files.readAllBytes(region)),"Reusing encoded buffers never changes saved data");
    }
    private static void malformedMetadataContainer(Path parent)throws IOException{
        Path directory=Files.createDirectory(parent.resolve("malformed-pdc-container")),region=directory.resolve("r.0.0.mca");
        try(var file=new RandomAccessFile(region.toFile(),"rw")){
            file.setLength(4*4096L);
            for(int slot=0;slot<2;slot++){
                var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(10);out.writeUTF("");
                if(slot==1){out.writeByte(10);out.writeUTF("Level");}
                tag(out,text("Status","full"));tag(out,slot==0?text("ChunkBukkitValues","damaged"):
                    new Tag(7,"ChunkBukkitValues",new byte[]{1,2,3}));out.writeByte(0);if(slot==1)out.writeByte(0);
                chunk(file,slot,slot+2,bytes.toByteArray(),2);
            }
        }
        byte[] before=Files.readAllBytes(region);var snapshot=new GeneratedChunkIndex().scanSnapshot(directory);
        check(snapshot.generated().isEmpty()&&snapshot.states().isEmpty(),"Malformed reserved metadata containers cannot hydrate a completed season or safe cleanup");
        check(snapshot.unreadable().size()==2,"Modern and legacy malformed metadata containers remain explicit unreadable chunks");
        check(snapshot.unreadable().values().stream().allMatch(reason->reason.contains("ChunkBukkitValues")),"Unreadable metadata containers retain their diagnostic");
        check(Arrays.equals(before,Files.readAllBytes(region)),"Malformed metadata is left untouched for safe recovery");
    }
    private static void decodedBufferBoundaries(Path parent)throws IOException{
        Path directory=Files.createDirectory(parent.resolve("decoded-buffer-boundaries")),region=directory.resolve("r.0.0.mca");
        String version="saved-"+"v".repeat(8200)+"\u0000é中\uD83C\uDF3F",revision="map-\u0000é中\uD83C\uDF3F";
        var expected=new SavedChunkState(true,version,version,true,129,true,true,revision);
        List<Integer> locations=new ArrayList<>();List<byte[]> originals=new ArrayList<>();
        try(var file=new RandomAccessFile(region.toFile(),"rw")){
            file.setLength(80*4096L);int slot=0;
            for(int format:new int[]{1,2,3})for(int padding:new int[]{0,1,254,255,256,257,8190,8191,8192,8193,16383,16384}){
                var bytes=new ByteArrayOutputStream();var data=new DataOutputStream(bytes);data.writeByte(10);data.writeUTF("");
                tag(data,new Tag(7,"padding",new byte[padding]));
                tag(data,text("ignored", "a".repeat(9000)+"\u0000é中"));tag(data,text("Status","full"));
                tag(data,new Tag(10,"ChunkBukkitValues",new Tag[]{text("trueseasons:season_version",version),
                    text("trueseasons:winter_pending",version),integer("trueseasons:winter_cursor",129),text("trueseasons:bluemap_revision",revision)}));data.writeByte(0);
                byte[] nbt=bytes.toByteArray();
                for(boolean truncated:new boolean[]{false,true}){
                    byte[] encoded=payload(truncated?Arrays.copyOf(nbt,nbt.length-1):nbt,format);int location=((slot+2)<<8)|1;
                    file.seek(slot*4L);file.writeInt(location);file.seek((slot+2)*4096L);file.writeInt(1);file.writeByte(128|format);
                    Files.write(directory.resolve("c."+(slot&31)+"."+(slot>>5)+".mcc"),encoded);locations.add(location);originals.add(encoded);slot++;
                }
            }
        }
        byte[] before=Files.readAllBytes(region);var buffers=new SavedChunkStatus.ReadBuffers();
        try(var file=new RandomAccessFile(region.toFile(),"r")){
            for(int slot=0;slot<locations.size();slot++){
                ChunkPos pos=new ChunkPos(slot&31,slot>>5);
                Path actual=directory.resolve("c."+pos.x()+"."+pos.z()+".mcc");
                if((slot&1)==0)check(SavedChunkStatus.read(file,directory,pos,locations.get(slot),buffers).equals(expected),"Decoded buffer keeps exact checkpoints across arrays, long modified UTF, compression and refill boundaries");
                else try{SavedChunkStatus.read(file,directory,pos,locations.get(slot),buffers);throw new AssertionError("Truncated root reused stale decoded bytes");}
                    catch(IOException rejected){check(true,"Truncated root stays unreadable after large valid buffer contents");}
                check(Arrays.equals(originals.get(slot),Files.readAllBytes(actual)),"Index reads preserve the complete external compressed record");
            }
        }
        check(Arrays.equals(before,Files.readAllBytes(region)),"Decoded buffering leaves region bytes untouched");
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trueseasons-status-regression-");
        try {
            Path region = directory.resolve("r.-1.2.mca");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(), "rw")) {
                file.setLength(11 * 4096L);
                chunk(file, 0, 2, status("minecraft:full", false), 2);
                chunk(file, 1, 3, status("minecraft:noise", false), 2);
                chunk(file, 2, 4, status("minecraft:features", false), 3);
                chunk(file, 3, 5, status("full", false), 1);
                chunk(file, 4, 6, status("full", true), 2);
                chunk(file, 5, 7, status("surface", true), 2);
                chunk(file, 6, 8, new byte[]{10, 0}, 2);
                chunk(file, 7, 9, new byte[]{10, 0, 0, 0}, 2);
                chunk(file, 8, 10, wrongStatusType(), 2);
            }
            byte[] before = Files.readAllBytes(region);
            GeneratedChunkIndex index = new GeneratedChunkIndex();
            var snapshot = index.scanSnapshot(directory);
            List<ChunkPos> full = List.of(new ChunkPos(-32,64), new ChunkPos(-29,64), new ChunkPos(-28,64));
            List<ChunkPos> proto = List.of(new ChunkPos(-31,64), new ChunkPos(-30,64), new ChunkPos(-27,64));
            check(snapshot.generated().equals(full), "Only FULL status joins migration across current, plain and legacy NBT");
            check(snapshot.states().keySet().equals(Set.copyOf(full)), "Only FULL terrain supplies saved completion checkpoints");
            check(snapshot.unfinished().equals(proto), "Noise, features and surface statuses remain unfinished terrain");
            check(snapshot.unreadable().keySet().equals(Set.of(new ChunkPos(-26,64),new ChunkPos(-25,64),new ChunkPos(-24,64))),
                    "Truncated NBT, missing Status and malformed Status types remain explicit unreadable errors");
            check(snapshot.unreadable().values().stream().allMatch(value -> value != null && !value.isBlank()), "Unreadable terrain carries a diagnostic");
            check(index.scan(directory).size() == 9, "Legacy occupied-header scanning still includes proto and unreadable entries");
            check(Arrays.equals(before, Files.readAllBytes(region)), "Snapshot indexing never changes a saved region");
            var repeated = index.scanSnapshot(directory);
            check(repeated.generated().equals(snapshot.generated()) && repeated.unfinished().equals(snapshot.unfinished())
                    && repeated.unreadable().keySet().equals(snapshot.unreadable().keySet()), "An unchanged snapshot is stable across repeated scans");
            var empty = index.scanSnapshot(directory.resolve("missing"));
            check(empty.generated().isEmpty() && empty.unfinished().isEmpty() && empty.unreadable().isEmpty(), "Missing region directories remain empty without being created");
            check(!Files.exists(directory.resolve("missing")), "Snapshot indexing never creates a world directory");
            checkpoints(directory);
            sharedRegion(directory);
            decompressionLimit(directory);
            ignoredUtf();ignoredPayloads(directory);reusedEncodedBuffers(directory);malformedMetadataContainer(directory);decodedBufferBoundaries(directory);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Generated chunk snapshot tests passed: " + assertions + " assertions");
    }
}
