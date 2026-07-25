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
import android.os.SystemClock;
import android.widget.TextView;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.zalexdev.stryker.MainActivity;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.logger.Logger;
import com.zalexdev.stryker.utils.Core;
import com.zalexdev.stryker.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handshake cracker (aircrack-ng / hashcat).
 * aircrack redraws progress with {@code \\r}, so we must not use readLine().
 */
public class BruteHandshake extends AsyncTask<Void, String, WiFINetwork> {
    public static final String ENGINE_AIRCRACK = "aircrack";
    public static final String ENGINE_HASHCAT = "hashcat";

    /** Optional UI hooks — card stays alive even when log dialog is closed. */
    public interface Listener {
        void onCardProgress(String progressLine, String etaLine);
        void onLogLine(String line);
        void onStatus(String status);
    }

    private static final Pattern PROGRESS = Pattern.compile(
            "\\[\\s*(\\d{1,2}:\\d{2}:\\d{2})\\s*]\\s*([\\d,]+)\\s*(?:/\\s*([\\d,]+))?\\s*keys?\\s+tested\\s*\\(\\s*([\\d.]+)\\s*k/s\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENT_PASS = Pattern.compile(
            "(?:Current\\s+passphrase|Trying|Testing)\\s*:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_LEFT = Pattern.compile(
            "Time\\s+left\\s*:\\s*(.+?)(?:\\s{2,}|\\d+(?:\\.\\d+)?%\\s*$|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_FOUND = Pattern.compile(
            "KEY\\s+FOUND!\\s*\\[\\s*(.+?)\\s*]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");

    public String exec = Core.EXECUTE;
    public String path;
    public String wordlist;
    public Core core;
    public Activity activity;
    public TextView progress;
    public TextView time;
    public TextView liveLog;
    public TextView statusView;
    public Context context;
    public int id;
    public Process process;
    public Logger logger;
    public String engine = ENGINE_AIRCRACK;
    public Listener listener;

    private final AtomicBoolean killed = new AtomicBoolean(false);
    private long lastUiMs;
    private long lastLogAppendMs;

    public BruteHandshake(String p, String w, Core c, Activity a, Context con, TextView pr, TextView t, int i) {
        this(p, w, c, a, con, pr, t, null, null, i, ENGINE_AIRCRACK);
    }

    public BruteHandshake(String p, String w, Core c, Activity a, Context con,
                          TextView pr, TextView t, TextView log, int i, String engine) {
        this(p, w, c, a, con, pr, t, log, null, i, engine);
    }

    public BruteHandshake(String p, String w, Core c, Activity a, Context con,
                          TextView pr, TextView t, TextView log, TextView status,
                          int i, String engine) {
        core = c;
        path = p;
        wordlist = w;
        activity = a;
        progress = pr;
        time = t;
        liveLog = log;
        statusView = status;
        context = con;
        id = i;
        this.engine = engine == null ? ENGINE_AIRCRACK : engine;
        logger = new Logger();
    }

    public BruteHandshake setListener(Listener listener) {
        this.listener = listener;
        return this;
    }

    /** Re-bind UI after RecyclerView recycle; crack process keeps running. */
    public void attachUi(TextView pr, TextView t, TextView log, TextView status) {
        progress = pr;
        time = t;
        liveLog = log;
        statusView = status;
    }

    @SuppressLint("WrongThread")
    @Override
    protected WiFINetwork doInBackground(Void... command) {
        WiFINetwork result = new WiFINetwork();
        logger.writeLine("Starting brute handshake (" + engine + ")", 1);
        publishUi("Starting " + engine + "…", true);
        try {
            process = Runtime.getRuntime().exec("su");
            OutputStream stdin = process.getOutputStream();
            InputStream stderr = process.getErrorStream();
            InputStream stdout = process.getInputStream();
            String cmd = buildCommand();
            publishUi("$ " + cmd.replace(exec, "chroot_exec "), true);
            stdin.write((cmd + '\n').getBytes());
            stdin.flush();
            // Keep stdin open briefly so aircrack can read the auto-selected index from the pipe
            // inside the chroot command; then close our side.
            stdin.close();

            Thread errThread = new Thread(() -> pumpStream(stderr, result), "hs-brute-err");
            errThread.start();
            pumpStream(stdout, result);
            try {
                errThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            process.waitFor();
            process.destroy();
        } catch (IOException | InterruptedException e) {
            publishUi("Error: " + e.getMessage(), true);
        }
        if (!result.getOK()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CreateNotification("Failed", "Password Not Found", 100, 100);
            }
            publishUi("Finished — passphrase not found in wordlist.", true);
        }
        return result;
    }

