package com.ytkitkat.app.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ytkitkat.app.Config;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Bộ lấy gợi ý từ khóa trực tiếp từ YouTube Suggest API:
 * {@code https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&q={encoded_query}}
 *
 * - Hỗ trợ cả định dạng JSONP {@code window.google.ac.h([...])} của {@code client=youtube}
 *   lẫn mảng JSON thuần {@code ["query", ["sug1", "sug2", ...]]}.
 * - Tích hợp cơ chế Debounce (300ms) bằng {@link Handler} để không spam request khi người dùng đang gõ.
 */
public final class SearchSuggestionClient {

    private static final String TAG = "SearchSuggestionClient";
    public static final long DEFAULT_DEBOUNCE_MS = 300L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingRunnable;
    private RequestHandle activeRequest;

    public SearchSuggestionClient() {
    }

    /**
     * Đặt lịch lấy gợi ý với độ trễ debounce (mặc định 300ms).
     * Nếu người dùng gõ phím tiếp theo trước 300ms, yêu cầu cũ sẽ bị hủy ngay lập tức.
     */
    public void requestSuggestionsDebounced(final String rawQuery,
                                            final ApiCallback<List<String>> callback) {
        requestSuggestionsDebounced(rawQuery, DEFAULT_DEBOUNCE_MS, callback);
    }

    public void requestSuggestionsDebounced(final String rawQuery,
                                            long debounceMs,
                                            final ApiCallback<List<String>> callback) {
        cancelPending();

        final String trimmed = rawQuery != null ? rawQuery.trim() : "";
        if (trimmed.length() == 0) {
            if (callback != null) {
                callback.onSuccess(Collections.<String>emptyList());
            }
            return;
        }

        pendingRunnable = new Runnable() {
            @Override
            public void run() {
                activeRequest = NetworkExecutor.get().execute(new Callable<List<String>>() {
                    @Override
                    public List<String> call() throws Exception {
                        return fetchSuggestionsSync(trimmed);
                    }
                }, callback);
            }
        };
        mainHandler.postDelayed(pendingRunnable, Math.max(0L, debounceMs));
    }

    /** Hủy mọi yêu cầu gợi ý đang chờ hoặc đang chạy. */
    public void cancelPending() {
        if (pendingRunnable != null) {
            mainHandler.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
        if (activeRequest != null) {
            activeRequest.cancel();
            activeRequest = null;
        }
    }

    /**
     * Gọi đồng bộ tới endpoint:
     * {@code https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&hl=vi&q={encoded_query}}
     */
    public static List<String> fetchSuggestionsSync(String query) throws IOException {
        if (query == null || query.trim().length() == 0) {
            return Collections.emptyList();
        }
        String url = HttpClient.buildUrl(
                Config.SEARCH_SUGGEST_URL,
                "client", "youtube",
                "ds", "yt",
                "hl", Config.LANGUAGE_CODE,
                "q", query.trim()
        );
        HttpResponse response = HttpClient.get(url, null);
        return parseSuggestionPayload(response.getBody());
    }

    /**
     * Phân tích chuỗi trả về từ {@code client=youtube}:
     * Có thể ở dạng JSONP: {@code window.google.ac.h(["q",[["gợi ý 1",0,[512]],["gợi ý 2",0,[512]]],{...}])}
     * hoặc mảng JSON lồng nhau: {@code ["q",["gợi ý 1","gợi ý 2"]]}.
     */
    public static List<String> parseSuggestionPayload(String body) {
        List<String> results = new ArrayList<String>();
        if (body == null || body.length() == 0) {
            return results;
        }
        String jsonText = unwrapJsonpIfNeeded(body.trim());
        try {
            JSONArray root = new JSONArray(jsonText);
            if (root.length() <= 1) {
                return results;
            }
            JSONArray secondElement = root.optJSONArray(1);
            if (secondElement == null) {
                return results;
            }
            for (int i = 0; i < secondElement.length(); i++) {
                Object item = secondElement.opt(i);
                String suggestion = null;
                if (item instanceof JSONArray) {
                    JSONArray nested = (JSONArray) item;
                    if (nested.length() > 0) {
                        suggestion = nested.optString(0, "");
                    }
                } else if (item instanceof String) {
                    suggestion = (String) item;
                }
                if (suggestion != null) {
                    String cleaned = suggestion.trim();
                    if (cleaned.length() > 0 && !results.contains(cleaned)) {
                        results.add(cleaned);
                    }
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Không phân tích được chuỗi gợi ý: " + e.getMessage());
        }
        return results;
    }

    private static String unwrapJsonpIfNeeded(String raw) {
        if (raw.startsWith("[")) {
            return raw;
        }
        int firstBracket = raw.indexOf('[');
        int lastBracket = raw.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            return raw.substring(firstBracket, lastBracket + 1);
        }
        return raw;
    }
}
