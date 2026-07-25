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

/**
 * Monitor-mode scan via airodump CSV — fills client counts.
 * Supports fixed duration or continuous (until {@link #stop()}).
 */
public class ScanWifiMonitor {

    public interface ProgressListener {
        void onProgress(ArrayList<WiFINetwork> snapshot, int elapsedSec);
    }

    private final Core core;
    private final String iface;
    /** 0 = until stopped */
    private final int seconds;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private ProgressListener listener;

    public ScanWifiMonitor(String iface, Core core, int seconds) {
        this.iface = iface;
        this.core = core;
        this.seconds = Math.max(0, seconds);
    }

    public ScanWifiMonitor setProgressListener(ProgressListener listener) {
        this.listener = listener;
        return this;
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
            core.customCommand("mkdir -p /sdcard/Stryker/hs; rm -f " + prefix + "-01.csv", true);
            String raw = iface == null || iface.isEmpty() ? "wlan0" : iface;
            boolean ok = core.monitorManager.enableMonitorMode(raw);
            if (!ok) {
                core.customCommand("svc wifi disable", true);
                try { Thread.sleep(600); } catch (InterruptedException ignored) {}
                ok = core.monitorManager.enableMonitorMode(raw);
            }
            if (!ok || stopped.get()) return out;

            String mon = core.getDeauthInterface();
            // Start airodump in background (no nested sleep in one blocking command)
            core.customChrootCommand(
                    "rm -f " + prefix + "-01.csv; "
                            + "airodump-ng " + mon
                            + " -w " + prefix
                            + " --output-format csv --write-interval 1 --ignore-negative-one"
                            + " >/dev/null 2>&1 &");

            File csv = new File("/storage/emulated/0/Stryker/hs/monscan-01.csv");
            File csvAlt = new File("/sdcard/Stryker/hs/monscan-01.csv");
            int elapsed = 0;
            while (!stopped.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                elapsed++;
                File f = csv.exists() ? csv : csvAlt;
                ArrayList<WiFINetwork> snap = parseCsv(f);
                sortByClients(snap);
                if (listener != null) {
                    try { listener.onProgress(new ArrayList<>(snap), elapsed); } catch (Exception ignored) {}
                }
                out = snap;
                if (seconds > 0 && elapsed >= seconds) break;
            }
            stop();
            // Final parse
            File f = csv.exists() ? csv : csvAlt;
            out = parseCsv(f);
            sortByClients(out);
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
        return out;
    }

    private static void sortByClients(ArrayList<WiFINetwork> list) {
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
                    try { n.setChannel(Integer.parseInt(cols[3].trim())); } catch (Exception ignored) {}
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