    private void pumpStream(InputStream in, WiFINetwork result) {
        StringBuilder buf = new StringBuilder();
        byte[] chunk = new byte[512];
        try {
            int n;
            while (!killed.get() && (n = in.read(chunk)) != -1) {
                for (int i = 0; i < n; i++) {
                    int c = chunk[i] & 0xff;
                    if (c == '\r' || c == '\n') {
                        flushBuf(buf, result);
                    } else if (c == 0x1b) {
                        // Skip ANSI CSI sequences: ESC [ ... cmd
                        if (i + 1 < n && (chunk[i + 1] & 0xff) == '[') {
                            i += 2;
                            while (i < n) {
                                int cc = chunk[i] & 0xff;
                                if ((cc >= 'A' && cc <= 'Z') || (cc >= 'a' && cc <= 'z')) break;
                                i++;
                            }
                        }
                    } else if (c >= 0x20 || c == '\t') {
                        buf.append((char) c);
                    }
                }
            }
            flushBuf(buf, result);
        } catch (IOException ignored) {
        }
    }

    private void flushBuf(StringBuilder buf, WiFINetwork result) {
        if (buf.length() == 0) return;
        String raw = ANSI.matcher(buf.toString()).replaceAll("").trim();
        buf.setLength(0);
        if (raw.isEmpty()) return;
        handleOutputLine(raw, result);
    }

    private void handleOutputLine(String line, WiFINetwork result) {
        logger.writeLine(line, 2);

        Matcher found = KEY_FOUND.matcher(line);
        if (found.find()) {
            String key = found.group(1);
            result.setPsk(key);
            result.setOK(true);
            publishUi("KEY FOUND! [ " + key + " ]", true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CreateNotification("Success", "Password found: " + key, 100, 100);
            }
            return;
        }
        if (line.toLowerCase(Locale.US).contains("passphrase not in")
                || line.toLowerCase(Locale.US).contains("quitting")) {
            publishUi(line, true);
            return;
        }

        Matcher prog = PROGRESS.matcher(line);
        Matcher cur = CURRENT_PASS.matcher(line);
        Matcher eta = TIME_LEFT.matcher(line);

        String elapsed = null;
        String tried = null;
        String total = null;
        String speed = null;
        String currentKey = null;
        String timeLeft = null;

        if (prog.find()) {
            elapsed = prog.group(1);
            tried = prog.group(2).replace(",", "");
            if (prog.group(3) != null) total = prog.group(3).replace(",", "");
            speed = prog.group(4);
        }
        if (cur.find()) {
            currentKey = cur.group(1).trim();
            // Strip trailing Master Key noise if aircrack jammed lines together
            int cut = currentKey.toLowerCase(Locale.US).indexOf("master key");
            if (cut > 0) currentKey = currentKey.substring(0, cut).trim();
        }
        if (eta.find()) {
            timeLeft = eta.group(1).trim();
        }

        // Some builds append the trying key as a trailing [ passphrase ]
        if (currentKey == null) {
            Matcher bracket = Pattern.compile(
                    "k/s\\s*\\)\\s*\\[\\s*(.+?)\\s*]\\s*$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (bracket.find()) {
                currentKey = bracket.group(1).trim();
            }
        }

        if (speed != null || tried != null || currentKey != null) {
            StringBuilder status = new StringBuilder();
            if (speed != null) status.append(speed).append(" k/s");
            if (tried != null) {
                if (status.length() > 0) status.append("  ·  ");
                status.append(tried);
                if (total != null && !total.isEmpty() && !total.equals("0")) {
                    status.append('/').append(total);
                }
                status.append(" keys");
            }
            if (elapsed != null) {
                if (status.length() > 0) status.append("  ·  ");
                status.append(elapsed);
            }
            if (currentKey != null && !currentKey.isEmpty()) {
                if (status.length() > 0) status.append('\n');
                status.append("trying: ").append(currentKey);
            }
            if (timeLeft != null && !timeLeft.isEmpty()) {
                if (status.length() > 0) status.append('\n');
                status.append("ETA ").append(timeLeft);
            }

            String statusText = status.toString();
            // Throttle UI redraws; still feel live (~4–5 Hz)
            long now = SystemClock.uptimeMillis();
            boolean forceLog = (now - lastLogAppendMs) > 2500;
            if ((now - lastUiMs) > 200 || forceLog) {
                lastUiMs = now;
                publishProgressStatus(statusText, tried, total, speed, timeLeft, currentKey, forceLog);
            }
            return;
        }

        // Non-progress informational lines
        if (line.length() > 2
                && !line.startsWith("---")
                && !line.matches("^[\\s|=_-]+$")) {
            publishUi(line, true);
        }
    }

