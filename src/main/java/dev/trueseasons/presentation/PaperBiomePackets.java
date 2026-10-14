package dev.trueseasons.presentation;

import io.netty.channel.Channel;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import dev.trueseasons.api.Season;
import dev.trueseasons.visual.SeasonVisualResolver;
import org.bukkit.Bukkit;
import io.papermc.paper.connection.PlayerCommonConnection;

/** Isolated, fail-fast adapter for Mojang-mapped Paper 26.3. No server registry or chunk is mutated. */
public final class PaperBiomePackets {
    private final Class<?> registryPacket, packedEntry, compound, chunkPacket, chunkData, biomePacket, biomeData, forgetPacket;
    private final Constructor<?> registryConstructor, entryConstructor, chunkConstructor, chunkDataConstructor, biomeConstructor, biomeDataConstructor;
    private final Field sectionBuffer, heightmaps, blockEntities, extraPackets;
    private final Map<String, Object> authoritative;

    public PaperBiomePackets() throws ReflectiveOperationException { this(null); }
    /** Dedicated packet tests supply authoritative encoded entries without starting a Minecraft server. */
    public PaperBiomePackets(Map<String, Object> authoritative) throws ReflectiveOperationException {
        registryPacket = type("net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket");
        packedEntry = type("net.minecraft.core.RegistrySynchronization$PackedRegistryEntry");
        compound = type("net.minecraft.nbt.CompoundTag");
        chunkPacket = type("net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket");
        chunkData = type("net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData");
        biomePacket = type("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket");
        biomeData = type("net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket$ChunkBiomeData");
        forgetPacket = type("net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket");
        registryConstructor = registryPacket.getConstructor(type("net.minecraft.resources.ResourceKey"), List.class);
        entryConstructor = packedEntry.getConstructor(type("net.minecraft.resources.Identifier"), Optional.class);
        chunkConstructor = chunkPacket.getConstructor(int.class, int.class, chunkData, type("net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData"));
        chunkDataConstructor = chunkData.getDeclaredConstructor(Map.class, byte[].class, List.class); chunkDataConstructor.setAccessible(true);
        biomeConstructor = biomePacket.getConstructor(List.class);
        biomeDataConstructor = biomeData.getConstructor(type("net.minecraft.world.level.ChunkPos"), byte[].class);
        sectionBuffer = field(chunkData, "buffer"); heightmaps = field(chunkData, "heightmaps");
        blockEntities = field(chunkData, "blockEntitiesData"); extraPackets = field(chunkData, "extraPackets");
        this.authoritative = authoritative == null ? packAuthoritativeBiomes() : Map.copyOf(authoritative);
    }

