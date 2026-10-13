package dev.trueseasons.snow;

import dev.trueseasons.api.Season;
import java.io.IOException;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.persistence.PersistentDataType;

/** Verify visible-edit intent separately from seasonal metadata-only migration. */
public final class SnowMigrationIntentTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        var f = new SnowReplenishmentTests.Fixture();
        var result = f.snow.applyStep(f.chunk, Season.SPRING, "spring", false, 256, () -> intent(f));
        check(!result.changed() && result.complete() && f.before == 0, "Metadata-only seasonal transition creates no render intent");
        check(f.snow.isComplete(f.chunk, "spring"), "Skipping render intent still finalizes season metadata");

        var removed = new SnowReplenishmentTests.Fixture(); byte[] initial = owned(removed, 0, 1);
        removed.column(0).target = Material.SNOW; removed.column(1).target = Material.SNOW;
        result = removed.snow.applyStep(removed.chunk, Season.SPRING, "spring", false, 256, () -> intent(removed));
        check(result.removed() == 2 && removed.before == 1, "One render intent covers a batch of actual owned snow removals");
        check(removed.order.equals(List.of("intent", "remove", "remove")), "Intent precedes the first physical removal exactly once");
        check(!removed.metadata.containsKey(removed.owned), "Successful restoration clears removed ownership");

        var replacement = new SnowReplenishmentTests.Fixture(); owned(replacement, 0);
        replacement.column(0).target = Material.DIAMOND_BLOCK;
        result = replacement.snow.applyStep(replacement.chunk, Season.SPRING, "spring", false, 256, () -> intent(replacement));
        check(!result.changed() && replacement.before == 0 && replacement.column(0).target == Material.DIAMOND_BLOCK,
                "Releasing stale ownership of a player replacement needs no render intent or block edit");
        check(!replacement.metadata.containsKey(replacement.owned), "Stale replacement ownership is released normally");

        var conflict = new SnowReplenishmentTests.Fixture(); byte[] conflicted = owned(conflict, 0);
        conflict.column(0).target = Material.SNOW; conflict.column(0).layers = 5;
        result = conflict.snow.applyStep(conflict.chunk, Season.SPRING, "spring", false, 256, () -> intent(conflict));
        check(result.conflicts() == 1 && !result.changed() && !result.complete() && conflict.before == 0,
                "Conflict-only batches retain ambiguous snow without render intent");
        check(Arrays.equals(conflicted, (byte[]) conflict.metadata.get(conflict.owned).value()), "Conflict-only ownership remains recoverable");

        var failed = new SnowReplenishmentTests.Fixture(); byte[] failedOwnership = owned(failed, 0, 1);
        failed.column(0).target = Material.DIAMOND_BLOCK; failed.column(1).target = Material.SNOW;
        try {
            failed.snow.applyStep(failed.chunk, Season.SPRING, "spring", false, 256, () -> { intent(failed); throw new IllegalStateException("Render journal failure"); });
            throw new AssertionError("Expected render-intent failure");
        } catch (IllegalStateException expected) {
            check(failed.column(1).target == Material.SNOW && failed.column(0).target == Material.DIAMOND_BLOCK,
                    "Intent failure before the first physical edit leaves every block unchanged");
            check(failed.metadata.get(failed.owned).value() == failedOwnership,
                    "Intent failure preserves existing ownership, including earlier tentative stale-record removals");
            check(failed.order.equals(List.of("intent")), "Intent failure makes no physical or ownership writes");
        }

        var malformed = new SnowReplenishmentTests.Fixture(); byte[] bad = new byte[]{9};
        malformed.metadata.put(malformed.owned, new SnowReplenishmentTests.Value(PersistentDataType.BYTE_ARRAY, bad));
        try {
            malformed.snow.applyStep(malformed.chunk, Season.SPRING, "spring", false, 256, () -> intent(malformed));
            throw new AssertionError("Expected malformed ownership failure");
        } catch (IOException expected) { check(malformed.before == 0 && malformed.metadata.get(malformed.owned).value() == bad,
                "Malformed ownership fails before render intent and remains untouched"); }
        System.out.println("Snow migration intent tests passed: " + checks + " assertions");
    }
    private static byte[] owned(SnowReplenishmentTests.Fixture fixture, int... columns) {
        SnowColumns map = new SnowColumns(); for (int column : columns) map.put(column, fixture.column(column).height + 1);
        byte[] encoded = map.encode(); fixture.metadata.put(fixture.owned, new SnowReplenishmentTests.Value(PersistentDataType.BYTE_ARRAY, encoded)); return encoded;
    }
    private static void intent(SnowReplenishmentTests.Fixture fixture) { fixture.before++; fixture.order.add("intent"); }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
