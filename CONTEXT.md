# CONTEXT.md - jtop Domain Glossary

This document defines the ubiquitous language for `jtop` architecture, modules, and domain concepts.

## Domain Concepts

- **Target JVM**: The Java Virtual Machine process (local, containerized, or remote) being monitored via JMX or Attach API.
- **Telemetry Snapshot**: Immutable snapshot of JVM metrics captured at a specific timestamp (`JvmMetricsSnapshot`).
- **View Seam**: The architectural interface (`View`) separating `JTopApp` state routing from view-specific layout, key handling, and rendering.
- **View Context**: Immutable payload (`ViewContext`) passed across the View seam containing metrics, history, theme, and service handle.
- **Telemetry Collector**: Deep adapter module (`CpuCollector`, `MemoryCollector`, `ThreadCollector`, `FrameworkCollector`) encapsulating MBean queries for a specific telemetry domain.
- **UI Formatter**: Centralized formatting engine (`UiFormatter`) handling byte formatting, text truncation, and theme-aware color styles.
