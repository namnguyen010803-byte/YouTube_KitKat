package com.ytkitkat.app.model;

import com.ytkitkat.app.util.TextFormat;

import org.json.JSONObject;

import java.io.Serializable;

/**
 * Kênh từ channels.list (part=snippet,statistics).
 */
public final class ChannelInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String title;
    private String customUrl;
    private String avatarUrl;
    private long subscriberCount = -1;
    private boolean subscriberCountHidden;
    private long videoCount = -1;

    public ChannelInfo() {
    }

    public ChannelInfo(String id, String title, String avatarUrl) {
        this.id = id != null ? id : "";
        this.title = title != null ? title : "";
        this.avatarUrl = avatarUrl != null ? avatarUrl : "";
    }

    public static ChannelInfo fromJson(JSONObject item) {
        if (item == null) {
            return null;
        }
        ChannelInfo channel = new ChannelInfo();
        channel.id = item.optString("id", "");
        if (channel.id.length() == 0) {
            return null;
        }

        JSONObject snippet = item.optJSONObject("snippet");
        if (snippet != null) {
            channel.title = snippet.optString("title", "");
            channel.customUrl = snippet.optString("customUrl", "");
            // "default" = 88x88: đủ cho avatar 36dp, tiết kiệm RAM trên máy cũ.
            channel.avatarUrl = VideoItem.pickThumbnail(snippet.optJSONObject("thumbnails"),
                    "default", "medium", "high");
        }

        JSONObject statistics = item.optJSONObject("statistics");
        if (statistics != null) {
            channel.subscriberCountHidden = statistics.optBoolean("hiddenSubscriberCount", false);
            channel.subscriberCount = channel.subscriberCountHidden
                    ? -1
                    : TextFormat.parseLongSafe(statistics.optString("subscriberCount", ""), -1);
            channel.videoCount = TextFormat.parseLongSafe(statistics.optString("videoCount", ""), -1);
        }
        return channel;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title != null ? title : "";
    }

    public String getCustomUrl() {
        return customUrl;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public long getSubscriberCount() {
        return subscriberCount;
    }

    public boolean isSubscriberCountHidden() {
        return subscriberCountHidden;
    }

    public long getVideoCount() {
        return videoCount;
    }

    public String getFormattedSubscribers() {
        return TextFormat.formatSubscribers(subscriberCount);
    }
}
