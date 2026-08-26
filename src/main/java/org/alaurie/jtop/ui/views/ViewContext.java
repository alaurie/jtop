package org.alaurie.jtop.ui.views;

import dev.tamboui.widgets.tabs.Tabs;
import dev.tamboui.widgets.tabs.TabsState;
import org.alaurie.jtop.model.JvmMetricsSnapshot;
import org.alaurie.jtop.model.JvmProcess;
import org.alaurie.jtop.model.MetricHistory;
import org.alaurie.jtop.service.JvmMonitorService;
import org.alaurie.jtop.ui.style.Theme;

/// Immutable context payload passed across the View seam during rendering and input handling.
public record ViewContext(
    JvmProcess currentProcess,
    JvmMetricsSnapshot currentMetrics,
    MetricHistory currentHistory,
    Theme currentTheme,
    Tabs tabsWidget,
    TabsState tabsState,
    JvmMonitorService monitorService
) {}
