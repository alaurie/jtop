package org.alaurie.jtop.service;

import java.util.stream.Gatherer;

/// Custom Java 25 Stream Gatherer to compute per-second rates from cumulative metrics.
public class RateGatherer {

    public record TimestampedValue(long timestampMs, double value) {}
    public record RateResult(long timestampMs, double rate) {}

    private static class RateState {
        boolean hasPrevious = false;
        long prevTimestampMs = 0;
        double prevValue = 0.0;
    }

    public static Gatherer<TimestampedValue, ?, RateResult> ratePerSecond() {
        return Gatherer.ofSequential(
            RateState::new,
            Gatherer.Integrator.ofGreedy((state, element, downstream) -> {
                if (state.hasPrevious) {
                    double deltaSec = (element.timestampMs() - state.prevTimestampMs) / 1000.0;
                    double deltaVal = element.value() - state.prevValue;
                    double rate = deltaSec > 0 ? deltaVal / deltaSec : 0.0;
                    downstream.push(new RateResult(element.timestampMs(), Math.max(0.0, rate)));
                } else {
                    state.hasPrevious = true;
                }
                state.prevTimestampMs = element.timestampMs();
                state.prevValue = element.value();
                return true;
            })
        );
    }
}
