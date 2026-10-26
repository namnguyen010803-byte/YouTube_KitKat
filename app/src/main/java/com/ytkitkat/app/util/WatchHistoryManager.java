package com.ytkitkat.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.ytkitkat.app.model.VideoItem;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Lưu lịch sử xem video cục bộ bằng SharedPreferences + JSONArray (Zero external dependencies).
 * Phục vụ Tab 3 ("Thư viện / Lịch sử xem") trên màn hình chính.
 */
public final class WatchHistoryManager {

    private static final String PREF_NAME = "yt_kitkat_watch_history";
    private static final String KEY_HISTORY_JSON = "history_items_json";
    private static final int MAX_HISTORY_ITEMS = 50;

    private static volatile WatchHistoryManager instance;
    private final SharedPreferences prefs;

    private WatchHistoryManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static WatchHistoryManager getInstance(Context context) {
        WatchHistoryManager local = instance;
        if (local == null) {
            synchronized (WatchHistoryManager.class) {
                local = instance;
                if (local == null) {
                    local = new WatchHistoryManager(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Ghi nhận một video vừa được bấm xem (đưa lên đầu danh sách lịch sử). */
    public synchronized void recordWatched(VideoItem video) {
        if (video == null || video.getId() == null || video.getId().length() == 0) {
            return;
        }
        List<VideoItem> current = getHistoryItems();
        List<VideoItem> updated = new ArrayList<VideoItem>();
        updated.add(video);

        for (VideoItem existing : current) {
            if (!video.getId().equals(existing.getId())) {
                updated.add(existing);
                if (updated.size() >= MAX_HISTORY_ITEMS) {
                    break;
                }
            }
        }
        saveList(updated);
    }

    /** Lấy danh sách video đã xem (mới nhất ở đầu). */
    public synchronized List<VideoItem> getHistoryItems() {
        List<VideoItem> list = new ArrayList<VideoItem>();
        String raw = prefs.getString(KEY_HISTORY_JSON, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                VideoItem item = deserializeItem(arr.optJSONObject(i));
                if (item != null) {
                    list.add(item);
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    public synchronized void clearHistory() {
        prefs.edit().remove(KEY_HISTORY_JSON).apply();
    }

    private void saveList(List<VideoItem> items) {
        JSONArray arr = new JSONArray();
        for (VideoItem v : items) {
            try {
                arr.put(serializeItem(v));
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_HISTORY_JSON, arr.toString()).apply();
    }

    private static JSONObject serializeItem(VideoItem v) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", v.getId());
        o.put("title", v.getTitle());
        o.put("channelId", v.getChannelId());
        o.put("channelTitle", v.getChannelTitle());
        o.put("thumbnailUrl", v.getThumbnailUrl());
        o.put("thumbnailHighUrl", v.getThumbnailHighUrl());
        o.put("channelAvatarUrl", v.getChannelAvatarUrl());
        o.put("durationSeconds", v.getDurationSeconds());
        o.put("viewCount", v.getViewCount());
        o.put("publishedAt", v.getPublishedAt());
        return o;
    }

    private static VideoItem deserializeItem(JSONObject o) {
        if (o == null) {
            return null;
        }
        String id = o.optString("id", "");
        if (id.length() == 0) {
            return null;
        }
        try {
            JSONObject fakeVideoJson = new JSONObject();
            fakeVideoJson.put("id", id);

            JSONObject snippet = new JSONObject();
            snippet.put("title", o.optString("title", ""));
            snippet.put("channelId", o.optString("channelId", ""));
            snippet.put("channelTitle", o.optString("channelTitle", ""));
            snippet.put("publishedAt", o.optString("publishedAt", ""));

            JSONObject thumbs = new JSONObject();
            String thumbUrl = o.optString("thumbnailUrl", "");
            if (thumbUrl.length() > 0) {
                JSONObject medium = new JSONObject();
                medium.put("url", thumbUrl);
                thumbs.put("medium", medium);
            }
            snippet.put("thumbnails", thumbs);
            fakeVideoJson.put("snippet", snippet);

            long dur = o.optLong("durationSeconds", 0L);
            if (dur > 0) {
                JSONObject cd = new JSONObject();
                cd.put("duration", "PT" + dur + "S");
                fakeVideoJson.put("contentDetails", cd);
            }

            long views = o.optLong("viewCount", -1L);
            if (views >= 0) {
                JSONObject stats = new JSONObject();
                stats.put("viewCount", String.valueOf(views));
                fakeVideoJson.put("statistics", stats);
            }

            VideoItem item = VideoItem.fromVideoJson(fakeVideoJson);
            if (item != null) {
                String avatar = o.optString("channelAvatarUrl", null);
                if (avatar != null && avatar.length() > 0) {
                    item.setChannelAvatarUrl(avatar);
                }
            }
            return item;
        } catch (JSONException e) {
            return null;
        }
    }
}
