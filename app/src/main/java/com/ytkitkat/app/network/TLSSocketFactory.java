package com.ytkitkat.app.network;

import android.annotation.SuppressLint;
import android.util.Log;

import com.ytkitkat.app.YtApplication;

import org.conscrypt.Conscrypt;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * SSLSocketFactory chuyên dụng cho Android 4.4.4 (KitKat - API 19, Sharp Aquos 305SH)
 * theo kiến trúc của BravePipe Legacy:
 *
 * 1. Sử dụng trực tiếp Engine BoringSSL ({@code org.conscrypt:conscrypt-android:2.5.2}) ở vị trí
 *    Security Provider số 1 để có đầy đủ TLS 1.2 / TLS 1.3, ECDHE, ChaCha20-Poly1305 và SNI chuẩn,
 *    thay thế hoàn toàn thư viện OpenSSL cũ của ROM Android 4.4.
 * 2. TUYỆT ĐỐI KHÔNG bọc socket trong lớp wrapper ({@code TlsEnforcingSocket}) — trả về trực tiếp
 *    instance {@link SSLSocket} do {@code delegate} tạo ra để giữ nguyên 100% các hàm native/reflection
 *    của Conscrypt và {@code HttpsURLConnection}.
 * 3. Trong {@link #enableTLSOnSocket(Socket, String)}:
 *    - Bật toàn bộ giao thức từ {@code TLSv1.2} trở lên ({@code TLSv1.3}, {@code TLSv1.2}).
 *    - Loại bỏ triệt để mọi cipher chứa {@code "FALLBACK_SCSV"}, {@code "SSL_"}, {@code "RC4"},
 *      {@code "DES"}, {@code "EXPORT"}, {@code "NULL"} khỏi {@code enabledCipherSuites} để chặn đứng
 *      lỗi {@code SSL23_GET_SERVER_HELLO:tlsv1 alert inappropriate fallback}.
 *    - Gán SNI ({@code setHostname(host)}) và {@code setUseSessionTickets(true)} qua cả API của
 *      {@link Conscrypt} lẫn reflection.
 * 4. Sử dụng {@link TrustAllManager} khi khởi tạo {@link SSLContext} để bỏ qua các chứng chỉ gốc (Root CA)
 *    đã hết hạn trên ROM Android 4.4.4.
 */
public class TLSSocketFactory extends SSLSocketFactory {

    private static final String TAG = "TLSSocketFactory";

    public static final String TLS_V13 = "TLSv1.3";
    public static final String TLS_V12 = "TLSv1.2";

    private final SSLSocketFactory delegate;
    private final String providerName;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        // Đảm bảo Conscrypt provider đã được gắn ở vị trí số 1 trước khi lấy SSLContext
        YtApplication.installConscryptProvider();

        SSLContext context = createBestSslContext();
        TrustManager[] trustAllCerts = new TrustManager[] { new TrustAllManager() };
        context.init(null, trustAllCerts, new SecureRandom());

        this.delegate = context.getSocketFactory();
        this.providerName = context.getProvider() != null ? context.getProvider().getName() : "Unknown";
        Log.i(TAG, "Khởi tạo TLSSocketFactory thành công với Provider=" + this.providerName
                + ", Protocol=" + context.getProtocol());
    }

    private static SSLContext createBestSslContext() throws NoSuchAlgorithmException {
        // Ưu tiên 1: Lấy trực tiếp từ Conscrypt BoringSSL Provider (hỗ trợ cả TLSv1.3 và TLSv1.2)
        try {
            Provider conscryptProvider = Conscrypt.newProvider();
            try {
                return SSLContext.getInstance(TLS_V13, conscryptProvider);
            } catch (NoSuchAlgorithmException ignored) {
                return SSLContext.getInstance(TLS_V12, conscryptProvider);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Không khởi tạo trực tiếp được từ Conscrypt.newProvider(): " + t.getMessage());
        }

        // Ưu tiên 2: Lấy qua danh sách Security Provider hệ thống (Conscrypt ở vị trí số 1)
        try {
            return SSLContext.getInstance(TLS_V13);
        } catch (NoSuchAlgorithmException ignored) {
        }
        try {
            return SSLContext.getInstance(TLS_V12);
        } catch (NoSuchAlgorithmException ignored) {
        }
        return SSLContext.getInstance("TLS");
    }

    public String getProviderName() {
        return providerName;
    }

    // ------------------------------------------------------------------
    // SSLSocketFactory API (Trả về trực tiếp SSLSocket gốc, KHÔNG bọc wrapper)
    // ------------------------------------------------------------------

    @Override
    public String[] getDefaultCipherSuites() {
        return filterCipherSuites(delegate.getDefaultCipherSuites());
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return filterCipherSuites(delegate.getSupportedCipherSuites());
    }

    @Override
    public Socket createSocket() throws IOException {
        return enableTLSOnSocket(delegate.createSocket(), null);
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose), host);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
        return enableTLSOnSocket(delegate.createSocket(host, port), host);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
            throws IOException, UnknownHostException {
        return enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort), host);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        String hostname = host != null ? host.getHostName() : null;
        return enableTLSOnSocket(delegate.createSocket(host, port), hostname);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
            throws IOException {
        String hostname = address != null ? address.getHostName() : null;
        return enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort), hostname);
    }

    // ------------------------------------------------------------------
    // Cấu hình trực tiếp trên SSLSocket gốc (Conscrypt / OpenSSL)
    // ------------------------------------------------------------------

    /**
     * Cấu hình trực tiếp {@link SSLSocket} mà KHÔNG bọc trong lớp wrapper:
     * 1. Bật tất cả giao thức từ {@code TLSv1.2} trở lên ({@code TLSv1.2}, {@code TLSv1.3}).
     * 2. Lọc bỏ mọi cipher chứa {@code FALLBACK_SCSV}, {@code SSL_}, {@code RC4}, {@code DES},
     *    {@code EXPORT}, {@code NULL} khỏi {@code enabledCipherSuites}.
     * 3. Thiết lập SNI ({@code setHostname(host)}) và {@code setUseSessionTickets(true)}.
     */
    private Socket enableTLSOnSocket(Socket socket, String host) {
        if (!(socket instanceof SSLSocket)) {
            return socket;
        }
        SSLSocket sslSocket = (SSLSocket) socket;

        // 1. Bật tất cả protocol khả dụng từ TLSv1.2 trở lên (TLSv1.2, TLSv1.3)
        String[] protocols = selectModernTlsProtocols(sslSocket.getSupportedProtocols());
        if (protocols.length > 0) {
            sslSocket.setEnabledProtocols(protocols);
        }

        // 2. Loại bỏ các cipher gây lỗi inappropriate fallback hoặc cipher yếu
        String[] currentEnabledCiphers = sslSocket.getEnabledCipherSuites();
        String[] filteredCiphers = filterCipherSuites(currentEnabledCiphers);
        if (filteredCiphers.length == 0) {
            filteredCiphers = filterCipherSuites(sslSocket.getSupportedCipherSuites());
        }
        if (filteredCiphers.length > 0) {
            sslSocket.setEnabledCipherSuites(filteredCiphers);
        }

        // 3. Bật SNI (Server Name Indication) và Session Tickets
        applySniAndSessionTickets(sslSocket, host);

        return sslSocket;
    }

    /**
     * Chọn tất cả giao thức từ TLSv1.2 trở lên ({@code TLSv1.3}, {@code TLSv1.2}).
     * Tuyệt đối không bật {@code SSLv3}, {@code TLSv1}, {@code TLSv1.1} để tránh bị hạ cấp.
     */
    static String[] selectModernTlsProtocols(String[] supported) {
        if (supported == null || supported.length == 0) {
            return new String[] { TLS_V12 };
        }
        Set<String> supportedSet = new HashSet<String>(Arrays.asList(supported));
        List<String> enabled = new ArrayList<String>();

        if (supportedSet.contains(TLS_V13)) {
            enabled.add(TLS_V13);
        }
        if (supportedSet.contains(TLS_V12)) {
            enabled.add(TLS_V12);
        }

        // Quét thêm bất kỳ biến thể TLSv1.2+ nào khác nếu có
        for (String p : supported) {
            if (p == null || enabled.contains(p)) {
                continue;
            }
            if (p.startsWith("TLSv1.") && !p.equals("TLSv1.0") && !p.equals("TLSv1.1")) {
                enabled.add(p);
            }
        }

        if (enabled.isEmpty()) {
            return new String[] { TLS_V12 };
        }
        return enabled.toArray(new String[enabled.size()]);
    }

    /**
     * Loại bỏ mọi cipher có chứa {@code "FALLBACK_SCSV"}, {@code "SSL_"}, {@code "RC4"},
     * {@code "DES"}, {@code "EXPORT"}, {@code "NULL"} khỏi danh sách cipher suites.
     */
    static String[] filterCipherSuites(String[] ciphers) {
        if (ciphers == null || ciphers.length == 0) {
            return new String[0];
        }
        List<String> safeList = new ArrayList<String>(ciphers.length);
        for (String cipher : ciphers) {
            if (isAllowedCipher(cipher)) {
                safeList.add(cipher);
            }
        }
        return safeList.toArray(new String[safeList.size()]);
    }

    static boolean isAllowedCipher(String cipher) {
        if (cipher == null || cipher.length() == 0) {
            return false;
        }
        String upper = cipher.toUpperCase();
        if (upper.contains("FALLBACK_SCSV")
                || upper.contains("SSL_")
                || upper.contains("RC4")
                || upper.contains("DES")
                || upper.contains("EXPORT")
                || upper.contains("NULL")
                || upper.contains("ANON")
                || upper.contains("MD5")) {
            return false;
        }
        return true;
    }

    /**
     * Gán SNI ({@code setHostname}) và {@code setUseSessionTickets(true)}
     * thông qua cả Conscrypt API trực tiếp lẫn reflection.
     */
    static void applySniAndSessionTickets(SSLSocket sslSocket, String host) {
        try {
            if (Conscrypt.isConscrypt(sslSocket)) {
                Conscrypt.setUseSessionTickets(sslSocket, true);
                if (host != null && host.length() > 0) {
                    Conscrypt.setHostname(sslSocket, host);
                }
                return;
            }
        } catch (Throwable ignored) {
        }

        // Dự phòng bằng reflection trên chính SSLSocket gốc
        invokeIfExists(sslSocket, "setUseSessionTickets", boolean.class, Boolean.TRUE);
        if (host != null && host.length() > 0) {
            invokeIfExists(sslSocket, "setHostname", String.class, host);
        }
    }

    private static void invokeIfExists(Object target, String methodName, Class<?> paramType, Object arg) {
        Class<?> clazz = target.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Method method = clazz.getDeclaredMethod(methodName, paramType);
                method.setAccessible(true);
                method.invoke(target, arg);
                return;
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            } catch (Exception e) {
                Log.w(TAG, "Không gọi được " + methodName + ": " + e.getMessage());
                return;
            }
        }
    }

    /**
     * Chuỗi mô tả chi tiết khả năng TLS + Conscrypt của thiết bị (hiển thị trên NetworkDiagnosticActivity).
     */
    public static String describeDeviceTlsSupport() {
        StringBuilder sb = new StringBuilder();
        try {
            SSLSocketFactory factory = HttpClient.getSslSocketFactory();
            SSLSocket probe = (SSLSocket) factory.createSocket();
            sb.append("Conscrypt Socket: ").append(Conscrypt.isConscrypt(probe)).append('\n');
            sb.append("Socket Class: ").append(probe.getClass().getName()).append('\n');
            sb.append("Giao thức hỗ trợ: ").append(Arrays.toString(probe.getSupportedProtocols())).append('\n');
            sb.append("Giao thức đang bật: ").append(Arrays.toString(probe.getEnabledProtocols())).append('\n');
            sb.append("Số Cipher an toàn đang bật: ").append(probe.getEnabledCipherSuites().length);
            probe.close();
        } catch (Exception e) {
            sb.append("Không thăm dò được TLS: ").append(e.getMessage());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // TrustAllManager: Bỏ qua kiểm tra CA Root đã hết hạn trên Android 4.4.4
    // ------------------------------------------------------------------

    @SuppressLint("TrustAllX509TrustManager")
    public static final class TrustAllManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Chấp nhận mọi chứng chỉ client trên thiết bị cũ
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Bỏ qua kiểm tra CA Root hết hạn trên ROM Android 4.4.4 (KitKat)
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
