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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Monitor-mode scan via airodump CSV — AP list + associated client counts.
 * <p>
 * Runs airodump in the foreground inside chroot (thread blocks until killed),
 * polls the CSV via root {@code cat}, and normalizes Power to the same
 * positive-attenuation convention as station {@code iw scan} (so UI
 * {@code 100 - power} works).
 */
public class ScanWifiMonitor {

    public interface ProgressListener {
        void onProgress(ArrayList<WiFINetwork> snapshot, int elapsedSec, int hopChannel);
    }

    private static final String HOST_DIR = "/sdcard/Stryker/hs";
    private static final String PREFIX = HOST_DIR + "/monscan";
    private static final String CSV_REL = "monscan-01.csv";
    private static final Pattern MAC_RE =
            Pattern.compile("(?i)((?:[0-9a-f]{2}:){5}[0-9a-f]{2})");

    private final Core core;
    private final String iface;
    /** 0 = until stopped */
    private final int seconds;
    private final String band;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final AtomicInteger hopChannel = new AtomicInteger(0);
    private ProgressListener listener;
    private Thread airoThread;
    /** Cache OUI → vendor across CSV polls in one scan. */
    private final Map<String, String> vendorCache = new HashMap<>();

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
            prepareDirs();
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
            // Resolve *mon rename if present
            try {
                String resolved = core.monitorManager.resolveMonitorIface(raw);
                if (resolved != null && !resolved.isEmpty()) mon = resolved;
            } catch (Exception ignored) {}

            final String airoCmd = buildAirodumpCmd(mon);
            airoThread = new Thread(() -> {
                // Foreground airodump — stays alive until stop() pkills it.
                // customChrootCommand blocks here (no background &).
                try {
                    core.customChrootCommand(airoCmd, true);
                } catch (Exception ignored) {}
            }, "mon-airodump");
            airoThread.start();

            // Wait for CSV to appear
            for (int i = 0; i < 8 && !stopped.get(); i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                if (!readNetworks().isEmpty()) break;
            }

            int elapsed = 0;
            while (!stopped.get()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                elapsed++;
                refreshCurrentChannel(mon);
                ArrayList<WiFINetwork> snap = readNetworks();
                enrichVendors(snap);
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
            try {
                if (airoThread != null) airoThread.join(3000);
            } catch (InterruptedException ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            out = readNetworks();
            enrichVendors(out);
            sortByClients(out);
        } catch (Exception e) {
            e.printStackTrace();
            stop();
        }
        return out;
    }

    private void enrichVendors(ArrayList<WiFINetwork> list) {
        if (list == null || core == null) return;
        for (WiFINetwork n : list) {
            if (n == null || n.getMac() == null) continue;
            String mac = n.getMac().toLowerCase(Locale.US);
            String cached = vendorCache.get(mac);
            if (cached != null) {
                if (!cached.isEmpty()) n.setVendor(cached);
                continue;
            }
            String cur = n.getVendor();
            if (cur != null && !cur.isEmpty() && !"Unknown".equalsIgnoreCase(cur)) {
                vendorCache.put(mac, cur);
                continue;
            }
            try {
                String v = core.getVendorByMacFromDB(n.getMac());
                if (v == null) v = "";
                vendorCache.put(mac, v);
                if (!v.isEmpty()) n.setVendor(v);
            } catch (Exception ignored) {
                vendorCache.put(mac, "");
            }
        }
    }

    private void prepareDirs() {
        core.customCommand("mkdir -p " + HOST_DIR
                + "; rm -f " + PREFIX + "-01.csv " + PREFIX + "-01.cap "
                + PREFIX + "-01.kismet.csv " + PREFIX + "-01.kismet.netxml "
                + HOST_DIR + "/monscan_airo.log", true);
        core.customChrootCommand(
                "mkdir -p /sdcard/Stryker/hs; rm -f /sdcard/Stryker/hs/monscan-01.csv "
                        + "/sdcard/Stryker/hs/monscan_airo.log", true);
    }

    private String buildAirodumpCmd(String mon) {
        // Only csv — faster writes, no huge pcap. write-interval 1 = update every second.
        StringBuilder sb = new StringBuilder();
        sb.append("airodump-ng ").append(mon)
                .append(" -w ").append(PREFIX)
                .append(" --output-format csv")
                .append(" --write-interval 1")
                .append(" --ignore-negative-one");
        // Do NOT mix a huge -c list with --band (breaks some builds).
        if ("bg".equals(band)) {
            sb.append(" --band bg");
        } else if ("a".equals(band)) {
            sb.append(" --band a");
        } else {
            sb.append(" --band abg");
        }
        sb.append(" >/sdcard/Stryker/hs/monscan_airo.log 2>&1");
        return sb.toString();
    }

