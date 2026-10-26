package com.ytkitkat.app.network;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Lỗi HTTP (mã >= 400). Tự phân tích định dạng lỗi chuẩn của Google APIs:
 * {"error":{"code":403,"message":"...","errors":[{"reason":"quotaExceeded"}]}}
 */
public class HttpException extends IOException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String body;
    private final String url;
    private final String googleReason;
    private final String googleMessage;

    public HttpException(int code, String body, String url) {
        super(buildMessage(code, body, url));
        this.code = code;
        this.body = body != null ? body : "";
        this.url = url;
        this.googleReason = parseGoogleReason(this.body);
        this.googleMessage = parseGoogleMessage(this.body);
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

    /** Ví dụ: "quotaExceeded", "keyInvalid", "commentsDisabled". Có thể null. */
    public String getGoogleReason() {
        return googleReason;
    }

    public String getGoogleMessage() {
        return googleMessage;
    }

    public boolean isServerError() {
        return code >= 500;
    }

    public boolean isRetryable() {
        return code >= 500 || code == 429 || code == 408;
    }

    public boolean isQuotaExceeded() {
        return "quotaExceeded".equals(googleReason) || "dailyLimitExceeded".equals(googleReason)
                || "rateLimitExceeded".equals(googleReason);
    }

    private static String buildMessage(int code, String body, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(code);
        String message = parseGoogleMessage(body);
        String reason = parseGoogleReason(body);
        if (reason != null) {
            sb.append(" [").append(reason).append(']');
        }
        if (message != null) {
            sb.append(": ").append(message);
        } else if (body != null && body.length() > 0) {
            String snippet = body.length() > 160 ? body.substring(0, 160) + "…" : body;
            sb.append(": ").append(snippet.replace('\n', ' '));
        }
        sb.append(" (").append(HttpClient.redactUrl(url)).append(')');
        return sb.toString();
    }

    private static JSONObject errorObject(String body) {
        if (body == null || body.length() == 0 || body.charAt(0) != '{') {
            return null;
        }
        try {
            JSONObject root = new JSONObject(body);
            Object error = root.opt("error");
            if (error instanceof JSONObject) {
                return (JSONObject) error;
            }
            if (error instanceof String) {
                JSONObject wrapper = new JSONObject();
                wrapper.put("message", error);
                return wrapper;
            }
            if (root.has("message")) {
                return root;
            }
        } catch (Exception ignored) {
            // body không phải JSON
        }
        return null;
    }

    private static String parseGoogleMessage(String body) {
        JSONObject error = errorObject(body);
        if (error == null) {
            return null;
        }
        String msg = error.optString("message", "");
        return msg.length() > 0 ? msg : null;
    }

    private static String parseGoogleReason(String body) {
        JSONObject error = errorObject(body);
        if (error == null) {
            return null;
        }
        JSONArray errors = error.optJSONArray("errors");
        if (errors != null && errors.length() > 0) {
            JSONObject first = errors.optJSONObject(0);
            if (first != null) {
                String reason = first.optString("reason", "");
                if (reason.length() > 0) {
                    return reason;
                }
            }
        }
        String status = error.optString("status", "");
        return status.length() > 0 ? status : null;
    }
}
