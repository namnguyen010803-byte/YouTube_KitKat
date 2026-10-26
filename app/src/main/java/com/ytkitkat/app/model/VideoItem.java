package com.ytkitkat.app.model;

import android.text.Html;

import com.ytkitkat.app.util.TextFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Serializable;

/**
 * Mô hình dữ liệu Video thống nhất cho cả 3 nguồn:
 * 1. YouTube InnerTube API ({@code videoRenderer} từ {@code /youtubei/v1/search} & {@code /youtubei/v1/browse}).
 * 2. YouTube Data API v3 ({@code videos.list} hoặc {@code search.list}).
 * 3. Piped API dự phòng cuối cùng.
 */
public final class VideoItem implements Serializable {

    private static final long serialVersionUID = 2L;

    private String id;
    private String title;
    private String description;
    private String channelId;
    private String channelTitle;
    private String publishedAt;
    private long publishedAtMillis;
    /** Thumbnail 16:9 cỡ vừa (320x180) - nhẹ cho ListView trên máy cũ. */
    private String thumbnailUrl;
    /** Thumbnail lớn (standard/high/maxres) - cho màn hình Player. */
    private String thumbnailHighUrl;
    private long durationSeconds;
    private String durationText;
    private long viewCount = -1;
    private String viewCountText;
    private long likeCount = -1;
    private long commentCount = -1;
    /** "none" | "live" | "upcoming" */
    private String liveBroadcastContent = "none";
    private String categoryId;

    // Bổ sung từ channels.list hoặc InnerTube channelThumbnailSupportedRenderers
    private String channelAvatarUrl;
    private long channelSubscriberCount = -1;

    public VideoItem() {
    }

    public VideoItem(String id) {
        this.id = id;
    }

    // ------------------------------------------------------------------
    // 1. Parse từ InnerTube videoRenderer (Nguồn chính không cần API Key)
    // ------------------------------------------------------------------

    /**
     * Phân tích một đối tượng {@code videoRenderer} từ phản hồi InnerTube
     * ({@code /youtubei/v1/search} hoặc {@code /youtubei/v1/browse}).
     */
    public static VideoItem fromInnerTubeVideoRenderer(JSONObject vr) {
        if (vr == null) {
            return null;
        }
        String videoId = vr.optString("videoId", "").trim();
        if (videoId.length() == 0) {
            return null;
        }

        VideoItem video = new VideoItem(videoId);

        // Tiêu đề: đọc từ title.runs[0..n].text hoặc title.simpleText
        video.title = extractInnerTubeText(vr.optJSONObject("title"));

        // Tên kênh: đọc từ ownerText.runs[0].text hoặc longBylineText.runs[0].text hoặc shortBylineText
        JSONObject ownerTextObj = vr.optJSONObject("ownerText");
        if (ownerTextObj == null) {
            ownerTextObj = vr.optJSONObject("longBylineText");
        }
        if (ownerTextObj == null) {
            ownerTextObj = vr.optJSONObject("shortBylineText");
        }
        video.channelTitle = extractInnerTubeText(ownerTextObj);
        video.channelId = extractInnerTubeBrowseId(ownerTextObj);

        // Avatar kênh: channelThumbnailSupportedRenderers.channelThumbnailWithLinkRenderer.thumbnail.thumbnails[0].url
        video.channelAvatarUrl = extractInnerTubeChannelAvatar(vr);

        // Thời lượng: lengthText.simpleText (hoặc runs[0].text) hoặc phát hiện LIVE
        String lengthStr = extractInnerTubeText(vr.optJSONObject("lengthText"));
        if (lengthStr.length() > 0) {
            video.durationText = lengthStr;
            video.durationSeconds = parseColonDurationSeconds(lengthStr);
        }
        if (isInnerTubeLive(vr, lengthStr)) {
            video.liveBroadcastContent = "live";
        }

        // Lượt xem: đọc từ shortViewCountText.simpleText hoặc viewCountText.simpleText
        String viewStr = extractInnerTubeText(vr.optJSONObject("viewCountText"));
        String shortViewStr = extractInnerTubeText(vr.optJSONObject("shortViewCountText"));
        String chosenViewStr = shortViewStr.length() > 0 ? shortViewStr : viewStr;
        if (chosenViewStr.length() > 0) {
            video.viewCountText = chosenViewStr;
            long parsedViews = parseApproxViewCount(viewStr.length() > 0 ? viewStr : shortViewStr);
            if (parsedViews >= 0) {
                video.viewCount = parsedViews;
            }
        }

        // Thời gian đăng: đọc từ publishedTimeText.simpleText
        String publishedStr = extractInnerTubeText(vr.optJSONObject("publishedTimeText"));
        if (publishedStr.length() > 0) {
            video.publishedAt = publishedStr;
        }

        // Mô tả ngắn (nếu có)
        video.description = extractInnerTubeDescription(vr);

        // Thumbnail 16:9 320x180 chuẩn nhẹ RAM cho KitKat
        video.thumbnailUrl = "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
        video.thumbnailHighUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        return video;
    }

