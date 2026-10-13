package dev.trueseasons.snow;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Material;

/** Monotonic grace regression; no sleeps, world loads or server needed. */
public final class SnowBreakCooldownTests {
    private static int checks;
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final long SECOND = 1_000_000_000L;
    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong(); SnowBreakCooldown cooldown = new SnowBreakCooldown(clock::get, 16);
        var key = SnowBreakCooldown.Column.at(WORLD, -1, 17);
        check(key.chunkX() == -1 && key.chunkZ() == 1 && key.column() == 31, "Negative block coordinates use the correct chunk and local column");
        cooldown.record(key, 60);
        check(cooldown.blocked(key), "A break immediately starts grace");
        clock.set(60 * SECOND - 1); check(cooldown.blocked(key), "Grace remains active up to the expiry boundary");
        clock.incrementAndGet(); check(!cooldown.blocked(key) && cooldown.size() == 0, "Grace expires exactly at the boundary and removes its entry");
        clock.set(0); cooldown.record(key, 60); clock.set(30 * SECOND); cooldown.record(key, 60);
        clock.set(60 * SECOND); check(cooldown.blocked(key), "Repeated breaks extend grace from the latest break");
        clock.set(90 * SECOND); check(!cooldown.blocked(key), "Extended grace expires at its new boundary");
        clock.set(0); cooldown.record(key, 60);
        check(!cooldown.blocked(SnowBreakCooldown.Column.at(WORLD, 0, 17)), "Adjacent horizontal columns remain independent");
        check(!cooldown.blocked(SnowBreakCooldown.Column.at(WORLD, -17, 17)), "The same local column in another chunk remains independent");
        check(!cooldown.blocked(SnowBreakCooldown.Column.at(OTHER, -1, 17)), "Worlds remain independent");
        check(cooldown.blocked(new SnowBreakCooldown.Column(WORLD, -1, 1, 31)), "New chunk/block instances after unload and reload resolve the same coordinate grace");
        int[] filtered = cooldown.available(WORLD, -1, 1, new int[]{31, 30, 0, 31});
        check(Arrays.equals(filtered, new int[]{30, 0}), "Shared random/direct sample filtering removes every cooled column");
        check(!cooldown.allAvailable(WORLD, -1, 1, new int[]{31, 30}), "Commit guard blocks a reentrant newly cooled column");
        check(cooldown.allAvailable(WORLD, -1, 1, new int[]{30, 0}), "Uncooled sampled columns remain available");
        clock.set(60 * SECOND);
        check(Arrays.equals(cooldown.available(WORLD, -1, 1, new int[]{31}), new int[]{31}), "Post-grace sampling may refill normally without scheduling an instant fill");
        cooldown.record(key, 60); cooldown.record(key, 0); check(!cooldown.blocked(key) && cooldown.size() == 0, "Zero disables grace and removes an existing matching entry");
        for (Material type : List.of(Material.AIR, Material.STONE, Material.DIRT, Material.DANDELION)) {
            cooldown.recordBreak(WORLD, -1, 17, type, false, true, 60);
            check(cooldown.size() == 0, "Non-snow breaks do not create grace: " + type);
        }
        cooldown.recordBreak(WORLD, -1, 17, Material.SNOW, true, true, 60);
        check(cooldown.size() == 0, "Cancelled snow breaks do not create grace");
        cooldown.recordBreak(WORLD, -1, 17, Material.SNOW, false, false, 60);
        check(cooldown.size() == 0, "Nonwinter/disabled/ineligible worlds do not create grace");
        cooldown.recordBreak(WORLD, -1, 17, Material.SNOW, false, true, 60);
        check(cooldown.blocked(key), "Winter grace records independently of rain, migration pause or TPS");
        check(cooldown.blocksFormation(WORLD, -1, 17, Material.SNOW, false, true, 60), "Native snow formation obeys the same grace");
        check(!cooldown.blocksFormation(WORLD, -1, 17, Material.ICE, false, true, 60), "Unrelated native ice formation is unaffected");
        check(!cooldown.blocksFormation(WORLD, -1, 17, Material.SNOW_BLOCK, false, true, 60), "Player snow-block placement is not a native layer-form cancellation");
        check(!cooldown.blocksFormation(WORLD, -1, 17, Material.SNOW, true, true, 60), "Already-cancelled formation is left to its original canceller");
        check(!cooldown.blocksFormation(WORLD, -1, 17, Material.SNOW, false, false, 60), "Grace does not suppress natural summer/cold-biome snowfall");
        check(!cooldown.blocksFormation(WORLD, -1, 17, Material.SNOW, false, true, 0), "Zero configuration disables native formation cancellation too");
        cooldown.clear(); cooldown.recordBreak(WORLD, -1, 17, Material.SNOW_BLOCK, false, true, 60);
        check(cooldown.blocked(key), "Breaking a full snow block also allows digging before snowfall resumes");
        clock.set(Long.MAX_VALUE - SECOND / 2); cooldown.clear(); cooldown.record(key, 1);
        clock.addAndGet(SECOND - 1); check(cooldown.blocked(key), "Monotonic wraparound retains live grace");
        clock.incrementAndGet(); check(!cooldown.blocked(key), "Monotonic wraparound preserves the exact expiry boundary");
        clock.set(0); SnowBreakCooldown bounded = new SnowBreakCooldown(clock::get, 3);
        for (int i = 0; i < 100; i++) { bounded.record(SnowBreakCooldown.Column.at(WORLD, i, 0), 60); check(bounded.size() <= 3, "Cache stays bounded under unique active breaks"); }
        check(bounded.blocked(SnowBreakCooldown.Column.at(WORLD, 99, 0)), "Newest break is retained at capacity; older active entries may be evicted");
        clock.set(60 * SECOND); bounded.maintain(1); check(bounded.size() == 2, "Maintenance examines only its bounded entry budget");
        bounded.maintain(128); check(bounded.size() == 0, "Maintenance eventually removes all expired entries");
        clock.set(0); SnowBreakCooldown reclaim = new SnowBreakCooldown(clock::get, 2);
        var first = SnowBreakCooldown.Column.at(WORLD, 1, 0);
        var expired = SnowBreakCooldown.Column.at(WORLD, 2, 0);
        reclaim.record(first, 60); reclaim.record(expired, 1); clock.set(2 * SECOND);
        reclaim.record(SnowBreakCooldown.Column.at(WORLD, 3, 0), 60);
        check(reclaim.blocked(first) && reclaim.size() == 2, "Capacity pressure cheaply reclaims expired entries before evicting live grace");
        System.out.println("Snow break cooldown tests passed: " + checks + " assertions");
    }
    private static void check(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
