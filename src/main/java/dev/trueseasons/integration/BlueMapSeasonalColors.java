package dev.trueseasons.integration;

import dev.trueseasons.api.SeasonVisualProfile;
import dev.trueseasons.visual.SeasonalVisualState;
import dev.trueseasons.visual.AutumnTreeColors;
import dev.trueseasons.api.Season;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Capability-checked, map-local BlueMap 5 tint hook. No resource packs or biome data are edited. */
final class BlueMapSeasonalColors implements AutoCloseable {
    private static final Set<String> GRASS = Set.of("grass_block", "grass", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern",
            "bamboo", "sugar_cane", "pink_petals", "bush", "wildflowers");
    // Fixed spruce/birch tints and already-coloured cherry/pale-oak/azalea textures retain native behaviour.
    private static final Set<String> LEAVES = Set.of("oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine", "leaf_litter");
    private final UUID world;
    private final Object manager;
    private final Field passesField, blockRendererField, renderersField, calculatorField;
    private final ThreadLocal<?> original;
    private final ThreadLocal<Object> replacement;
    private final Class<?> blockPassClass, calculatorClass;
    private final Object defaultRenderer;
    private final Method getRenderer;
    private final MethodHandle blockId, accessBlockState, keyValue, keyNamespace, blockBiome, biomeKey, colorSet;
    private final Map<Object, Object[]> originals = Collections.synchronizedMap(new WeakHashMap<>());
    private final Class<?> renderPassClass, worldClass, chunkClass;
    private final Method chunkAt, chunkState, chunkGenerated, stateProperties;
    private final MethodHandle chunkAtHandle, chunkStateHandle, chunkGeneratedHandle;
    private final Constructor<?> stateConstructor;
    private final Class<?> paletteChunkClass;
    private final Field chunkSectionsField, sectionPaletteField;
    private final Map<AutumnTreeColors.Appearance,Object> leafKeys = new EnumMap<>(AutumnTreeColors.Appearance.class);
    private record Palette(SeasonalVisualState state, Map<String, Optional<SeasonVisualProfile>> cache) {}
    private volatile Palette palette = new Palette(null, new ConcurrentHashMap<>());
    private volatile boolean closed;
    private volatile String failure;

