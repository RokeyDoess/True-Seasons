package dev.trueseasons.visual;

import dev.trueseasons.api.Season;
import java.util.Objects;

/** Small immutable client target; map rendering always uses the seasonal endpoint. */
public record SeasonColorFrame(Season from, Season to, int progress) {
    public static final int COMPLETE=256, PALETTE_STEPS=8;
    public SeasonColorFrame {
        Objects.requireNonNull(from);Objects.requireNonNull(to);
        if(progress<0||progress>COMPLETE)throw new IllegalArgumentException("Invalid seasonal color progress");
    }
    public static SeasonColorFrame settled(Season season){return new SeasonColorFrame(season,season,COMPLETE);}
    public int paletteStep(){return from==to?PALETTE_STEPS:progress*PALETTE_STEPS/COMPLETE;}
    public SeasonColorFrame palette(){return paletteStep()==PALETTE_STEPS?settled(to):new SeasonColorFrame(from,to,paletteStep()*COMPLETE/PALETTE_STEPS);}
    public int autumnCoverage(){return to==Season.AUTUMN?(from==to?COMPLETE:progress):from==Season.AUTUMN?COMPLETE-progress:0;}
    public int springCoverage(){return to==Season.SPRING?(from==to?COMPLETE:progress):from==Season.SPRING?COMPLETE-progress:0;}
}
