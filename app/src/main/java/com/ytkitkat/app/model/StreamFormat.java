package com.ytkitkat.app.model;

import com.ytkitkat.app.network.HttpClient;
import com.ytkitkat.app.util.TextFormat;

import org.json.JSONObject;

import java.io.Serializable;

/**
 * Một định dạng luồng (video hoặc audio) trích xuất từ InnerTube ({@code formats} / {@code adaptiveFormats}),
 * Invidious API hoặc Piped API.
 *
 * Lưu kèm chính xác {@code userAgent} của Client đã bóc tách ra URL để truyền vào
 * {@code MediaPlayer.setDataSource(context, uri, headers)} nhằm tránh lỗi {@code HTTP 403 Forbidden}
 * từ CDN {@code googlevideo.com}.
 */
public final class StreamFormat implements Serializable {

    private static final long serialVersionUID = 3L;

    public static final int ITAG_MP4_360P_PROGRESSIVE = 18;
    public static final int ITAG_MP4_720P_PROGRESSIVE = 22;

    public static final int ITAG_AVC_144P = 160;
    public static final int ITAG_AVC_240P = 133;
    public static final int ITAG_AVC_360P = 134;
    public static final int ITAG_AVC_480P = 135;
    public static final int ITAG_AVC_720P = 136;
    public static final int ITAG_AVC_720P60 = 298;
    public static final int ITAG_AVC_1080P = 137;
    public static final int ITAG_AVC_1080P60 = 299;

    public static final int ITAG_AAC_48K = 139;
    public static final int ITAG_AAC_128K = 140;
    public static final int ITAG_AAC_256K = 141;

    private final int itag;
    private final String url;
    private final String mimeType;
    private final String codecs;
    private final int width;
    private final int height;
    private final int fps;
    /** Nhãn chất lượng chuẩn hóa: "1080p60", "1080p", "720p60", "720p", "480p", "360p", "144p". */
    private final String qualityLabel;
    /** Nhãn hiển thị chuẩn: "MPEG-4 1080p60", "MPEG-4 720p60", "MPEG-4 360p", "M4A 128kbps"... */
    private final String displayLabel;
    private final long bitrate;
    private final long contentLength;
    private final int audioSampleRate;
    private final int audioChannels;
    private final boolean adaptive;
    private final String sourceClient;
    /** User-Agent khớp với Client đã tạo URL này (bắt buộc cho googlevideo.com). */
    private final String userAgent;

    /** Thông tin âm thanh gốc (tránh phát nhầm bản lồng tiếng AI tự động của YouTube). */
    private final String audioTrackId;
    private final String audioTrackDisplayName;
    private final boolean audioIsDefault;

    public StreamFormat(int itag,
                        String url,
                        String mimeType,
                        String codecs,
                        int width,
                        int height,
                        int fps,
                        String qualityLabel,
                        String displayLabel,
                        long bitrate,
                        long contentLength,
                        int audioSampleRate,
                        int audioChannels,
                        boolean adaptive,
                        String sourceClient,
                        String userAgent,
                        String audioTrackId,
                        String audioTrackDisplayName,
                        boolean audioIsDefault) {
        this.itag = itag;
        this.url = url;
        this.mimeType = mimeType != null ? mimeType : "";
        this.codecs = codecs != null ? codecs : "";
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.qualityLabel = qualityLabel != null ? qualityLabel : "";
        this.displayLabel = displayLabel != null ? displayLabel : "";
        this.bitrate = bitrate;
        this.contentLength = contentLength;
        this.audioSampleRate = audioSampleRate;
        this.audioChannels = audioChannels;
        this.adaptive = adaptive;
        this.sourceClient = sourceClient != null ? sourceClient : "";
        this.userAgent = (userAgent != null && userAgent.length() > 0)
                ? userAgent
                : HttpClient.DEFAULT_USER_AGENT;
        this.audioTrackId = audioTrackId != null ? audioTrackId : "";
        this.audioTrackDisplayName = audioTrackDisplayName != null ? audioTrackDisplayName : "";
        this.audioIsDefault = audioIsDefault;
    }

