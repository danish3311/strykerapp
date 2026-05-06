package com.zalexdev.stryker.utils;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class AssetFiles {
    private AssetFiles() {}

    public static boolean assetExists(Context context, String assetPath) {
        try (InputStream ignored = context.getAssets().open(assetPath)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static File copyAssetToFile(Context context, String assetPath, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        try (InputStream in = context.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(outFile, false)) {
            byte[] buf = new byte[1024 * 128];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            out.flush();
        }

        return outFile;
    }
}

