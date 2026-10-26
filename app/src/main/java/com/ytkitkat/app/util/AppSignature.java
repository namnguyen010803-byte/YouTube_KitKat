package com.ytkitkat.app.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;

/**
 * Lấy SHA-1 chữ ký APK để gửi header X-Android-Cert.
 * Cần thiết khi API key bị giới hạn "Android apps" trên Google Cloud Console.
 */
public final class AppSignature {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private AppSignature() {
    }

    /** @return SHA-1 dạng HEX viết hoa, không dấu ':' (định dạng Google yêu cầu), hoặc null. */
    @SuppressWarnings("deprecation")
    public static String getSha1Hex(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature[] signatures = info.signatures;
            if (signatures == null || signatures.length == 0) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(signatures[0].toByteArray());
            char[] out = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                int v = hash[i] & 0xFF;
                out[i * 2] = HEX[v >>> 4];
                out[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(out);
        } catch (Exception e) {
            return null;
        }
    }
}