    /**
     * Phân tích một phần tử trong mảng {@code formats} hoặc {@code adaptiveFormats} của InnerTube.
     */
    public static StreamFormat fromInnerTubeJson(JSONObject obj,
                                                 boolean adaptive,
                                                 String sourceClient,
                                                 String clientUserAgent) {
        if (obj == null) {
            return null;
        }
        String url = obj.optString("url", "");
        if (url.length() == 0) {
            return null;
        }
        int itag = obj.optInt("itag", -1);
        String rawMime = obj.optString("mimeType", "");
        String cleanMime = extractCleanMime(rawMime);
        String codecs = extractCodecs(rawMime);

        int width = obj.optInt("width", 0);
        int height = obj.optInt("height", 0);
        int fps = obj.optInt("fps", 0);
        if (height <= 0) {
            height = inferHeightFromItag(itag);
        }
        if (fps <= 0) {
            fps = inferFpsFromItag(itag);
        }

        String rawQualityLabel = obj.optString("qualityLabel", "");
        String normalizedQuality = normalizeVideoQualityLabel(rawQualityLabel, height, fps, itag);
        long bitrate = obj.optLong("averageBitrate", obj.optLong("bitrate", 0L));
        long contentLength = TextFormat.parseLongSafe(obj.optString("contentLength", ""), -1L);
        int audioSampleRate = TextFormat.parseIntSafe(obj.optString("audioSampleRate", ""), 0);
        int audioChannels = obj.optInt("audioChannels", 0);

        String audioTrackId = "";
        String audioTrackName = "";
        boolean audioIsDefault = true;
        JSONObject audioTrack = obj.optJSONObject("audioTrack");
        if (audioTrack != null) {
            audioTrackId = audioTrack.optString("id", "");
            audioTrackName = audioTrack.optString("displayName", "");
            audioIsDefault = audioTrack.optBoolean("audioIsDefault", false);
        }

        String displayLabel = buildDisplayLabel(cleanMime, normalizedQuality, bitrate, audioTrackName);

        return new StreamFormat(
                itag,
                url,
                cleanMime,
                codecs,
                width,
                height,
                fps,
                normalizedQuality,
                displayLabel,
                bitrate,
                contentLength,
                audioSampleRate,
                audioChannels,
                adaptive,
                sourceClient,
                clientUserAgent,
                audioTrackId,
                audioTrackName,
                audioIsDefault
        );
    }

    /**
     * Phân tích một phần tử từ Invidious API ({@code /api/v1/videos/{videoId}} -> {@code formatStreams} / {@code adaptiveFormats}).
     */
    public static StreamFormat fromInvidiousJson(JSONObject obj, boolean adaptive) {
        if (obj == null) {
            return null;
        }
        String url = obj.optString("url", "");
        if (url.length() == 0) {
            return null;
        }
        int itag = TextFormat.parseIntSafe(obj.optString("itag", ""), obj.optInt("itag", -1));
        String rawMime = obj.optString("type", obj.optString("mimeType", ""));
        String cleanMime = extractCleanMime(rawMime);
        String codecs = extractCodecs(rawMime);

        int width = 0;
        int height = 0;
        String sizeStr = obj.optString("size", "");
        if (sizeStr.contains("x")) {
            String[] dims = sizeStr.split("x");
            if (dims.length == 2) {
                width = TextFormat.parseIntSafe(dims[0], 0);
                height = TextFormat.parseIntSafe(dims[1], 0);
            }
        }
        if (height <= 0) {
            height = inferHeightFromItag(itag);
        }
        int fps = obj.optInt("fps", inferFpsFromItag(itag));

        String rawQualityLabel = obj.optString("qualityLabel", obj.optString("quality", ""));
        String normalizedQuality = cleanMime.startsWith("audio/")
                ? ""
                : normalizeVideoQualityLabel(rawQualityLabel, height, fps, itag);
        long bitrate = TextFormat.parseLongSafe(obj.optString("bitrate", ""), obj.optLong("bitrate", 0L));
        long contentLength = TextFormat.parseLongSafe(obj.optString("clen", ""), -1L);

        String audioTrackId = "";
        String audioTrackName = "";
        boolean audioIsDefault = true;
        JSONObject audioTrack = obj.optJSONObject("audioTrack");
        if (audioTrack != null) {
            audioTrackId = audioTrack.optString("id", "");
            audioTrackName = audioTrack.optString("displayName", "");
            audioIsDefault = audioTrack.optBoolean("audioIsDefault", true);
        }

        String displayLabel = buildDisplayLabel(cleanMime, normalizedQuality, bitrate, audioTrackName);

        return new StreamFormat(
                itag,
                url,
                cleanMime,
                codecs,
                width,
                height,
                fps,
                normalizedQuality,
                displayLabel,
                bitrate,
                contentLength,
                0,
                0,
                adaptive,
                "INVIDIOUS",
                HttpClient.DEFAULT_USER_AGENT,
                audioTrackId,
                audioTrackName,
                audioIsDefault
        );
    }

