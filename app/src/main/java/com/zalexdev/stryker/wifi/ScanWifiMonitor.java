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
            core.customChrootCommand(
                    "pkill -9 -f 'airodump-ng.*monscan'; killall -9 airodump-ng 2>/dev/null; true");
        } catch (Exception ignored) {
        }
    }

    /** Blocking — call from a worker thread. */
    public ArrayList<WiFINetwork> run() {
        ArrayList<WiFINetwork> out = new ArrayList<>();
        String prefix = "/sdcard/Stryker/hs/monscan";
        try {
            core.customCommand("mkdir -p /sdcard/Stryker/hs; rm -f " + prefix + "-01.csv "
                    + prefix + "-01.cap " + prefix + "-01.kismet.csv", true);
            String raw = iface == null || iface.isEmpty() ? "wlan0" : iface;

            // Fresh monitor — do NOT lock a channel (that freezes hop on ch 1)
            boolean ok = core.monitorManager.enableMonitorMode(raw);
            if (!ok) {
                core.customCommand("svc wifi disable", true);
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                ok = core.monitorManager.enableMonitorMode(raw);
            }
            if (!ok || stopped.get()) return out;

            String mon = core.getDeauthInterface();
            String hopList = hopChannelsForBand(band);
            // Explicit hop list + band so drivers that ignore default hop still sweep
            String airo = "airodump-ng " + mon
                    + " -w " + prefix
                    + " --output-format csv --write-interval 1 --ignore-negative-one"
                    + " --band " + band
                    + (hopList.isEmpty() ? "" : (" -c " + hopList))
                    + " >/dev/null 2>&1 &";
            core.customChrootCommand("rm -f " + prefix + "-01.csv; " + airo);

            File csv = new File("/storage/emulated/0/Stryker/hs/monscan-01.csv");
            File csvAlt = new File("/sdcard/Stryker/hs/monscan-01.csv");
            int elapsed = 0;
            while (!stopped.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                elapsed++;
                refreshCurrentChannel(mon);
                File f = csv.exists() ? csv : csvAlt;
                ArrayList<WiFINetwork> snap = parseCsv(f);
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
            File f = csv.exists() ? csv : csvAlt;
            out = parseCsv(f);
            sortByClients(out);
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
        return out;
    }

    private void refreshCurrentChannel(String mon) {
        try {
            ArrayList<String> out = core.customChrootCommand(
                    "iw dev " + mon + " info 2>/dev/null | awk '/channel/ {print $2; exit}'", true);
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

    static String hopChannelsForBand(String band) {
        String b = normalizeBand(band);
        if ("a".equals(b)) {
            return "36,40,44,48,52,56,60,64,100,104,108,112,116,120,124,128,132,136,140,149,153,157,161,165";
        }
        if ("bg".equals(b)) {
            return "1,2,3,4,5,6,7,8,9,10,11,12,13,14";
        }
        // both — airodump may prefer --band abg without -c on some builds;
        // still pass a combined list for stubborn drivers
        return "1,6,11,2,7,12,3,8,13,4,9,14,5,10,"
                + "36,40,44,48,149,153,157,161,165";
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
        Map<String, WiFINetwork> byBssid = new HashMap<>();
        if (csv == null || !csv.exists()) return aps;
        boolean stations = false;
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
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
        } catch (Exception e) {
            e.printStackTrace();
        }
        return aps;
    }
}