    public record RegistryResult(Object packet, int originalCount, List<String> biomeKeys, int registrySize) {}
    public RegistryResult registry(Object packet) throws ReflectiveOperationException {
        return registry(packet, null);
    }
    public RegistryResult registry(Object packet, SeasonVisualResolver visuals) throws ReflectiveOperationException {
        if (!registryPacket.isInstance(packet)) return null;
        Object key = call(packet, "registry");
        if (!call(key, "identifier").toString().equals("minecraft:worldgen/biome")) return null;
        List<?> entries = (List<?>) call(packet, "entries");
        List<Object> expanded = new ArrayList<>(entries);
        Set<String> identifiers = new HashSet<>(); for (Object entry : entries) identifiers.add(call(entry, "id").toString());
        for (Object entry : entries) {
            Object id = call(entry, "id");
            String aliasName = "trueseasons:winter/" + id.toString().replace(':', '/');
            if (identifiers.contains(aliasName)) throw new IllegalStateException("Reserved client biome alias already exists: " + aliasName);
            Optional<?> value = (Optional<?>) call(entry, "data");
            Object tag = value.orElse(null);
            if (tag == null) tag = authoritative.get(id.toString());
            if (tag == null || !compound.isInstance(tag)) throw new IllegalStateException("Cannot materialize biome " + id);
            Object cold = call(tag, "copy");
            compound.getMethod("putFloat", String.class, float.class).invoke(cold, "temperature", -1.0f);
            compound.getMethod("putBoolean", String.class, boolean.class).invoke(cold, "has_precipitation", true);
            compound.getMethod("putString", String.class, String.class).invoke(cold, "temperature_modifier", "none");
            Object alias = type("net.minecraft.resources.Identifier").getMethod("parse", String.class).invoke(null, aliasName);
            expanded.add(entryConstructor.newInstance(alias, Optional.of(cold)));
        }
        if (visuals != null) {
            // All season banks are delivered once during configuration. Switching
            // seasons then changes only the client's already-loaded biome palettes.
            for (int bank = 0; bank < 5; bank++) for (Object entry : entries) {
                String biome = call(entry, "id").toString();
                Season season = bank == 4 ? Season.WINTER : Season.values()[bank];
                String aliasName = "trueseasons:" + (bank == 4 ? "snow_color" : "color_" + season.key()) + "/" + biome.replace(':', '/');
                if (identifiers.contains(aliasName)) throw new IllegalStateException("Reserved client biome alias already exists: " + aliasName);
                Object tag = ((Optional<?>) call(entry, "data")).orElse(null);
                if (tag == null) tag = authoritative.get(biome);
                if (tag == null || !compound.isInstance(tag)) throw new IllegalStateException("Cannot materialize biome " + biome);
                Object colored = call(tag, "copy");
                Object effects = compound.getMethod("getCompoundOrEmpty", String.class).invoke(colored, "effects");
                effects = call(effects, "copy");
                var profile = visuals.resolve(season, biome);
                compound.getMethod("putInt", String.class, int.class).invoke(effects, "grass_color", profile.grassRgb());
                compound.getMethod("putInt", String.class, int.class).invoke(effects, "foliage_color", profile.foliageRgb());
                compound.getMethod("putInt", String.class, int.class).invoke(effects, "dry_foliage_color", profile.foliageRgb());
                compound.getMethod("putString", String.class, String.class).invoke(effects, "grass_color_modifier", "none");
                compound.getMethod("put", String.class, type("net.minecraft.nbt.Tag")).invoke(colored, "effects", effects);
                if (bank == 4) {
                    compound.getMethod("putFloat", String.class, float.class).invoke(colored, "temperature", -1.0f);
                    compound.getMethod("putBoolean", String.class, boolean.class).invoke(colored, "has_precipitation", true);
                    compound.getMethod("putString", String.class, String.class).invoke(colored, "temperature_modifier", "none");
                }
                Object alias = type("net.minecraft.resources.Identifier").getMethod("parse", String.class).invoke(null, aliasName);
                expanded.add(entryConstructor.newInstance(alias, Optional.of(colored)));
            }
        }
        List<String> biomeKeys = new ArrayList<>(); for (Object entry : entries) biomeKeys.add(call(entry, "id").toString());
        return new RegistryResult(registryConstructor.newInstance(key, List.copyOf(expanded)), entries.size(), List.copyOf(biomeKeys), expanded.size());
    }

