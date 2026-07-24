package com.zalexdev.stryker.handshakes.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.Utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BruteHandshake extends AsyncTask<Void, String, WiFINetwork> {
    public static final String ENGINE_AIRCRACK = "aircrack";
    public static final String ENGINE_HASHCAT = "hashcat";

    public String exec = Core.EXECUTE;
    public String path;
    public String wordlist;
    public Core core;
    public Activity activity;
    public TextView progress;
    public TextView time;
    public TextView liveLog;
    public Context context;
    public int id;
    public Process process;
    public Logger logger;
    public String engine = ENGINE_AIRCRACK;

    public BruteHandshake(String p, String w, Core c, Activity a, Context con, TextView pr, TextView t, int i) {
        this(p, w, c, a, con, pr, t, null, i, ENGINE_AIRCRACK);
    }

    public BruteHandshake(String p, String w, Core c, Activity a, Context con,
                          TextView pr, TextView t, TextView log, int i, String engine) {
        core = c;
        path = p;
        wordlist = w;
        activity = a;
        progress = pr;
        time = t;
        liveLog = log;
        context = con;
        id = i;
        this.engine = engine == null ? ENGINE_AIRCRACK : engine;
        logger = new Logger();
    }

    @SuppressLint("WrongThread")
    @Override
    protected WiFINetwork doInBackground(Void... command) {
        String line;
        WiFINetwork result = new WiFINetwork();
        logger.writeLine("Starting brute handshake (" + engine + ")", 1);
        try {
            process = Runtime.getRuntime().exec("su");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            stdin.write((buildCommand() + '\n').getBytes());
            stdin.flush();
            stdin.close();

            BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
            while ((line = br.readLine()) != null) {
                logger.writeLine(line, 2);
                onProgressUpdate(line);
                if (lineContainsKey(line)) {
                    String key = extractKey(line);
                    if (key != null) result.setPsk(key);
                    result.setOK(true);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        CreateNotification("Success", "Password found: " + result.getPsk(), 100, 100);
                    }
                }
            }
            br.close();
            br = new BufferedReader(new InputStreamReader(stderr));
            while ((line = br.readLine()) != null) {
                logger.writeLine(line, 3);
                onProgressUpdate(line);
                if (lineContainsKey(line)) {
                    String key = extractKey(line);
                    if (key != null) result.setPsk(key);
                    result.setOK(true);
                }
            }
            br.close();
            process.waitFor();
            process.destroy();

        } catch (IOException | InterruptedException e) {
            // ignore
        }
        if (!result.getOK()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CreateNotification("Failed", "Password Not Found", 100, 100);
            }
        }
        return result;
    }

    private String buildCommand() {
        // Paths are already /sdcard/... relative forms from the adapter.
        String cap = path.startsWith("/") ? path : "/sdcard/Stryker/captured/" + path;
        String wl = wordlist.startsWith("/") ? wordlist : "/sdcard/Stryker/wordlists/" + wordlist;
        if (ENGINE_HASHCAT.equals(engine)) {
            // Convert .cap → .hccapx inside chroot when needed, then run hashcat -m 2500.
            return exec + "'bash -lc \"set -e; "
                    + "CAP=" + shellQuote(cap) + "; WL=" + shellQuote(wl) + "; "
                    + "OUT=/tmp/stryker_hs.hccapx; "
                    + "if command -v cap2hccapx >/dev/null 2>&1; then cap2hccapx \\\"$CAP\\\" \\\"$OUT\\\"; "
                    + "elif command -v aircrack-ng >/dev/null 2>&1; then aircrack-ng -j /tmp/stryker_hs \\\"$CAP\\\" >/dev/null; OUT=/tmp/stryker_hs.hccapx; fi; "
                    + "hashcat -m 2500 -a 0 \\\"$OUT\\\" \\\"$WL\\\" --force --status --status-timer=2\"'";
        }
        return exec + "'aircrack-ng -w " + wl + " " + cap + " '";
    }

    private static String shellQuote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private boolean lineContainsKey(String line) {
        if (line == null) return false;
        return line.contains("KEY FOUND! [ ")
                || (line.toLowerCase(Locale.US).contains("cracked") && line.contains(":"));
    }

    private String extractKey(String line) {
        Matcher matcher = Pattern.compile("\\[ (.*?)\\]").matcher(line);
        if (matcher.find()) return matcher.group(1);
        // hashcat Recovered line style varies; keep simple trailing token after :
        if (line.toLowerCase(Locale.US).contains("cracked")) {
            String[] parts = line.split(":");
            if (parts.length > 1) return parts[parts.length - 1].trim();
        }
        return null;
    }

    public void kill() {
        if (process != null) {
            process.destroy();
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        super.onProgressUpdate(values);
        activity.runOnUiThread(() -> {
            String raw = values[0];
            if (liveLog != null) {
                liveLog.append(raw + "\n");
                if (liveLog.getLayout() != null) {
                    int scroll = liveLog.getLayout().getLineTop(liveLog.getLineCount()) - liveLog.getHeight();
                    liveLog.scrollTo(0, Math.max(scroll, 0));
                }
            }

            String rem = "";
            Matcher matcher = Pattern.compile("\\d+/\\d+").matcher(raw);
            Matcher matcher2 = Pattern.compile("\\d+ hours").matcher(raw);
            Matcher matcher3 = Pattern.compile("\\d+ minutes").matcher(raw);
            Matcher matcher4 = Pattern.compile("\\d+ seconds").matcher(raw);
            Matcher speed = Pattern.compile("([\\d.]+)\\s*k/s", Pattern.CASE_INSENSITIVE).matcher(raw);
            if (matcher2.find()) rem = rem + matcher2.group(0) + " ";
            if (matcher3.find()) rem = rem + matcher3.group(0) + " ";
            if (matcher4.find()) rem = rem + matcher4.group(0) + " ";

            int pr = 0;
            int all = 0;
            String ratio = null;
            if (matcher.find()) {
                ratio = matcher.group(0);
                pr = Integer.parseInt(Objects.requireNonNull(ratio).split("/")[0]);
                all = Integer.parseInt(Objects.requireNonNull(ratio).split("/")[1]);
                String speedTxt = speed.find() ? speed.group(1) + " k/s" : "";
                if (progress != null) {
                    progress.setText("Tried " + ratio + (speedTxt.isEmpty() ? "" : " · " + speedTxt));
                }
            } else if (speed.find() && progress != null) {
                progress.setText("Speed " + speed.group(1) + " k/s");
            }
            if (rem.length() != 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    CreateNotification(ratio != null ? ratio : "Brute", rem, pr, all);
                }
                if (time != null) {
                    time.setVisibility(TextView.VISIBLE);
                    time.setText("ETA " + rem.trim());
                }
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public void CreateNotification(String key, String left, int prog, int max) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(context, 0, intent, Utils.setPendingIntentFlag());
        String CHANNEL_ID = "BruteForce";
        NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "BruteForce", NotificationManager.IMPORTANCE_LOW);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context);

        b.setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(R.drawable.bolt)
                .setTicker("Brute")
                .setContentTitle(left)
                .setContentText(key)
                .setChannelId(CHANNEL_ID)
                .setDefaults(Notification.DEFAULT_LIGHTS | Notification.DEFAULT_SOUND)
                .setContentIntent(contentIntent)
                .setProgress(max, prog, false)
                .setContentInfo("Info");

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(id, b.build());
    }
}
