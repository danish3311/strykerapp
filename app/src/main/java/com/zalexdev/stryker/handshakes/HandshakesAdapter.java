package com.zalexdev.stryker.handshakes;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.zalexdev.stryker.R;
import com.zalexdev.stryker.custom.WiFINetwork;
import com.zalexdev.stryker.handshakes.utils.BruteHandshake;
import com.zalexdev.stryker.utils.Core;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HandshakesAdapter extends RecyclerView.Adapter<HandshakesAdapter.ViewHolder> {

    private static final Pattern MAC_PATTERN = Pattern.compile("((\\w{2}:){5}\\w{2})");

    public ArrayList<String> hslist;
    public Context context;
    public Activity activity;
    public Core core;
    public Runnable onChangeListener;
    public int id = 100;

    public HandshakesAdapter(Context context, Activity activity, ArrayList<String> hsList) {
        this.context = context;
        this.hslist = hsList;
        this.activity = activity;
        this.core = new Core(context);
    }

    public void setOnChangeListener(Runnable listener) {
        this.onChangeListener = listener;
    }

    /** Called by HandshakeStorage after reload to open Logs from a notification. */
    public void consumePendingLogRequest() {
        String path = HandshakeCrackRegistry.consumePendingShowLogs();
        if (path == null) return;
        HandshakeCrackRegistry.Job job = HandshakeCrackRegistry.get(path);
        if (job != null && job.running) {
            HandshakeCrackRegistry.showLogDialog(activity, context, job);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.handshake_item, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint({"SetTextI18n", "RecyclerView"})
    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        String path = hslist.get(position);
        String displayName = new File(path).getName();

        // Detach recycled views from any previous job before rebinding
        for (HandshakeCrackRegistry.Job j : HandshakeCrackRegistry.snapshot().values()) {
            if (j.cardProgress == h.progress || j.cardEta == h.timeLeft) {
                j.cardProgress = null;
                j.cardEta = null;
                if (j.task != null) {
                    j.task.attachUi(null, null, null, null);
                }
            }
        }

        h.brute.setVisibility(View.VISIBLE);
        h.logs.setVisibility(View.GONE);
        h.cancel.setVisibility(View.GONE);
        h.stateChip.setVisibility(View.GONE);
        h.progress.setText("");
        h.timeLeft.setVisibility(View.GONE);
        h.timeLeft.setText("");
        h.itemView.setOnClickListener(null);
        h.logs.setOnClickListener(null);
        h.cancel.setOnClickListener(null);

        String mac = path;
        Matcher m = MAC_PATTERN.matcher(path);
        if (m.find()) mac = m.group(0);
        final String finalMac = mac;

        String stored = core.getString(mac);
        boolean cracked = stored != null && stored.length() > 0;

        h.name.setText(displayName);

        HandshakeCrackRegistry.Job job = HandshakeCrackRegistry.get(path);
        if (job != null && job.running) {
            bindCrackingUi(h, path, job);
            h.overflow.setOnClickListener(v -> showOverflow(v, position, path, displayName, finalMac));
            return;
        }

        if (cracked) {
            h.stateChip.setVisibility(View.VISIBLE);
            h.stateChip.setText(R.string.hs_state_cracked);
            h.stateChip.setTextColor(Color.parseColor("#388E3C"));
            h.progress.setText(context.getResources().getString(R.string.pass_founded) + stored);
            h.progress.setTextColor(Color.parseColor("#388E3C"));
            h.itemView.setOnClickListener(v -> showDetails(path, displayName, finalMac));
        } else {
            h.progress.setTextColor(Color.parseColor("#9E9E9E"));
            File f = captureFile(path);
            if (f.exists()) {
                h.progress.setText(humanSize(f.length()) + "  ·  tap for details");
            } else {
                h.progress.setText("tap for details");
            }
            h.itemView.setOnClickListener(v -> showDetails(path, displayName, finalMac));
        }

        h.brute.setOnClickListener(v -> startBrute(h, path, finalMac));
        h.overflow.setOnClickListener(v -> showOverflow(v, position, path, displayName, finalMac));
    }

    private void bindCrackingUi(ViewHolder h, String path, HandshakeCrackRegistry.Job job) {
        h.brute.setVisibility(View.GONE);
        h.logs.setVisibility(View.VISIBLE);
        h.cancel.setVisibility(View.VISIBLE);
        h.stateChip.setVisibility(View.VISIBLE);
        h.stateChip.setText(R.string.hs_state_brute);
        h.stateChip.setTextColor(Color.parseColor("#AB47BC"));
        h.progress.setTextColor(Color.parseColor("#9E9E9E"));
        if (job.lastProgress != null && !job.lastProgress.isEmpty()) {
            h.progress.setText(job.lastProgress);
        } else {
            h.progress.setText(R.string.hs_progress_starting);
        }
        h.timeLeft.setVisibility(View.VISIBLE);
        if (job.lastEta != null && !job.lastEta.isEmpty()) {
            h.timeLeft.setText(job.lastEta);
        } else {
            h.timeLeft.setText("ETA …");
        }
        job.cardProgress = h.progress;
        job.cardEta = h.timeLeft;
        if (job.task != null) {
            job.task.attachUi(h.progress, h.timeLeft, null, null);
        }
        h.logs.setOnClickListener(v -> HandshakeCrackRegistry.showLogDialog(activity, context, job));
        h.cancel.setOnClickListener(v -> stopBrute(path, h));
        h.itemView.setOnClickListener(v -> showDetails(path, new File(path).getName(), job.mac));
        h.brute.setOnClickListener(null);
    }

    private void stopBrute(String path, ViewHolder h) {
        HandshakeCrackRegistry.stop(path, context);
        h.cancel.setVisibility(View.GONE);
        h.logs.setVisibility(View.GONE);
        h.brute.setVisibility(View.VISIBLE);
        h.stateChip.setVisibility(View.GONE);
        h.timeLeft.setVisibility(View.GONE);
        h.progress.setText(R.string.hs_crack_stopped);
        h.progress.setTextColor(Color.parseColor("#9E9E9E"));
        h.itemView.setOnClickListener(v -> {
            String mac = path;
            Matcher m = MAC_PATTERN.matcher(path);
            if (m.find()) mac = m.group(0);
            showDetails(path, new File(path).getName(), mac);
        });
        int pos = hslist.indexOf(path);
        if (pos >= 0) notifyItemChanged(pos);
    }

    private void showDetails(String path, String displayName, String mac) {
        File f = captureFile(path);
        String stored = core.getString(mac);
        HandshakeCrackRegistry.Job job = HandshakeCrackRegistry.get(path);
        boolean cracking = job != null && job.running;

        StringBuilder body = new StringBuilder();
        body.append("File: ").append(displayName).append('\n');
        body.append("BSSID: ").append(mac != null ? mac : "—").append('\n');
        if (f.exists()) {
            body.append("Size: ").append(humanSize(f.length())).append('\n');
            body.append("Path: ").append(f.getAbsolutePath()).append('\n');
        }
        body.append('\n');
        if (cracking) {
            body.append("Status: CRACKING (").append(job.engine).append(")\n");
            body.append("Progress: ").append(job.lastProgress).append('\n');
            body.append("ETA: ").append(job.lastEta).append('\n');
        } else if (stored != null && !stored.isEmpty()) {
            body.append("Status: CRACKED\n");
            body.append("Password: ").append(stored).append('\n');
        } else {
            body.append("Status: Not cracked\n");
        }

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(context)
                .setTitle("Handshake details")
                .setMessage(body.toString())
                .setNegativeButton(android.R.string.ok, null);
        if (cracking) {
            b.setPositiveButton(R.string.hs_view_logs, (d, w) ->
                    HandshakeCrackRegistry.showLogDialog(activity, context, job));
            b.setNeutralButton(R.string.hs_action_stop, (d, w) -> {
                HandshakeCrackRegistry.stop(path, context);
                int pos = hslist.indexOf(path);
                if (pos >= 0) notifyItemChanged(pos);
            });
        } else if (stored != null && !stored.isEmpty()) {
            b.setPositiveButton(R.string.hs_password_copy, (d, w) -> copyPassword(stored));
        }
        b.show();
    }

    private void showOverflow(View anchor, int position, String path, String displayName, String mac) {
        PopupMenu menu = new PopupMenu(context, anchor);
        String pwd = core.getString(mac);
        boolean cracked = pwd != null && !pwd.isEmpty();
        menu.getMenu().add(0, 6, 0, R.string.hs_details);
        if (cracked) {
            menu.getMenu().add(0, 5, 1, R.string.hs_password_copy);
        }
        if (HandshakeCrackRegistry.isRunning(path)) {
            menu.getMenu().add(0, 7, 2, R.string.hs_view_logs);
            menu.getMenu().add(0, 8, 3, R.string.hs_action_stop);
        }
        menu.getMenu().add(0, 1, 4, R.string.hs_action_upload);
        menu.getMenu().add(0, 2, 5, R.string.hs_action_share);
        menu.getMenu().add(0, 3, 6, R.string.hs_action_rename);
        menu.getMenu().add(0, 4, 7, R.string.hs_action_delete);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: askEmailAndUpload(path, displayName); return true;
                case 2: shareFile(path); return true;
                case 3: renameFile(position, path, displayName); return true;
                case 4: deleteFile(position, path, displayName); return true;
                case 5: copyPassword(pwd); return true;
                case 6: showDetails(path, displayName, mac); return true;
                case 7: {
                    HandshakeCrackRegistry.Job j = HandshakeCrackRegistry.get(path);
                    if (j != null) HandshakeCrackRegistry.showLogDialog(activity, context, j);
                    return true;
                }
                case 8:
                    HandshakeCrackRegistry.stop(path, context);
                    notifyItemChanged(position);
                    return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void copyPassword(String password) {
        if (password == null || password.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("psk", password));
        }
        toaster(context.getString(R.string.hs_password_copied));
    }

    private void startBrute(ViewHolder h, String path, String finalMac) {
        ArrayList<String> get = core.getListFiles(core.getStorage() + "Stryker/wordlists");
        if (get.isEmpty()) {
            toaster(context.getString(R.string.hs_wordlist_empty));
            return;
        }
        String[] names = new String[get.size()];
        for (int i = 0; i < get.size(); i++) {
            names[i] = get.get(i).replace(core.getStorage() + "Stryker/wordlists/", "");
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.hs_wordlist_title)
                .setItems(names, (di, idx) -> pickEngineThenBrute(h, path, finalMac, get.get(idx)))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void pickEngineThenBrute(ViewHolder h, String path, String finalMac, String wordlistPath) {
        boolean hashcatOk = core.checkFile("/data/local/stryker/release/usr/bin/hashcat")
                || core.checkFile("/data/local/stryker/release/usr/bin/hashcat.bin");
        if (!hashcatOk) {
            launchBrute(h, path, finalMac, wordlistPath, BruteHandshake.ENGINE_AIRCRACK);
            return;
        }
        CharSequence[] engines = new CharSequence[]{"aircrack-ng", "hashcat"};
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.hs_engine_title)
                .setItems(engines, (d, which) -> {
                    String engine = which == 1 ? BruteHandshake.ENGINE_HASHCAT : BruteHandshake.ENGINE_AIRCRACK;
                    launchBrute(h, path, finalMac, wordlistPath, engine);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void launchBrute(ViewHolder h, String path, String finalMac, String wordlistPath, String engine) {
        if (HandshakeCrackRegistry.isRunning(path)) {
            toaster(context.getString(R.string.hs_cracking_bg));
            HandshakeCrackRegistry.Job existing = HandshakeCrackRegistry.get(path);
            if (existing != null) {
                HandshakeCrackRegistry.showLogDialog(activity, context, existing);
            }
            return;
        }

        h.progress.setVisibility(View.VISIBLE);
        h.progress.setTextColor(Color.parseColor("#9E9E9E"));
        h.timeLeft.setVisibility(View.VISIBLE);
        h.timeLeft.setText("ETA …");
        h.stateChip.setVisibility(View.VISIBLE);
        h.stateChip.setText(R.string.hs_state_brute);
        h.stateChip.setTextColor(Color.parseColor("#AB47BC"));
        h.brute.setVisibility(View.GONE);
        h.logs.setVisibility(View.VISIBLE);
        h.cancel.setVisibility(View.VISIBLE);
        h.progress.setText(R.string.hs_progress_starting);

        id++;
        final int notifId = id;
        final HandshakeCrackRegistry.Job job =
                new HandshakeCrackRegistry.Job(path, finalMac, engine, notifId);
        job.lastProgress = context.getString(R.string.hs_progress_starting);
        job.lastEta = "ETA …";
        job.cardProgress = h.progress;
        job.cardEta = h.timeLeft;
        HandshakeCrackRegistry.put(job);

        h.logs.setOnClickListener(v ->
                HandshakeCrackRegistry.showLogDialog(activity, context, job));
        h.itemView.setOnClickListener(v -> showDetails(path, new File(path).getName(), finalMac));
        h.cancel.setOnClickListener(v -> stopBrute(path, h));

        toaster(context.getString(R.string.hs_cracking_bg));

        new Thread(() -> {
            try {
                String capRel = path.replace(core.getStorage(), "/sdcard/");
                String wlRel = wordlistPath.replace(core.getStorage(), "/sdcard/");
                BruteHandshake br = new BruteHandshake(capRel, wlRel, core, activity, context,
                        h.progress, h.timeLeft, null, null, notifId, engine);
                br.setCapturePath(path).setNotifId(notifId);
                br.setListener(new BruteHandshake.Listener() {
                    @Override
                    public void onCardProgress(String progressLine, String etaLine) {
                        if (progressLine != null && !progressLine.isEmpty()) {
                            job.lastProgress = progressLine;
                            if (job.cardProgress != null) job.cardProgress.setText(progressLine);
                        }
                        if (etaLine != null && !etaLine.isEmpty()) {
                            job.lastEta = etaLine;
                            if (job.cardEta != null) {
                                job.cardEta.setVisibility(View.VISIBLE);
                                job.cardEta.setText(etaLine);
                            }
                        }
                    }

                    @Override
                    public void onLogLine(String line) {
                        job.appendLog(line);
                    }

                    @Override
                    public void onStatus(String status) {
                        if (status != null) job.lastStatus = status;
                    }
                });
                job.task = br;

                // Seed notification so actions appear immediately
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    br.CreateNotification(engine + " · cracking", new File(path).getName(), 0, 0);
                }

                WiFINetwork net = br.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR).get();
                activity.runOnUiThread(() -> {
                    HandshakeCrackRegistry.remove(path);
                    job.running = false;
                    applyFinished(path, finalMac, net);
                });
            } catch (ExecutionException | InterruptedException e) {
                e.printStackTrace();
                activity.runOnUiThread(() -> {
                    HandshakeCrackRegistry.remove(path);
                    int pos = hslist.indexOf(path);
                    if (pos >= 0) notifyItemChanged(pos);
                    toaster("Crack failed: " + e.getMessage());
                });
            }
        }, "hs-brute-" + notifId).start();
    }

    private void applyFinished(String path, String finalMac, WiFINetwork net) {
        if (net != null && net.getOK()) {
            core.putString(finalMac, net.getPsk());
            toaster(context.getResources().getString(R.string.pass_founded) + net.getPsk());
            if (onChangeListener != null) onChangeListener.run();
        } else {
            toaster(context.getString(R.string.pass_not_found));
        }
        int pos = hslist.indexOf(path);
        if (pos >= 0) notifyItemChanged(pos);
    }

    private void askEmailAndUpload(String path, String displayName) {
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.input_dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = dialog.findViewById(R.id.title);
        TextInputEditText valueEdit = dialog.findViewById(R.id.value);
        MaterialButton ok = dialog.findViewById(R.id.ok);
        MaterialButton dismiss = dialog.findViewById(R.id.cancel);
        title.setText(R.string.hs_upload_email_title);
        valueEdit.setHint(R.string.hs_upload_email_hint);
        dismiss.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            String email = Objects.requireNonNull(valueEdit.getText()).toString().trim();
            dialog.dismiss();
            toaster("Uploading " + displayName);
            new Thread(() -> {
                ArrayList<String> result = core.customChrootCommand(
                        "curl -s -X POST -F \"email=" + email + "\" -F \"file=@/sdcard/Stryker/captured/" + displayName + "\" https://api.onlinehashcrack.com");
                UploadResult outcome = parseUploadResult(result);
                activity.runOnUiThread(() -> {
                    switch (outcome.outcome) {
                        case ALREADY:
                            toaster(context.getString(R.string.file_was_uploaded));
                            break;
                        case SUCCESS:
                            toaster(context.getString(R.string.upload_success));
                            break;
                        default:
                            toaster(outcome.message.isEmpty()
                                    ? context.getString(R.string.error_upload)
                                    : outcome.message);
                            break;
                    }
                });
            }).start();
        });
        dialog.show();
    }

    private enum UploadOutcome {SUCCESS, ALREADY, FAILED}

    private static final class UploadResult {
        final UploadOutcome outcome;
        final String message;

        UploadResult(UploadOutcome outcome, String message) {
            this.outcome = outcome;
            this.message = message;
        }
    }

    private static UploadResult parseUploadResult(ArrayList<String> lines) {
        StringBuilder builder = new StringBuilder();
        if (lines != null) {
            for (String line : lines) {
                builder.append(line).append('\n');
            }
        }
        String body = builder.toString().trim();
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                org.json.JSONObject json = new org.json.JSONObject(body.substring(start, end + 1));
                String message = json.optString("message", "");
                if (json.optBoolean("success", false)) {
                    boolean already = message.toLowerCase(java.util.Locale.ROOT).contains("already");
                    return new UploadResult(already ? UploadOutcome.ALREADY : UploadOutcome.SUCCESS, message);
                }
                return new UploadResult(UploadOutcome.FAILED, message);
            } catch (org.json.JSONException ignored) {
            }
        }
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("already")) {
            return new UploadResult(UploadOutcome.ALREADY, "");
        }
        if (lower.contains("success") || lower.contains("added") || lower.contains("uploaded")) {
            return new UploadResult(UploadOutcome.SUCCESS, "");
        }
        return new UploadResult(UploadOutcome.FAILED, "");
    }

    private void shareFile(String path) {
        File f = captureFile(path);
        if (!f.exists()) {
            toaster(context.getString(R.string.hs_share_failed));
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, f.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(share, context.getString(R.string.hs_action_share));
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(chooser);
        } catch (Exception e) {
            android.util.Log.w("HandshakesAdapter", "share failed", e);
            toaster(context.getString(R.string.hs_share_failed));
        }
    }

    private File captureFile(String name) {
        if (name != null && name.startsWith("/")) return new File(name);
        return new File(core.getStorage() + "Stryker/captured", name);
    }

    private void renameFile(int position, String path, String displayName) {
        final Dialog dialog = new Dialog(context);
        dialog.setContentView(R.layout.input_dialog);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        TextView title = dialog.findViewById(R.id.title);
        TextInputEditText valueEdit = dialog.findViewById(R.id.value);
        MaterialButton ok = dialog.findViewById(R.id.ok);
        MaterialButton dismiss = dialog.findViewById(R.id.cancel);
        title.setText(R.string.hs_rename_title);
        valueEdit.setHint(R.string.hs_rename_hint);
        valueEdit.setText(displayName);
        dismiss.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            String newName = Objects.requireNonNull(valueEdit.getText()).toString().trim();
            if (newName.isEmpty() || newName.equals(displayName)) { dialog.dismiss(); return; }
            if (!newName.toLowerCase(Locale.US).endsWith(".cap")
                    && !newName.toLowerCase(Locale.US).endsWith(".pcap")) {
                newName = newName + ".cap";
            }
            File src = captureFile(path);
            File dst = captureFile(newName);
            if (dst.exists()) {
                toaster("A file with that name already exists");
                return;
            }
            boolean okRename = src.renameTo(dst);
            if (!okRename) {
                core.customCommand("mv -f '" + src.getAbsolutePath() + "' '" + dst.getAbsolutePath() + "'");
                okRename = dst.exists() && !src.exists();
            }
            if (okRename) {
                // Keep absolute path when list used absolute paths
                String stored = path.startsWith("/") ? dst.getAbsolutePath() : newName;
                HandshakeCrackRegistry.Job job = HandshakeCrackRegistry.get(path);
                if (job != null) {
                    HandshakeCrackRegistry.remove(path);
                    HandshakeCrackRegistry.Job moved = new HandshakeCrackRegistry.Job(
                            stored, job.mac, job.engine, job.notifId);
                    moved.task = job.task;
                    moved.running = job.running;
                    moved.lastProgress = job.lastProgress;
                    moved.lastEta = job.lastEta;
                    moved.lastStatus = job.lastStatus;
                    moved.appendLog(job.log.toString());
                    HandshakeCrackRegistry.put(moved);
                }
                hslist.set(position, stored);
                notifyItemChanged(position);
                if (onChangeListener != null) onChangeListener.run();
                toaster("Renamed to " + newName);
            } else {
                toaster("Rename failed");
            }
            dialog.dismiss();
        });
        dialog.show();
    }

    private void deleteFile(int position, String path, String displayName) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.hs_delete_title)
                .setMessage(context.getString(R.string.hs_delete_body, displayName))
                .setPositiveButton(R.string.hs_action_delete, (d, w) -> {
                    File f = captureFile(path);
                    boolean deleted = f.delete();
                    if (!deleted && f.exists()) {
                        core.customCommand("rm -f '" + f.getAbsolutePath() + "'");
                        deleted = !f.exists();
                    }
                    if (deleted) {
                        hslist.remove(position);
                        notifyItemRemoved(position);
                        notifyItemRangeChanged(position, hslist.size());
                        if (onChangeListener != null) onChangeListener.run();
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public int getItemCount() {
        return hslist.size();
    }

    @Override
    public long getItemId(int position) {
        return hslist.get(position).hashCode();
    }

    @Override
    public int getItemViewType(int position) {
        return 0;
    }

    public void toaster(String msg) {
        if (activity == null) return;
        activity.runOnUiThread(() -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show());
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView name;
        public TextView progress;
        public TextView timeLeft;
        public TextView stateChip;
        public ImageView brute;
        public ImageView logs;
        public ImageView cancel;
        public ImageView overflow;
        public ImageView icon;

        public ViewHolder(View v) {
            super(v);
            name = v.findViewById(R.id.hs_name);
            progress = v.findViewById(R.id.hs_progress);
            timeLeft = v.findViewById(R.id.hs_time_left);
            stateChip = v.findViewById(R.id.hs_state_chip);
            brute = v.findViewById(R.id.hs_brute);
            logs = v.findViewById(R.id.hs_logs);
            cancel = v.findViewById(R.id.hs_cancel);
            overflow = v.findViewById(R.id.hs_overflow);
            icon = v.findViewById(R.id.hs_icon);
        }
    }
}
