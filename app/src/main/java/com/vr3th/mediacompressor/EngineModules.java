package com.vr3th.mediacompressor;

import android.content.Context;
import android.graphics.Bitmap;
import com.squareup.gifencoder.GifEncoder;
import com.squareup.gifencoder.ImageOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

final class EngineModules {
    private EngineModules() {}

    static void initPdf(Context context) {
        PDFBoxResourceLoader.init(context.getApplicationContext());
    }

    static void protectPdf(File input, File output, String userPassword, String ownerPassword) throws IOException {
        try (PDDocument doc = PDDocument.load(input)) {
            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy =
                    new StandardProtectionPolicy(ownerPassword, userPassword, permissions);
            policy.setEncryptionKeyLength(128);
            policy.setPermissions(permissions);
            doc.protect(policy);
            doc.save(output);
        }
    }

    static void createGif(OutputStream output, int width, int height,
                           Bitmap[] frames, long delayMs) throws IOException {
        if (frames == null || frames.length == 0) throw new IOException("No GIF frames");
        GifEncoder encoder = new GifEncoder(output, width, height, 0);
        for (Bitmap bitmap : frames) {
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                throw new IOException("GIF frame dimensions do not match");
            }
            int[] flat = new int[width * height];
            bitmap.getPixels(flat, 0, width, 0, 0, width, height);
            int[][] pixels = new int[width][height];
            int p = 0;
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) pixels[x][y] = flat[p++];
            }
            ImageOptions options = new ImageOptions();
            options.setDelay(delayMs, TimeUnit.MILLISECONDS);
            encoder.addImage(pixels, options);
        }
        encoder.finishEncoding();
        output.flush();
    }
}
