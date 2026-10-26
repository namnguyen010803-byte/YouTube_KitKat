package com.ytkitkat.app.network;

import android.annotation.SuppressLint;
import android.util.Log;

import com.ytkitkat.app.Config;
import com.ytkitkat.app.YtApplication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;

/**
 * HTTP client tối giản dựa trên {@link HttpURLConnection}, luôn gán {@link TLSSocketFactory}
 * đã khởi tạo với Conscrypt BoringSSL cho mọi kết nối {@link HttpsURLConnection}.
 */
public final class HttpClient {

    private static final String TAG = "HttpClient";

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 4.4.4; KitKat) AppleWebKit/537.36 (KHTML, like Gecko) YouTubeKitKat/1.0";

    private static final Pattern KEY_PARAM = Pattern.compile("([?&]key=)[^&]+");
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static final Object LOCK = new Object();
    private static volatile SSLSocketFactory sslSocketFactory;
    private static volatile boolean customTlsActive;

    @SuppressLint("BadHostnameVerifier")
    private static final HostnameVerifier TRUST_ALL_HOSTNAME_VERIFIER = new HostnameVerifier() {
        @Override
        public boolean verify(String hostname, SSLSession session) {
            return hostname != null && hostname.length() > 0;
        }
    };

    private HttpClient() {
    }

    // ------------------------------------------------------------------
    // TLS (Conscrypt BoringSSL + TLSSocketFactory)
    // ------------------------------------------------------------------

    public static SSLSocketFactory getSslSocketFactory() {
        SSLSocketFactory factory = sslSocketFactory;
        if (factory == null) {
            synchronized (LOCK) {
                if (sslSocketFactory == null) {
                    try {
                        YtApplication.installConscryptProvider();
                        TLSSocketFactory tlsFactory = new TLSSocketFactory();
                        sslSocketFactory = tlsFactory;
                        customTlsActive = true;
                        HttpsURLConnection.setDefaultSSLSocketFactory(tlsFactory);
                        HttpsURLConnection.setDefaultHostnameVerifier(TRUST_ALL_HOSTNAME_VERIFIER);
                    } catch (GeneralSecurityException e) {
                        Log.e(TAG, "Không tạo được TLSSocketFactory, dùng factory hệ thống", e);
                        sslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
                        customTlsActive = false;
                    }
                }
                factory = sslSocketFactory;
            }
        }
        return factory;
    }

    public static HostnameVerifier getHostnameVerifier() {
        return TRUST_ALL_HOSTNAME_VERIFIER;
    }

    public static boolean isCustomTlsActive() {
        getSslSocketFactory();
        return customTlsActive;
    }

    // ------------------------------------------------------------------
    // API công khai
    // ------------------------------------------------------------------

    public static HttpResponse get(String url, Map<String, String> headers) throws IOException {
        return execute("GET", url, headers, null, Config.HTTP_MAX_RETRIES);
    }

    public static HttpResponse postJson(String url, Map<String, String> customHeaders, String jsonBody)
            throws IOException {
        return postJson(url, jsonBody, customHeaders);
    }

    /**
     * Gửi POST JSON. Nếu {@code customHeaders} được truyền vào (ví dụ cho các Mobile InnerTube
     * clients như {@code IOS}, {@code ANDROID_VR}, {@code ANDROID}), CHỈ gửi đúng các header
     * có trong {@code customHeaders} và {@code Content-Type: application/json} — tuyệt đối
     * không tự động chèn {@code Origin}, {@code Referer} hay {@code User-Agent} của trình duyệt web.
     */
    public static HttpResponse postJson(String url, String jsonBody, Map<String, String> customHeaders)
            throws IOException {
        boolean strictHeaders = customHeaders != null && !customHeaders.isEmpty();
        Map<String, String> merged = new LinkedHashMap<String, String>();
        merged.put("Content-Type", "application/json");
        if (strictHeaders) {
            merged.putAll(customHeaders);
        } else {
            merged.put("Accept", "application/json");
        }
        byte[] body = jsonBody != null ? jsonBody.getBytes("UTF-8") : new byte[0];
        return execute("POST", url, merged, body, Config.HTTP_MAX_RETRIES, strictHeaders);
    }

    /**
     * Thực thi request, tự thử lại khi lỗi IO tạm thời / 5xx / 429.
     *
     * @throws HttpException khi mã phản hồi >= 400 (sau khi hết lượt thử lại)
     */
    public static HttpResponse execute(String method, String url, Map<String, String> headers,
                                       byte[] body, int maxRetries) throws IOException {
        return execute(method, url, headers, body, maxRetries, false);
    }

    public static HttpResponse execute(String method, String url, Map<String, String> headers,
                                       byte[] body, int maxRetries, boolean strictCustomHeaders)
            throws IOException {
        IOException lastError = null;
        int attempts = Math.max(0, maxRetries) + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Request bị hủy");
            }
            try {
                HttpResponse response = executeOnce(method, url, headers, body, strictCustomHeaders);
                if (response.getCode() >= 400) {
                    HttpException httpError = new HttpException(response.getCode(), response.getBody(), url);
                    if (httpError.isRetryable() && attempt < attempts) {
                        lastError = httpError;
                        sleepBackoff(attempt);
                        continue;
                    }
                    throw httpError;
                }
                return response;
            } catch (HttpException e) {
                throw e;
            } catch (InterruptedIOException e) {
                if (Thread.currentThread().isInterrupted() || attempt >= attempts) {
                    throw e;
                }
                lastError = e;
                sleepBackoff(attempt);
            } catch (IOException e) {
                if (attempt >= attempts) {
                    throw e;
                }
                lastError = e;
                if (Config.DEBUG_LOG) {
                    Log.w(TAG, "Lỗi IO lần " + attempt + " với " + redactUrl(url) + ": " + e);
                }
                sleepBackoff(attempt);
            }
        }
        throw lastError != null ? lastError : new IOException("Request thất bại: " + redactUrl(url));
    }

    private static HttpResponse executeOnce(String method, String url, Map<String, String> headers,
                                            byte[] body, boolean strictCustomHeaders) throws IOException {
        HttpURLConnection connection = openConnection(url, method, headers, strictCustomHeaders);
        boolean success = false;
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String charset = parseCharset(connection.getContentType());
            String text = in != null ? readFully(in, charset) : "";
            String finalUrl = connection.getURL() != null ? connection.getURL().toString() : url;
            HttpResponse response = new HttpResponse(code, text, finalUrl, connection.getHeaderFields());
            success = true;
            if (Config.DEBUG_LOG) {
                Log.d(TAG, method + " " + redactUrl(url) + " -> " + code + " (" + text.length() + " ký tự)");
            }
            return response;
        } finally {
            if (!success) {
                connection.disconnect();
            }
        }
    }

    /**
     * Mở {@link HttpURLConnection} và luôn gán {@link TLSSocketFactory} (khởi tạo với Conscrypt).
     */
    public static HttpURLConnection openConnection(String url, String method, Map<String, String> headers)
            throws IOException {
        return openConnection(url, method, headers, false);
    }

    /**
     * Mở {@link HttpURLConnection}. Khi {@code strictCustomHeaders == true}, CHỈ gửi đúng các
     * header có trong {@code headers} mà không tự động chèn bất kỳ header mặc định nào của trình duyệt.
     */
    public static HttpURLConnection openConnection(String url, String method, Map<String, String> headers,
                                                   boolean strictCustomHeaders)
            throws IOException {
        URL target = new URL(url);
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
            httpsConn.setSSLSocketFactory(getSslSocketFactory());
            httpsConn.setHostnameVerifier(TRUST_ALL_HOSTNAME_VERIFIER);
        }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(Config.CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(Config.READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(true);
        connection.setDoInput(true);

        boolean hasCustomUserAgent = false;
        boolean hasCustomAccept = false;
        boolean hasCustomAcceptLang = false;

        if (headers != null) {
            for (Map.Entry<String, String> header : headers.entrySet()) {
                String k = header.getKey();
                String v = header.getValue();
                if (k == null || v == null) {
                    continue;
                }
                if ("User-Agent".equalsIgnoreCase(k)) {
                    hasCustomUserAgent = true;
                } else if ("Accept".equalsIgnoreCase(k)) {
                    hasCustomAccept = true;
                } else if ("Accept-Language".equalsIgnoreCase(k)) {
                    hasCustomAcceptLang = true;
                }
                connection.setRequestProperty(k, v);
            }
        }

        if (!strictCustomHeaders) {
            if (!hasCustomUserAgent) {
                connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
            }
            if (!hasCustomAccept) {
                connection.setRequestProperty("Accept", "*/*");
            }
            if (!hasCustomAcceptLang) {
                connection.setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.7,en;q=0.5");
            }
            connection.setRequestProperty("Connection", "keep-alive");
        }

        return connection;
    }

    // ------------------------------------------------------------------
    // Tiện ích
    // ------------------------------------------------------------------

    public static String readFully(InputStream in, String charset) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        int total = 0;
        try {
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_BODY_BYTES) {
                    throw new IOException("Phản hồi quá lớn (> " + MAX_BODY_BYTES + " byte)");
                }
                buffer.write(chunk, 0, read);
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        try {
            return buffer.toString(charset != null ? charset : "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return buffer.toString("UTF-8");
        }
    }

    private static String parseCharset(String contentType) {
        if (contentType == null) {
            return "UTF-8";
        }
        String[] parts = contentType.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith("charset=")) {
                String cs = trimmed.substring(8).trim().replace("\"", "");
                if (cs.length() > 0) {
                    return cs;
                }
            }
        }
        return "UTF-8";
    }

    private static void sleepBackoff(int attempt) throws InterruptedIOException {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Request bị hủy trong lúc chờ thử lại");
        }
    }

    /** URL-encode UTF-8 (thay '+' bằng '%20' cho chuẩn). */
    public static String encode(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    public static String buildUrl(String base, String... keyValues) {
        StringBuilder sb = new StringBuilder(base);
        boolean hasQuery = base.indexOf('?') >= 0;
        if (keyValues != null) {
            for (int i = 0; i + 1 < keyValues.length; i += 2) {
                String key = keyValues[i];
                String value = keyValues[i + 1];
                if (key == null || value == null || value.length() == 0) {
                    continue;
                }
                sb.append(hasQuery ? '&' : '?');
                hasQuery = true;
                sb.append(encode(key)).append('=').append(encode(value));
            }
        }
        return sb.toString();
    }

    /** Ẩn API key trước khi ghi log / hiển thị lỗi. */
    public static String redactUrl(String url) {
        if (url == null) {
            return "(null)";
        }
        return KEY_PARAM.matcher(url).replaceAll("$1***");
    }
}
