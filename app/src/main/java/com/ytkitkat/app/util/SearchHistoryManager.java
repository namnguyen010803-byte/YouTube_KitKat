package com.ytkitkat.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/**
 * Quản lý lịch sử tìm kiếm cục bộ bằng {@link SharedPreferences} (lưu tối đa 20 từ khóa gần nhất).
 * Hỗ trợ thêm mới, lọc theo tiền tố, xóa từng từ khóa hoặc xóa toàn bộ lịch sử.
 */
public final class SearchHistoryManager {

    private static final String PREF_NAME = "yt_kitkat_search_history";
    private static final String KEY_QUERIES_JSON = "recent_queries_json";
    public static final int MAX_HISTORY_SIZE = 20;

    private static volatile SearchHistoryManager instance;
    private final SharedPreferences prefs;

    private SearchHistoryManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static SearchHistoryManager getInstance(Context context) {
        SearchHistoryManager local = instance;
        if (local == null) {
            synchronized (SearchHistoryManager.class) {
                local = instance;
                if (local == null) {
                    local = new SearchHistoryManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Lưu từ khóa vừa tìm lên đầu danh sách (tối đa 20 mục, không trùng lặp). */
    public synchronized void addQuery(String query) {
        if (query == null) {
            return;
        }
        String trimmed = query.trim();
        if (trimmed.length() == 0) {
            return;
        }
        List<String> current = getAllQueries();
        List<String> updated = new ArrayList<String>();
        updated.add(trimmed);
        for (int i = 0; i < current.size(); i++) {
            String existing = current.get(i);
            if (!existing.equalsIgnoreCase(trimmed)) {
                updated.add(existing);
                if (updated.size() >= MAX_HISTORY_SIZE) {
                    break;
                }
            }
        }
        saveList(updated);
    }

    /** Lấy toàn bộ các từ khóa trong lịch sử (mới nhất ở đầu). */
    public synchronized List<String> getAllQueries() {
        List<String> list = new ArrayList<String>();
        String raw = prefs.getString(KEY_QUERIES_JSON, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String q = array.optString(i, "").trim();
                if (q.length() > 0) {
                    list.add(q);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** Lọc các từ khóa lịch sử khớp với chuỗi đang gõ. */
    public synchronized List<String> getMatchingQueries(String prefix) {
        List<String> all = getAllQueries();
        if (prefix == null || prefix.trim().length() == 0) {
            return all;
        }
        String lower = prefix.trim().toLowerCase();
        List<String> matched = new ArrayList<String>();
        for (int i = 0; i < all.size(); i++) {
            String item = all.get(i);
            if (item.toLowerCase().contains(lower)) {
                matched.add(item);
            }
        }
        return matched;
    }

    /** Xóa một từ khóa cụ thể khỏi lịch sử. */
    public synchronized void removeQuery(String query) {
        if (query == null) {
            return;
        }
        List<String> current = getAllQueries();
        List<String> updated = new ArrayList<String>();
        for (int i = 0; i < current.size(); i++) {
            String item = current.get(i);
            if (!item.equalsIgnoreCase(query.trim())) {
                updated.add(item);
            }
        }
        saveList(updated);
    }

    /** Xóa toàn bộ lịch sử tìm kiếm. */
    public synchronized void clearAll() {
        prefs.edit().remove(KEY_QUERIES_JSON).apply();
    }

    private void saveList(List<String> queries) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < queries.size(); i++) {
            array.put(queries.get(i));
        }
        prefs.edit().putString(KEY_QUERIES_JSON, array.toString()).apply();
    }
}
