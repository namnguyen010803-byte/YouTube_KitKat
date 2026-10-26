package com.ytkitkat.app.network;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Kết quả một request HTTP (đã đọc xong toàn bộ body).
 */
public final class HttpResponse {

    private final int code;
    private final String body;
    private final String url;
    private final Map<String, List<String>> headers;

    public HttpResponse(int code, String body, String url, Map<String, List<String>> headers) {
        this.code = code;
        this.body = body != null ? body : "";
        this.url = url;
        if (headers != null) {
            this.headers = headers;
        } else {
            this.headers = Collections.emptyMap();
        }
    }

    public int getCode() {
        return code;
    }

    public String getBody() {
        return body;
    }

    public String getUrl() {
        return url;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    /** Lấy giá trị đầu tiên của header (không phân biệt hoa thường). */
    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.equalsIgnoreCase(name)) {
                List<String> values = entry.getValue();
                if (values != null && !values.isEmpty()) {
                    return values.get(0);
                }
            }
        }
        return null;
    }

    public boolean isSuccessful() {
        return code >= 200 && code < 300;
    }

    public JSONObject asJsonObject() throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Phản hồi không phải JSON object hợp lệ từ "
                    + HttpClient.redactUrl(url) + ": " + e.getMessage());
        }
    }

    public JSONArray asJsonArray() throws IOException {
        try {
            return new JSONArray(body);
        } catch (JSONException e) {
            throw new IOException("Phản hồi không phải JSON array hợp lệ từ "
                    + HttpClient.redactUrl(url) + ": " + e.getMessage());
        }
    }
}
