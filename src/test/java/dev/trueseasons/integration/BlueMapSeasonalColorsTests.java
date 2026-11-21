package dev.trueseasons.integration;

import de.bluecolored.bluemap.core.map.hires.*;
import de.bluecolored.bluemap.core.map.hires.block.*;
import de.bluecolored.bluemap.core.map.hires.block.color.BlockColorCalculator;
import de.bluecolored.bluemap.core.resources.pack.PackVersion;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.*;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.BlockProperties;
import de.bluecolored.bluemap.core.world.DimensionType;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.biome.Biome;
import de.bluecolored.bluemap.core.world.block.BlockAccess;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.world.block.ExtendedBlock;
import com.flowpowered.math.vector.Vector3f;
import com.flowpowered.math.vector.Vector4f;
import dev.trueseasons.api.Season;
import dev.trueseasons.climate.SeasonClimatePolicy;
import dev.trueseasons.visual.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import org.bukkit.configuration.file.YamlConfiguration;

/** Uses the installed BlueMap classes and actual default renderer cache to prove tint-hook support. */
public final class BlueMapSeasonalColorsTests {
    public static final class Manager {
        final ResourcePack pack = new ResourcePack(new PackVersion(86, 0));
        final TextureGallery gallery = new TextureGallery();
        final RenderSettings settings = (RenderSettings) Proxy.newProxyInstance(RenderSettings.class.getClassLoader(), new Class<?>[]{RenderSettings.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : method.getReturnType() == float.class ? 0f : method.getReturnType() == int.class ? 0 : null);
        final ThreadLocal<Collection<RenderPass>> renderPasses = ThreadLocal.withInitial(() -> List.of(new BlockRenderPass(pack, gallery, settings)));
        Manager() throws Exception {
            // BlueMap 5.29's shipped resourceExtensions blockColors.json uses these fixed values.
            Path config = Files.createTempFile("trueseasons-native-leaf-tints-", ".json");
            try {
                Files.writeString(config, "{\"minecraft:birch_leaves\":8431445,\"minecraft:spruce_leaves\":6396257}");
                Object colors = field(pack, "blockColorsConfig"); colors.getClass().getMethod("load", Path.class).invoke(colors, config);
            } finally { Files.deleteIfExists(config); }
        }
    }
    public static final class MapFixture {
        final Manager manager;
        MapFixture() throws Exception { manager = new Manager(); }
        public Manager getHiresModelManager() { return manager; }
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static ResourceModelRenderer renderer(MapFixture fixture) throws Exception {
        ThreadLocal<?> passes = (ThreadLocal<?>) field(fixture.manager, "renderPasses");
        Object pass = BlueMapSeasonalColors.nativePass(((Collection<?>) passes.get()).iterator().next());
        Object renderer = field(pass, "blockRenderer"), cache = field(renderer, "blockRenderers");
        Object resourceRenderer = renderer.getClass().getDeclaredField("blockRenderers").getType().getMethod("get", Object.class).invoke(cache, BlockRendererType.DEFAULT);
        return (ResourceModelRenderer) resourceRenderer;
    }
    private static BlockColorCalculator calculator(MapFixture fixture) throws Exception { return renderer(fixture).getBlockColorCalculator(); }
    private static BlockAccess access(String biomeName, String blockName) {
        Biome biome = (Biome) Proxy.newProxyInstance(Biome.class.getClassLoader(), new Class<?>[]{Biome.class},
                (proxy, method, args) -> method.getName().equals("getKey") ? new Key(biomeName) : null);
        return (BlockAccess) Proxy.newProxyInstance(BlockAccess.class.getClassLoader(), new Class<?>[]{BlockAccess.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBiome" -> biome;
                    case "getBlockState" -> BlockState.fromString(blockName);
                    default -> null;
                });
    }
    private static int color(BlockColorCalculator calculator, String biome, String block) {
        BlockAccess access = access(biome, block);
        return calculator.getBlockColor(access, access.getBlockState(), new Color()).getInt() & 0xffffff;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void cutoutBlend(BlockColorCalculator calculator, String blockName) {
        Color tint = calculator.getBlockColor(access("minecraft:plains", blockName), new Color());
        check(tint.premultiplied && tint.a == 1f, "seasonal tint uses native premultiplied opaque-colour representation");
        // This is ResourceModelRenderer.createElementFace's lowres path: texture average,
        // multiply by tint, then add to the block accumulator before flattening its RGB.
        Color texture = new Color().set(0.4f, 0.3f, 0.2f, 0.5f, true);
        texture.multiply(tint);
        check(texture.premultiplied && texture.a == 0.5f, "tint preserves cutout texture premultiplication and alpha");
        Color accumulated = new Color().set(0, 0, 0, 0, true).add(texture);
        check(accumulated.a == 0.5f && accumulated.r > 0, "cutout texture can be accumulated without a blending error");
    }
    /** Runs the installed ResourceModelRenderer, not an imitation, on a tinted cutout face. */
    private static void renderCutout(MapFixture fixture, String blockName, int rgb) throws Exception {
        BufferedImage pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, 0xffffffff); pixels.setRGB(1, 0, 0xffffffff); pixels.setRGB(0, 1, 0xffffffff);
        Key textureKey = Key.minecraft("trueseasons_test_cutout");
        Texture texture = Texture.from(textureKey, pixels);
        check(texture.getColorPremultiplied().a > 0f && texture.getColorPremultiplied().a < 1f, "fixture exercises transparent texture pixels");
        fixture.manager.pack.getTextures().put(textureKey, texture); fixture.manager.gallery.put(textureKey, texture);
        ResourcePath<Texture> texturePath = new ResourcePath<>(textureKey); texturePath.setResource(texture);
        Face face = new Face(new Vector4f(0, 0, 16, 16), new TextureVariable(texturePath), null, 0, 0);
        Element element = new Element(Vector3f.ZERO, new Vector3f(16, 16, 16), Rotation.ZERO, false, 0, Map.of(Direction.UP, face));
        Model model = new Model(Map.of(), new Element[]{element}, false);
        ResourcePath<Model> modelPath = new ResourcePath<>(Key.minecraft("trueseasons_test_leaf_model")); modelPath.setResource(model);
        BlockNeighborhood block = new BlockNeighborhood(access("minecraft:plains", blockName), fixture.manager.pack,
                fixture.manager.settings, DimensionType.OVERWORLD) {
            @Override public LightData getLightData() { return new LightData(15, 0); }
            @Override public ExtendedBlock getNeighborBlock(int x, int y, int z) { return this; }
            @Override public BlockProperties getProperties() { return BlockProperties.DEFAULT; }
            @Override public boolean isRemoveIfCave() { return false; }
        };
        ArrayTileModel geometry = new ArrayTileModel(2);
        Color lowres = new Color().set(0, 0, 0, 0, true);
        renderer(fixture).render(block, new Variant(modelPath), new TileModelView(geometry), lowres);
        check(geometry.size() == 2, "actual renderer emits both tinted cutout face triangles");
        check(lowres.a > 0f && lowres.a < 1f, "actual lowres renderer preserves texture alpha");
        check(Math.abs(lowres.r - ((rgb >> 16) & 255) / 255f) < 0.00001f
                && Math.abs(lowres.g - ((rgb >> 8) & 255) / 255f) < 0.00001f
                && Math.abs(lowres.b - (rgb & 255) / 255f) < 0.00001f, "actual lowres renderer writes shared seasonal RGB");
    }
    public static void main(String[] args) throws Exception {
        YamlConfiguration config = new YamlConfiguration(); config.set("enabled", true);
        for (Season season : Season.values()) config.set("palettes.temperate." + season.key(), List.of("83B957", "D98637"));
        SeasonVisualResolver resolver = new SeasonVisualResolver(config, new SeasonClimatePolicy(SeasonClimatePolicy.HOT, SeasonClimatePolicy.COLD));
        UUID world = UUID.randomUUID(); MapFixture fixture = new MapFixture();
        BlockColorCalculator nativeCalculator = calculator(fixture);
        BlueMapSeasonalColors colors = new BlueMapSeasonalColors(fixture, world);
        colors.update(new SeasonalVisualState(resolver, Season.AUTUMN, Set.of(world)));
        BlockColorCalculator autumn = calculator(fixture);
        check(autumn != nativeCalculator, "default renderer receives map-local calculator");
        check(color(autumn, "minecraft:plains", "minecraft:oak_leaves") == 0xD98637, "oak leaves receive shared autumn foliage tint");
        check((autumn.getBlockColor(access("minecraft:plains", "minecraft:oak_leaves"), new Color()).getInt() & 0xffffff) == 0xD98637,
                "renderer two-argument default colour method receives seasonal tint");
        check(color(autumn, "minecraft:plains", "minecraft:grass_block") == 0x83B957, "grass receives shared grass tint");
        cutoutBlend(autumn, "minecraft:oak_leaves"); cutoutBlend(autumn, "minecraft:short_grass");
        renderCutout(fixture, "minecraft:oak_leaves", 0xD98637);
        renderCutout(fixture, "minecraft:short_grass", 0x83B957);
        check(color(autumn, "minecraft:plains", "minecraft:spruce_leaves") == 6396257, "actual fixed evergreen spruce RGB remains intact");
        check(color(autumn, "minecraft:plains", "minecraft:birch_leaves") == 8431445, "actual fixed birch RGB remains intact");
        check(color(autumn, "minecraft:plains", "minecraft:leaf_litter") == 0xD98637, "dry foliage shares native seasonal foliage palette");
        for (String original : List.of("spruce_leaves", "birch_leaves", "cherry_leaves", "pale_oak_leaves", "stone"))
            check(color(autumn, "minecraft:plains", "minecraft:" + original) == color(nativeCalculator, "minecraft:plains", "minecraft:" + original), original + " keeps native tint");
        colors.update(new SeasonalVisualState(resolver, Season.WINTER, Set.of(world)));
        check(color(autumn, "minecraft:desert", "minecraft:oak_leaves") == color(nativeCalculator, "minecraft:desert", "minecraft:oak_leaves"), "hot winter uses native colour");
        colors.update(new SeasonalVisualState(resolver, Season.SUMMER, Set.of(world)));
        check(color(autumn, "minecraft:snowy_plains", "minecraft:grass_block") == color(nativeCalculator, "minecraft:snowy_plains", "minecraft:grass_block"), "cold summer uses native colour");
        for(Season active:List.of(Season.AUTUMN,Season.WINTER,Season.SUMMER)){
            Season unused=active==Season.SUMMER?Season.WINTER:Season.SUMMER;
            var before=new SeasonalVisualState(resolver,active,Set.of(world));colors.update(before);
            String excluded=active==Season.SUMMER?"minecraft:snowy_plains":"minecraft:jungle";
            for(String block:List.of("minecraft:grass_block","minecraft:oak_leaves")){
                int land=color(autumn,"minecraft:plains",block),nativeBiome=color(autumn,excluded,block);
                config.set("palettes.temperate."+unused.key(),List.of("123456","ABCDEF"));
                var after=new SeasonalVisualState(new SeasonVisualResolver(config,new SeasonClimatePolicy(SeasonClimatePolicy.HOT,SeasonClimatePolicy.COLD)),active,Set.of(world));
                colors.update(after);
                check(color(autumn,"minecraft:plains",block)==land&&color(autumn,excluded,block)==nativeBiome,"Actual native calculator ignores unused palette in "+active+" for "+block);
                check(color(autumn,excluded,block)==color(nativeCalculator,excluded,block),"Exempt biome still delegates to native colours after inactive-season edit");
                check(before.fingerprint(world).equals(after.fingerprint(world)),"Unchanged native-rendered pixels preserve map work across unused "+unused+" palette edits");
                config.set("palettes.temperate."+unused.key(),List.of("83B957","D98637"));colors.update(before);
            }
        }
        colors.update(new SeasonalVisualState(resolver, Season.AUTUMN, Set.of()));
        check(color(autumn, "minecraft:plains", "minecraft:oak_leaves") == color(nativeCalculator, "minecraft:plains", "minecraft:oak_leaves"), "cleanup restores native tint immediately");
        colors.update(new SeasonalVisualState(resolver, Season.AUTUMN, Set.of(world)));
        CompletableFuture.runAsync(() -> {
            try { check(color(calculator(fixture), "minecraft:plains", "minecraft:oak_leaves") == 0xD98637, "new render worker receives tint hook"); }
            catch (Exception failure) { throw new CompletionException(failure); }
        }).join();
        check(colors.failure() == null, "installed BlueMap capability probe succeeds");
        colors.close();
        check(calculator(fixture) == nativeCalculator, "disable detaches original thread-local and owned calculators");
        check(color(autumn, "minecraft:plains", "minecraft:oak_leaves") == color(nativeCalculator, "minecraft:plains", "minecraft:oak_leaves"), "in-flight calculator delegates after disable");
        check(BlueMapSeasonalColors.tintKind("mod", "oak_leaves") == 0, "custom models stay native");
        try { new BlueMapSeasonalColors(new Object(), world); throw new AssertionError("unsupported native map silently accepted"); }
        catch (NoSuchMethodException expected) { /* Capability failure is explicit and cannot alter a map. */ }
        System.out.println("BlueMap seasonal colour tests passed");
    }
}
