package com.zalexdev.stryker.handshakes;

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.method.ScrollingMovementMethod;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.handshakes.utils.BruteHandshake;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide handshake crack jobs. Survives fragment/adapter recreation
 * so Stop / Logs still work after navigating away and back.
 */
public final class HandshakeCrackRegistry {

    public static final class Job {
        public final String path;
        public final String mac;
        public final String engine;
        public final int notifId;
        public final StringBuilder log = new StringBuilder();
        public BruteHandshake task;
        public String lastStatus = "Starting…";
        public String lastProgress = "Starting…";
        public String lastEta = "ETA …";
        public volatile boolean running = true;
        /** Bound card views (may be null when scrolled off-screen). */
        @Nullable public TextView cardProgress;
        @Nullable public TextView cardEta;

        Job(String path, String mac, String engine, int notifId) {
            this.path = path;
            this.mac = mac;
            this.engine = engine;
            this.notifId = notifId;
        }

        public synchronized void appendLog(String line) {
            if (line == null) return;
            log.append(line);
            if (!line.endsWith("\n")) log.append('\n');
            // Cap memory
            if (log.length() > 80_000) {
                log.delete(0, log.length() - 60_000);
            }
        }
    }

    private static final ConcurrentHashMap<String, Job> JOBS = new ConcurrentHashMap<>();
    private static volatile String pendingShowLogPath;

    private HandshakeCrackRegistry() {
    }

    public static Job put(Job job) {
        JOBS.put(job.path, job);
        return job;
    }

    @Nullable
    public static Job get(String path) {
        return path == null ? null : JOBS.get(path);
    }

    public static boolean isRunning(String path) {
        Job j = get(path);
        return j != null && j.running;
    }

    public static List<Job> allRunning() {
        ArrayList<Job> out = new ArrayList<>();
        for (Job j : JOBS.values()) {
            if (j.running) out.add(j);
        }
        return out;
    }

    public static void remove(String path) {
        if (path != null) JOBS.remove(path);
    }

    public static void stop(String path, @Nullable Context context) {
        Job job = JOBS.remove(path);
        if (job == null) return;
        job.running = false;
        if (job.task != null) job.task.kill();
        if (context != null) {
            try {
                NotificationManager nm =
                        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(job.notifId);
            } catch (Exception ignored) {
            }
        }
    }

    public static void requestShowLogs(String path) {
        pendingShowLogPath = path;
    }

    @Nullable
    public static String consumePendingShowLogs() {
        String p = pendingShowLogPath;
        pendingShowLogPath = null;
        return p;
    }

    /** Show (or rebuild) the live log dialog for a running job. */
    public static void showLogDialog(Activity activity, Context context, Job job) {
        if (activity == null || context == null || job == null || activity.isFinishing()) return;
        final Dialog logDialog = new Dialog(context);
        logDialog.setContentView(R.layout.dialog_hs_brute_log);
        Window w = logDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView liveLog = logDialog.findViewById(R.id.hs_brute_log);
        TextView statusView = logDialog.findViewById(R.id.hs_brute_status);
        TextView logTitle = logDialog.findViewById(R.id.hs_brute_log_title);
        MaterialButton hideLog = logDialog.findViewById(R.id.hs_brute_log_hide);
        logTitle.setText("Cracking · " + job.engine);
        statusView.setText(job.lastStatus != null ? job.lastStatus : "…");
        liveLog.setMovementMethod(new ScrollingMovementMethod());
        synchronized (job) {
            liveLog.setText(job.log.toString());
        }
        if (liveLog.getLayout() != null) {
            int scroll = liveLog.getLayout().getLineTop(liveLog.getLineCount()) - liveLog.getHeight();
            liveLog.scrollTo(0, Math.max(scroll, 0));
        }
        // Keep appending while dialog is open
        if (job.task != null) {
            job.task.attachUi(job.cardProgress, job.cardEta, liveLog, statusView);
        }
        hideLog.setOnClickListener(v -> {
            try { logDialog.dismiss(); } catch (Exception ignored) {}
            if (job.task != null) {
                job.task.attachUi(job.cardProgress, job.cardEta, null, null);
            }
        });
        logDialog.setCancelable(true);
        logDialog.setCanceledOnTouchOutside(true);
        try {
            logDialog.show();
        } catch (Exception ignored) {
        }
    }

    public static Map<String, Job> snapshot() {
        return JOBS;
    }
}