    /**
     * Phân tích một phần tử từ Piped API ({@code /streams/{videoId}}).
     */
    public static StreamFormat fromPipedJson(JSONObject obj, boolean audioOnly, boolean videoOnly) {
        if (obj == null) {
            return null;
        }
        String url = obj.optString("url", "");
        if (url.length() == 0) {
            return null;
        }
        int itag = obj.optInt("itag", -1);
        String mimeType = obj.optString("mimeType", audioOnly ? "audio/mp4" : "video/mp4");
        String cleanMime = extractCleanMime(mimeType);
        String codec = obj.optString("codec", "");
        int width = obj.optInt("width", 0);
        int height = obj.optInt("height", inferHeightFromItag(itag));
        int fps = obj.optInt("fps", inferFpsFromItag(itag));
        String rawQuality = obj.optString("quality", "");
        String normalizedQuality = audioOnly
                ? ""
                : normalizeVideoQualityLabel(rawQuality, height, fps, itag);
        long bitrate = obj.optLong("bitrate", 0L);
        long contentLength = obj.optLong("contentLength", -1L);
        boolean adaptive = audioOnly || videoOnly;

        String audioTrackId = obj.optString("audioTrackId", "");
        String audioTrackName = obj.optString("audioTrackName", "");
        String audioTrackType = obj.optString("audioTrackType", "");
        boolean audioIsDefault = audioTrackId.length() == 0
                || audioTrackId.toLowerCase().contains("original")
                || "ORIGINAL".equalsIgnoreCase(audioTrackType);

        String displayLabel = buildDisplayLabel(cleanMime, normalizedQuality, bitrate, audioTrackName);

        return new StreamFormat(
                itag,
                url,
                cleanMime,
                codec,
                width,
                height,
                fps,
                normalizedQuality,
                displayLabel,
                bitrate,
                contentLength,
                0,
                0,
                adaptive,
                "PIPED",
                HttpClient.DEFAULT_USER_AGENT,
                audioTrackId,
                audioTrackName,
                audioIsDefault
        );
    }

