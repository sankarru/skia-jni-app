package com.example.skiajni;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;

/**
 * Converts Skia raster pixels into an Android {@link Bitmap}.
 *
 * Skia surfaces on Android are BGRA in memory, and the raw byte[] from
 * {@link SkiaCanvas#getPixels()} is interpreted as RGBA by Android's
 * {@link Bitmap.Config#ARGB_8888}, which swaps the red and blue channels.
 * Swapping R and B in the byte stream before copying yields correct colors.
 */
public final class Pixels {
    private Pixels() {}

    /** Convert a Skia pixel byte[] (w*h*4 bytes) into an ARGB_8888 bitmap. */
    public static Bitmap toBitmap(int w, int h, byte[] px) {
        byte[] sw = new byte[px.length];
        for (int i = 0; i < px.length; i += 4) {
            sw[i]     = px[i + 2];
            sw[i + 1] = px[i + 1];
            sw[i + 2] = px[i];
            sw[i + 3] = px[i + 3];
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(sw));
        return bmp;
    }
}
