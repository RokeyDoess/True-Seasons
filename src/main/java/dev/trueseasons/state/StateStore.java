package dev.trueseasons.state;

import dev.trueseasons.api.Season;
import dev.trueseasons.visual.SeasonColorFrame;
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
    public String pauseReason="";
    public String currentVersion="";
    public SeasonColorFrame colorFrame;
    public double pausedCalendarProgress;
    public int revision;
    public String winterVersion="";
    public int winterColumns;
    public Season lastSeason;
    public String meltVersion="";
    public String meltOrderVersion="";
    public int meltColumns;
    public int meltStartColumns;
    public String announcementVersion="";
    public boolean migrationAnnouncedComplete;
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
        pauseReason=data.getString("pause-reason","");
        currentVersion=data.getString("current-version","");
        if(data.contains("colors.target"))colorFrame=new SeasonColorFrame(Season.valueOf(data.getString("colors.from")),Season.valueOf(data.getString("colors.target")),data.getInt("colors.progress"));
        pausedCalendarProgress=data.getDouble("colors.paused-calendar-progress",0);
        if(!Double.isFinite(pausedCalendarProgress)||pausedCalendarProgress<0||pausedCalendarProgress>1)throw new IOException("Invalid paused calendar progress");
        winterVersion=data.getString("winter-transition.version","");
        winterColumns=data.getInt("winter-transition.coverage-columns",0);
        if(winterColumns<0||winterColumns>256)throw new IOException("Invalid saved winter coverage");
        String previous=data.getString("last-season");lastSeason=previous==null?null:Season.valueOf(previous);
        meltVersion=data.getString("snowmelt.version","");meltColumns=data.getInt("snowmelt.remaining-columns",0);
        meltOrderVersion=data.getString("snowmelt.order-version","");
        meltStartColumns=data.getInt("snowmelt.start-columns",meltColumns);
        if(meltStartColumns<0||meltStartColumns>256)throw new IOException("Invalid snowmelt starting coverage");
        if(meltColumns<0||meltColumns>256)throw new IOException("Invalid saved snowmelt coverage");
        announcementVersion=data.getString("announcements.migration-version","");
        migrationAnnouncedComplete=data.getBoolean("announcements.migration-complete",false);
        var worlds = data.getConfigurationSection("worlds");
        if (worlds != null) for (String id : worlds.getKeys(false)) managedWorlds.put(id, worlds.getString(id));
    }
    public void save() throws IOException {
        YamlConfiguration data = new YamlConfiguration();
        data.set("schema", 1); data.set("override", override == null ? null : override.name());
        data.set("cleanup", cleanup); data.set("clean", clean); data.set("paused", paused);
        data.set("pause-reason",pauseReason);
        data.set("current-version",currentVersion);
        if(colorFrame!=null){data.set("colors.from",colorFrame.from().name());data.set("colors.target",colorFrame.to().name());data.set("colors.progress",colorFrame.progress());}
        data.set("colors.paused-calendar-progress",pausedCalendarProgress);
        data.set("revision", revision);
        data.set("winter-transition.version",winterVersion); data.set("winter-transition.coverage-columns",winterColumns);
        data.set("last-season",lastSeason==null?null:lastSeason.name());
        data.set("snowmelt.version",meltVersion);data.set("snowmelt.remaining-columns",meltColumns);
        data.set("snowmelt.order-version",meltOrderVersion);
        data.set("snowmelt.start-columns",meltStartColumns);
        data.set("announcements.migration-version",announcementVersion);
        data.set("announcements.migration-complete",migrationAnnouncedComplete);
        managedWorlds.forEach((id, name) -> data.set("worlds." + id, name));
        atomicWrite(file, data.saveToString());
    }
    public static void atomicWrite(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
    public void seasonTransition(Season previous,Season next,String target){
        if(cleanup||clean||next==Season.WINTER){meltVersion="";meltOrderVersion="";meltColumns=0;meltStartColumns=0;}
        else if(!target.equals(meltVersion)){
            if(meltColumns>0&&!meltVersion.isEmpty()){meltVersion=target;meltStartColumns=meltColumns;}
            else if(previous==Season.WINTER){meltVersion=target;meltOrderVersion=winterVersion;meltColumns=winterColumns;meltStartColumns=meltColumns;}
            else {meltVersion="";meltOrderVersion="";meltColumns=0;meltStartColumns=0;}
        }
        lastSeason=next;
    }
}
