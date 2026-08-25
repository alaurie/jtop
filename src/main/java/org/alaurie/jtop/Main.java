package org.alaurie.jtop;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import org.alaurie.jtop.service.SshMonitorService;
import org.alaurie.jtop.ui.JTopApp;
import org.alaurie.jtop.ui.style.Theme;
import org.alaurie.jtop.util.JTopLogger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.util.concurrent.Callable;

/// Entry point for the `jtop` terminal monitor application powered by PicoCLI.
@Command(
    name = "jtop",
    mixinStandardHelpOptions = true,
    version = "jtop 1.0.0 (Java 25)",
    description = "Modern Java 25 JDK Terminal Monitor (htop/btop for JVM)",
    footer = {
        "",
        "@|bold Keybindings in jtop:|@",
        "  1 / F1                 Process Selector View (with Docker & K8s Pod Names)",
        "  2 / F2                 Overview Dashboard View (btop-style)",
        "  3 / F3                 Threads Detail Inspector (with Stack Trace Inspector)",
        "  4 / F4                 GC & Memory Pools Inspector",
        "  5 / F5                 JVM Launch Flags, ClassLoading & JIT Inspector",
        "  6 / F6                 Live JDK Flight Recorder (JFR) Event Stream",
        "  7 / F7                 Spring Boot & Quarkus Telemetry (HikariCP / Agroal / Loggers)",
        "  ← / →                  Switch active Tabs",
        "  Enter                  Inspect Stack Trace & Lock Owner (Threads / JFR Views)",
        "  t                      Cycle Color Theme live (btop -> dracula -> nord -> solarized)",
        "  g                      Trigger Manual System.gc() (GC View)",
        "  /                      Search/Filter processes or threads",
        "  s                      Cycle Sort Order (CPU%%, Duration, ID, Name, State)",
        "  v                      Toggle Virtual Threads filter",
        "  q / Ctrl+C             Quit jtop"
    }
)
public class Main implements Callable<Integer> {

    @Option(names = {"-p", "--pid"}, description = "Target JVM process ID (default: self)")
    private Long targetPid;

    @Option(names = {"-s", "--ssh"}, description = "Connect to remote host over SSH (e.g. admin@10.0.1.50:22 or ssh://user@host)")
    private String sshTarget;

    @Option(names = {"-j", "--jmx"}, description = "Connect to remote JVM via JMX TCP URL (e.g. localhost:9999)")
    private String jmxUrl;

    @Option(names = {"-k", "--key"}, description = "Path to SSH private key (default: 1Password Agent / ~/.ssh/id_ed25519)")
    private String sshKeyPath;

    @Option(names = {"-i", "--interval"}, defaultValue = "500", description = "Polling interval in milliseconds (default: 500, min: 100)")
    private long intervalMs;

    @Option(names = {"-t", "--theme"}, defaultValue = "btop", description = "Color palette theme: btop, dracula, nord, solarized")
    private String themeName;

    @Option(names = {"-a", "--ascii"}, description = "Force clean ASCII rendering (disables Unicode/emojis)")
    private boolean asciiOnly;

    @Parameters(index = "0", arity = "0..1", description = "Optional remote SSH target URL (e.g. ssh://user@host:port)")
    private String positionalSshUrl;

    @Override
    public Integer call() throws Exception {
        JTopLogger.info("Starting jtop terminal monitor...");

        var selectedTheme = Theme.fromName(themeName);

        var finalSshUrl = sshTarget != null ? sshTarget : positionalSshUrl;
        if (finalSshUrl != null) {
            var creds = SshMonitorService.parseSshUrl(finalSshUrl, sshKeyPath);
            jmxUrl = creds.host() + ":9999";
            JTopLogger.info("Parsed SSH target: " + creds.user() + "@" + creds.host() + ":" + creds.port());
        }

        System.setProperty("jdk.attach.allowAttachSelf", "true");

        try (var app = new JTopApp(targetPid, jmxUrl, intervalMs, selectedTheme, asciiOnly);
             var runner = TuiRunner.create(TuiConfig.builder().tickRate(Duration.ofMillis(100)).build())) {
            runner.run(app, app);
        } catch (Exception e) {
            JTopLogger.error("Fatal error running jtop: " + e.getMessage(), e);
            return 1;
        }

        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}
