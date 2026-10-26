package com.ytkitkat.app.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.util.TypedValue;
import android.widget.ImageView;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.YtApplication;
import com.ytkitkat.app.network.HttpClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bộ tải và biến đổi hình ảnh siêu nhẹ RAM tối ưu cho Android 4.4.4 (KitKat - API 19):
 *
 * 1. RAM Cache: {@link LruCache} dung lượng bằng {@code Runtime.getRuntime().maxMemory() / 8}.
 * 2. Disk Cache: Lưu file MD5 trong {@code context.getCacheDir()/thumbs} qua {@link DiskImageCache}.
 * 3. Giải mã tiết kiệm 50% RAM:
 *    - Dùng {@code Bitmap.Config.RGB_565} (2 byte/pixel thay vì 4 byte/pixel của ARGB_8888) khi giải mã.
 *    - Tính toán {@code inSampleSize} chuẩn xác để downsample ảnh về đúng kích thước hiển thị
 *      (tránh lỗi {@link OutOfMemoryError} trên máy KitKat).
 * 4. Biến đổi hình học bằng {@link Canvas} + {@link BitmapShader} + {@link Paint#ANTI_ALIAS_FLAG}:
 *    - {@link #createRoundedRectBitmap(Bitmap, int, int, float)}: bo góc 8dp hoặc 12dp cho Thumbnail.
 *    - {@link #createCircleCropBitmap(Bitmap, int)}: cắt ảnh hình tròn cho Avatar kênh.
 * 5. Chống nhảy/lệch ảnh khi cuộn ListView nhanh:
 *    - Gắn {@code imageView.setTag(cacheKey)} trước khi đẩy vào {@link ExecutorService} và đối chiếu
 *      lại tag trên Main Thread trước khi gán {@code setImageBitmap(...)}.
 */
public final class ImageLoader {

    private static final String TAG = "ImageLoader";

    public static final int SHAPE_RECT = 0;
    public static final int SHAPE_ROUNDED = 1;
    public static final int SHAPE_CIRCLE = 2;

    private static volatile ImageLoader instance;

    private final LruCache<String, Bitmap> memoryCache;
    private final DiskImageCache diskCache;
    private final ExecutorService imageExecutor;
    private final Handler mainHandler;
    private final DisplayMetrics displayMetrics;
    private final Map<ImageView, String> activeViewMap =
            Collections.synchronizedMap(new WeakHashMap<ImageView, String>());

    private ImageLoader(Context context) {
        Context appContext = context.getApplicationContext();
        this.displayMetrics = appContext.getResources().getDisplayMetrics();
        this.mainHandler = new Handler(Looper.getMainLooper());

        long maxMemory = Runtime.getRuntime().maxMemory();
        int cacheSizeBytes = (int) Math.min(maxMemory / 8L, (long) Integer.MAX_VALUE);

        this.memoryCache = new LruCache<String, Bitmap>(cacheSizeBytes) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
            }
        };

        this.diskCache = new DiskImageCache(appContext, Config.IMAGE_DISK_CACHE_MAX_BYTES);

        this.imageExecutor = Executors.newFixedThreadPool(Config.IMAGE_LOADER_THREAD_COUNT, new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger(1);

            @Override
            public Thread newThread(final Runnable r) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        r.run();
                    }
                }, "yt-img-" + count.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public static ImageLoader getInstance() {
        ImageLoader local = instance;
        if (local == null) {
            synchronized (ImageLoader.class) {
                local = instance;
                if (local == null) {
                    local = new ImageLoader(YtApplication.getAppContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    // ------------------------------------------------------------------
    // API công khai cho Thumbnail & Avatar
    // ------------------------------------------------------------------

    /**
     * Tải ảnh Thumbnail tỷ lệ 16:9 bo góc (8dp hoặc 12dp) vào ImageView.
     */
    public void loadRoundedThumbnail(ImageView imageView,
                                     String url,
                                     int targetWidthPx,
                                     int targetHeightPx,
                                     float cornerRadiusDp,
                                     int placeholderResId) {
        float radiusPx = dpToPx(cornerRadiusDp);
        int w = targetWidthPx > 0 ? targetWidthPx : Math.min(displayMetrics.widthPixels, 640);
        int h = targetHeightPx > 0 ? targetHeightPx : (w * 9) / 16;
        loadInternal(imageView, url, w, h, SHAPE_ROUNDED, radiusPx, placeholderResId);
    }

    /**
     * Tải ảnh Avatar kênh cắt hình tròn (Circle Crop) vào ImageView.
     */
    public void loadCircleAvatar(ImageView imageView,
                                 String url,
                                 int diameterDp,
                                 int placeholderResId) {
        int sizePx = Math.max(24, (int) dpToPx(diameterDp));
        loadInternal(imageView, url, sizePx, sizePx, SHAPE_CIRCLE, 0f, placeholderResId);
    }

    /**
     * Tải ảnh phẳng thông thường (downsample RGB_565) vào ImageView.
     */
    public void loadPlain(ImageView imageView,
                          String url,
                          int targetWidthPx,
                          int targetHeightPx,
                          int placeholderResId) {
        int w = targetWidthPx > 0 ? targetWidthPx : 320;
        int h = targetHeightPx > 0 ? targetHeightPx : 180;
        loadInternal(imageView, url, w, h, SHAPE_RECT, 0f, placeholderResId);
    }

    private void loadInternal(final ImageView imageView,
                              final String url,
                              final int reqWidth,
                              final int reqHeight,
                              final int shapeType,
                              final float cornerRadiusPx,
                              final int placeholderResId) {
        if (imageView == null) {
            return;
        }
        if (url == null || url.trim().length() == 0) {
            activeViewMap.remove(imageView);
            imageView.setTag(null);
            imageView.setImageBitmap(null);
            if (placeholderResId != 0) {
                imageView.setBackgroundResource(placeholderResId);
            }
            return;
        }

        final String cleanUrl = url.trim();
        final String cacheKey = buildCacheKey(cleanUrl, reqWidth, reqHeight, shapeType, (int) cornerRadiusPx);

        // Gắn tag URL/cacheKey trực tiếp vào ImageView để chống hiển thị nhầm khi cuộn ListView
        imageView.setTag(cacheKey);
        activeViewMap.put(imageView, cacheKey);

        Bitmap cached = getFromMemoryCache(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            imageView.setImageBitmap(cached);
            return;
        }

        imageView.setImageBitmap(null);
        if (placeholderResId != 0) {
            imageView.setBackgroundResource(placeholderResId);
        }

        imageExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (!isImageViewStillMatching(imageView, cacheKey)) {
                    return;
                }
                Bitmap result = loadAndTransformBitmap(
                        cleanUrl, cacheKey, reqWidth, reqHeight, shapeType, cornerRadiusPx);
                if (result == null) {
                    return;
                }
                putIntoMemoryCache(cacheKey, result);
                if (!isImageViewStillMatching(imageView, cacheKey)) {
                    return;
                }
                final Bitmap finalBitmap = result;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = imageView.getTag();
                        if (cacheKey.equals(tag) && !finalBitmap.isRecycled()) {
                            imageView.setImageBitmap(finalBitmap);
                        }
                    }
                });
            }
        });
    }

    private boolean isImageViewStillMatching(ImageView imageView, String expectedKey) {
        String current = activeViewMap.get(imageView);
        return expectedKey.equals(current);
    }

    // ------------------------------------------------------------------
    // Giải mã tiết kiệm RAM (RGB_565 + inSampleSize) & Biến đổi BitmapShader
    // ------------------------------------------------------------------

    private Bitmap loadAndTransformBitmap(String url,
                                          String cacheKey,
                                          int reqWidth,
                                          int reqHeight,
                                          int shapeType,
                                          float cornerRadiusPx) {
        Bitmap mem = getFromMemoryCache(cacheKey);
        if (mem != null && !mem.isRecycled()) {
            return mem;
        }

        byte[] rawBytes = diskCache.getBytes(url);
        if (rawBytes == null || rawBytes.length == 0) {
            rawBytes = downloadImageBytes(url);
            if (rawBytes != null && rawBytes.length > 0) {
                diskCache.putBytes(url, rawBytes);
            }
        }
        if (rawBytes == null || rawBytes.length == 0) {
            return null;
        }

        try {
            Bitmap decoded = decodeSampledBitmapFromBytes(rawBytes, reqWidth, reqHeight);
            if (decoded == null) {
                return null;
            }
            if (shapeType == SHAPE_ROUNDED && cornerRadiusPx > 0f) {
                Bitmap rounded = createRoundedRectBitmap(decoded, reqWidth, reqHeight, cornerRadiusPx);
                if (rounded != decoded) {
                    decoded.recycle();
                }
                return rounded;
            }
            if (shapeType == SHAPE_CIRCLE) {
                int diameter = Math.min(reqWidth, reqHeight);
                Bitmap circle = createCircleCropBitmap(decoded, diameter);
                if (circle != decoded) {
                    decoded.recycle();
                }
                return circle;
            }
            return decoded;
        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "OutOfMemoryError khi giải mã ảnh, dọn dẹp LruCache: " + url);
            clearMemoryCache();
            System.gc();
            return null;
        }
    }

    /**
     * Giải mã mảng byte ảnh với {@code Bitmap.Config.RGB_565} và {@code inSampleSize}.
     */
    public static Bitmap decodeSampledBitmapFromBytes(byte[] data, int reqWidth, int reqHeight) {
        if (data == null || data.length == 0) {
            return null;
        }
        BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
        boundsOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, boundsOptions);

        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null;
        }

        BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
        decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, reqWidth, reqHeight);
        // RGB_565 giảm 50% bộ nhớ so với ARGB_8888 (2 byte/pixel thay vì 4 byte/pixel)
        decodeOptions.inPreferredConfig = Bitmap.Config.RGB_565;
        decodeOptions.inDither = true;
        decodeOptions.inPurgeable = true;
        decodeOptions.inInputShareable = true;
        return BitmapFactory.decodeByteArray(data, 0, data.length, decodeOptions);
    }

    /**
     * Tính lũy thừa của 2 cho {@code inSampleSize} sao cho ảnh giải mã vẫn đủ nét cho khung hiển thị.
     */
    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (reqWidth <= 0 || reqHeight <= 0) {
            return 1;
        }

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return Math.max(1, inSampleSize);
    }

    /**
     * Tạo Bitmap bo góc (8dp hoặc 12dp) tỷ lệ chuẩn (Center-Crop) bằng Canvas + BitmapShader + Paint(ANTI_ALIAS_FLAG).
     * Đồng thời tự động cắt bỏ viền đen trên/dưới nếu ảnh gốc là 4:3 (480x360) sang khung 16:9.
     */
    public static Bitmap createRoundedRectBitmap(Bitmap source,
                                                 int targetWidth,
                                                 int targetHeight,
                                                 float cornerRadiusPx) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        int outW = targetWidth > 0 ? targetWidth : source.getWidth();
        int outH = targetHeight > 0 ? targetHeight : source.getHeight();

        // Dùng ARGB_8888 cho bitmap đích để 4 góc bo có kênh alpha trong suốt trên nền #0F0F0F/#272727
        Bitmap output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = createCenterCropMatrix(source.getWidth(), source.getHeight(), outW, outH);
        shader.setLocalMatrix(matrix);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(shader);

        RectF rect = new RectF(0f, 0f, outW, outH);
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint);
        return output;
    }

    /**
     * Cắt ảnh hình tròn (Circle Crop) cho Avatar kênh bằng Canvas + BitmapShader + Paint(ANTI_ALIAS_FLAG).
     */
    public static Bitmap createCircleCropBitmap(Bitmap source, int diameterPx) {
        if (source == null || source.isRecycled()) {
            return null;
        }
        int size = diameterPx > 0 ? diameterPx : Math.min(source.getWidth(), source.getHeight());
        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = createCenterCropMatrix(source.getWidth(), source.getHeight(), size, size);
        shader.setLocalMatrix(matrix);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(shader);

        float radius = size / 2f;
        canvas.drawCircle(radius, radius, radius, paint);
        return output;
    }

    private static Matrix createCenterCropMatrix(int srcW, int srcH, int dstW, int dstH) {
        Matrix matrix = new Matrix();
        float scale;
        float dx = 0f;
        float dy = 0f;

        if (srcW * dstH > dstW * srcH) {
            scale = (float) dstH / (float) srcH;
            dx = (dstW - srcW * scale) * 0.5f;
        } else {
            scale = (float) dstW / (float) srcW;
            dy = (dstH - srcH * scale) * 0.5f;
        }
        matrix.setScale(scale, scale);
        matrix.postTranslate(Math.round(dx), Math.round(dy));
        return matrix;
    }

    // ------------------------------------------------------------------
    // Tải mạng qua HttpClient (hỗ trợ TLS 1.2 trên KitKat)
    // ------------------------------------------------------------------

    private static byte[] downloadImageBytes(String url) {
        HttpURLConnection connection = null;
        InputStream in = null;
        try {
            connection = HttpClient.openConnection(url, "GET", null);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            in = connection.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            if (Config.DEBUG_LOG) {
                Log.w(TAG, "Không tải được ảnh " + url + ": " + e.getMessage());
            }
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------
    // Quản lý Memory Cache
    // ------------------------------------------------------------------

    private Bitmap getFromMemoryCache(String key) {
        synchronized (memoryCache) {
            return memoryCache.get(key);
        }
    }

    private void putIntoMemoryCache(String key, Bitmap bitmap) {
        if (key == null || bitmap == null || bitmap.isRecycled()) {
            return;
        }
        synchronized (memoryCache) {
            memoryCache.put(key, bitmap);
        }
    }

    public void clearMemoryCache() {
        synchronized (memoryCache) {
            memoryCache.evictAll();
        }
    }

    public float dpToPx(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, displayMetrics);
    }

    public int getScreenWidthPx() {
        return displayMetrics.widthPixels;
    }

    private static String buildCacheKey(String url, int w, int h, int shape, int radius) {
        return url + "#w" + w + "h" + h + "s" + shape + "r" + radius;
    }
}
