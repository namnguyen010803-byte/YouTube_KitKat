package com.ytkitkat.app.model;

import org.json.JSONObject;

import java.io.Serializable;

/**
 * Danh mục video (videoCategories.list) - dùng cho hàng Chip lọc ở Home.
 */
public final class VideoCategory implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String title;
    private final boolean assignable;

    public VideoCategory(String id, String title, boolean assignable) {
        this.id = id;
        this.title = title;
        this.assignable = assignable;
    }

    public static VideoCategory fromJson(JSONObject item) {
        if (item == null) {
            return null;
        }
        String id = item.optString("id", "");
        JSONObject snippet = item.optJSONObject("snippet");
        if (id.length() == 0 || snippet == null) {
            return null;
        }
        return new VideoCategory(id, snippet.optString("title", ""), snippet.optBoolean("assignable", false));
    }

    /** Chip "Tất cả" (không lọc danh mục). */
    public static VideoCategory all() {
        return new VideoCategory(null, "Tất cả", true);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public boolean isAssignable() {
        return assignable;
    }

    public boolean isAll() {
        return id == null;
    }
}
