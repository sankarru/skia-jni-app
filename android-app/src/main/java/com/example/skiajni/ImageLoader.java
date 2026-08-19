package com.example.skiajni;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Fetches images from https://picsum.photos (free random images).
 * Returns raw bytes that Skia decodes via SkiaCanvas.createImage().
 */
public final class ImageLoader {

    public interface Callback {
        void onResult(byte[] bytes); // null on failure
    }

    private ImageLoader() {}

    public static void fetch(final String urlStr, final Callback cb) {
        new Thread(() -> {
            byte[] result = null;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                int code = conn.getResponseCode();
                if (code == 200 || code == 301 || code == 302) {
                    // follow redirect manually if needed
                    String loc = conn.getHeaderField("Location");
                    if (loc != null && (code == 301 || code == 302)) {
                        URL url2 = new URL(url, loc);
                        HttpURLConnection conn2 = (HttpURLConnection) url2.openConnection();
                        conn2.setConnectTimeout(15000);
                        conn2.setReadTimeout(30000);
                        conn2.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android)");
                        if (conn2.getResponseCode() == 200) {
                            result = readAll(conn2.getInputStream());
                        }
                        conn2.disconnect();
                    } else {
                        result = readAll(conn.getInputStream());
                    }
                }
                conn.disconnect();
            } catch (Throwable t) {
                result = null;
            }
            if (cb != null) cb.onResult(result);
        }, "img-loader").start();
    }

    private static byte[] readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }
}
