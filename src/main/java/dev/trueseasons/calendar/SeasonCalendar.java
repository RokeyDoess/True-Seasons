package dev.trueseasons.calendar;

import dev.trueseasons.api.Season;
import dev.trueseasons.visual.SeasonColorFrame;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** The sole calendar authority, including the year at the start of winter. */
public final class SeasonCalendar {
    public record State(Season season, int startYear, double progress) {
        public String version() { return startYear + "-" + season; }
    }
    private final ZoneId zone;
    private final Season[] months = new Season[13];

    public SeasonCalendar(ZoneId zone, Map<Season, List<Integer>> mapping) {
        this.zone = zone;
        for (Season season : Season.values()) {
            List<Integer> values = mapping.get(season);
            if (values == null || values.isEmpty() || values.size() == 12)
                throw new IllegalArgumentException("Every season needs 1–11 months");
            for (int month : values) {
                if (month < 1 || month > 12 || months[month] != null)
                    throw new IllegalArgumentException("Months must be unique and between 1 and 12");
                months[month] = season;
            }
        }
        for (int month = 1; month <= 12; month++)
            if (months[month] == null) throw new IllegalArgumentException("Missing month " + month);
        for (Season season : Season.values()) {
            int starts = 0;
            for (int month = 1; month <= 12; month++)
                if (months[month] == season && months[month == 1 ? 12 : month - 1] != season) starts++;
            if (starts != 1) throw new IllegalArgumentException("Season months must be consecutive: " + season);
        }
    }

    public State at(Instant instant) {
        LocalDate today = instant.atZone(zone).toLocalDate();
        Season season = months[today.getMonthValue()];
        LocalDate start = today.withDayOfMonth(1);
        while (months[start.minusMonths(1).getMonthValue()] == season) start = start.minusMonths(1);
        LocalDate end = start.plusMonths(1);
        while (months[end.getMonthValue()] == season) end = end.plusMonths(1);
        double elapsed = Duration.between(start.atStartOfDay(zone).toInstant(), instant).toMillis();
        double duration = Duration.between(start.atStartOfDay(zone).toInstant(), end.atStartOfDay(zone).toInstant()).toMillis();
        return new State(season, start.getYear(), Math.clamp(elapsed / duration, 0.0, 1.0));
    }
    public ZoneId zone() { return zone; }
    public Season previous(Season season){
        for(int month=1;month<=12;month++)if(months[month]==season&&months[month==1?12:month-1]!=season)
            return months[month==1?12:month-1];
        throw new IllegalArgumentException("Unknown season");
    }
    public SeasonColorFrame colorsAt(Instant instant,double openingDays){
        if(!Double.isFinite(openingDays)||openingDays<0||openingDays>365)throw new IllegalArgumentException("Color opening days must be 0..365");
        LocalDate start=instant.atZone(zone).toLocalDate().withDayOfMonth(1);
        Season season=months[start.getMonthValue()];
        while(months[start.minusMonths(1).getMonthValue()]==season)start=start.minusMonths(1);
        if(openingDays==0)return SeasonColorFrame.settled(season);
        LocalDate end=start.plusMonths(1);while(months[end.getMonthValue()]==season)end=end.plusMonths(1);
        double openingMillis=Math.min(openingDays*86400000.0,Duration.between(start.atStartOfDay(zone),end.atStartOfDay(zone)).toMillis());
        long elapsed=Duration.between(start.atStartOfDay(zone).toInstant(),instant).toMillis();
        int progress=(int)Math.clamp(Math.floor(elapsed/openingMillis*SeasonColorFrame.COMPLETE),0,SeasonColorFrame.COMPLETE);
        return progress==SeasonColorFrame.COMPLETE?SeasonColorFrame.settled(season):new SeasonColorFrame(previous(season),season,progress);
    }
}
