package com.ytkitkat.app.util;

import android.content.Context;
import android.util.Log;

import com.ytkitkat.app.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Bộ nhớ đệm ảnh trên đĩa (Disk Cache) lưu trong {@code context.getCacheDir()/thumbs}
 * với tên file mã hóa MD5 32 ký tự hex.
 *
 * Tự động dọn dẹp các file cũ nhất (LRU theo lastModified) khi tổng dung lượng vượt quá
 * {@link Config#IMAGE_DISK_CACHE_MAX_BYTES}.
 */
public final class DiskImageCache {

    private static final String TAG = "DiskImageCache";
    private static final String SUBDIR_NAME = "thumbs";
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private final File cacheDir;
    private final long maxBytes;
    private volatile long writeCountSinceTrim = 0L;

    public DiskImageCache(Context context, long maxBytes) {
        File base = context.getCacheDir();
        this.cacheDir = new File(base, SUBDIR_NAME);
        this.maxBytes = maxBytes;
        if (!this.cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.cacheDir.mkdirs();
        }
    }

    public File getFileForKey(String url) {
        String name = md5Hex(url) + ".img";
        return new File(cacheDir, name);
    }

    /** Đọc mảng byte ảnh từ đĩa nếu có; cập nhật lastModified để phục vụ LRU. */
    public byte[] getBytes(String url) {
        if (url == null || url.length() == 0) {
            return null;
        }
        File file = getFileForKey(url);
        if (!file.exists() || !file.isFile() || file.length() <= 0) {
            return null;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(file);
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(file.length(), 65536L));
            byte[] buf = new byte[8192];
            int read;
            while ((read = fis.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            // Đánh dấu truy cập gần nhất
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Ghi mảng byte ảnh nguyên bản xuống đĩa (ghi qua file .tmp rồi đổi tên để tránh đọc file dở dang). */
    public void putBytes(String url, byte[] data) {
        if (url == null || url.length() == 0 || data == null || data.length == 0) {
            return;
        }
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        File target = getFileForKey(url);
        File temp = new File(cacheDir, target.getName() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(temp);
            fos.write(data);
            fos.flush();
            fos.close();
            fos = null;
            if (target.exists()) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            temp.renameTo(target);

            writeCountSinceTrim++;
            if (writeCountSinceTrim % 25 == 0) {
                trimToSize();
            }
        } catch (IOException e) {
            Log.w(TAG, "Không ghi được disk cache: " + e.getMessage());
            if (temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Xóa bớt các file cũ nhất nếu tổng dung lượng vượt quá maxBytes. */
    public synchronized void trimToSize() {
        File[] files = cacheDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        long total = 0L;
        for (File f : files) {
            if (f.isFile()) {
                total += f.length();
            }
        }
        if (total <= maxBytes) {
            return;
        }

        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long la = a.lastModified();
                long lb = b.lastModified();
                return la < lb ? -1 : (la == lb ? 0 : 1);
            }
        });

        long targetSize = (long) (maxBytes * 0.8);
        for (File f : files) {
            if (total <= targetSize) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                total -= len;
            }
        }
    }

    public synchronized void clear() {
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public static String md5Hex(String input) {
        if (input == null) {
            return "null";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = HEX_DIGITS[v >>> 4];
                out[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