    private ArrayList<WiFINetwork> readNetworks() {
        ArrayList<String> lines = catCsvLines();
        if (lines != null && !lines.isEmpty()) {
            return parseCsvLines(lines);
        }
        File[] candidates = new File[]{
                new File("/storage/emulated/0/Stryker/hs/" + CSV_REL),
                new File("/sdcard/Stryker/hs/" + CSV_REL),
                new File("/data/local/stryker/release/sdcard/Stryker/hs/" + CSV_REL)
        };
        for (File f : candidates) {
            if (f.exists() && f.length() > 32) {
                ArrayList<WiFINetwork> parsed = parseCsv(f);
                if (!parsed.isEmpty()) return parsed;
            }
        }
        return new ArrayList<>();
    }

    private ArrayList<String> catCsvLines() {
        String[] paths = new String[]{
                "/sdcard/Stryker/hs/" + CSV_REL,
                "/storage/emulated/0/Stryker/hs/" + CSV_REL,
                "/data/local/stryker/release/sdcard/Stryker/hs/" + CSV_REL
        };
        for (String path : paths) {
            // sync so we see airodump's latest write
            ArrayList<String> out = core.customCommand(
                    "sync; test -s '" + path + "' && cat '" + path + "'", true);
            if (out == null || out.isEmpty()) {
                out = core.customChrootCommand(
                        "sync; test -s '" + path + "' && cat '" + path + "'", true);
            }
            if (out == null || out.isEmpty()) continue;
            ArrayList<String> cleaned = new ArrayList<>();
            for (String line : out) {
                if (line == null) continue;
                String t = line.trim();
                if (t.isEmpty()) continue;
                if (t.startsWith("Password:") || t.startsWith("su:") || t.startsWith("Permission")) {
                    continue;
                }
                cleaned.add(line);
            }
            if (looksLikeAirodumpCsv(cleaned)) return cleaned;
        }
        return null;
    }