    public static String normalizeVideoQualityLabel(String rawLabel, int height, int fps, int itag) {
        if (itag == ITAG_AVC_1080P60) {
            return "1080p60";
        }
        if (itag == ITAG_AVC_720P60) {
            return "720p60";
        }
        if (itag == ITAG_AVC_1080P) {
            return "1080p";
        }
        if (itag == ITAG_AVC_720P || itag == ITAG_MP4_720P_PROGRESSIVE) {
            return fps > 30 ? "720p60" : "720p";
        }
        if (itag == ITAG_AVC_480P) {
            return "480p";
        }
        if (itag == ITAG_AVC_360P || itag == ITAG_MP4_360P_PROGRESSIVE) {
            return "360p";
        }
        if (itag == ITAG_AVC_144P) {
            return "144p";
        }
        if (rawLabel != null && rawLabel.length() > 0) {
            String trimmed = rawLabel.trim();
            if (trimmed.startsWith("1080p") && (fps > 30 || trimmed.contains("60") || trimmed.contains("50"))) {
                return "1080p60";
            }
            if (trimmed.startsWith("720p") && (fps > 30 || trimmed.contains("60") || trimmed.contains("50"))) {
                return "720p60";
            }
            if (trimmed.startsWith("1080p")) {
                return "1080p";
            }
            if (trimmed.startsWith("720p")) {
                return "720p";
            }
            if (trimmed.startsWith("480p")) {
                return "480p";
            }
            if (trimmed.startsWith("360p")) {
                return "360p";
            }
            if (trimmed.startsWith("240p")) {
                return "240p";
            }
            if (trimmed.startsWith("144p")) {
                return "144p";
            }
            return trimmed;
        }
        if (height > 0) {
            if (fps > 30 && (height == 720 || height == 1080)) {
                return height + "p60";
            }
            return height + "p";
        }
        return "";
    }

    public static String buildDisplayLabel(String cleanMime,
                                           String qualityLabel,
                                           long bitrate,
                                           String audioTrackName) {
        if (cleanMime != null && cleanMime.startsWith("audio/")) {
            long kbps = bitrate > 0 ? Math.round(bitrate / 1000.0) : 128;
            String base = "M4A " + kbps + "kbps";
            if (audioTrackName != null && audioTrackName.length() > 0) {
                return base + " (" + audioTrackName + ")";
            }
            return base;
        }
        if (qualityLabel != null && qualityLabel.length() > 0) {
            return "MPEG-4 " + qualityLabel;
        }
        return "MPEG-4";
    }

    private static int inferHeightFromItag(int itag) {
        switch (itag) {
            case ITAG_AVC_1080P:
            case ITAG_AVC_1080P60:
                return 1080;
            case ITAG_AVC_720P:
            case ITAG_AVC_720P60:
            case ITAG_MP4_720P_PROGRESSIVE:
                return 720;
            case ITAG_AVC_480P:
                return 480;
            case ITAG_AVC_360P:
            case ITAG_MP4_360P_PROGRESSIVE:
                return 360;
            case ITAG_AVC_240P:
                return 240;
            case ITAG_AVC_144P:
                return 144;
            default:
                return 0;
        }
    }

    private static int inferFpsFromItag(int itag) {
        if (itag == ITAG_AVC_1080P60 || itag == ITAG_AVC_720P60) {
            return 60;
        }
        return 30;
    }

    private static String extractCleanMime(String rawMime) {
        if (rawMime == null) {
            return "";
        }
        int semi = rawMime.indexOf(';');
        return (semi >= 0 ? rawMime.substring(0, semi) : rawMime).trim().toLowerCase();
    }

    private static String extractCodecs(String rawMime) {
        if (rawMime == null) {
            return "";
        }
        int idx = rawMime.indexOf("codecs=");
        if (idx < 0) {
            return "";
        }
        String sub = rawMime.substring(idx + 7).trim();
        if (sub.startsWith("\"")) {
            int endQuote = sub.indexOf('"', 1);
            if (endQuote > 1) {
                return sub.substring(1, endQuote);
            }
        }
        int semi = sub.indexOf(';');
        return (semi >= 0 ? sub.substring(0, semi) : sub).replace("\"", "").trim();
    }

    public boolean isVideo() {
        return mimeType.startsWith("video/");
    }

    public boolean isAudio() {
        return mimeType.startsWith("audio/");
    }

    public boolean is60Fps() {
        return fps > 30
                || itag == ITAG_AVC_720P60
                || itag == ITAG_AVC_1080P60
                || qualityLabel.endsWith("p60");
    }

