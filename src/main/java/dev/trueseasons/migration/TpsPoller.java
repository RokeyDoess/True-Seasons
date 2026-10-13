package dev.trueseasons.migration;

import java.util.function.DoubleSupplier;

/** Cache Paper's historical TPS aggregation across ticks and command requests. */
public final class TpsPoller {
    private final DoubleSupplier source;
    private long sampledAt = Long.MIN_VALUE;
    private double value = 20;
    public TpsPoller(DoubleSupplier source) { this.source = source; }
    public double read(long now) {
        if (sampledAt == Long.MIN_VALUE || now < sampledAt || now - sampledAt >= 1000) {
            value = source.getAsDouble(); sampledAt = now;
        }
        return value;
    }
}
