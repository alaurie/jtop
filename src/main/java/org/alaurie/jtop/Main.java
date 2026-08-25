package org.alaurie.jtop;

import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import org.alaurie.jtop.service.SshMonitorService;
import org.alaurie.jtop.ui.JTopApp;
import org.alaurie.jtop.ui.style.Theme;
import org.alaurie.jtop.util.JTopLogger;

import java.time.Duration;

/// Entry point for the `jtop` terminal monitor application.
///
/// Supports local PID attach, remote JMX URLs, and native SSH connection URLs.
public class Main {

    public static void main(String[] args) {
        Long targetPid = null;
        String jmxUrl = null;
        String sshKeyPath = null;
        long intervalMs = 500;
        Theme theme = Theme.BTOP;
        boolean asciiOnly = false;

        JTopLogger.info("Starting jtop terminal monitor...");

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--pid") || arg.equals("-p")) {
                if (i + 1 < args.length) {
                    try {
                        targetPid = Long.parseLong(args[++i]);
                    } catch (NumberFormatException e) {
                        JTopLogger.error("Invalid PID argument: " + args[i], e);
                        System.exit(1);
                    }
                }
            } else if (arg.equals("--jmx") || arg.equals("-j")) {
                if (i + 1 < args.length) {
                    jmxUrl = args[++i];
                }
            } else if (arg.equals("--key") || arg.equals("-k")) {
                if (i + 1 < args.length) {
                    sshKeyPath = args[++i];
                }
            } else if (arg.startsWith("ssh://")) {
                SshMonitorService.SshCredentials creds = SshMonitorService.parseSshUrl(arg, sshKeyPath);
                jmxUrl = creds.host() + ":9999";
                JTopLogger.info("Parsed SSH remote URL target: " + creds.host() + ":" + creds.port());
            } else if (arg.equals("--interval") || arg.equals("-i")) {
                if (i + 1 < args.length) {
                    try {
                        intervalMs = Long.parseLong(args[++i]);
                    } catch (NumberFormatException e) {
                        JTopLogger.error("Invalid interval argument: " + args[i], e);
                        System.exit(1);
                    }
                }
            } else if (arg.equals("--theme") || arg.equals("-t")) {
                if (i + 1 < args.length) {
                    theme = Theme.fromName(args[++i]);
                }
            } else if (arg.equals("--ascii") || arg.equals("-a")) {
                asciiOnly = true;
            } else if (arg.equals("--help") || arg.equals("-h")) {
                printHelp();
                return;
            }
        }

        System.setProperty("jdk.attach.allowAttachSelf", "true");

        try (JTopApp app = new JTopApp(targetPid, jmxUrl, intervalMs, theme, asciiOnly);
             TuiRunner runner = TuiRunner.create(TuiConfig.builder().tickRate(Duration.ofMillis(100)).build())) {
            runner.run(app, app);
        } catch (Exception e) {
            JTopLogger.error("Fatal error running jtop: " + e.getMessage(), e);
        }
    }

    private static void printHelp() {
        System.out.println("""
            jtop - Modern Java 25 JDK Terminal Monitor (htop/btop for JVM)
            
            Usage:
              jtop [options] [ssh://user@host:port]
            
            Options:
              -p, --pid <PID>        Target JVM process ID (default: self)
              -j, --jmx <HOST:PORT>  Connect to remote JVM via JMX TCP URL (e.g. localhost:9999)
              -k, --key <PATH>       Path to SSH private key (default: 1Password Agent / ~/.ssh/id_ed25519)
              -i, --interval <MS>    Polling interval in milliseconds (default: 500, min: 100)
              -t, --theme <THEME>    Color palette theme: btop (default), dracula, nord, solarized
              -a, --ascii            Force clean ASCII rendering (disables Unicode/emojis)
              -h, --help             Show this help message
            
            SSH Auth Support:
              - 1Password / OpenSSH Agent (via SSH_AUTH_SOCK)
              - Identity Keys (~/.ssh/id_ed25519, ~/.ssh/id_rsa, ~/.ssh/id_ecdsa)
              - Interactive Password Fallback
            
            Keybindings in jtop:
              1 / F1                 Process Selector View (with Docker & K8s Pod Names)
              2 / F2                 Overview Dashboard View (btop-style)
              3 / F3                 Threads Detail Inspector (with Stack Trace Inspector)
              4 / F4                 GC & Memory Pools Inspector
              5 / F5                 JVM Launch Flags, ClassLoading & JIT Inspector
              6 / F6                 Live JDK Flight Recorder (JFR) Event Stream (with Stack Inspector)
              7 / F7                 Spring Boot & Quarkus Telemetry (HikariCP / Agroal / Loggers)
              ← / →                  Switch active Tabs
              Enter                  Inspect Stack Trace & Lock Owner (Threads / JFR Views)
              h                      Trigger Live HotSpot .hprof Heap Dump
              m                      Toggle HotSpot Diagnostic VM Options Tuning Modal
              t                      Cycle Color Theme live (btop -> dracula -> nord -> solarized)
              g                      Trigger Manual System.gc() (GC View)
              /                      Search/Filter processes or threads
              s                      Cycle Sort Order (CPU%, Duration, ID, Name, State)
              v                      Toggle Virtual Threads filter
              q / Ctrl+C             Quit jtop
            """);
    }
}
