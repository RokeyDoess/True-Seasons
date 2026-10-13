package dev.trueseasons.state;

import dev.trueseasons.api.Season;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/** Job intent is durable; chunk PDC is the authority for completed world edits. */
public final class StateStore {
    private final Path file;
    public Season override;
    public boolean cleanup, clean, paused;
    public int revision;
    public String winterVersion="";
    public int winterColumns;
    public final Map<String, String> managedWorlds = new LinkedHashMap<>();

    public StateStore(Path directory) throws Exception {
        Files.createDirectories(directory);
        file = directory.resolve("jobs.yml");
        if (!Files.exists(file)) return;
        YamlConfiguration data = new YamlConfiguration();
        data.load(file.toFile());
        if (data.getInt("schema") != 1) throw new IOException("Unknown job state schema");
        String value = data.getString("override");
        override = value == null ? null : Season.valueOf(value);
        cleanup = data.getBoolean("cleanup"); clean = data.getBoolean("clean");
        paused = data.getBoolean("paused"); revision = data.getInt("revision");
        winterVersion=data.getString("winter-transition.version","");
        winterColumns=data.getInt("winter-transition.coverage-columns",0);
        if(winterColumns<0||winterColumns>256)throw new IOException("Invalid saved winter coverage");
        var worlds = data.getConfigurationSection("worlds");
        if (worlds != null) for (String id : worlds.getKeys(false)) managedWorlds.put(id, worlds.getString(id));
    }
    public void save() throws IOException {
        YamlConfiguration data = new YamlConfiguration();
        data.set("schema", 1); data.set("override", override == null ? null : override.name());
        data.set("cleanup", cleanup); data.set("clean", clean); data.set("paused", paused);
        data.set("revision", revision);
        data.set("winter-transition.version",winterVersion); data.set("winter-transition.coverage-columns",winterColumns);
        managedWorlds.forEach((id, name) -> data.set("worlds." + id, name));
        atomicWrite(file, data.saveToString());
    }
    public static void atomicWrite(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
