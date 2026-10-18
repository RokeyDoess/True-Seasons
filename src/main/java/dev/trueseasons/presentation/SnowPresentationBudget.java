package dev.trueseasons.presentation;

import dev.trueseasons.config.SnowPresentationLimits;

/** Independent server-wide pool for extra snow reveal, preview and melt packets. */
final class SnowPresentationBudget extends PresentationWorkBudget {
    SnowPresentationBudget(){this(SnowPresentationLimits.DEFAULT.chunks(),SnowPresentationLimits.DEFAULT.blocks());}
    SnowPresentationBudget(int chunks,int blocks){super(chunks,blocks);}
    void limits(SnowPresentationLimits limits){limits(limits.chunks(),limits.blocks());}
}
