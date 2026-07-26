package com.zalexdev.stryker.wifi;

import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.Core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Monitor-mode scan via airodump CSV — fills client counts.
 * Supports fixed duration or continuous (until {@link #stop()}).
 * Band: {@code bg} (2.4), {@code a} (5 GHz), {@code abg} (both).
 */
public class ScanWifiMonitor {

    public interface ProgressListener {
        void onProgress(ArrayList<WiFINetwork> snapshot, int elapsedSec, int hopChannel);
    }

    private static final String HOST_DIR = "/sdcard/Stryker/hs";
    private static final String PREFIX = HOST_DIR + "/monscan";
    private static final String CSV_NAME = "monscan-01.csv";

    private final Core core;
    private final String iface;
    /** 0 = until stopped */
    private final int seconds;
    /** airodump --band: bg | a | abg */
    private final String band;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger hopChannel = new AtomicInteger(0);
    private ProgressListener listener;

    public ScanWifiMonitor(String iface, Core core, int seconds) {
        this(iface, core, seconds, "abg");
    }

    public ScanWifiMonitor(String iface, Core core, int seconds, String band) {
        this.iface = iface;
        this.core = core;
        this.seconds = Math.max(0, seconds);
        this.band = normalizeBand(band);
    }

    private static String normalizeBand(String band) {
        if (band == null) return "abg";
        String b = band.trim().toLowerCase(Locale.US);
        if (b.equals("a") || b.equals("5") || b.equals("5ghz") || b.equals("5g")) return "a";
        if (b.equals("bg") || b.equals("b") || b.equals("g") || b.equals("2.4")
                || b.equals("24") || b.equals("2.4ghz") || b.equals("2g")) return "bg";
        return "abg";
    }

    public ScanWifiMonitor setProgressListener(ProgressListener listener) {
        this.listener = listener;
        return this;
    }

    public int getHopChannel() {
        return hopChannel.get();
    }

    public void stop() {
        stopped.set(true);
        try {
            // Host + chroot — kill by binary name (background setsid jobs survive shell exit)
            core.customCommand(
                    "pkill -9 -f airodump-ng; killall -9 airodump-ng 2>/dev/null; true", true);
            core.customChrootCommand(
                    "pkill -9 -f airodump-ng; killall -9 airodump-ng 2>/dev/null; true", true);
        } catch (Exception ignored) {
        }
    }

    /** Blocking — call from a worker thread. */
    public ArrayList<WiFINetwork> run() {
        ArrayList<WiFINetwork> out = new ArrayList<>();
        try {
            core.customCommand("mkdir -p " + HOST_DIR
                    + "; rm -f " + PREFIX + "-01.csv " + PREFIX + "-01.cap "
                    + PREFIX + "-01.kismet.csv " + HOST_DIR + "/monscan_airo.log "
                    + HOST_DIR + "/monscan.pid", true);
            // Also clear inside chroot mount if distinct
            core.customChrootCommand("mkdir -p /sdcard/Stryker/hs; rm -f /sdcard/Stryker/hs/monscan-01.csv "
                    + "/sdcard/Stryker/hs/monscan_airo.log", true);

            String raw = iface == null || iface.isEmpty() ? "wlan0" : iface;

            boolean ok = core.monitorManager.enableMonitorMode(raw);
            if (!ok) {
                core.customCommand("svc wifi disable", true);
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                ok = core.monitorManager.enableMonitorMode(raw);
            }
            if (!ok || stopped.get()) return out;

            String mon = core.getDeauthInterface();
            if (mon == null || mon.isEmpty()) mon = raw;

            // Do not combine --band with a long -c hop list (many airodump builds exit).
            // Prefer --band for all/5GHz; use -c only for 2.4-only.
            StringBuilder airo = new StringBuilder();
            airo.append("airodump-ng ").append(mon)
                    .append(" -w ").append(PREFIX)
                    .append(" --output-format csv --write-interval 1 --ignore-negative-one");
            if ("bg".equals(band)) {
                airo.append(" --band bg -c 1,2,3,4,5,6,7,8,9,10,11,12,13");
            } else if ("a".equals(band)) {
                airo.append(" --band a");
            } else {
                airo.append(" --band abg");
            }

            // setsid+nohup so airodump survives chroot ash exit (customChrootCommand sends exit)
            String launch = "cd /sdcard/Stryker/hs; "
                    + "rm -f monscan-01.csv monscan.pid; "
                    + "setsid nohup " + airo
                    + " >monscan_airo.log 2>&1 </dev/null & "
                    + "echo $! >monscan.pid; sleep 0.4; "
                    + "test -f monscan.pid && echo AIRO_PID=$(cat monscan.pid); "
                    + "ps | grep -F airodump | grep -v grep | head -3; "
                    + "ls -la /sdcard/Stryker/hs/monscan* 2>/dev/null | head -10";
            ArrayList<String> startOut = core.customChrootCommand(launch, true);
            // Fallback: host-side start if chroot binary path differs
            boolean started = false;
            for (String line : startOut) {
                if (line != null && (line.contains("AIRO_PID=") || line.contains("airodump"))) {
                    started = true;
                    break;
                }
            }
            if (!started) {
                core.customCommand(
                        "mkdir -p " + HOST_DIR + "; cd " + HOST_DIR + "; "
                                + "setsid nohup " + airo
                                + " >monscan_airo.log 2>&1 </dev/null & "
                                + "echo $! >monscan.pid", true);
            }

            // Give airodump a moment to create the CSV
            try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

            int elapsed = 0;
            while (!stopped.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                elapsed++;
                refreshCurrentChannel(mon);
                ArrayList<WiFINetwork> snap = readNetworks();
                sortByClients(snap);
                int hop = hopChannel.get();
                if (listener != null) {
                    try {
                        listener.onProgress(new ArrayList<>(snap), elapsed, hop);
                    } catch (Exception ignored) {}
                }
                out = snap;
                if (seconds > 0 && elapsed >= seconds) break;
            }
            stop();
            // Final flush wait
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            out = readNetworks();
            sortByClients(out);
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
        return out;
    }

    /**
     * Prefer root {@code cat} — app File APIs often cannot see root-written sdcard files.
     */
    private ArrayList<WiFINetwork> readNetworks() {
        ArrayList<String> lines = catCsvLines();
        if (lines != null && !lines.isEmpty()) {
            return parseCsvLines(lines);
        }
        // Fallback file reads
        File[] candidates = new File[]{
                new File("/storage/emulated/0/Stryker/hs/" + CSV_NAME),
                new File("/sdcard/Stryker/hs/" + CSV_NAME),
                new File("/data/local/stryker/release/sdcard/Stryker/hs/" + CSV_NAME)
        };
        for (File f : candidates) {
            if (f.exists() && f.length() > 0) {
                ArrayList<WiFINetwork> parsed = parseCsv(f);
                if (!parsed.isEmpty()) return parsed;
            }
        }
        return new ArrayList<>();
    }

    private ArrayList<String> catCsvLines() {
        String[] paths = new String[]{
                "/sdcard/Stryker/hs/" + CSV_NAME,
                "/storage/emulated/0/Stryker/hs/" + CSV_NAME,
                "/data/local/stryker/release/sdcard/Stryker/hs/" + CSV_NAME
        };
        for (String path : paths) {
            ArrayList<String> out = core.customCommand(
                    "test -s '" + path + "' && cat '" + path + "'", true);
            if (out == null || out.isEmpty()) {
                out = core.customChrootCommand(
                        "test -s '" + path + "' && cat '" + path + "'", true);
            }
            if (out != null && !out.isEmpty()) {
                // Filter noise (su prompts etc.)
                ArrayList<String> cleaned = new ArrayList<>();
                for (String line : out) {
                    if (line == null) continue;
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.startsWith("Password:") || t.startsWith("su:")) continue;
                    cleaned.add(line);
                }
                if (looksLikeAirodumpCsv(cleaned)) return cleaned;
            }
        }
        return null;
    }

    private static boolean looksLikeAirodumpCsv(ArrayList<String> lines) {
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.startsWith("BSSID") || t.startsWith("Station MAC")
                    || (t.length() > 16 && t.contains(":") && t.contains(","))) {
                return true;
            }
        }
        return false;
    }

    private void refreshCurrentChannel(String mon) {
        try {
            ArrayList<String> out = core.customChrootCommand(
                    "iw dev " + mon + " info 2>/dev/null | awk '/channel/ {print $2; exit}'", true);
            if (out == null || out.isEmpty()) {
                out = core.customCommand(
                        "iw dev " + mon + " info 2>/dev/null | awk '/channel/ {print $2; exit}'", true);
            }
            if (out != null) {
                for (String line : out) {
                    if (line == null) continue;
                    String t = line.trim();
                    if (t.matches("\\d+")) {
                        hopChannel.set(Integer.parseInt(t));
                        return;
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void sortByClients(ArrayList<WiFINetwork> list) {
        if (list == null) return;
        Collections.sort(list, (a, b) -> {
            int c = Integer.compare(b.getClientCount(), a.getClientCount());
            if (c != 0) return c;
            return Integer.compare(a.getPower(), b.getPower());
        });
    }

    static ArrayList<WiFINetwork> parseCsv(File csv) {
        ArrayList<WiFINetwork> aps = new ArrayList<>();
        if (csv == null || !csv.exists()) return aps;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            ArrayList<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            return parseCsvLines(lines);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return aps;
    }

    static ArrayList<WiFINetwork> parseCsvLines(ArrayList<String> lines) {
        ArrayList<WiFINetwork> aps = new ArrayList<>();
        Map<String, WiFINetwork> byBssid = new HashMap<>();
        if (lines == null) return aps;
        boolean stations = false;
        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("Station MAC")) {
                stations = true;
                continue;
            }
            if (line.startsWith("BSSID")) {
                stations = false;
                continue;
            }
            String[] cols = line.split(",", -1);
            if (!stations) {
                if (cols.length < 14) continue;
                String bssid = cols[0].trim();
                if (!bssid.contains(":") || bssid.equalsIgnoreCase("BSSID")) continue;
                // Skip empty leftover rows
                if (bssid.equalsIgnoreCase("Station MAC")) continue;
                WiFINetwork n = new WiFINetwork();
                n.setMac(bssid);
                int ch = 0;
                try { ch = Integer.parseInt(cols[3].trim()); } catch (Exception ignored) {}
                if (ch > 0) n.setChannel(ch);
                n.setIs5hhz(ch >= 36);
                try { n.setPower(Integer.parseInt(cols[8].trim())); } catch (Exception ignored) {}
                String essid = cols[13].trim();
                n.setSsid(essid.isEmpty() ? "<hidden>" : essid);
                String privacy = cols.length > 5 ? cols[5].toUpperCase(Locale.US) : "";
                n.setWps(privacy.contains("WPS"));
                byBssid.put(bssid.toLowerCase(Locale.US), n);
                aps.add(n);
            } else {
                if (cols.length < 6) continue;
                String sta = cols[0].trim().toLowerCase(Locale.US);
                String ap = cols[5].trim().toLowerCase(Locale.US);
                if (!sta.contains(":") || !ap.contains(":")) continue;
                if (ap.contains("(not associated)")) continue;
                WiFINetwork n = byBssid.get(ap);
                if (n != null) n.addClient(sta);
            }
        }
        return aps;
    }
}
