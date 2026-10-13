package dev.trueseasons.api;

import java.util.Locale;

public enum Season {
    SPRING, SUMMER, AUTUMN, WINTER;
    public String key() { return name().toLowerCase(Locale.ROOT); }
}