    /**
     * Đọc chuỗi văn bản từ cấu trúc {@code {"simpleText": "..."}} hoặc {@code {"runs": [{"text": "..."}]}} của InnerTube.
     */
    public static String extractInnerTubeText(JSONObject textObject) {
        if (textObject == null) {
            return "";
        }
        String simple = textObject.optString("simpleText", "");
        if (simple != null && simple.length() > 0) {
            return simple.trim();
        }
        JSONArray runs = textObject.optJSONArray("runs");
        if (runs != null && runs.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null) {
                    sb.append(run.optString("text", ""));
                }
            }
            return sb.toString().trim();
        }
        return "";
    }

    private static String extractInnerTubeBrowseId(JSONObject bylineObj) {
        if (bylineObj == null) {
            return "";
        }
        JSONArray runs = bylineObj.optJSONArray("runs");
        if (runs != null && runs.length() > 0) {
            JSONObject firstRun = runs.optJSONObject(0);
            if (firstRun != null) {
                JSONObject nav = firstRun.optJSONObject("navigationEndpoint");
                if (nav != null) {
                    JSONObject browse = nav.optJSONObject("browseEndpoint");
                    if (browse != null) {
                        return browse.optString("browseId", "");
                    }
                }
            }
        }
        return "";
    }

    private static String extractInnerTubeChannelAvatar(JSONObject vr) {
        JSONObject supported = vr.optJSONObject("channelThumbnailSupportedRenderers");
        if (supported != null) {
            JSONObject withLink = supported.optJSONObject("channelThumbnailWithLinkRenderer");
            if (withLink != null) {
                JSONObject thumbContainer = withLink.optJSONObject("thumbnail");
                if (thumbContainer != null) {
                    JSONArray thumbs = thumbContainer.optJSONArray("thumbnails");
                    if (thumbs != null && thumbs.length() > 0) {
                        JSONObject first = thumbs.optJSONObject(0);
                        if (first != null) {
                            String url = first.optString("url", "").trim();
                            if (url.startsWith("//")) {
                                return "https:" + url;
                            }
                            if (url.length() > 0) {
                                return url;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String extractInnerTubeDescription(JSONObject vr) {
        JSONObject descSnippet = vr.optJSONObject("descriptionSnippet");
        if (descSnippet != null) {
            String text = extractInnerTubeText(descSnippet);
            if (text.length() > 0) {
                return text;
            }
        }
        JSONArray detailed = vr.optJSONArray("detailedMetadataSnippets");
        if (detailed != null && detailed.length() > 0) {
            JSONObject first = detailed.optJSONObject(0);
            if (first != null) {
                String text = extractInnerTubeText(first.optJSONObject("snippetText"));
                if (text.length() > 0) {
                    return text;
                }
            }
        }
        return "";
    }

    private static boolean isInnerTubeLive(JSONObject vr, String lengthStr) {
        if ("LIVE".equalsIgnoreCase(lengthStr) || "TRỰC TIẾP".equalsIgnoreCase(lengthStr)) {
            return true;
        }
        JSONArray badges = vr.optJSONArray("badges");
        if (badges != null) {
            for (int i = 0; i < badges.length(); i++) {
                JSONObject badgeObj = badges.optJSONObject(i);
                if (badgeObj != null) {
                    JSONObject renderer = badgeObj.optJSONObject("metadataBadgeRenderer");
                    if (renderer != null) {
                        String style = renderer.optString("style", "");
                        if (style.contains("LIVE")) {
                            return true;
                        }
                    }
                }
            }
        }
        JSONArray overlays = vr.optJSONArray("thumbnailOverlays");
        if (overlays != null) {
            for (int i = 0; i < overlays.length(); i++) {
                JSONObject ov = overlays.optJSONObject(i);
                if (ov != null) {
                    JSONObject timeStatus = ov.optJSONObject("thumbnailOverlayTimeStatusRenderer");
                    if (timeStatus != null && "LIVE".equalsIgnoreCase(timeStatus.optString("style", ""))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Chuyển chuỗi thời lượng dạng "MM:SS" hoặc "HH:MM:SS" thành tổng số giây. */
    public static long parseColonDurationSeconds(String text) {
        if (text == null || text.length() == 0) {
            return 0L;
        }
        String[] parts = text.trim().split(":");
        try {
            if (parts.length == 2) {
                long m = Long.parseLong(parts[0].trim());
                long s = Long.parseLong(parts[1].trim());
                return m * 60L + s;
            } else if (parts.length == 3) {
                long h = Long.parseLong(parts[0].trim());
                long m = Long.parseLong(parts[1].trim());
                long s = Long.parseLong(parts[2].trim());
                return h * 3600L + m * 60L + s;
            }
        } catch (NumberFormatException ignored) {
        }
        return 0L;
    }

    /**
     * Ước lượng số lượt xem dạng số nguyên từ chuỗi hiển thị của InnerTube
     * (ví dụ: "1.234.567 lượt xem", "1,2 Tr lượt xem", "45 N lượt xem", "1.5M views").
     */
    private static long parseApproxViewCount(String raw) {
        if (raw == null || raw.length() == 0) {
            return -1L;
        }
        String lower = raw.toLowerCase();
        try {
            double multiplier = 1.0;
            if (lower.contains("tỷ") || lower.contains("b ")) {
                multiplier = 1000000000.0;
            } else if (lower.contains("tr") || lower.contains("m ") || lower.contains("million")) {
                multiplier = 1000000.0;
            } else if (lower.contains(" n ") || lower.endsWith(" n") || lower.contains("k ") || lower.contains("nghìn")) {
                multiplier = 1000.0;
            }

            StringBuilder numSb = new StringBuilder();
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == ',') {
                    numSb.append(c);
                } else if (numSb.length() > 0) {
                    break;
                }
            }
            if (numSb.length() == 0) {
                return -1L;
            }
            String numStr = numSb.toString();
            if (multiplier > 1.0) {
                numStr = numStr.replace(',', '.');
                return (long) (Double.parseDouble(numStr) * multiplier);
            } else {
                String digitsOnly = numStr.replace(".", "").replace(",", "");
                return Long.parseLong(digitsOnly);
            }
        } catch (Exception ignored) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------
    // 2. Parse từ Data API v3 & Piped
    // ------------------------------------------------------------------

    /** Từ phần tử của videos.list (id là chuỗi). */
    public static VideoItem fromVideoJson(JSONObject item) {
        if (item == null) {
            return null;
        }
        VideoItem video = new VideoItem(item.optString("id", ""));
        video.applySnippet(item.optJSONObject("snippet"), false);

        JSONObject contentDetails = item.optJSONObject("contentDetails");
        if (contentDetails != null) {
            video.durationSeconds = TextFormat.parseIsoDuration(contentDetails.optString("duration", ""));
        }

        JSONObject statistics = item.optJSONObject("statistics");
        if (statistics != null) {
            video.viewCount = TextFormat.parseLongSafe(statistics.optString("viewCount", ""), -1);
            video.likeCount = TextFormat.parseLongSafe(statistics.optString("likeCount", ""), -1);
            video.commentCount = TextFormat.parseLongSafe(statistics.optString("commentCount", ""), -1);
        }
        return video.id.length() > 0 ? video : null;
    }

    /** Từ phần tử của search.list (id là object {kind, videoId}). Tiêu đề bị HTML-escape. */
    public static VideoItem fromSearchJson(JSONObject item) {
        if (item == null) {
            return null;
        }
        JSONObject idObject = item.optJSONObject("id");
        String videoId = idObject != null ? idObject.optString("videoId", "") : "";
        if (videoId.length() == 0) {
            return null;
        }
        VideoItem video = new VideoItem(videoId);
        video.applySnippet(item.optJSONObject("snippet"), true);
        return video;
    }

    /**
     * Phân tích một phần tử video từ Piped API (/trending hoặc /search).
     */
    public static VideoItem fromPipedStreamItemJson(JSONObject item) {
        if (item == null) {
            return null;
        }
        String type = item.optString("type", "stream");
        if (!"stream".equalsIgnoreCase(type)) {
            return null;
        }
        String rawUrl = item.optString("url", "");
        String videoId = "";
        int idx = rawUrl.indexOf("v=");
        if (idx >= 0) {
            videoId = rawUrl.substring(idx + 2);
            int amp = videoId.indexOf('&');
            if (amp >= 0) {
                videoId = videoId.substring(0, amp);
            }
        } else if (rawUrl.startsWith("/watch/")) {
            videoId = rawUrl.substring("/watch/".length());
        }
        if (videoId.length() == 0) {
            return null;
        }

        VideoItem v = new VideoItem(videoId);
        v.title = item.optString("title", "");
        v.description = item.optString("shortDescription", "");
        v.channelTitle = item.optString("uploaderName", item.optString("uploader", ""));
        String uploaderUrl = item.optString("uploaderUrl", "");
        if (uploaderUrl.startsWith("/channel/")) {
            v.channelId = uploaderUrl.substring("/channel/".length());
        }
        v.channelAvatarUrl = item.optString("uploaderAvatar", null);
        v.thumbnailUrl = item.optString("thumbnail",
                "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg");
        v.thumbnailHighUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        long dur = item.optLong("duration", 0L);
        v.durationSeconds = dur > 0 ? dur : 0L;
        v.viewCount = item.optLong("views", -1L);
        long uploadedMillis = item.optLong("uploaded", 0L);
        if (uploadedMillis > 0) {
            v.publishedAtMillis = uploadedMillis;
        }
        v.publishedAt = item.optString("uploadedDate", "");
        if (dur < 0 || item.optBoolean("isShort", false) && dur == 0) {
            v.liveBroadcastContent = "live";
        }
        return v;
    }

    private void applySnippet(JSONObject snippet, boolean htmlEscaped) {
        if (snippet == null) {
            return;
        }
        String rawTitle = snippet.optString("title", "");
        String rawChannel = snippet.optString("channelTitle", "");
        String rawDescription = snippet.optString("description", "");
        if (htmlEscaped) {
            rawTitle = unescapeHtml(rawTitle);
            rawChannel = unescapeHtml(rawChannel);
            rawDescription = unescapeHtml(rawDescription);
        }
        title = rawTitle;
        channelTitle = rawChannel;
        description = rawDescription;
        channelId = snippet.optString("channelId", "");
        publishedAt = snippet.optString("publishedAt", "");
        publishedAtMillis = TextFormat.parseIsoDate(publishedAt);
        liveBroadcastContent = snippet.optString("liveBroadcastContent", "none");
        categoryId = snippet.optString("categoryId", null);

        JSONObject thumbnails = snippet.optJSONObject("thumbnails");
        thumbnailUrl = pickThumbnail(thumbnails, "medium", "high", "standard", "default");
        thumbnailHighUrl = pickThumbnail(thumbnails, "standard", "high", "maxres", "medium", "default");
        if (thumbnailUrl == null && id != null) {
            thumbnailUrl = "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
        }
        if (thumbnailHighUrl == null && id != null) {
            thumbnailHighUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        }
    }

    /** Chép các trường chi tiết (thời lượng, thống kê) từ kết quả videos.list. */
    public void mergeDetails(VideoItem detailed) {
        if (detailed == null) {
            return;
        }
        if (detailed.title != null && detailed.title.length() > 0) {
            title = detailed.title;
        }
        if (detailed.description != null && detailed.description.length() > 0) {
            description = detailed.description;
        }
        if (detailed.thumbnailHighUrl != null) {
            thumbnailHighUrl = detailed.thumbnailHighUrl;
        }
        durationSeconds = detailed.durationSeconds;
        viewCount = detailed.viewCount;
        likeCount = detailed.likeCount;
        commentCount = detailed.commentCount;
        liveBroadcastContent = detailed.liveBroadcastContent;
        if (detailed.categoryId != null) {
            categoryId = detailed.categoryId;
        }
    }

    static String pickThumbnail(JSONObject thumbnails, String... preferred) {
        if (thumbnails == null) {
            return null;
        }
        for (String key : preferred) {
            JSONObject thumb = thumbnails.optJSONObject(key);
            if (thumb != null) {
                String url = thumb.optString("url", "");
                if (url.length() > 0) {
                    return url;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    static String unescapeHtml(String text) {
        if (text == null || text.indexOf('&') < 0) {
            return text;
        }
        return Html.fromHtml(text).toString();
    }

    // ------------------------------------------------------------------
    // Hiển thị
    // ------------------------------------------------------------------

    public boolean isLive() {
        return "live".equals(liveBroadcastContent);
    }

    public boolean isUpcoming() {
        return "upcoming".equals(liveBroadcastContent);
    }

    public String getFormattedDuration() {
        if (durationSeconds > 0) {
            return TextFormat.formatDuration(durationSeconds);
        }
        return durationText != null ? durationText : "";
    }

    /** "Tên kênh · 1,2 Tr lượt xem · 3 ngày trước" */
    public String getMetaLine() {
        StringBuilder sb = new StringBuilder();
        if (channelTitle != null && channelTitle.length() > 0) {
            sb.append(channelTitle);
        }
        String views = TextFormat.formatViews(viewCount);
        if (views.length() == 0 && viewCountText != null && viewCountText.length() > 0) {
            views = viewCountText;
        }
        if (views.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(views);
        }
        String time;
        if (isLive()) {
            time = "Đang phát trực tiếp";
        } else if (publishedAtMillis > 0) {
            time = TextFormat.formatRelativeTime(publishedAtMillis);
        } else if (publishedAt != null && publishedAt.length() > 0) {
            time = publishedAt;
        } else {
            time = "";
        }
        if (time.length() > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(time);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Getters / Setters
    // ------------------------------------------------------------------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title != null ? title : "";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description != null ? description : "";
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelTitle() {
        return channelTitle != null ? channelTitle : "";
    }

    public void setChannelTitle(String channelTitle) {
        this.channelTitle = channelTitle;
    }

    public String getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(String publishedAt) {
        this.publishedAt = publishedAt;
    }

    public long getPublishedAtMillis() {
        return publishedAtMillis;
    }

    public void setPublishedAtMillis(long publishedAtMillis) {
        this.publishedAtMillis = publishedAtMillis;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getThumbnailHighUrl() {
        return thumbnailHighUrl;
    }

    public void setThumbnailHighUrl(String thumbnailHighUrl) {
        this.thumbnailHighUrl = thumbnailHighUrl;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public long getViewCount() {
        return viewCount;
    }

    public long getLikeCount() {
        return likeCount;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public String getLiveBroadcastContent() {
        return liveBroadcastContent;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getChannelAvatarUrl() {
        return channelAvatarUrl;
    }

    public void setChannelAvatarUrl(String channelAvatarUrl) {
        this.channelAvatarUrl = channelAvatarUrl;
    }

    public long getChannelSubscriberCount() {
        return channelSubscriberCount;
    }

    public void setChannelSubscriberCount(long channelSubscriberCount) {
        this.channelSubscriberCount = channelSubscriberCount;
    }

    @Override
    public String toString() {
        return "VideoItem{" + id + ", " + title + "}";
    }
}