    public boolean isChunk(Object packet) { return chunkPacket.isInstance(packet); }
    public boolean isBiomes(Object packet) { return biomePacket.isInstance(packet); }
    public boolean isForget(Object packet) { return forgetPacket.isInstance(packet); }
    public long chunkKey(Object packet) throws ReflectiveOperationException {
        return key((int) call(packet, "x"), (int) call(packet, "z"));
    }
    public long forgottenKey(Object packet) throws ReflectiveOperationException { return positionKey(call(packet, "pos")); }
    public long positionKey(Object pos) throws ReflectiveOperationException { return key((int) call(pos, "x"), (int) call(pos, "z")); }
    public record ChunkResult(Object packet, byte[] originalBiomes) {}
    public ChunkResult chunk(Object packet, int count, boolean winter) throws ReflectiveOperationException {
        return chunk(packet, count, winter, id -> true);
    }
    public ChunkResult chunk(Object packet, int count, boolean winter, IntPredicate winterBiome) throws ReflectiveOperationException {
        return chunk(packet, count, Math.multiplyExact(count, 2), id -> id + (winter && winterBiome.test(id) ? count : 0));
    }
    public ChunkResult chunk(Object packet, int count, int registrySize, IntUnaryOperator mapping) throws ReflectiveOperationException {
        Object old = call(packet, "chunkData");
        BiomeWireCodec.ChunkData mapped = BiomeWireCodec.chunk((byte[]) sectionBuffer.get(old), count, registrySize, mapping);
        Object copy = chunkDataConstructor.newInstance(heightmaps.get(old), mapped.sections(), blockEntities.get(old));
        extraPackets.set(copy, extraPackets.get(old));
        chunkData.getMethod("setReady", boolean.class).invoke(copy, true);
        return new ChunkResult(chunkConstructor.newInstance(call(packet, "x"), call(packet, "z"), copy, call(packet, "lightData")), mapped.biomes());
    }
    public record BiomeResult(Object packet, Map<Long, byte[]> originals) {}
    public BiomeResult biomes(Object packet, int count, boolean winter) throws ReflectiveOperationException {
        return biomes(packet, count, winter, id -> true);
    }
    public BiomeResult biomes(Object packet, int count, boolean winter, IntPredicate winterBiome) throws ReflectiveOperationException {
        return biomes(packet, count, Math.multiplyExact(count, 2), id -> id + (winter && winterBiome.test(id) ? count : 0));
    }
    public BiomeResult biomes(Object packet, int count, int registrySize, IntUnaryOperator mapping) throws ReflectiveOperationException {
        List<?> data = (List<?>) call(packet, "chunkBiomeData"); List<Object> copies = new ArrayList<>(); Map<Long, byte[]> originals = new HashMap<>();
        for (Object entry : data) {
            Object pos = call(entry, "pos"); byte[] buffer = (byte[]) call(entry, "buffer");
            originals.put(positionKey(pos), buffer.clone());
            copies.add(biomeDataConstructor.newInstance(pos, BiomeWireCodec.biomes(buffer, count, registrySize, mapping)));
        }
        return new BiomeResult(biomeConstructor.newInstance(List.copyOf(copies)), originals);
    }
    public Object refresh(Map<Long, byte[]> originals, int count, boolean winter) throws ReflectiveOperationException {
        return refresh(originals, count, winter, id -> true);
    }
    public Object refresh(Map<Long, byte[]> originals, int count, boolean winter, IntPredicate winterBiome) throws ReflectiveOperationException {
        return refresh(originals, count, Math.multiplyExact(count, 2), id -> id + (winter && winterBiome.test(id) ? count : 0));
    }
    public Object refresh(Map<Long, byte[]> originals, int count, int registrySize, IntUnaryOperator mapping) throws ReflectiveOperationException {
        List<Object> data = new ArrayList<>(); Constructor<?> posConstructor = type("net.minecraft.world.level.ChunkPos").getConstructor(int.class, int.class);
        for (var entry : originals.entrySet()) {
            Object pos = posConstructor.newInstance((int) (long) entry.getKey(), (int) (entry.getKey() >> 32));
            data.add(biomeDataConstructor.newInstance(pos, BiomeWireCodec.biomes(entry.getValue(), count, registrySize, mapping)));
        }
        return biomeConstructor.newInstance(List.copyOf(data));
    }
    public Channel channel(PlayerCommonConnection api) throws ReflectiveOperationException {
        Field listener = field(type("io.papermc.paper.connection.PaperCommonConnection"), "packetListener");
        Object packets = listener.get(api), connection = packets.getClass().getField("connection").get(packets);
        return (Channel) connection.getClass().getField("channel").get(connection);
    }

    private Map<String, Object> packAuthoritativeBiomes() throws ReflectiveOperationException {
        Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
        Object access = server.getClass().getMethod("registryAccess").invoke(server);
        Object nbtOps = type("net.minecraft.nbt.NbtOps").getField("INSTANCE").get(null);
        Object registryOps = type("net.minecraft.resources.RegistryOps").getMethod("create", type("com.mojang.serialization.DynamicOps"), type("net.minecraft.core.HolderLookup$Provider")).invoke(null, nbtOps, access);
        Map<String, Object> data = new HashMap<>();
        BiConsumer<Object, List<?>> collect = (key, entries) -> {
            try {
                if (call(key, "identifier").toString().equals("minecraft:worldgen/biome")) for (Object entry : entries)
                    data.put(call(entry, "id").toString(), ((Optional<?>) call(entry, "data")).orElseThrow());
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        };
        type("net.minecraft.core.RegistrySynchronization").getMethod("packRegistries", type("com.mojang.serialization.DynamicOps"), type("net.minecraft.core.RegistryAccess"), Set.class, BiConsumer.class).invoke(null, registryOps, access, Set.of(), collect);
        if (data.isEmpty()) throw new IllegalStateException("No authoritative biome climate registry available");
        return Map.copyOf(data);
    }
    static Object call(Object target, String method) throws ReflectiveOperationException { return target.getClass().getMethod(method).invoke(target); }
    private static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
    private static Field field(Class<?> owner, String name) throws NoSuchFieldException { Field result = owner.getDeclaredField(name); result.setAccessible(true); return result; }
    static long key(int x, int z) { return ((long) z << 32) | (x & 0xffffffffL); }
}
