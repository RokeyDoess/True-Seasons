package dev.trueseasons;

import dev.trueseasons.api.Season;
import dev.trueseasons.calendar.SeasonCalendar;
import dev.trueseasons.migration.*;
import dev.trueseasons.snow.SnowColumns;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class CoreTests {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++; if (!condition) throw new AssertionError(message);
    }
    private static Map<Season, List<Integer>> standard() {
        return Map.of(Season.SPRING, List.of(3,4,5), Season.SUMMER, List.of(6,7,8),
                Season.AUTUMN, List.of(9,10,11), Season.WINTER, List.of(12,1,2));
    }
    public static void main(String[] args) throws Exception {
        SeasonCalendar calendar = new SeasonCalendar(ZoneId.of("America/Los_Angeles"), standard());
        check(calendar.at(Instant.parse("2026-03-01T07:59:59Z")).season() == Season.WINTER, "Local winter boundary");
        check(calendar.at(Instant.parse("2026-03-01T08:00:00Z")).season() == Season.SPRING, "Local spring boundary");
        check(calendar.at(Instant.parse("2027-01-01T12:00:00Z")).version().equals("2026-WINTER"), "Winter wraps year");
        check(calendar.at(Instant.parse("2026-06-01T07:00:00Z")).progress() == 0, "Progress starts at zero");
        check(calendar.at(Instant.parse("2026-12-01T07:59:59Z")).season() == Season.AUTUMN, "December local boundary");
        check(calendar.at(Instant.parse("2028-02-29T12:00:00Z")).season() == Season.WINTER, "Leap day");
        for (int month = 1; month <= 12; month++) {
            var state = calendar.at(LocalDate.of(2026, month, 15).atStartOfDay(ZoneOffset.UTC).toInstant());
            check(state.progress() >= 0 && state.progress() < 1, "Progress bounded");
        }
        var broken = new EnumMap<Season,List<Integer>>(Season.class); broken.putAll(standard());
        broken.put(Season.SPRING, List.of(3,4,6));
        try { new SeasonCalendar(ZoneOffset.UTC, broken); throw new AssertionError("Accepted duplicate month"); }
        catch (IllegalArgumentException expected) { assertions++; }
        var southern = new SeasonCalendar(ZoneOffset.UTC, Map.of(Season.SPRING,List.of(9,10,11),Season.SUMMER,List.of(12,1,2),Season.AUTUMN,List.of(3,4,5),Season.WINTER,List.of(6,7,8)));
        check(southern.at(Instant.parse("2026-01-10T00:00:00Z")).version().equals("2025-SUMMER"), "Southern calendar");
        for (int radius = 0; radius <= 16; radius++) {
            ChunkPos center = new ChunkPos(-7, 11);
            List<ChunkPos> area = PlayerNeighborhood.around(center, radius);
            check(area.size() == (radius * 2 + 1) * (radius * 2 + 1), "Entire square neighborhood included");
            check(new HashSet<>(area).size() == area.size(), "Neighborhood contains no duplicates");
            int previousDistance = -1;
            boolean ordered = true;
            for (ChunkPos pos : area) {
                int distance = Math.max(Math.abs(pos.x() - center.x()), Math.abs(pos.z() - center.z()));
                ordered &= distance >= previousDistance && distance <= radius; previousDistance = distance;
            }
            check(ordered, "Nearest rings ordered first");
        }
        Set<Integer> snowOrder = new HashSet<>();
        for (int cursor = 0; cursor < 256; cursor++) snowOrder.add(PlayerNeighborhood.columnAt(cursor,-2,3,"winter-test"));
        check(snowOrder.size() == 256, "Transition visits each column exactly once");
        check(PlayerNeighborhood.columnAt(15,-2,3,"winter-test") == PlayerNeighborhood.columnAt(15,-2,3,"winter-test"),"Transition order resumes deterministically");
        Random random = new Random(13);
        for (int iteration = 0; iteration < 100; iteration++) {
            SnowColumns columns = new SnowColumns();
            for (int col = 0; col < 256; col++) if (random.nextBoolean()) columns.put(col, random.nextInt(384) - 64);
            check(SnowColumns.decode(columns.encode()).entries().equals(columns.entries()), "Snow bitmap round trip");
            check(columns.encode().length <= 545, "Compact metadata");
        }
        SnowColumns columns = new SnowColumns(); columns.put(255,-64); columns.put(0,319);
        byte[] bytes = columns.encode(); bytes[0] = 99;
        try { SnowColumns.decode(bytes); throw new AssertionError("Unknown schema accepted"); }
        catch (IOException expected) { assertions++; }
        try { SnowColumns.decode(new byte[] {1,0}); throw new AssertionError("Truncation accepted"); }
        catch (IOException expected) { assertions++; }
        Path temp = Files.createTempDirectory("trueseasons-core-");
        try {
            Path region = temp.resolve("r.-2.1.mca");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(),"rw")) {
                file.setLength(4 * 4096); file.writeInt((2 << 8) | 1);
                file.seek(1023 * 4L); file.writeInt((3 << 8) | 1);
            }
            byte[] before = Files.readAllBytes(region);
            List<ChunkPos> found = new GeneratedChunkIndex().scan(temp);
            check(found.equals(List.of(new ChunkPos(-64,32),new ChunkPos(-33,63))), "Negative region coordinates");
            check(Arrays.equals(before,Files.readAllBytes(region)), "Scanner never writes region data");
            check(new ChunkPos(-1,-33).regionX() == -1 && new ChunkPos(-1,-33).regionZ() == -2, "Floor division");
            check(new GeneratedChunkIndex().scan(temp.resolve("absent")).isEmpty(), "Missing directory remains absent");
            try (RandomAccessFile file = new RandomAccessFile(region.toFile(),"rw")) { file.seek(0); file.writeInt((100 << 8) | 1); }
            try { new GeneratedChunkIndex().scan(temp); throw new AssertionError("Invalid region accepted"); }
            catch (IOException expected) { assertions++; }
        } finally {
            Path cleanupRoot=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
            if (!temp.toRealPath().startsWith(cleanupRoot) || temp.toRealPath().equals(cleanupRoot))
                throw new java.io.IOException("Test cleanup escaped its temporary directory");
            try (var paths = Files.walk(temp)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
        System.out.println("Core tests passed: " + assertions + " assertions");
    }
}
