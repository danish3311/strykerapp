package com.zalexdev.stryker.appintro.slides;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.PorterDuff;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.race604.drawable.wave.WaveDrawable;
import com.zalexdev.stryker.BuildConfig;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.appintro.AppIntroActivity;
import com.zalexdev.stryker.appintro.install.InstallStage;
import com.zalexdev.stryker.appintro.install.LogAdapter;
import com.zalexdev.stryker.appintro.install.LogLevel;
import com.zalexdev.stryker.appintro.install.LogLine;
import com.zalexdev.stryker.ota.CoreDownloader;
import com.zalexdev.stryker.ota.RemoteManifest;
import com.zalexdev.stryker.ota.VerifiedDownloader;
import com.zalexdev.stryker.utils.Core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Locale;

public class Slide3 extends Fragment {

    @SuppressLint("SdCardPath")
    private static final String DOWNLOADED_CHROOT_PATH =
            "/data/data/com.zalexdev.stryker/files/core.tar.gz";

    private static final int NOTIFICATION_ID = 34;
    private static final long MIN_ARCHIVE_BYTES = 1024L * 1024L;

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private TextView statusTitle;
    private TextView statusSubtitle;
    private ImageView statusIcon;
    private ProgressBar statusSpinner;

    private LinearLayout downloadBlock;
    private LinearProgressIndicator progress;
    private TextView downloadText;

    private TextView stagesHeader;
    private LinearLayout stagesContainer;
    private View stagesCard;

    private TextView logHeader;
    private View logCard;
    private RecyclerView logRecycler;
    private LogAdapter logAdapter;

    private View installActions;
    private MaterialButton autoInstallButton;
    private MaterialButton selectLocalButton;
    private TextView localHint;

    private final EnumMap<InstallStage, StageRow> stageRows = new EnumMap<>(InstallStage.class);

    private NotificationCompat.Builder notification;
    private NotificationManager notificationManager;

