# AGENTS.md - jtop Developer & Agent Guide

`jtop` is a terminal-based monitoring utility (htop/btop for the JVM) specifically designed for inspecting Java Virtual Machines running on Java 21–25+. It uses [TamboUI](https://tamboui.dev) for its TUI framework, leverages Java 25 features (`Gatherers`, Record Patterns, Virtual Threads), and provides real-time monitoring of local, containerized (Docker/Kubernetes), and remote JVM processes.

---

## 1. Project Architecture & Package Structure

- **`org.alaurie.jtop`**:
  - `Main.java`: CLI entry point bootstrapping `TuiRunner`, `TuiConfig`, and parsing options (`--pid`, `--jmx`, `--interval`, `--theme`, `--ascii`).
- **`org.alaurie.jtop.ui`**:
  - `JTopApp.java`: Main TUI application controller implementing TamboUI `EventHandler` and `Renderer`. Controls view switching, top tabs bar, bottom action footer, and 100ms tick re-rendering loop.
- **`org.alaurie.jtop.ui.views`**:
  - `ProcessListView.java` (Tab 1): Discovered local, Docker, and Kubernetes Pod JVM processes with `/` filter search.
  - `DashboardView.java` (Tab 2): 4-quadrant btop layout featuring Process/System CPU gauges, load history sparklines, Memory overview, GC collectors, and top active threads table.
  - `ThreadView.java` (Tab 3): Thread Inspector with deadlock alert banners, sort field cycling (`s`), virtual thread toggle (`v`), and lazy stack trace inspection (`Enter`).
  - `GcView.java` (Tab 4): Allocation rate (MB/s), GC pause rate (ms/s), full-width GC pause trend sparklines, Memory pool matrix, and off-heap buffer pools (`direct`/`mapped`).
  - `JvmInfoView.java` (Tab 5): Open file descriptors (`openFds`), Host RAM/Swap, JIT compilation times, loaded classes, JVM flags, and system properties.
  - `JfrEventsView.java` (Tab 6): Live JDK Flight Recorder (JFR) event stream (`SocketRead`, `ThreadPark`, `GarbageCollection`, `FileWrite`).
  - `FrameworkView.java` (Tab 7): Framework-aware telemetry for Spring Boot (HikariCP) and Quarkus (Agroal) database connection pools, HTTP thread pools, and live loggers.
- **`org.alaurie.jtop.ui.style`**:
  - `Theme.java`: Color palette definitions (`btop`, `dracula`, `nord`, `solarized`).
  - `Glyph.java`: Centralized ASCII fallback engine (`--ascii` mode) and multi-segment progress bar builder.
- **`org.alaurie.jtop.service`**:
  - `JvmDiscoveryService.java`: Finds local JVM PIDs, command lines, and container namespaces (`/proc/<pid>/cgroup`).
  - `JvmMonitorService.java`: Non-blocking metric polling engine via Attach API or Remote JMX (`--jmx host:port`), Java 25 Stream Gatherers (`windowSliding`), and Virtual Threads executor.
  - `RateGatherer.java`: Custom Java 25 `Gatherer` computing rates per second from cumulative counters.
- **`org.alaurie.jtop.model`**:
  - Immutable Java records: `JvmProcess`, `JvmMetricsSnapshot`, `ThreadSnapshot`, `GcSnapshot`, `MemoryPoolSnapshot`, `BufferPoolSnapshot`, `JvmRuntimeInfo`, `FrameworkInfo`, `MetricHistory`.
---

## 2. Core Invariants & Rules for AI Agents

When editing or extending `jtop`, you MUST follow these invariants:

1. **Java 25 Target & Preview Features**:
   - `build.gradle` targets Java 25 (`options.compilerArgs += ['--enable-preview']`).
   - Maintain Preview feature flags (`--enable-preview`, `--enable-native-access=ALL-UNNAMED`) in compiler and test configurations.

2. **Zero-Overhead Polling**:
   - Routine 250ms polling in `JvmMonitorService.pollSnapshot()` MUST fetch thread info at **depth 0** (`getThreadInfo(threadIds, 0)`).
   - Deep 15-frame stack traces MUST be fetched lazily on-demand only when a user inspects a specific thread (`fetchThreadStackTrace(threadId)`).

3. **Realtime Render Loop**:
   - `JTopApp.handle()` MUST return `true` on `TickEvent` so TamboUI's event loop executes `safeRender()` and redraws the frame continuously every 100ms.
   - Background polling updates MUST issue `runner.runLater(() -> {})` to trigger immediate frame updates.

4. **TamboUI Block Border Rule**:
   - Every `Block.builder()` MUST specify `.borders(Borders.ALL)` or `.bordered()`. Omitting this setting results in invisible box border bugs.

5. **Theme & Glyph Compatibility**:
   - Use `Theme` palette colors and `Glyph` providers rather than hardcoding static RGB values.
   - Support clean ASCII fallbacks (`Glyph.isAsciiOnly()`) for non-UTF8 or restricted SSH PTYs (`--ascii`).

---

## 3. Verification & Build Commands

Always run build and test verification before delivering changes:

- **Build Application Binary**:
  ```bash
  gradle installDist
  ```
  Generates binary at `./build/install/jtop/bin/jtop`.

- **Run Unit & Integration Tests**:
  ```bash
  gradle test --rerun-tasks
  ```

- **Test Application Execution**:
  ```bash
  ./build/install/jtop/bin/jtop --help
  ```
