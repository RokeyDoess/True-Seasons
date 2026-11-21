package dev.trueseasons;

import dev.trueseasons.snow.SeasonalSnowService;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Tests durable completion decisions using chunk metadata, without a running server. */
public final class SnowCompletionTests {
    private record Value(PersistentDataType<?, ?> type, Object value) {}
    private static int assertions;

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static Object identity(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "MetadataFixture";
            default -> throw new UnsupportedOperationException(name);
        };
    }

    private static void invalidCoverage(SeasonalSnowService snow, Chunk chunk, String version, String message) {
        try { snow.coveredColumns(chunk, version); throw new AssertionError(message); }
        catch (IOException expected) { assertions++; }
    }

    public static void main(String[] args) throws IOException {
        Plugin plugin = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] { Plugin.class },
                (proxy, method, values) -> switch (method.getName()) {
                    case "getName" -> "TrueSeasons";
                    case "namespace" -> "trueseasons";
                    default -> identity(proxy, method.getName(), values);
                });
        Map<NamespacedKey, Value> metadata = new HashMap<>();
        PersistentDataContainer pdc = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[] { PersistentDataContainer.class }, (proxy, method, values) -> {
                    return switch (method.getName()) {
                        case "has" -> {
                            Value value = metadata.get(values[0]);
                            yield value != null && (values.length == 1 || value.type.getPrimitiveType().equals(
                                    ((PersistentDataType<?, ?>) values[1]).getPrimitiveType()));
                        }
                        case "get", "getOrDefault" -> {
                            Value value = metadata.get(values[0]);
                            if (value == null) yield values.length == 3 ? values[2] : null;
                            if (!value.type.getPrimitiveType().equals(((PersistentDataType<?, ?>) values[1]).getPrimitiveType()))
                                throw new IllegalArgumentException("Stored primitive type differs from requested type");
                            yield value.value;
                        }
                        default -> identity(proxy, method.getName(), values);
                    };
                });
        Chunk chunk = (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[] { Chunk.class },
                (proxy, method, values) -> method.getName().equals("getPersistentDataContainer") ? pdc : identity(proxy, method.getName(), values));
        // Completion only inspects durable metadata; accessing settings, terrain or a server fails this fixture.
        SeasonalSnowService snow = new SeasonalSnowService(plugin, () -> { throw new AssertionError("Completion must not read settings"); });
        NamespacedKey version = new NamespacedKey(plugin, "season_version");
        NamespacedKey pending = new NamespacedKey(plugin, "winter_pending");
        NamespacedKey cursor = new NamespacedKey(plugin, "winter_cursor");
        String current = "manual-WINTER-r7-snow-regression";

        check(!snow.isComplete(chunk, current), "An unmarked new chunk requires migration");
        check(!snow.isComplete(chunk, null), "Missing desired version cannot certify completion");
        check(!snow.hasPending(chunk), "Missing transition markers mean no pending step");
        metadata.put(version, new Value(PersistentDataType.INTEGER, 7));
        check(!snow.isComplete(chunk, current), "Malformed version metadata cannot certify completion");
        metadata.put(version, new Value(PersistentDataType.STRING, "previous-season"));
        check(!snow.isComplete(chunk, current), "A stale saved version requires migration");
        metadata.put(version, new Value(PersistentDataType.STRING, current));
        check(snow.isComplete(chunk, current), "Matching durable version without transition markers is complete");
        metadata.put(pending, new Value(PersistentDataType.STRING, current));
        check(snow.hasPending(chunk) && !snow.isComplete(chunk, current), "A matching version cannot hide pending winter coverage");
        metadata.remove(pending);
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 0));
        check(snow.hasPending(chunk) && !snow.isComplete(chunk, current), "An orphan cursor at zero requires revalidation");
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 256));
        check(!snow.isComplete(chunk, current), "A cursor at 256 cannot substitute for finalization");
        metadata.put(cursor, new Value(PersistentDataType.STRING, "malformed"));
        check(snow.hasPending(chunk) && !snow.isComplete(chunk, current), "Malformed cursor metadata cannot certify completion");
        metadata.remove(cursor);
        metadata.put(pending, new Value(PersistentDataType.INTEGER, 16));
        check(snow.hasPending(chunk) && !snow.isComplete(chunk, current), "Malformed pending metadata cannot certify completion");
        metadata.remove(pending);
        Map<NamespacedKey, Value> before = Map.copyOf(metadata);
        check(snow.isComplete(chunk, current) && !snow.hasPending(chunk), "Removing finalized transition markers restores completion");
        check(metadata.equals(before), "Completion checks never modify durable metadata");

        check(snow.coveredColumns(chunk, current) == 256, "A finalized matching season contributes all 256 covered columns");
        metadata.put(pending, new Value(PersistentDataType.STRING, current));
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 128));
        check(snow.coveredColumns(chunk, current) == 128 && !snow.isComplete(chunk, current), "A matching pending transition contributes only its durable partial coverage");
        check(snow.coveredColumns(chunk, "next-winter") == 0, "A previous transition cannot raise the next winter's global coverage floor");
        check(snow.coveredColumns(chunk, null) == 0, "A missing desired version cannot reuse pending coverage");
        metadata.remove(pending);
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 0));
        check(snow.coveredColumns(chunk, current) == 0 && !snow.isComplete(chunk, current), "An orphan zero cursor can be repaired but never certifies finalization");
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 128));
        invalidCoverage(snow, chunk, current, "A nonzero orphan cursor must be rejected");
        metadata.put(pending, new Value(PersistentDataType.STRING, current));
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, -1));
        invalidCoverage(snow, chunk, current, "Negative coverage must be rejected");
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 257));
        invalidCoverage(snow, chunk, current, "Coverage beyond the chunk must be rejected");
        metadata.put(cursor, new Value(PersistentDataType.STRING, "malformed"));
        invalidCoverage(snow, chunk, current, "Malformed cursor type must be rejected");
        metadata.put(cursor, new Value(PersistentDataType.INTEGER, 0));
        metadata.put(pending, new Value(PersistentDataType.INTEGER, 7));
        invalidCoverage(snow, chunk, current, "Malformed pending type must be rejected");
        metadata.clear();
        check(snow.coveredColumns(chunk, current) == 0, "Unmarked new terrain contributes zero existing coverage");

        System.out.println("Snow completion tests passed: " + assertions + " assertions");
    }
}