    private ActivityResultLauncher<String[]> localChrootPicker;
    private boolean installRunning;
    private boolean usingLocalArchive;
    private LayoutInflater layoutInflater;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        localChrootPicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                this::onLocalChrootPicked);
    }

    @SuppressLint({"SdCardPath", "SetTextI18n"})
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide3, container, false);
        activity = getActivity();
        context = getContext();
        core = new Core(context);

        createNotificationChannel();
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        mPager = activity.findViewById(R.id.view_pager);

        statusTitle = view.findViewById(R.id.status_title);
        statusSubtitle = view.findViewById(R.id.status_subtitle);
        statusIcon = view.findViewById(R.id.status_icon);
        statusSpinner = view.findViewById(R.id.status_spinner);

        downloadBlock = view.findViewById(R.id.download_block);
        progress = view.findViewById(R.id.slide_install_progress);
        downloadText = view.findViewById(R.id.download_text);

        stagesHeader = view.findViewById(R.id.stages_header);
        stagesContainer = view.findViewById(R.id.stages_container);
        stagesCard = view.findViewById(R.id.stages_card);

        logHeader = view.findViewById(R.id.log_header);
        logCard = view.findViewById(R.id.log_card);
        logRecycler = view.findViewById(R.id.log_recycler);
        logRecycler.setLayoutManager(new LinearLayoutManager(context));
        logAdapter = new LogAdapter(context);
        logRecycler.setAdapter(logAdapter);

        installActions = view.findViewById(R.id.install_actions);
        autoInstallButton = view.findViewById(R.id.login);
        selectLocalButton = view.findViewById(R.id.select_local_chroot);
        localHint = view.findViewById(R.id.local_hint);
        layoutInflater = inflater;

        buildStageRows(false);

        autoInstallButton.setOnClickListener(v -> startInstallFromNetwork());
        selectLocalButton.setOnClickListener(v -> openLocalPicker());
        return view;
    }

    private void openLocalPicker() {
        if (installRunning) {
            return;
        }
        localChrootPicker.launch(new String[]{
                "application/gzip",
                "application/x-gzip",
                "application/x-gtar",
                "application/x-tar",
                "application/octet-stream",
                "*/*"
        });
    }

    private void onLocalChrootPicked(@Nullable Uri uri) {
        if (uri == null || installRunning) {
            return;
        }
        try {
            final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ignored) {
            // Some providers do not support persistable grants; openInputStream still works.
        }
        startInstallFromLocal(uri);
    }

    private void beginUiSession(boolean localArchive) {
        installRunning = true;
        usingLocalArchive = localArchive;
        installActions.setVisibility(View.GONE);
        stagesHeader.setVisibility(View.VISIBLE);
        stagesContainer.setVisibility(View.VISIBLE);
        stagesCard.setVisibility(View.VISIBLE);
        logHeader.setVisibility(View.VISIBLE);
        logCard.setVisibility(View.VISIBLE);
        logRecycler.setVisibility(View.VISIBLE);
        buildStageRows(localArchive);
        resetStages();
        setStatus(StatusKind.RUNNING, getString(R.string.chroot_status_title), "Starting...");
        log(LogLevel.INFO, "Architecture: " + (core.is64Bit() ? "64-bit (arm64-v8a)" : "32-bit (armeabi-v7a)"));
        log(LogLevel.INFO, "Stryker " + BuildConfig.VERSION_NAME + " · build " + BuildConfig.VERSION_CODE);
    }

    @SuppressLint("SdCardPath")
    private void startInstallFromNetwork() {
        if (installRunning) {
            return;
        }
        beginUiSession(false);
        log(LogLevel.INFO, "Source: internet download");

        new Thread(() -> {
            markStage(InstallStage.PREPARING, RowState.ACTIVE);
            log(LogLevel.STEP, "Preparing storage layout");
            clear();
            markStage(InstallStage.PREPARING, RowState.DONE);
            log(LogLevel.SUCCESS, "Storage layout ready");

            RemoteManifest.Asset chrootAsset = CoreDownloader.resolve(context, core.is64Bit());
            markStage(InstallStage.DOWNLOADING, RowState.ACTIVE);
            log(LogLevel.CMD, "GET " + chrootAsset.url);

            if (downloadChroot(chrootAsset)) {
                markStage(InstallStage.DOWNLOADING, RowState.DONE);
                log(LogLevel.SUCCESS, "Download complete");
                runOnUi(() -> downloadBlock.setVisibility(View.GONE));
                finishInstallAfterArchive();
            } else {
                markStage(InstallStage.DOWNLOADING, RowState.FAILED);
                notificationManager.cancel(NOTIFICATION_ID);
                failWith(getString(R.string.chroot_download_failed_hint));
            }
        }, "stryker-chroot-download").start();
    }

    @SuppressLint("SdCardPath")
    private void startInstallFromLocal(Uri uri) {
        if (installRunning) {
            return;
        }
        beginUiSession(true);
        String displayName = queryDisplayName(uri);
        log(LogLevel.INFO, "Source: local file"
                + (displayName != null && !displayName.isEmpty() ? " (" + displayName + ")" : ""));

        new Thread(() -> {
            markStage(InstallStage.PREPARING, RowState.ACTIVE);
            log(LogLevel.STEP, "Preparing storage layout");
            clear();
            markStage(InstallStage.PREPARING, RowState.DONE);
            log(LogLevel.SUCCESS, "Storage layout ready");

            markStage(InstallStage.IMPORTING, RowState.ACTIVE);
            log(LogLevel.STEP, "Importing selected archive into app storage");
            String copyError = copyLocalArchive(uri);
            if (copyError == null) {
                markStage(InstallStage.IMPORTING, RowState.DONE);
                log(LogLevel.SUCCESS, "Local archive ready");
                runOnUi(() -> downloadBlock.setVisibility(View.GONE));
                finishInstallAfterArchive();
            } else {
                markStage(InstallStage.IMPORTING, RowState.FAILED);
                notificationManager.cancel(NOTIFICATION_ID);
                failWith(copyError);
            }
        }, "stryker-chroot-import").start();
    }

    /** Shared path after core.tar.gz is present at DOWNLOADED_CHROOT_PATH. */
    @SuppressLint("SdCardPath")
    private void finishInstallAfterArchive() {
        markStage(InstallStage.UNPACKING, RowState.ACTIVE);
        log(LogLevel.STEP, "Extracting archive into /data/local/stryker");
        runOnUi(() -> progress.setIndeterminate(true));

        if (!unTarFile()) {
            markStage(InstallStage.UNPACKING, RowState.FAILED);
            notificationManager.cancel(NOTIFICATION_ID);
            failWith("Failed to extract — archive may be corrupt or busybox not present");
            return;
        }

        markStage(InstallStage.UNPACKING, RowState.DONE);
        log(LogLevel.SUCCESS, "Archive extracted");

        notificationManager.cancel(NOTIFICATION_ID);
        core.deleteFile(DOWNLOADED_CHROOT_PATH);

        markStage(InstallStage.MOUNTING, RowState.ACTIVE);
        log(LogLevel.STEP, "Mounting chroot via bootroot");
        if (!core.mountCore()) {
            markStage(InstallStage.MOUNTING, RowState.FAILED);
            failWith(getString(R.string.chroot_mount_failed));
            return;
        }
        markStage(InstallStage.MOUNTING, RowState.DONE);
        log(LogLevel.SUCCESS, "Chroot mounted");

        markStage(InstallStage.UPGRADING, RowState.ACTIVE);
        if (hasInternet()) {
            log(LogLevel.STEP, "Upgrading Alpine packages");
            log(LogLevel.CMD, "apk upgrade -U --no-cache");
            core.customChrootCommand("apk upgrade -U --no-cache");
            markStage(InstallStage.UPGRADING, RowState.DONE);
            log(LogLevel.SUCCESS, "Alpine packages up to date");
        } else {
            log(LogLevel.WARN, usingLocalArchive
                    ? "Offline install — skipped apk upgrade"
                    : "No internet — skipped apk upgrade");
            markStage(InstallStage.UPGRADING, RowState.DONE);
        }

        markStage(InstallStage.DEPLOYING_EXPLOITS, RowState.ACTIVE);
        log(LogLevel.STEP, "Deploying built-in exploits");
        core.deleteFile("/sdcard/Stryker/exploits/");
        core.copyFile("/data/data/com.zalexdev.stryker/files/checker.py",
                "/data/local/stryker/release/exploits/checker.py");
        core.copyFile("/data/local/stryker/release/exploits/", "/sdcard/Stryker/exploits");
        core.chmodFolder("/data/data/com.zalexdev.stryker/files");
        int authCopied = deployAuthLists();
        log(LogLevel.INFO, "Copied " + authCopied + " auth_*.txt list"
                + (authCopied == 1 ? "" : "s") + " to /sdcard/Stryker/rs");
        markStage(InstallStage.DEPLOYING_EXPLOITS, RowState.DONE);
        log(LogLevel.SUCCESS, "Exploits deployed to /sdcard/Stryker/exploits");

        markStage(InstallStage.FINALIZING, RowState.ACTIVE);
        log(LogLevel.CMD, "echo update > /data/local/stryker/release/4.0");
        core.customCommand("echo update > /data/local/stryker/release/4.0");
        core.deleteFile("/sdcard/Stryker/exploits/checker.py");
        markStage(InstallStage.FINALIZING, RowState.DONE);
        log(LogLevel.SUCCESS, "Version marker written");

        markStage(InstallStage.DONE, RowState.DONE);
        setStatus(StatusKind.SUCCESS, getString(R.string.chroot_status_title),
                "Installation complete — moving on...");
        log(LogLevel.SUCCESS, "All stages passed");
        installRunning = false;

        runOnUi(() -> {
            progress.setVisibility(View.INVISIBLE);
            WaveDrawable mWaveDrawable = ((AppIntroActivity) activity).getWaveDrawable();
            core.setSmoothLevel(mWaveDrawable, 7500);
            core.moveNext(mPager);
        });
    }

    /**
     * Copies a SAF/content URI into the fixed core.tar.gz path used by busybox extract.
     * @return null on success, otherwise a user-facing error message
     */
    @Nullable
    private String copyLocalArchive(Uri uri) {
        runOnUi(() -> {
            downloadBlock.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
            downloadText.setText(R.string.chroot_copying_local);
        });
        notification = new NotificationCompat.Builder(context, context.getResources().getString(R.string.notification_channel_updater))
                .setOngoing(true)
                .setContentTitle(context.getResources().getString(R.string.notification_channel_updater))
                .setContentText(context.getResources().getString(R.string.chroot_copying_local))
                .setSmallIcon(R.drawable.bolt)
                .setOnlyAlertOnce(true)
                .setProgress(100, 0, true);
        notificationManager.notify(NOTIFICATION_ID, notification.build());

        File outFile = new File(DOWNLOADED_CHROOT_PATH);
        File part = new File(DOWNLOADED_CHROOT_PATH + ".part");
        long knownSize = querySize(uri);
        long copied = 0;
        long lastUiMs = 0;
        int lastPercent = -1;

        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                return getString(R.string.chroot_copy_failed);
            }
            try (BufferedInputStream in = new BufferedInputStream(raw);
                 FileOutputStream out = new FileOutputStream(part)) {
                byte[] magic = new byte[2];
                in.mark(2);
                int magicRead = in.read(magic);
                in.reset();
                if (magicRead < 2 || (magic[0] & 0xFF) != 0x1F || (magic[1] & 0xFF) != 0x8B) {
                    deleteQuietly(part);
                    return getString(R.string.chroot_invalid_archive);
                }

                byte[] buffer = new byte[256 * 1024];
                int read;
                long startMs = System.currentTimeMillis();
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    copied += read;
                    long now = System.currentTimeMillis();
                    if (now - lastUiMs < 200) {
                        continue;
                    }
                    lastUiMs = now;
                    final long copiedFinal = copied;
                    final long elapsed = now - startMs;
                    if (knownSize > 0) {
                        int percent = (int) Math.min(100, (copied * 100L) / knownSize);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final int percentFinal = percent;
                            runOnUi(() -> {
                                progress.setIndeterminate(false);
                                progress.setProgress(percentFinal, true);
                                downloadText.setText(formatMb(copiedFinal) + " / " + formatMb(knownSize)
                                        + " (" + percentFinal + "%) · " + formatSpeed(copiedFinal, elapsed));
                            });
                            notification.setProgress(100, percentFinal, false);
                            notificationManager.notify(NOTIFICATION_ID, notification.build());
                        }
                    } else {
                        runOnUi(() -> downloadText.setText(formatMb(copiedFinal)
                                + " · " + formatSpeed(copiedFinal, elapsed)));
                    }
                }
                out.getFD().sync();
            }
        } catch (Exception e) {
            deleteQuietly(part);
            String msg = e.getMessage();
            return msg != null && !msg.isEmpty() ? msg : getString(R.string.chroot_copy_failed);
        }

        if (copied < MIN_ARCHIVE_BYTES) {
            deleteQuietly(part);
            return getString(R.string.chroot_invalid_archive);
        }

        deleteQuietly(outFile);
        if (!part.renameTo(outFile)) {
            deleteQuietly(part);
            return getString(R.string.chroot_copy_failed);
        }
        log(LogLevel.INFO, "Imported " + formatMb(copied));
        return null;
    }

    private void failWith(String reason) {
        installRunning = false;
        setStatus(StatusKind.FAILED, getString(R.string.chroot_status_title), reason);
        log(LogLevel.ERROR, reason);
        runOnUi(() -> {
            progress.setIndeterminate(false);
            downloadBlock.setVisibility(View.GONE);
            autoInstallButton.setText(R.string.try_again);
            installActions.setVisibility(View.VISIBLE);
        });
    }

    private long downloadStartMs;

    @SuppressLint({"SdCardPath", "SetTextI18n"})
    private boolean downloadChroot(RemoteManifest.Asset asset) {
        runOnUi(() -> {
            downloadBlock.setVisibility(View.VISIBLE);
            progress.setIndeterminate(true);
            progress.setVisibility(View.VISIBLE);
            downloadText.setText("Connecting...");
        });
        notification = new NotificationCompat.Builder(context, context.getResources().getString(R.string.notification_channel_updater))
                .setOngoing(true)
                .setContentTitle(context.getResources().getString(R.string.notification_channel_updater))
                .setContentText(context.getResources().getString(R.string.downloading_core))
                .setSmallIcon(R.drawable.bolt)
                .setOnlyAlertOnce(true)
                .setProgress(100, 0, true);
        notificationManager.notify(NOTIFICATION_ID, notification.build());

        File outFile = new File(DOWNLOADED_CHROOT_PATH);
        downloadStartMs = System.currentTimeMillis();
        final int[] lastPercent = {-1};
        final long[] lastUiMs = {0};

        VerifiedDownloader.Result result = VerifiedDownloader.download(
                asset.url, outFile, asset.sha256, asset.size,
                (downloaded, total) -> {
                    long now = System.currentTimeMillis();
                    if (now - lastUiMs[0] < 200) {
                        return;
                    }
                    lastUiMs[0] = now;
                    final long elapsedMs = now - downloadStartMs;
                    if (total > 0) {
                        int percent = (int) ((downloaded * 100L) / total);
                        if (percent == lastPercent[0]) {
                            return;
                        }
                        lastPercent[0] = percent;
                        final int percentFinal = percent;
                        runOnUi(() -> {
                            progress.setIndeterminate(false);
                            progress.setProgress(percentFinal, true);
                            downloadText.setText(formatMb(downloaded) + " / " + formatMb(total)
                                    + " (" + percentFinal + "%) · " + formatSpeed(downloaded, elapsedMs)
                                    + " · ETA " + formatEta(downloaded, total, elapsedMs));
                        });
                        notification.setProgress(100, percentFinal, false);
                        notificationManager.notify(NOTIFICATION_ID, notification.build());
                    } else {
                        runOnUi(() -> downloadText.setText(formatMb(downloaded)
                                + " · " + formatSpeed(downloaded, elapsedMs)));
                    }
                });

        if (!result.ok) {
            log(LogLevel.ERROR, "Download: " + result.error);
            return false;
        }
        if (asset.sha256 == null || asset.sha256.isEmpty()) {
            log(LogLevel.WARN, "No checksum in manifest — integrity not verified");
        } else {
            log(LogLevel.SUCCESS, "SHA-256 verified");
        }
        return true;
    }

    private static String formatSpeed(long bytes, long elapsedMs) {
        if (elapsedMs <= 0) return "?/s";
        double bytesPerSec = bytes / (elapsedMs / 1000.0);
        if (bytesPerSec < 1024 * 1024) {
            return String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1024.0 / 1024.0);
    }

    private static String formatMb(long bytes) {
        if (bytes <= 0) return "? MB";
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    private static String formatEta(long downloaded, long total, long elapsedMs) {
        if (downloaded <= 0 || elapsedMs <= 0 || total <= 0) return "—";
        long remaining = total - downloaded;
        if (remaining <= 0) return "0s";
        double bytesPerSec = downloaded / (elapsedMs / 1000.0);
        if (bytesPerSec < 1) return "—";
        long sec = (long) (remaining / bytesPerSec);
        if (sec < 60) return sec + "s";
        if (sec < 3600) return String.format(Locale.US, "%dm %02ds", sec / 60, sec % 60);
        return String.format(Locale.US, "%dh %02dm", sec / 3600, (sec % 3600) / 60);
    }

    @SuppressLint("SdCardPath")
    private void clear() {
        core.chmodFolder("/data/data/com.zalexdev.stryker/files/");
        core.createFolder(core.getStorage() + "/Stryker/");
        core.createFolder("/data/local/stryker");
        if (core.checkFolder("/data/local/stryker/release/sdcard/Stryker")) {
            core.unmountCore();
        }
        core.deleteFile("/data/local/stryker/release");
        core.deleteFile(core.getStorage() + "Stryker/release");
        core.deleteFile(core.getStorage() + "Download/stryker.apk");
        core.createFolder(core.getStorage() + "Stryker");
        core.createFolder(core.getStorage() + "Stryker/hs");
        core.createFolder(core.getStorage() + "Stryker/captured");
        core.createFolder(core.getStorage() + "Stryker/exploits");
        core.createFolder(core.getStorage() + "Stryker/wordlists");
        core.createFolder(core.getStorage() + "Stryker/reports");
        core.createFolder(core.getStorage() + "Stryker/rs");
    }

    private int deployAuthLists() {
        return com.zalexdev.stryker.routerscan.utils.AuthLists.ensureDeployed(activity);
    }

    private boolean unTarFile() {
        notification.setContentText(context.getResources().getString(R.string.installing_core));
        notification.setProgress(100, 0, true);
        notificationManager.notify(NOTIFICATION_ID, notification.build());
        core.customCommand(Core.BUSYBOX + " tar -xzf " + DOWNLOADED_CHROOT_PATH + " -C /data/local/stryker/");
        return core.checkFolder("/data/local/stryker/release/bin/");
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    private long querySize(Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !cursor.isNull(idx)) {
                    return cursor.getLong(idx);
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private boolean hasInternet() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    context.getResources().getString(R.string.notification_channel_updater),
                    context.getResources().getString(R.string.notification_channel_updater),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private void runOnUi(Runnable r) {
        if (activity == null || !isAdded()) return;
        activity.runOnUiThread(() -> {
            if (isAdded()) r.run();
        });
    }

    private void log(LogLevel level, String text) {
        runOnUi(() -> {
            logAdapter.append(new LogLine(level, text));
            if (logAdapter.size() > 0) {
                logRecycler.scrollToPosition(logAdapter.size() - 1);
            }
        });
    }

    private enum StatusKind { RUNNING, SUCCESS, FAILED }

    @SuppressLint("SetTextI18n")
    private void setStatus(StatusKind kind, String title, String subtitle) {
        runOnUi(() -> {
            statusTitle.setText(title);
            statusSubtitle.setText(subtitle);
            switch (kind) {
                case SUCCESS:
                    statusSpinner.setVisibility(View.GONE);
                    statusIcon.setVisibility(View.VISIBLE);
                    statusIcon.setImageResource(R.drawable.done);
                    statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.green),
                            PorterDuff.Mode.SRC_IN);
                    break;
                case FAILED:
                    statusSpinner.setVisibility(View.GONE);
                    statusIcon.setVisibility(View.VISIBLE);
                    statusIcon.setImageResource(R.drawable.error);
                    statusIcon.setColorFilter(ContextCompat.getColor(context, R.color.red),
                            PorterDuff.Mode.SRC_IN);
                    break;
                case RUNNING:
                default:
                    statusIcon.setVisibility(View.GONE);
                    statusIcon.clearColorFilter();
                    statusSpinner.setVisibility(View.VISIBLE);
                    break;
            }
        });
    }

    private enum RowState { PENDING, ACTIVE, DONE, FAILED }

    private void buildStageRows(boolean localArchive) {
        if (layoutInflater == null || stagesContainer == null) {
            return;
        }
        stagesContainer.removeAllViews();
        stageRows.clear();
        for (InstallStage stage : InstallStage.values()) {
            if (localArchive && stage == InstallStage.DOWNLOADING) {
                continue;
            }
            if (!localArchive && stage == InstallStage.IMPORTING) {
                continue;
            }
            View row = layoutInflater.inflate(R.layout.install_stage_row, stagesContainer, false);
            TextView title = row.findViewById(R.id.stage_title);
            ImageView icon = row.findViewById(R.id.stage_icon);
            ProgressBar spinner = row.findViewById(R.id.stage_spinner);
            FrameLayout indicator = row.findViewById(R.id.stage_indicator);
            title.setText(stage.title);
            StageRow handles = new StageRow(title, icon, spinner, indicator);
            applyRowState(handles, RowState.PENDING);
            stageRows.put(stage, handles);
            stagesContainer.addView(row);
        }
    }

    private void resetStages() {
        runOnUi(() -> {
            for (StageRow row : stageRows.values()) {
                applyRowState(row, RowState.PENDING);
            }
        });
    }

    private void markStage(InstallStage stage, RowState newState) {
        runOnUi(() -> {
            StageRow row = stageRows.get(stage);
            if (row == null) return;
            applyRowState(row, newState);
            if (newState == RowState.ACTIVE) {
                statusSubtitle.setText(stage.title);
            }
        });
    }

    private void applyRowState(StageRow row, RowState state) {
        int color;
        switch (state) {
            case ACTIVE:
                color = ContextCompat.getColor(context, R.color.stryker_accent);
                row.spinner.setVisibility(View.VISIBLE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, android.graphics.Typeface.BOLD);
                break;
            case DONE:
                color = ContextCompat.getColor(context, R.color.green);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.done);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, android.graphics.Typeface.NORMAL);
                break;
            case FAILED:
                color = ContextCompat.getColor(context, R.color.red);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.error);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, android.graphics.Typeface.BOLD);
                break;
            case PENDING:
            default:
                color = ContextCompat.getColor(context, R.color.grey);
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, android.graphics.Typeface.NORMAL);
                break;
        }
        row.title.setTextColor(color);
        if (row.indicator.getBackground() != null) {
            row.indicator.getBackground().mutate()
                    .setColorFilter(color, PorterDuff.Mode.SRC_IN);
            row.indicator.getBackground().setAlpha(60);
        }
    }

    private static final class StageRow {
        final TextView title;
        final ImageView icon;
        final ProgressBar spinner;
        final FrameLayout indicator;

        StageRow(TextView title, ImageView icon, ProgressBar spinner, FrameLayout indicator) {
            this.title = title;
            this.icon = icon;
            this.spinner = spinner;
            this.indicator = indicator;
        }
    }
}
