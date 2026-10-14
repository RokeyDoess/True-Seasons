package dev.trueseasons.integration;

import dev.trueseasons.api.SeasonVisualProfile;
import dev.trueseasons.visual.SeasonalVisualState;
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
    private final Map<Object, Object[]> originals = Collections.synchronizedMap(new IdentityHashMap<>());
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
                Object passes = original.get();
                if (!closed) try { for (Object pass : (Collection<?>) passes) if (blockPassClass.isInstance(pass)) install(pass); }
                catch (ReflectiveOperationException | RuntimeException unsupported) { failure = unsupported.getMessage(); }
                return passes;
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
