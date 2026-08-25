package org.alaurie.jtop.model;

import java.util.List;

/// Historical metrics buffer for charts, sparklines, and trend rendering.
public record MetricHistory(
    List<Double> cpuHistory,
    List<Long> heapHistory,
    List<Long> gcTimeHistory
) {}