    public boolean isAvcMp4Video() {
        if (!mimeType.contains("video/mp4")) {
            return false;
        }
        String lowerCodec = codecs.toLowerCase();
        if (lowerCodec.contains("vp9") || lowerCodec.contains("vp09") || lowerCodec.contains("av01")) {
            return false;
        }
        if (lowerCodec.contains("avc1") || lowerCodec.contains("h264")) {
            return true;
        }
        return itag == ITAG_MP4_360P_PROGRESSIVE
                || itag == ITAG_MP4_720P_PROGRESSIVE
                || itag == ITAG_AVC_144P
                || itag == ITAG_AVC_240P
                || itag == ITAG_AVC_360P
                || itag == ITAG_AVC_480P
                || itag == ITAG_AVC_720P
                || itag == ITAG_AVC_720P60
                || itag == ITAG_AVC_1080P
                || itag == ITAG_AVC_1080P60;
    }

    public boolean isAacMp4Audio() {
        if (!mimeType.contains("audio/mp4")) {
            return false;
        }
        String lowerCodec = codecs.toLowerCase();
        return lowerCodec.length() == 0
                || lowerCodec.contains("mp4a")
                || lowerCodec.contains("aac")
                || itag == ITAG_AAC_128K
                || itag == ITAG_AAC_48K
                || itag == ITAG_AAC_256K;
    }

    public boolean isOriginalAudioTrack() {
        if (audioIsDefault) {
            return true;
        }
        String lowerId = audioTrackId.toLowerCase();
        if (lowerId.contains("original") || lowerId.endsWith(".4")) {
            return true;
        }
        String lowerName = audioTrackDisplayName.toLowerCase();
        return lowerName.contains("original") || lowerName.contains("gốc");
    }

    public int getAudioPriorityScore() {
        int score = 0;
        if (audioIsDefault) {
            score += 1000;
        }
        String lowerId = audioTrackId.toLowerCase();
        String lowerName = audioTrackDisplayName.toLowerCase();
        if (lowerId.contains("original") || lowerName.contains("original") || lowerName.contains("gốc")) {
            score += 800;
        }
        if (audioTrackId.length() == 0 && audioTrackDisplayName.length() == 0) {
            score += 600;
        }
        if (itag == ITAG_AAC_128K) {
            score += 200;
        }
        return score;
    }

    public boolean isProgressiveWithAudio() {
        return !adaptive && isVideo() && (itag == ITAG_MP4_360P_PROGRESSIVE
                || itag == ITAG_MP4_720P_PROGRESSIVE
                || codecs.contains(","));
    }

    public int getItag() {
        return itag;
    }

    public String getUrl() {
        return url;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getCodecs() {
        return codecs;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFps() {
        return fps;
    }

    public String getQualityLabel() {
        return qualityLabel;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public long getBitrate() {
        return bitrate;
    }

    public long getContentLength() {
        return contentLength;
    }

    public int getAudioSampleRate() {
        return audioSampleRate;
    }

    public int getAudioChannels() {
        return audioChannels;
    }

    public boolean isAdaptive() {
        return adaptive;
    }

    public String getSourceClient() {
        return sourceClient;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getAudioTrackId() {
        return audioTrackId;
    }

    public String getAudioTrackDisplayName() {
        return audioTrackDisplayName;
    }

    public boolean isAudioIsDefault() {
        return audioIsDefault;
    }

    public String toTechnicalSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(displayLabel).append(" (itag=").append(itag).append(')');
        sb.append(" [").append(mimeType);
        if (codecs.length() > 0) {
            sb.append("; ").append(codecs);
        }
        sb.append(']');
        if (bitrate > 0) {
            sb.append(' ').append(TextFormat.formatBitrate(bitrate));
        }
        if (contentLength > 0) {
            sb.append(" (").append(TextFormat.formatFileSize(contentLength)).append(')');
        }
        if (isAudio() && (audioTrackId.length() > 0 || audioTrackDisplayName.length() > 0)) {
            sb.append(" {track=").append(audioTrackDisplayName.length() > 0 ? audioTrackDisplayName : audioTrackId)
                    .append(", default=").append(audioIsDefault).append('}');
        }
        if (sourceClient.length() > 0) {
            sb.append(" <").append(sourceClient).append('>');
        }
        return sb.toString();
    }
}
