package com.ytkitkat.app;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.ytkitkat.app.network.HttpClient;
import com.ytkitkat.app.network.YouTubeApi;

import org.conscrypt.Conscrypt;

import java.security.Provider;
import java.security.Security;

import javax.net.ssl.HttpsURLConnection;

/**
 * Application: khởi tạo Security Provider Conscrypt (BoringSSL) ở vị trí ưu tiên số 1
 * và cấu hình TLSSocketFactory trước khi thực hiện bất kỳ kết nối mạng nào.
 */
public class YtApplication extends Application {

    private static final String TAG = "YtApplication";

    private static volatile Context appContext;
    private static volatile boolean conscryptInstalled = false;

    @Override
    public void onCreate() {
        super.onCreate();
        appContext = getApplicationContext();

        // 1. Đăng ký Conscrypt (BoringSSL) làm Security Provider ưu tiên số 1 cho Android 4.4.4 (KitKat)
        installConscryptProvider();

        // 2. Giữ kết nối keep-alive để tái sử dụng socket TLS
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "8");

        // 3. Đặt TLSSocketFactory (chạy trên nền Conscrypt + TrustAllManager) làm mặc định cho mọi HttpsURLConnection
        HttpsURLConnection.setDefaultSSLSocketFactory(HttpClient.getSslSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier(HttpClient.getHostnameVerifier());

        YouTubeApi.getInstance().init(appContext);

        Log.i(TAG, "Khởi động trên Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), Conscrypt=" + conscryptInstalled
                + ", TLS factory tùy chỉnh=" + HttpClient.isCustomTlsActive());
    }

    /**
     * Đảm bảo Conscrypt được cài đặt ở vị trí số 1 trong danh sách Security Providers.
     * Có thể gọi an toàn nhiều lần từ Application hoặc HttpClient.
     */
    public static synchronized void installConscryptProvider() {
        if (conscryptInstalled) {
            return;
        }
        try {
            Provider provider = Conscrypt.newProvider();
            int pos = Security.insertProviderAt(provider, 1);
            conscryptInstalled = (pos == 1 || Security.getProvider(provider.getName()) != null);
            Log.i(TAG, "Đã đăng ký Conscrypt BoringSSL provider tại vị trí: " + pos);
        } catch (Throwable t) {
            Log.w(TAG, "Không thể đăng ký Conscrypt provider: " + t.getMessage());
        }
    }

    public static boolean isConscryptInstalled() {
        return conscryptInstalled;
    }

    public static Context getAppContext() {
        return appContext;
    }
}
