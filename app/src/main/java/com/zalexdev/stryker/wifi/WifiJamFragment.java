package com.zalexdev.stryker.wifi;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
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
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.utils.AdvancedProcess;
import com.zalexdev.stryker.utils.Core;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dedicated WiFi jamming / RF attack hub (mdk4 modes).
 * Mass deauth FAB also uses {@link #launchMdk4(Activity, Context, Core, String, String)}.
 */
public class WifiJamFragment extends Fragment {

    public static final class Mode {
        public final String flag;
        public final String title;
        public final String subtitle;

        Mode(String flag, String title, String subtitle) {
            this.flag = flag;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    public static final Mode[] MODES = {
            new Mode("d", "Deauth / Disassoc", "Mass disconnect clients (channel hop)"),
            new Mode("b", "Beacon flood", "Flood fake APs / SSIDs"),
            new Mode("a", "Auth DoS", "Authentication flood against APs"),
            new Mode("p", "Probe flood", "Probe request flood"),
            new Mode("m", "Michael TKIP", "TKIP countermeasures shutdown"),
            new Mode("w", "Wifi traffic", "Continuous 802.11 traffic generator"),
    };

    private Core core;
    private Activity activity;
    private Context context;
    private AdvancedProcess mdk4;

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
            row.setOnClickListener(v -> launchMdk4(activity, context, core, mode.flag, mode.title));
            list.addView(row);
        }
    }

    @Override
    public void onDestroyView() {
        if (mdk4 != null) mdk4.kill();
        super.onDestroyView();
    }

    /** Shared launcher used by this screen and WiFi mass-deauth FAB. */
    public static void launchMdk4(Activity activity, Context context, Core core,
                                  String modeFlag, String title) {
        if (activity == null || context == null || core == null) return;
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
        outputtext.setMovementMethod(new ScrollingMovementMethod());

        String flag = (modeFlag == null || modeFlag.isEmpty()) ? "d" : modeFlag;
        outputtext.append("Starting monitor + mdk4 mode '" + flag + "' on "
                + core.getString("wlan_deauth") + "...\n");

        final AdvancedProcess[] proc = {null};

        new Thread(() -> {
            String deauthRaw = core.getString("wlan_deauth");
            if (deauthRaw == null || deauthRaw.isEmpty()) {
                deauthRaw = core.getDeauthInterface();
            }
            if (core.monitorManager.enableMonitorMode(deauthRaw)) {
                String deauthIface = core.getDeauthInterface();
                activity.runOnUiThread(() -> outputtext.append(
                        "Monitor ready on " + deauthIface + " · mdk4 " + flag + "\n"));
                proc[0] = new AdvancedProcess(activity, context,
                        "mdk4 " + deauthIface + " " + flag, true) {
                    @Override
                    public void onFinished(ArrayList<String> outputList) {
                        activity.runOnUiThread(() -> {
                            outputcard.setVisibility(View.GONE);
                            resulttext.setVisibility(View.VISIBLE);
                            resulttext.setText("Attack stopped");
                        });
                    }

                    @Override
                    public void onNewLine(String line) {
                        if (line == null) return;
                        if (line.contains("Packets sent")) {
                            activity.runOnUiThread(() -> outputtext.setText(""));
                        }
                        String out = line;
                        if (core.getBoolean("hide")) {
                            Matcher m = Pattern.compile("((\\w{2}:){5}\\w{2})").matcher(out);
                            if (m.find()) {
                                out = out.replace(m.group(), Core.HIDDEN_MAC);
                            }
                        }
                        String finalOut = out;
                        activity.runOnUiThread(() -> {
                            outputtext.append(finalOut + "\n");
                            if (outputtext.getLayout() != null) {
                                int scroll = outputtext.getLayout().getLineTop(outputtext.getLineCount())
                                        - outputtext.getHeight();
                                outputtext.scrollTo(0, Math.max(scroll, 0));
                            }
                        });
                    }

                    @Override
                    public void onEvent(String line) {
                    }
                };
            } else {
                activity.runOnUiThread(() -> {
                    outputcard.setVisibility(View.GONE);
                    resulttext.setVisibility(View.VISIBLE);
                    resulttext.setText("Failed to start monitor mode");
                });
            }
        }, "mdk4-launch").start();

        stop.setOnClickListener(v -> {
            if (proc[0] != null) proc[0].kill();
            new Thread(() -> core.monitorManager.disableMonitorMode(core.getDeauthInterface())).start();
            stop.setVisibility(View.GONE);
            dialog.setCancelable(true);
            outputcard.setVisibility(View.GONE);
            resulttext.setVisibility(View.VISIBLE);
            resulttext.setText("Attack stopped");
        });
        dialog.show();
    }
}
