package com.local.minesweeper;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

final class LeaderboardAvatars {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DIMENSION = 8192;
    private static final int AVATAR_DIMENSION = 128;
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(2 * MAX_BYTES) {
        @Override protected int sizeOf(String url, Bitmap bitmap) {
            return bitmap.getByteCount();
        }
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor loader = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    void bind(ImageView image, String value) {
        image.setImageResource(R.drawable.leaderboard_default_avatar);
        String url = supportedUrl(value);
        if (url == null) return;
        Bitmap cached = CACHE.get(url);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }

        WeakReference<ImageView> target = new WeakReference<>(image);
        loader.execute(() -> {
            Bitmap bitmap = CACHE.get(url);
            if (bitmap == null) {
                bitmap = fetch(url);
                if (bitmap == null) return;
                CACHE.put(url, bitmap);
            }
            Bitmap result = bitmap;
            main.post(() -> {
                ImageView view = target.get();
                if (!loader.isShutdown() && view != null && view.isAttachedToWindow()) view.setImageBitmap(result);
            });
        });
    }

    void cancelQueued() {
        loader.getQueue().clear();
    }

    void close() {
        loader.shutdownNow();
    }

    static String supportedUrl(String value) {
        if (value == null || value.length() > 2048) return null;
        String url = value.trim();
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return host.equals("tapimg.com") || host.endsWith(".tapimg.com") ? url : null;
        } catch (IllegalArgumentException failure) {
            return null;
        }
    }

    private static Bitmap fetch(String url) {
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK
                    || connection.getContentLength() > MAX_BYTES) return null;
            try (InputStream stream = connection.getInputStream()) {
                return decode(stream);
            }
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Bitmap decode(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = stream.read(chunk)) != -1) {
            if (buffer.size() + count > MAX_BYTES || Thread.currentThread().isInterrupted()) return null;
            buffer.write(chunk, 0, count);
        }
        byte[] bytes = buffer.toByteArray();
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int largest = Math.max(options.outWidth, options.outHeight);
        if (options.outWidth <= 0 || options.outHeight <= 0 || largest > MAX_DIMENSION) return null;
        options.inSampleSize = 1;
        while (largest / options.inSampleSize > AVATAR_DIMENSION) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
