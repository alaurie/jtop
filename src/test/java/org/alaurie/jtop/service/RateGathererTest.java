package org.alaurie.jtop.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateGathererTest {

    @Test
    void testRatePerSecondCalculation() {
        List<RateGatherer.TimestampedValue> inputs = List.of(
            new RateGatherer.TimestampedValue(1000, 100.0),
            new RateGatherer.TimestampedValue(2000, 150.0), // +50 over 1 sec -> rate 50.0
            new RateGatherer.TimestampedValue(4000, 250.0)  // +100 over 2 sec -> rate 50.0
        );

        List<RateGatherer.RateResult> results = inputs.stream()
            .gather(RateGatherer.ratePerSecond())
            .toList();

        assertEquals(2, results.size());
        assertEquals(50.0, results.get(0).rate(), 0.01);
        assertEquals(50.0, results.get(1).rate(), 0.01);
    }
}