    private static boolean looksLikeAirodumpCsv(ArrayList<String> lines) {
        for (String line : lines) {
            if (line == null) continue;
            String t = line.trim();
            if (t.startsWith("BSSID") || t.startsWith("Station MAC")) return true;
            Matcher m = MAC_RE.matcher(t);
            if (m.find() && t.contains(",")) return true;
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
            // Lower attenuation = stronger (same convention as station scan)
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

    /**
     * Parse airodump CSV.
     * AP columns: BSSID, First, Last, channel, Speed, Privacy, Cipher, Auth,
     * Power, #beacons, #IV, LAN IP, ID-length, ESSID, Key
     * Station: Station MAC, First, Last, Power, #packets, BSSID, Probed ESSIDs
     * <p>
     * Power is stored as positive attenuation (abs of dBm) to match station scan UI.
     */
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
                WiFINetwork n = parseApRow(cols);
                if (n == null) continue;
                String key = n.getMac().toLowerCase(Locale.US);
                byBssid.put(key, n);
                aps.add(n);
            } else {
                parseStationRow(cols, byBssid);
            }
        }
        return aps;
    }

    private static WiFINetwork parseApRow(String[] cols) {
        if (cols == null || cols.length < 14) return null;
        String bssid = cols[0].trim();
        Matcher m = MAC_RE.matcher(bssid);
        if (!m.find()) return null;
        bssid = m.group(1);

        WiFINetwork n = new WiFINetwork();
        n.setMac(bssid);

        int ch = parseIntSafe(cols[3]);
        if (ch > 0) {
            n.setChannel(ch);
            n.setIs5hhz(ch >= 36);
        }

        // Power: modern airodump = negative dBm (-30..-100), -1 = unknown
        int pwr = normalizeAirodumpPower(cols.length > 8 ? cols[8] : null);
        n.setPower(pwr);

        String privacy = cols.length > 5 ? safe(cols[5]) : "";
        String cipher = cols.length > 6 ? safe(cols[6]) : "";
        String auth = cols.length > 7 ? safe(cols[7]) : "";
        String enc = joinEnc(privacy, cipher, auth);
        n.setEncryption(enc);
        String encUp = enc.toUpperCase(Locale.US);
        // WPS rarely in airodump privacy; still detect if present
        n.setWps(encUp.contains("WPS"));

        n.setUptimeSec(computeUptimeSec(
                cols.length > 1 ? cols[1] : null,
                cols.length > 2 ? cols[2] : null));

        String essid = extractEssid(cols);
        n.setSsid(essid == null || essid.isEmpty() ? "<hidden>" : essid);

        ArrayList<String> info = new ArrayList<>();
        if (!enc.isEmpty()) info.add("Encryption: " + enc);
        if (ch > 0) info.add("Channel: " + ch + (ch >= 36 ? " (5 GHz)" : " (2.4 GHz)"));
        int beacons = cols.length > 9 ? parseIntSafe(cols[9]) : 0;
        if (beacons > 0) info.add("Beacons: " + beacons);
        int ivs = cols.length > 10 ? parseIntSafe(cols[10]) : 0;
        if (ivs > 0) info.add("Data/IVs: " + ivs);
        if (n.getUptimeSec() > 0) info.add("Seen for: " + n.getUptimeLabel());
        n.setInfo(info);
        return n;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private static String joinEnc(String privacy, String cipher, String auth) {
        StringBuilder sb = new StringBuilder();
        if (!privacy.isEmpty()) sb.append(privacy);
        if (!cipher.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(cipher);
        }
        if (!auth.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(auth);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** Seconds between airodump First/Last time seen. */
    static int computeUptimeSec(String first, String last) {
        long a = parseAirodumpTime(first);
        long b = parseAirodumpTime(last);
        if (a <= 0 || b <= 0 || b < a) return 0;
        return (int) Math.min(Integer.MAX_VALUE, (b - a) / 1000L);
    }

    private static long parseAirodumpTime(String raw) {
        if (raw == null) return 0;
        String t = raw.trim();
        if (t.isEmpty()) return 0;
        // 2024-07-26 12:48:58
        String[] patterns = {
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss.SSS"
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat fmt =
                        new java.text.SimpleDateFormat(p, Locale.US);
                fmt.setLenient(true);
                java.util.Date d = fmt.parse(t);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static void parseStationRow(String[] cols, Map<String, WiFINetwork> byBssid) {
        if (cols == null || cols.length < 6) return;
        Matcher staM = MAC_RE.matcher(cols[0].trim());
        if (!staM.find()) return;
        String sta = staM.group(1).toLowerCase(Locale.US);

        // BSSID is column 5; may have spaces. Probed ESSIDs follow.
        String apField = cols[5].trim();
        if (apField.isEmpty() || apField.toLowerCase(Locale.US).contains("not associated")) {
            return;
        }
        Matcher apM = MAC_RE.matcher(apField);
        if (!apM.find()) return;
        String ap = apM.group(1).toLowerCase(Locale.US);

        WiFINetwork n = byBssid.get(ap);
        if (n != null) {
            n.addClient(sta);
        }
    }

    private static String extractEssid(String[] cols) {
        if (cols.length < 14) return "";
        int idLen = parseIntSafe(cols[12]);
        // Prefer joining ESSID..Key-1 (handles commas in name)
        if (cols.length >= 15) {
            StringBuilder sb = new StringBuilder(cols[13] == null ? "" : cols[13]);
            for (int i = 14; i < cols.length - 1; i++) {
                sb.append(',').append(cols[i] == null ? "" : cols[i]);
            }
            String joined = sb.toString().trim();
            if (idLen > 0 && joined.length() > idLen) {
                // trim to declared length when it looks padded
                return joined.substring(0, Math.min(idLen, joined.length())).trim();
            }
            return joined;
        }
        return cols[13] == null ? "" : cols[13].trim();
    }

    /**
     * Convert airodump Power to positive attenuation used by station scan / UI
     * ({@code percent ≈ 100 - power}).
     */
    static int normalizeAirodumpPower(String raw) {
        if (raw == null) return 70;
        String t = raw.trim();
        if (t.isEmpty()) return 70;
        int p;
        try {
            p = Integer.parseInt(t);
        } catch (Exception e) {
            return 70;
        }
        // Unknown / not yet measured
        if (p == -1 || p == 0) return 90;
        // Modern airodump: negative dBm
        if (p < 0) {
            p = Math.abs(p);
            return Math.max(1, Math.min(99, p));
        }
        // Already positive attenuation (rare) or legacy odd values
        if (p > 100) {
            // legacy unsigned-ish — treat as weak
            return 85;
        }
        return Math.max(1, Math.min(99, p));
    }

    private static int parseIntSafe(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }
}
