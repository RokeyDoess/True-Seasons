package dev.trueseasons.presentation;

import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import dev.trueseasons.climate.SeasonClimatePolicy;
import dev.trueseasons.api.Season;
import dev.trueseasons.visual.SeasonVisualResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;

/** Exercises actual 26.3 NMS packet records, packet codec, and vanilla biome climate decoder. */
public final class NativeSnowPacketTests {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        registryAndClimate(); palettesAndRefresh(); climateExclusions(); seasonalColors(); registryCaching();
        System.out.println("Native snow packet tests passed: " + assertions + " assertions");
        Class.forName("org.apache.logging.log4j.LogManager").getMethod("shutdown").invoke(null);
    }
    private static void registryAndClimate() throws Exception {
        Object tag = biome(2.0f, false), copy = call(tag, "copy");
        Object dry = entry("minecraft:desert", Optional.empty());
        Object forest = entry("minecraft:forest", Optional.of(tag));
        Object packet = registry(List.of(dry, forest));
        PaperBiomePackets bridge = new PaperBiomePackets(Map.of("minecraft:desert", tag));
        EmbeddedChannel channel = new EmbeddedChannel(new NativeSnowPresentation.ClimateHandler(bridge, Logger.getAnonymousLogger()));
        check(channel.writeOutbound(packet), "registry emitted");
        Object rewritten = channel.readOutbound();
        Object decoded = wireRoundTrip(rewritten);
        List<?> entries = (List<?>) call(decoded, "entries");
        check(entries.size() == 4, "original entries plus one alias each");
        check(call(entries.get(0), "id").toString().equals("minecraft:desert"), "known-pack original key unchanged");
        check(((Optional<?>) call(entries.get(0), "data")).isEmpty(), "known-pack original encoding unchanged");
        check(((Optional<?>) call(entries.get(1), "data")).orElseThrow().equals(copy), "original payload unchanged");
        for (int i = 2; i < 4; i++) {
            Object cold = ((Optional<?>) call(entries.get(i), "data")).orElseThrow();
            check(call(entries.get(i), "id").toString().startsWith("trueseasons:winter/"), "private client biome key");
            check(cold.getClass().getMethod("get", String.class).invoke(cold, "effects").equals(tag.getClass().getMethod("get", String.class).invoke(tag, "effects")), "explicit biome effects preserved");
            Object biome = decodeBiome(cold);
            check((boolean) call(biome, "hasPrecipitation"), "dry biome acquires native precipitation");
            for (int y : new int[]{-64, 64, 320}) {
                Object pos = type("net.minecraft.core.BlockPos").getConstructor(int.class, int.class, int.class).newInstance(0, y, 0);
                Object precipitation = biome.getClass().getMethod("getPrecipitationAt", type("net.minecraft.core.BlockPos"), int.class).invoke(biome, pos, 63);
                check(precipitation.toString().equals("SNOW"), "actual vanilla cold climate is SNOW at y=" + y);
            }
        }
        check(tag.equals(copy), "authoritative/shared source payload was never mutated");
        channel.finishAndReleaseAll();
    }
    private static void palettesAndRefresh() throws Exception {
        int count = 70;
        List<Object> entries = new ArrayList<>(); Object tag = biome(2.0f, false);
        for (int i = 0; i < count; i++) entries.add(entry("minecraft:test_" + i, Optional.of(tag)));
        PaperBiomePackets bridge = new PaperBiomePackets(Map.of());
        var handler = new NativeSnowPresentation.ClimateHandler(bridge, Logger.getAnonymousLogger());
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.writeOutbound(registry(entries)); channel.readOutbound();
        int[] original = new int[64]; for (int i = 0; i < 64; i++) original[i] = i;
        ByteBuf packed = Unpooled.buffer(); BiomeWireCodec.writeBiomes(packed, original, count, false, count);
        byte[] originalWire = new byte[packed.readableBytes()]; packed.readBytes(originalWire); packed.release();
        ByteBuf section = Unpooled.buffer(); section.writeShort(11).writeShort(7).writeByte(0).writeByte(0).writeBytes(originalWire);
        byte[] sectionWire = new byte[section.readableBytes()]; section.readBytes(sectionWire); section.release();
        Object chunkData = chunkData(sectionWire), chunk = chunk(chunkData, 4, -3);
        channel.writeOutbound(chunk);
        Object warm = channel.readOutbound();
        check(warm != chunk, "full chunk cloned per viewer");
        check(Arrays.equals(sectionWire, buffer(chunkData)), "original chunk packet unmodified");
        check(buffer(call(warm, "chunkData"))[6] == 8, "expanded registry adjusts full-chunk direct palette width");
        handler.setWinter(true);
        Object winter = channel.readOutbound();
        int[] coldIds = ids(winter);
        for (int i = 0; i < 64; i++) check(coldIds[i] == i + count, "winter native biome mapping " + i);
        check(Arrays.equals(nativeIds(winter, count * 2), coldIds), "vanilla PalettedContainer decoder accepts winter global palette");
        handler.setWinter(false);
        Object restored = channel.readOutbound();
        check(Arrays.equals(ids(restored), original), "online season/cleanup restores every original biome ID");
        check(Arrays.equals(nativeIds(restored, count * 2), original), "vanilla PalettedContainer decoder accepts restored global palette");
        Object single = biomePacket(4, -3, originalWire); channel.writeOutbound(single); channel.readOutbound();
        handler.setWinter(true); check(channel.readOutbound() != null, "biome updates remain cached and refreshable");
        Object pos = type("net.minecraft.world.level.ChunkPos").getConstructor(int.class, int.class).newInstance(4, -3);
        Object forget = type("net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket").getConstructor(type("net.minecraft.world.level.ChunkPos")).newInstance(pos);
        channel.writeOutbound(forget); channel.readOutbound(); handler.setWinter(false);
        check(channel.readOutbound() == null, "forgotten chunks are not refreshed or loaded");
        for (int distinct : new int[]{1, 2, 8, 9, 64}) {
            int[] input = new int[64]; for (int i = 0; i < 64; i++) input[i] = i % distinct;
            ByteBuf b = Unpooled.buffer(); BiomeWireCodec.writeBiomes(b, input, count, false, count);
            byte[] wire = new byte[b.readableBytes()]; b.readBytes(wire); b.release();
            byte[] mapped = BiomeWireCodec.biomes(wire, count, true);
            ByteBuf read = Unpooled.wrappedBuffer(mapped); int[] output = BiomeWireCodec.readBiomes(read); read.release();
            for (int i = 0; i < 64; i++) check(output[i] == input[i] + count, "palette shape " + distinct);
            check(Arrays.equals(nativePaletteIds(mapped, count * 2), output), "actual vanilla palette decode shape " + distinct);
        }
        channel.finishAndReleaseAll();
    }
    private static void climateExclusions() throws Exception {
        Object desert = biome(2.0f,false), jungle = biome(.95f,true), forest = biome(.7f,true), frozen = biome(-.5f,true);
        var policy = new SeasonClimatePolicy(SeasonClimatePolicy.HOT, SeasonClimatePolicy.COLD);
        var handler = new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()), Logger.getAnonymousLogger(), policy);
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.writeOutbound(registry(List.of(entry("minecraft:desert",Optional.of(desert)),entry("minecraft:jungle",Optional.of(jungle)),
            entry("minecraft:forest",Optional.of(forest)),entry("minecraft:snowy_plains",Optional.of(frozen)))));
        Object encoded = wireRoundTrip(channel.readOutbound());
        List<?> registered = (List<?>)call(encoded,"entries");
        check(((Optional<?>)call(registered.get(0),"data")).orElseThrow().equals(desert), "Excluded desert payload remains exactly vanilla");
        check(((Optional<?>)call(registered.get(1),"data")).orElseThrow().equals(jungle), "Excluded jungle payload remains exactly vanilla");
        int[] original = new int[64]; for (int i = 0; i < 64; i++) original[i] = i % 4;
        ByteBuf packed = Unpooled.buffer(); BiomeWireCodec.writeBiomes(packed, original,4,false,4);
        byte[] wire = new byte[packed.readableBytes()]; packed.readBytes(wire); packed.release();
        ByteBuf section = Unpooled.buffer(); section.writeShort(0).writeShort(0).writeByte(0).writeByte(0).writeBytes(wire);
        byte[] source = new byte[section.readableBytes()]; section.readBytes(source); section.release();
        Object originalPacket = chunk(chunkData(source),0,0);
        channel.writeOutbound(originalPacket); channel.readOutbound();
        handler.setWinter(true); Object winter = channel.readOutbound(); int[] winterIds = ids(winter);
        for (int i = 0; i < 64; i++) check(winterIds[i] == original[i] + (original[i] >= 2 ? 4 : 0), "Mixed-biome winter routing preserves hot cell " + i);
        check(Arrays.equals(winterIds,nativeIds(winter,8)), "Vanilla decoder accepts mixed originals and winter aliases");
        check(!((boolean)call(decodeBiome(desert),"hasPrecipitation")), "Excluded desert keeps vanilla no precipitation");
        handler.setClimate(true,new SeasonClimatePolicy(List.of("forest"),List.of()));
        int[] reloaded = ids(channel.readOutbound());
        for (int i = 0; i < 64; i++) check(reloaded[i] == original[i] + (original[i] != 2 ? 4 : 0), "Policy reload refreshes cached cell " + i);
        handler.setWinter(false); check(Arrays.equals(ids(channel.readOutbound()),original), "Season exit restores all mixed original IDs");
        channel.finishAndReleaseAll();
    }
    private static Object biome(float temperature, boolean precipitation) throws Exception {
        Class<?> compound = type("net.minecraft.nbt.CompoundTag"); Object tag = compound.getConstructor().newInstance(), effects = compound.getConstructor().newInstance();
        compound.getMethod("putFloat", String.class, float.class).invoke(tag, "temperature", temperature);
        compound.getMethod("putFloat", String.class, float.class).invoke(tag, "downfall", 0.0f);
        compound.getMethod("putBoolean", String.class, boolean.class).invoke(tag, "has_precipitation", precipitation);
        compound.getMethod("putInt", String.class, int.class).invoke(effects, "water_color", 4159204);
        compound.getMethod("putInt", String.class, int.class).invoke(effects, "grass_color", 1193046);
        compound.getMethod("put", String.class, type("net.minecraft.nbt.Tag")).invoke(tag, "effects", effects);
        return tag;
    }
    private static void seasonalColors() throws Exception {
        var config = new YamlConfiguration();
        for (Season season : Season.values()) config.set("palettes.temperate." + season.key(),
            List.of("83B957", season == Season.AUTUMN ? "D98637" : "68AE48"));
        var policy = new SeasonClimatePolicy(SeasonClimatePolicy.HOT, SeasonClimatePolicy.COLD);
        var visuals = new SeasonVisualResolver(config, policy);
        YamlConfiguration ordered = new YamlConfiguration();
        for (String family : List.of("first", "second")) {
            ordered.set("families." + family, List.of("forest"));
            for (Season season : Season.values()) ordered.set("palettes." + family + "." + season.key(),
                List.of("83B957", family.equals("first") ? "AA6633" : "DD9933"));
        }
        for (Season season : Season.values()) ordered.set("palettes.temperate." + season.key(), List.of("83B957", "D98637"));
        var before = new SeasonVisualResolver(ordered, policy);
        ordered.set("families", null); ordered.set("families.second", List.of("forest")); ordered.set("families.first", List.of("forest"));
        var after = new SeasonVisualResolver(ordered, policy);
        check(!before.equals(after) && !before.fingerprint().equals(after.fingerprint()), "Overlapping family order changes require a new client palette");
        check(before.resolve(Season.AUTUMN,"minecraft:forest").foliageRgb()!=after.resolve(Season.AUTUMN,"minecraft:forest").foliageRgb(), "Palette selection honours family priority");
        var handler = new NativeSnowPresentation.ClimateHandler(new PaperBiomePackets(Map.of()), Logger.getAnonymousLogger(), policy, () -> visuals);
        var channel = new EmbeddedChannel(handler);
        Object forest = biome(.7f, true), desert = biome(2f, false), frozen = biome(-.5f, true);
        Object copy = call(forest, "copy");
        channel.writeOutbound(registry(List.of(entry("minecraft:forest", Optional.of(forest)),
            entry("minecraft:desert", Optional.of(desert)), entry("minecraft:snowy_plains", Optional.of(frozen)))));
        List<?> entries = (List<?>) call(wireRoundTrip(channel.readOutbound()), "entries");
        check(entries.size() == 3*PaperBiomePackets.COLOR_BANKS, "All fixed seasonal stages fit the configured client registry");
        check(forest.equals(copy), "Color preparation never mutates the authoritative biome payload");
        for (Season season : Season.values()) {
            Object colored = decodeBiome(((Optional<?>) call(entries.get((2 + season.ordinal()) * 3), "data")).orElseThrow());
            var expected = visuals.resolve(season, "minecraft:forest");
            check((int) colored.getClass().getMethod("getGrassColor", double.class, double.class).invoke(colored, 0d, 0d) == expected.grassRgb(), "Vanilla biome codec applies configured grass for " + season);
            check((int) call(colored, "getFoliageColor") == expected.foliageRgb(), "Vanilla biome codec applies configured leaf tint for " + season);
            check((int) call(colored, "getDryFoliageColor") == expected.foliageRgb(), "Dry-foliage tint matches configured leaves for " + season);
            check((float) call(colored, "getBaseTemperature") == .7f, "Color aliases preserve original climate for " + season);
        }
        int[] original = new int[64]; for (int i=0;i<64;i++) original[i]=i%3;
        ByteBuf packed=Unpooled.buffer(); BiomeWireCodec.writeBiomes(packed,original,3,false,3);
        byte[] wire=new byte[packed.readableBytes()];packed.readBytes(wire);packed.release();
        channel.writeOutbound(biomePacket(0,0,wire));channel.readOutbound(); // Biome-only updates do not introduce unseen chunks into the cache.
        ByteBuf section=Unpooled.buffer();section.writeShort(0).writeShort(0).writeByte(0).writeByte(0).writeBytes(wire);
        byte[] source=new byte[section.readableBytes()];section.readBytes(source);section.release();
        channel.writeOutbound(chunk(chunkData(source),0,0));channel.readOutbound();
        handler.setPresentation(false,Season.AUTUMN,policy);
        Object autumn=channel.readOutbound();int[] autumnIds=ids(autumn);
        for(int i=0;i<64;i++)check(autumnIds[i]==original[i]+(original[i]==1?0:12),"Autumn tint routing preserves hot biomes "+i);
        check(Arrays.equals(autumnIds,nativeIds(autumn,entries.size())),"Vanilla palette accepts the enlarged seasonal registry");
        handler.setPresentation(true,Season.WINTER,policy);
        Object winter=channel.readOutbound();int[] winterIds=ids(winter);
        for(int i=0;i<64;i++)check(winterIds[i]==original[i]+(original[i]==1?0:18),"Winter color/native snow routing preserves hot biomes "+i);
        handler.setPresentation(false,Season.SUMMER,policy);
        int[] summerIds=ids(channel.readOutbound());
        for(int i=0;i<64;i++)check(summerIds[i]==original[i]+(original[i]==2?0:9),"Summer tint leaves cold biomes vanilla "+i);
        handler.setPresentation(false,null,policy);
        check(Arrays.equals(ids(channel.readOutbound()),original),"Disabling seasonal colors restores original IDs without loading terrain");
        for(int step=0;step<=dev.trueseasons.visual.SeasonColorFrame.PALETTE_STEPS;step++){
            var frame=new dev.trueseasons.visual.SeasonColorFrame(Season.SUMMER,Season.AUTUMN,step*32);
            int bank=PaperBiomePackets.colorBank(frame,false);
            Object staged=decodeBiome(((Optional<?>)call(entries.get(bank*3),"data")).orElseThrow());
            check((int)staged.getClass().getMethod("getGrassColor",double.class,double.class).invoke(staged,0d,0d)==visuals.resolve(frame,"minecraft:forest").grassRgb(),"Native intermediate palette matches shared resolver at step "+step);
            handler.setPresentation(false,Season.AUTUMN,policy,frame);
            int[] stagedIds=ids(channel.readOutbound());
            for(int i=0;i<64;i++)check(stagedIds[i]==original[i]+(original[i]==1||step==0&&original[i]==2?0:3*bank),"Intermediate mapping preserves source and target climate exclusions at step "+step);
            handler.setPresentation(false,Season.AUTUMN,policy,frame);check(channel.readOutbound()==null,"Unchanged stage emits no extra biome update");
            var coldFrame=new dev.trueseasons.visual.SeasonColorFrame(Season.AUTUMN,Season.WINTER,step*32);
            Object cold=decodeBiome(((Optional<?>)call(entries.get(PaperBiomePackets.colorBank(coldFrame,true)*3),"data")).orElseThrow());
            check((float)call(cold,"getBaseTemperature")==-1f,"Every winter stage retains native cold precipitation "+step);
        }
        // Test global-palette width separately: enlarged registries can cross several bit boundaries.
        int[] many=new int[64];for(int i=0;i<64;i++)many[i]=i%12;
        packed=Unpooled.buffer();BiomeWireCodec.writeBiomes(packed,many,12,false,12);
        byte[] direct=new byte[packed.readableBytes()];packed.readBytes(direct);packed.release();
        byte[] mapped=BiomeWireCodec.biomes(direct,12,84,id->id+48);
        int[] decoded=nativePaletteIds(mapped,84);
        for(int i=0;i<64;i++)check(decoded[i]==many[i]+48,"Native decoder reads seasonal global palette cell "+i);
        channel.finishAndReleaseAll();
    }
    private static void registryCaching()throws Exception{
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        for(Season season:Season.values())config.set("palettes.temperate."+season.key(),List.of("83B957","D98637"));
        var resolver=new SeasonVisualResolver(config);Object source=biome(.7f,true),original=call(source,"copy");
        var bridge=new PaperBiomePackets(Map.of("minecraft:forest",source));
        Object packet=registry(List.of(entry("minecraft:forest",Optional.of(source))));
        var first=bridge.registry(packet,resolver);
        Object alias=((Optional<?>)call(((List<?>)call(first.packet(),"entries")).get(2),"data")).orElseThrow();
        Object effects=alias.getClass().getMethod("getCompoundOrEmpty",String.class).invoke(alias,"effects");
        effects.getClass().getMethod("putInt",String.class,int.class).invoke(effects,"grass_color",0);
        var second=bridge.registry(registry(List.of(entry("minecraft:forest",Optional.empty()))),resolver);
        List<?> entries=(List<?>)call(second.packet(),"entries");
        check(((Optional<?>)call(entries.getFirst(),"data")).isEmpty(),"Cache retains each connection's original known-pack encoding");
        Object cached=((Optional<?>)call(entries.get(2),"data")).orElseThrow();
        check((int)decodeBiome(cached).getClass().getMethod("getGrassColor",double.class,double.class).invoke(decodeBiome(cached),0d,0d)==0x83B957,"Editing one outgoing alias never mutates the shared template");
        check(source.equals(original),"Caching and independent client aliases leave authoritative NBT unchanged");
        source.getClass().getMethod("putFloat",String.class,float.class).invoke(source,"temperature",.3f);
        var changed=bridge.registry(packet,resolver);Object revised=((Optional<?>)call(((List<?>)call(changed.packet(),"entries")).get(2),"data")).orElseThrow();
        check((float)call(decodeBiome(revised),"getBaseTemperature")==.3f,"Changed source biome NBT invalidates the shared template");
        config.set("palettes.temperate.spring",List.of("123456","654321"));
        var recolored=bridge.registry(packet,new SeasonVisualResolver(config));
        Object updated=((Optional<?>)call(((List<?>)call(recolored.packet(),"entries")).get(2),"data")).orElseThrow();Object biome=decodeBiome(updated);
        check((int)biome.getClass().getMethod("getGrassColor",double.class,double.class).invoke(biome,0d,0d)==0x123456,"Changed visual settings invalidate the shared template");
        config.set("transition.opening-days",0);var immediate=new SeasonVisualResolver(config);
        check(bridge.registry(packet,immediate).registrySize()==7,"Disabling gradual colors omits all unused intermediate aliases");
        check(!immediate.equals(resolver),"Changing the available alias banks requires registry reconfiguration");
        config.set("enabled",false);
        check(bridge.registry(packet,new SeasonVisualResolver(config)).registrySize()==2,"Disabling seasonal colors retains only the original and cold climate banks");
    }
    private static Object entry(String id, Optional<?> data) throws Exception { return type("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry").getConstructor(type("net.minecraft.resources.Identifier"), Optional.class).newInstance(identifier(id), data); }
    private static Object registry(List<?> entries) throws Exception {
        Object key = type("net.minecraft.resources.ResourceKey").getMethod("createRegistryKey", type("net.minecraft.resources.Identifier")).invoke(null, identifier("minecraft:worldgen/biome"));
        return type("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket").getConstructor(type("net.minecraft.resources.ResourceKey"), List.class).newInstance(key, entries);
    }
    private static Object wireRoundTrip(Object packet) throws Exception {
        Object codec = packet.getClass().getField("STREAM_CODEC").get(null); ByteBuf raw = Unpooled.buffer();
        Object friendly = type("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class).newInstance(raw);
        Class<?> streamCodec = type("net.minecraft.network.codec.StreamCodec");
        try { streamCodec.getMethod("encode", Object.class, Object.class).invoke(codec, friendly, packet); return streamCodec.getMethod("decode", Object.class).invoke(codec, friendly); }
        finally { raw.release(); }
    }
    private static Object decodeBiome(Object tag) throws Exception {
        Object codec = type("net.minecraft.world.level.biome.Biome").getField("NETWORK_CODEC").get(null), ops = type("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
        Object result = type("com.mojang.serialization.Codec").getMethod("parse", type("com.mojang.serialization.DynamicOps"), Object.class).invoke(codec, ops, tag);
        return type("com.mojang.serialization.DataResult").getMethod("getOrThrow").invoke(result);
    }
    private static Object chunkData(byte[] data) throws Exception {
        Constructor<?> c = type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData").getDeclaredConstructor(Map.class, byte[].class, List.class); c.setAccessible(true); return c.newInstance(Map.of(), data, List.of());
    }
    private static Object chunk(Object data, int x, int z) throws Exception { return type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket").getConstructor(int.class, int.class, data.getClass(), type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData")).newInstance(x, z, data, null); }
    private static Object biomePacket(int x, int z, byte[] wire) throws Exception {
        Object pos = type("net.minecraft.world.level.ChunkPos").getConstructor(int.class, int.class).newInstance(x, z);
        Object data = type("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData").getConstructor(pos.getClass(), byte[].class).newInstance(pos, wire);
        return type("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket").getConstructor(List.class).newInstance(List.of(data));
    }
    private static byte[] buffer(Object data) throws Exception { Field f = data.getClass().getDeclaredField("buffer"); f.setAccessible(true); return (byte[]) f.get(data); }
    private static int[] ids(Object packet) throws Exception {
        Object data = ((List<?>) call(packet, "chunkBiomeData")).getFirst(); ByteBuf b = Unpooled.wrappedBuffer((byte[]) call(data, "buffer"));
        try { return BiomeWireCodec.readBiomes(b); } finally { b.release(); }
    }
    private static int[] nativeIds(Object packet, int registrySize) throws Exception {
        Object data = ((List<?>) call(packet, "chunkBiomeData")).getFirst();
        return nativePaletteIds((byte[]) call(data, "buffer"), registrySize);
    }
    private static int[] nativePaletteIds(byte[] wire, int registrySize) throws Exception {
        Object mapper = type("net.minecraft.core.IdMapper").getConstructor().newInstance();
        Object[] values = new Object[registrySize];
        for (int i = 0; i < registrySize; i++) { values[i] = new Object(); mapper.getClass().getMethod("add", Object.class).invoke(mapper, values[i]); }
        Object strategy = type("net.minecraft.world.level.chunk.Strategy").getMethod("createForBiomes", type("net.minecraft.core.IdMap")).invoke(null, mapper);
        Object container = type("net.minecraft.world.level.chunk.PalettedContainer").getConstructor(Object.class, type("net.minecraft.world.level.chunk.Strategy")).newInstance(values[0], strategy);
        ByteBuf raw = Unpooled.wrappedBuffer(wire);
        try {
            Object friendly = type("net.minecraft.network.FriendlyByteBuf").getConstructor(ByteBuf.class).newInstance(raw);
            container.getClass().getMethod("read", friendly.getClass()).invoke(container, friendly);
            check(!raw.isReadable(), "vanilla decoder consumes exactly the packet data");
            int[] result = new int[64];
            for (int i = 0; i < 64; i++) { Object value = container.getClass().getMethod("get", int.class).invoke(container, i); result[i] = (int) mapper.getClass().getMethod("getId", Object.class).invoke(mapper, value); }
            return result;
        } finally { raw.release(); }
    }
    private static Object identifier(String id) throws Exception { return type("net.minecraft.resources.Identifier").getMethod("parse", String.class).invoke(null, id); }
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static void check(boolean condition, String description) { assertions++; if (!condition) throw new AssertionError(description); }
}