    private void publishProgressStatus(String statusText, String tried, String total,
                                       String speed, String timeLeft, String currentKey,
                                       boolean appendToLog) {
        activity.runOnUiThread(() -> {
            if (statusView != null) {
                statusView.setText(statusText);
            }
            String card = "";
            if (speed != null) card = speed + " k/s";
            if (tried != null) {
                if (!card.isEmpty()) card += " · ";
                card += tried + (total != null && !total.equals("0") ? "/" + total : "");
            }
            if (currentKey != null && !currentKey.isEmpty()) {
                if (!card.isEmpty()) card += " · ";
                card += currentKey;
            }
            String eta = (timeLeft != null && !timeLeft.isEmpty()) ? ("ETA " + timeLeft) : null;
            if (progress != null && !card.isEmpty()) progress.setText(card);
            if (time != null && eta != null) {
                time.setVisibility(TextView.VISIBLE);
                time.setText(eta);
            }
            if (listener != null) {
                listener.onCardProgress(card.isEmpty() ? statusText : card, eta);
                listener.onStatus(statusText);
                if (appendToLog) listener.onLogLine(statusText.replace('\n', ' '));
            }
            if (liveLog != null && appendToLog) {
                lastLogAppendMs = SystemClock.uptimeMillis();
                liveLog.append(statusText.replace('\n', ' ') + "\n");
                scrollLog();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tried != null) {
                try {
                    int pr = Integer.parseInt(tried);
                    int all = total != null ? Integer.parseInt(total) : 0;
                    CreateNotification(
                            speed != null ? speed + " k/s" : "Brute",
                            currentKey != null ? currentKey : (timeLeft != null ? timeLeft : tried),
                            pr, all > 0 ? all : Math.max(pr, 1));
                } catch (NumberFormatException ignored) {
                }
            }
        });
    }

    private void publishUi(String line, boolean appendLog) {
        activity.runOnUiThread(() -> {
            if (statusView != null && line != null && line.startsWith("KEY FOUND")) {
                statusView.setText(line);
            }
            if (listener != null) {
                if (line != null && line.startsWith("KEY FOUND")) listener.onStatus(line);
                if (appendLog) listener.onLogLine(line);
            }
            if (liveLog != null && appendLog) {
                liveLog.append(line + "\n");
                scrollLog();
            }
        });
    }

    private void scrollLog() {
        if (liveLog == null || liveLog.getLayout() == null) return;
        int scroll = liveLog.getLayout().getLineTop(liveLog.getLineCount()) - liveLog.getHeight();
        liveLog.scrollTo(0, Math.max(scroll, 0));
    }

    private String buildCommand() {
        // Paths from the adapter are already /sdcard/... forms.
        String cap = path.startsWith("/") ? path : "/sdcard/Stryker/captured/" + path;
        String wl = wordlist.startsWith("/") ? wordlist : "/sdcard/Stryker/wordlists/" + wordlist;
        // Prefer /sdcard for chroot bind-mount.
        cap = cap.replace("/storage/emulated/0/", "/sdcard/");
        wl = wl.replace("/storage/emulated/0/", "/sdcard/");

        if (ENGINE_HASHCAT.equals(engine)) {
            // Flat command — nested quotes break chroot_exec/ash.
            return exec + "'hashcat -m 2500 -a 0 " + cap + " " + wl + " --force --status --status-timer=1'";
        }

        // Keep quoting identical to the original working form:
        //   chroot_exec 'aircrack-ng -w WORDLIST CAP'
        // Nested sh -c / pipes caused: "syntax error: unterminated quoted string"
        StringBuilder cmd = new StringBuilder("aircrack-ng -a2");
        Matcher mac = Pattern.compile("([0-9A-Fa-f]{2}[-:]){5}[0-9A-Fa-f]{2}").matcher(path);
        if (mac.find()) {
            cmd.append(" -b ").append(mac.group().replace('-', ':'));
        }
        cmd.append(" -w ").append(wl).append(' ').append(cap);
        return exec + "'" + cmd + "'";
    }

    public void kill() {
        killed.set(true);
        if (process != null) {
            process.destroy();
        }
    }

    @Override
    protected void onProgressUpdate(String... values) {
        // unused — we push UI directly for lower latency on \\r updates
        super.onProgressUpdate(values);
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
