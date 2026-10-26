package com.ytkitkat.app.model;

import com.ytkitkat.app.util.TextFormat;

import org.json.JSONObject;

import java.io.Serializable;

/**
 * Bình luận cấp 1 từ commentThreads.list (textFormat=plainText).
 */
public final class CommentItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String authorName;
    private String authorAvatarUrl;
    private String authorChannelId;
    private String text;
    private long likeCount;
    private long publishedAtMillis;
    private boolean edited;
    private int replyCount;

    public static CommentItem fromThreadJson(JSONObject thread) {
        if (thread == null) {
            return null;
        }
        JSONObject threadSnippet = thread.optJSONObject("snippet");
        if (threadSnippet == null) {
            return null;
        }
        JSONObject topLevel = threadSnippet.optJSONObject("topLevelComment");
        JSONObject snippet = topLevel != null ? topLevel.optJSONObject("snippet") : null;
        if (snippet == null) {
            return null;
        }

        CommentItem comment = new CommentItem();
        comment.id = thread.optString("id", "");
        comment.authorName = snippet.optString("authorDisplayName", "");
        comment.authorAvatarUrl = snippet.optString("authorProfileImageUrl", null);
        JSONObject authorChannel = snippet.optJSONObject("authorChannelId");
        comment.authorChannelId = authorChannel != null ? authorChannel.optString("value", null) : null;

        String original = snippet.optString("textOriginal", "");
        comment.text = original.length() > 0
                ? original
                : VideoItem.unescapeHtml(snippet.optString("textDisplay", ""));

        comment.likeCount = snippet.optLong("likeCount", 0);
        String published = snippet.optString("publishedAt", "");
        String updated = snippet.optString("updatedAt", "");
        comment.publishedAtMillis = TextFormat.parseIsoDate(published);
        comment.edited = updated.length() > 0 && !updated.equals(published);
        comment.replyCount = threadSnippet.optInt("totalReplyCount", 0);
        return comment;
    }

    public String getId() {
        return id;
    }

    public String getAuthorName() {
        return authorName;
    }

    public String getAuthorAvatarUrl() {
        return authorAvatarUrl;
    }

    public String getAuthorChannelId() {
        return authorChannelId;
    }

    public String getText() {
        return text;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getPublishedAtMillis() {
        return publishedAtMillis;
    }

    public boolean isEdited() {
        return edited;
    }

    public int getReplyCount() {
        return replyCount;
    }

    /** "@tacgia · 2 ngày trước (đã chỉnh sửa)" */
    public String getMetaLine() {
        StringBuilder sb = new StringBuilder();
        sb.append(authorName != null ? authorName : "");
        String time = TextFormat.formatRelativeTime(publishedAtMillis);
        if (time.length() > 0) {
            sb.append(" · ").append(time);
        }
        if (edited) {
            sb.append(" (đã chỉnh sửa)");
        }
        return sb.toString();
    }
}