    BlueMapSeasonalColors(Object nativeMap, UUID world) throws ReflectiveOperationException {
        this.world = world;
        ClassLoader loader = nativeMap.getClass().getClassLoader();
        manager = nativeMap.getClass().getMethod("getHiresModelManager").invoke(nativeMap);
        passesField = field(manager.getClass(), "renderPasses");
        if (passesField.getType() != ThreadLocal.class) throw new NoSuchFieldException("BlueMap renderPasses is not thread-local");
        original = (ThreadLocal<?>) passesField.get(manager);
        blockPassClass = Class.forName("de.bluecolored.bluemap.core.map.hires.block.BlockRenderPass", false, loader);
        Class<?> rendererClass = Class.forName("de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer", false, loader);
        blockRendererField = field(blockPassClass, "blockRenderer");
        renderersField = field(blockRendererField.getType(), "blockRenderers");
        calculatorField = field(rendererClass, "blockColorCalculator"); calculatorClass = calculatorField.getType();
        Class<?> rendererType = Class.forName("de.bluecolored.bluemap.core.map.hires.block.BlockRendererType", false, loader);
        defaultRenderer = rendererType.getField("DEFAULT").get(null);
        getRenderer = renderersField.getType().getMethod("get", Object.class);
        Class<?> access = Class.forName("de.bluecolored.bluemap.core.world.block.BlockAccess", false, loader);
        Class<?> block = Class.forName("de.bluecolored.bluemap.core.world.BlockState", false, loader);
        Class<?> key = Class.forName("de.bluecolored.bluemap.core.util.Key", false, loader);
        renderPassClass = Class.forName("de.bluecolored.bluemap.core.map.hires.RenderPass", false, loader);
        worldClass = Class.forName("de.bluecolored.bluemap.core.world.World", false, loader);
        chunkClass = Class.forName("de.bluecolored.bluemap.core.world.Chunk", false, loader);
        chunkAt = worldClass.getMethod("getChunkAtBlock", int.class, int.class);
        chunkState = chunkClass.getMethod("getBlockState", int.class, int.class, int.class);
        chunkGenerated = chunkClass.getMethod("isGenerated");
        chunkAtHandle = MethodHandles.publicLookup().unreflect(chunkAt).asType(MethodType.methodType(Object.class,Object.class,int.class,int.class));
        chunkStateHandle = MethodHandles.publicLookup().unreflect(chunkState).asType(MethodType.methodType(Object.class,Object.class,int.class,int.class,int.class));
        chunkGeneratedHandle = MethodHandles.publicLookup().unreflect(chunkGenerated).asType(MethodType.methodType(boolean.class,Object.class));
        stateProperties = block.getMethod("getProperties"); stateConstructor = block.getConstructor(key, Map.class);
        Class<?> checkedChunk = null; Field checkedSections = null, checkedPalette = null;
        try {
            checkedChunk = Class.forName("de.bluecolored.bluemap.core.world.mca.chunk.Chunk_1_18",false,loader);
            checkedSections = field(checkedChunk,"sections");
            checkedPalette = field(checkedSections.getType().getComponentType(),"blockPalette");
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            // Optional optimization only. An unfamiliar reader still uses normal block access.
            checkedChunk = null; checkedSections = null; checkedPalette = null;
        }
        paletteChunkClass = checkedChunk; chunkSectionsField = checkedSections; sectionPaletteField = checkedPalette;
        for (var appearance : AutumnTreeColors.Appearance.values()) if (appearance.blockKey() != null)
            leafKeys.put(appearance, key.getConstructor(String.class).newInstance(appearance.blockKey()));
        Class<?> biome = Class.forName("de.bluecolored.bluemap.core.world.biome.Biome", false, loader);
        blockId = handle(block.getMethod("getId")); accessBlockState = handle(access.getMethod("getBlockState"));
        keyValue = handle(key.getMethod("getValue")); keyNamespace = handle(key.getMethod("getNamespace"));
        blockBiome = handle(access.getMethod("getBiome")); biomeKey = handle(biome.getMethod("getKey"));
        colorSet = MethodHandles.publicLookup().unreflect(calculatorClass.getMethod("getBlockColor", access, block,
                Class.forName("de.bluecolored.bluemap.core.util.math.Color", false, loader)).getReturnType().getMethod("set", int.class, boolean.class))
                .asType(MethodType.methodType(Object.class, Object.class, int.class, boolean.class));
        // Each worker owns its calculators. Initialize and alter them only on that worker.
        replacement = new ThreadLocal<>() {
            @Override protected Object initialValue() {
                Collection<?> passes = (Collection<?>) original.get();
                List<Object> wrapped = new ArrayList<>();
                try { for (Object pass : passes) {
                    if (blockPassClass.isInstance(pass)) {
                        if (!closed) install(pass);
                        wrapped.add(Proxy.newProxyInstance(loader, new Class<?>[]{renderPassClass}, new Pass(pass)));
                    } else wrapped.add(pass);
                } }
                catch (ReflectiveOperationException | RuntimeException unsupported) { failure = unsupported.getMessage(); }
                return wrapped.size() == passes.size() ? List.copyOf(wrapped) : passes;
            }
        };
        // Validate the actual installed renderer before installing on other workers.
        for (Object pass : (Collection<?>) original.get()) if (blockPassClass.isInstance(pass)) {
            Object renderer = getRenderer.invoke(renderersField.get(blockRendererField.get(pass)), defaultRenderer);
            if (!rendererClass.isInstance(renderer)) throw new ReflectiveOperationException("BlueMap default renderer has no supported tint calculator");
        }
        passesField.set(manager, replacement);
    }
    private static MethodHandle handle(Method method) throws IllegalAccessException {
        return MethodHandles.publicLookup().unreflect(method).asType(MethodType.methodType(Object.class, Object.class));
    }
    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field result = type.getDeclaredField(name); result.setAccessible(true); return result;
    }
    void update(SeasonalVisualState next) {
        if (!Objects.equals(palette.state, next)) palette = new Palette(next, new ConcurrentHashMap<>());
    }
    String failure() { return failure; }
    /** Native renderer caches expire after one idle minute. Check each tile, not just each worker. */
    private final class Pass implements InvocationHandler {
        private final Object pass;
        Pass(Object pass) { this.pass = pass; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("render") && !closed) {
                try { install(pass); }
                catch (ReflectiveOperationException | RuntimeException unsupported) {
                    failure = unsupported.getMessage(); throw unsupported;
                }
                SeasonalVisualState captured = palette.state;
                if (captured != null && captured.active(world) && captured.season() == Season.AUTUMN && captured.resolver().autumnTrees()) {
                    args = args.clone(); args[0] = new AutumnWorld(args[0], captured.seed(world)).proxy;
                }
            }
            try { return method.invoke(pass, args); }
            catch (InvocationTargetException thrown) { throw thrown.getCause(); }
        }
    }
    static Object nativePass(Object pass) {
        if (Proxy.isProxyClass(pass.getClass()) && Proxy.getInvocationHandler(pass) instanceof BlueMapSeasonalColors.Pass wrapper) return wrapper.pass;
        return pass;
    }
    /** Tile-scoped facade reads only BlueMap's saved-world reader. No Bukkit calls or world writes. */
    private final class AutumnWorld {
        final Object source, proxy;
        final Map<Long,Object> originals = new LinkedHashMap<>();
        final Map<Object,Object> chunks = new IdentityHashMap<>();
        final Map<Object,Object[]> states = new IdentityHashMap<>();
        final Map<Object,AutumnTreeColors.Kind> kinds = new IdentityHashMap<>();
        long lastCoordinate = Long.MIN_VALUE;
        Object lastChunk;
        final AutumnTreeColors.Session trees;
        AutumnWorld(Object source, long seed) {
            this.source = source;
            trees = new AutumnTreeColors.Session(seed, (x,y,z) -> {
                try {
                    Object chunk = originalChunk(x,z);
                    if (!(boolean) chunkGeneratedHandle.invokeExact(chunk)) return AutumnTreeColors.Kind.UNKNOWN;
                    Object state = (Object) chunkStateHandle.invokeExact(chunk,x,y,z);
                    return kind(state);
                } catch (Throwable failure) { throw new IllegalStateException("Cannot sample saved autumn tree", failure); }
            });
            proxy = Proxy.newProxyInstance(worldClass.getClassLoader(), new Class<?>[]{worldClass}, (object,method,args) -> {
                if (method.getName().equals("getChunkAtBlock")) return wrapChunk(originalChunk((int)args[0],(int)args[1]));
                if (method.getName().equals("getChunk")) return wrapChunk(originalChunk((int)args[0]<<4,(int)args[1]<<4));
                Object result = call(method,source,args);
                return chunkClass.isInstance(result) ? wrapChunk(result) : result;
            });
        }
        Object originalChunk(int x,int z) throws Throwable {
            long coordinate = ((long)(x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL);
            // Face culling and ambient occlusion revisit the same chunk for most voxels.
            // Avoid both native Caffeine access and boxed coordinate allocation there.
            if (coordinate == lastCoordinate && lastChunk != null) return lastChunk;
            Object chunk = originals.get(coordinate);
            if (chunk == null) {
                chunk = (Object) chunkAtHandle.invokeExact(source,x,z);
                if (originals.size() >= 256) originals.remove(originals.keySet().iterator().next());
                originals.put(coordinate,chunk);
            }
            lastCoordinate = coordinate; lastChunk = chunk; return chunk;
        }
        AutumnTreeColors.Kind kind(Object state) throws Throwable {
            AutumnTreeColors.Kind kind = kinds.get(state);
            if (kind != null) return kind;
            kind = AutumnTreeColors.kind(((Object)blockId.invokeExact(state)).toString());
            if (kinds.size() >= 4096) kinds.clear();
            kinds.put(state,kind); return kind;
        }
        Object wrapChunk(Object chunk) throws Throwable {
            Object wrapped = chunks.get(chunk);
            if (wrapped != null) return wrapped;
            if (chunks.size() >= 256) chunks.clear();
            if (!mayHaveAutumnLeaves(chunk)) { chunks.put(chunk,chunk); return chunk; }
            wrapped = Proxy.newProxyInstance(chunkClass.getClassLoader(),new Class<?>[]{chunkClass},(object,method,args) -> {
                if (!method.getName().equals("getBlockState")) return call(method,chunk,args);
                Object result = (Object) chunkStateHandle.invokeExact(chunk,(int)args[0],(int)args[1],(int)args[2]);
                if (!kind(result).leaves()) return result;
                var appearance = trees.appearanceAt((int)args[0],(int)args[1],(int)args[2]);
                if (appearance == AutumnTreeColors.Appearance.ORIGINAL) return result;
                Object[] painted = states.get(result);
                if (painted == null) {
                    if (states.size() >= 512) states.clear();
                    painted = new Object[AutumnTreeColors.Appearance.values().length]; states.put(result,painted);
                }
                if (painted[appearance.ordinal()] == null) painted[appearance.ordinal()] = stateConstructor.newInstance(leafKeys.get(appearance),stateProperties.invoke(result));
                return painted[appearance.ordinal()];
            });
            chunks.put(chunk,wrapped); return wrapped;
        }
        boolean mayHaveAutumnLeaves(Object chunk) throws Throwable {
            if (paletteChunkClass == null || chunk.getClass() != paletteChunkClass) return true;
            try {
                for (Object section : (Object[])chunkSectionsField.get(chunk)) if (section != null)
                    for (Object state : (Object[])sectionPaletteField.get(section)) if (kind(state).leaves()) return true;
                return false;
            } catch (ReflectiveOperationException | RuntimeException unfamiliar) {
                // Fail open to ordinary read-only rendering if this optional internal layout changes.
                return true;
            }
        }
    }
    private static Object call(Method method,Object target,Object[] args) throws Throwable {
        try { return method.invoke(target,args); }
        catch (InvocationTargetException thrown) { throw thrown.getCause(); }
    }
    private void install(Object pass) throws ReflectiveOperationException {
        Object renderer = getRenderer.invoke(renderersField.get(blockRendererField.get(pass)), defaultRenderer);
        synchronized (originals) {
            if (closed || originals.containsKey(renderer)) return;
            Object nativeCalculator = calculatorField.get(renderer);
            Object calculator = Proxy.newProxyInstance(calculatorClass.getClassLoader(), new Class<?>[]{calculatorClass},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getBlockColor")) {
                            Palette captured = palette;
                            SeasonalVisualState current = captured.state;
                            if (!closed && current != null && current.active(world)) {
                                Object block = args.length == 3 ? args[1] : (Object) accessBlockState.invokeExact(args[0]);
                                Object key = (Object) blockId.invokeExact(block);
                                String namespace = (String) (Object) keyNamespace.invokeExact(key);
                                String value = (String) (Object) keyValue.invokeExact(key);
                                int kind = tintKind(namespace, value);
                                if (kind != 0) {
                                    Object biome = (Object) blockBiome.invokeExact(args[0]);
                                    Object biomeId = (Object) biomeKey.invokeExact(biome);
                                    String biomeName = biomeId.toString();
                                    Optional<SeasonVisualProfile> profile = captured.cache.computeIfAbsent(biomeName, name ->
                                            current.resolver().applies(current.season(), name)
                                            ? Optional.of(current.resolver().resolve(current.season(), name)) : Optional.empty());
                                    if (profile.isPresent()) {
                                        Object color = args[args.length - 1];
                                        int rgb = kind == 1 ? profile.get().grassRgb() : profile.get().foliageRgb();
                                        // Native colormap colours are premultiplied. Multiplying a translucent
                                        // texture by a straight tint converts its map colour to straight alpha,
                                        // which ResourceModelRenderer cannot add to its lowres accumulator.
                                        return (Object) colorSet.invokeExact(color, rgb | 0xff000000, true);
                                    }
                                }
                            }
                        }
                        try { return method.invoke(nativeCalculator, args); }
                        catch (InvocationTargetException thrown) { throw thrown.getCause(); }
                    });
            calculatorField.set(renderer, calculator); originals.put(renderer, new Object[]{nativeCalculator, calculator});
        }
    }
    static int tintKind(String namespace, String block) {
        if (!namespace.equals("minecraft")) return 0;
        return GRASS.contains(block) ? 1 : LEAVES.contains(block) ? 2 : 0;
    }
    @Override public void close() {
        closed = true; palette = new Palette(null, Map.of());
        try {
            if (passesField.get(manager) == replacement) passesField.set(manager, original);
            synchronized (originals) {
                for (var entry : originals.entrySet())
                    if (calculatorField.get(entry.getKey()) == entry.getValue()[1]) calculatorField.set(entry.getKey(), entry.getValue()[0]);
                originals.clear();
            }
        } catch (ReflectiveOperationException failure) { this.failure = "Could not detach tint hook: " + failure.getMessage(); }
    }
}
