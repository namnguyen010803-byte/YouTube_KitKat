package com.ytkitkat.app.model;

import com.ytkitkat.app.network.HttpClient;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Toàn bộ thông tin luồng đã bóc tách cho một video từ InnerTube
 * (IOS / ANDROID_VR / ANDROID_TESTSUITE / ANDROID) hoặc Invidious / Piped.
 */
public final class StreamInfo implements Serializable {

    private static final long serialVersionUID = 3L;

    public static final String QUALITY_1080P60 = "1080p60";
    public static final String QUALITY_1080P = "1080p";
    public static final String QUALITY_720P60 = "720p60";
    public static final String QUALITY_720P = "720p";
    public static final String QUALITY_480P = "480p";
    public static final String QUALITY_360P = "360p";
    public static final String QUALITY_144P = "144p";
    public static final String QUALITY_AUDIO_ONLY = "Audio Only";

    public static final String[] ALL_QUALITIES_ORDERED = new String[] {
            QUALITY_1080P60,
            QUALITY_1080P,
            QUALITY_720P60,
            QUALITY_720P,
            QUALITY_480P,
            QUALITY_360P,
            QUALITY_144P,
            QUALITY_AUDIO_ONLY
    };

    private final String videoId;
    private final String title;
    private final String author;
    private final String channelId;
    private final long durationSeconds;
    private final long viewCount;
    private final boolean liveContent;
    private final String thumbnailUrl;

    /** Nguồn 1a: Luồng HLS master .m3u8 từ client IOS (hoặc Invidious/Piped). */
    private final String hlsManifestUrl;
    /** User-Agent tương ứng với luồng HLS (ví dụ User-Agent của iOS). */
    private final String hlsUserAgent;

    /**
     * Nguồn 1b: Các URL .m3u8 con đã bóc từ thẻ #EXT-X-STREAM-INF của master playlist
     * (ánh xạ "1080p60" -> url, "720p60" -> url, "720p" -> url, "480p" -> url, "360p" -> url...).
     */
    private final Map<String, String> hlsVariantUrls;

    /** Nguồn 2: Các luồng Video H.264 adaptive đã lọc (itag 299/137/298/136/135/134/160), xếp từ cao xuống thấp. */
    private final List<StreamFormat> adaptiveAvcVideos;

    /** Nguồn 2 & 3: Các luồng Audio AAC adaptive (itag 140, đã ưu tiên Original Audio lên đầu). */
    private final List<StreamFormat> adaptiveAacAudios;

    /** Nguồn 3: Các luồng Progressive MP4 có cả hình + tiếng (itag 18 - 360p, itag 22 - 720p). */
    private final List<StreamFormat> progressiveFormats;

    private final long resolvedAtMillis;
    private final long expiresAtMillis;
    private final String resolverSummary;

    public StreamInfo(String videoId,
                      String title,
                      String author,
                      String channelId,
                      long durationSeconds,
                      long viewCount,
                      boolean liveContent,
                      String thumbnailUrl,
                      String hlsManifestUrl,
                      String hlsUserAgent,
                      Map<String, String> hlsVariantUrls,
                      List<StreamFormat> adaptiveAvcVideos,
                      List<StreamFormat> adaptiveAacAudios,
                      List<StreamFormat> progressiveFormats,
                      long resolvedAtMillis,
                      long expiresAtMillis,
                      String resolverSummary) {
        this.videoId = videoId != null ? videoId : "";
        this.title = title != null ? title : "";
        this.author = author != null ? author : "";
        this.channelId = channelId != null ? channelId : "";
        this.durationSeconds = durationSeconds;
        this.viewCount = viewCount;
        this.liveContent = liveContent;
        this.thumbnailUrl = thumbnailUrl;
        this.hlsManifestUrl = (hlsManifestUrl != null && hlsManifestUrl.length() > 0) ? hlsManifestUrl : null;
        this.hlsUserAgent = (hlsUserAgent != null && hlsUserAgent.length() > 0)
                ? hlsUserAgent
                : HttpClient.DEFAULT_USER_AGENT;
        this.hlsVariantUrls = hlsVariantUrls != null
                ? new LinkedHashMap<String, String>(hlsVariantUrls)
                : new LinkedHashMap<String, String>();
        this.adaptiveAvcVideos = adaptiveAvcVideos != null
                ? new ArrayList<StreamFormat>(adaptiveAvcVideos)
                : new ArrayList<StreamFormat>();
        this.adaptiveAacAudios = adaptiveAacAudios != null
                ? new ArrayList<StreamFormat>(adaptiveAacAudios)
                : new ArrayList<StreamFormat>();
        this.progressiveFormats = progressiveFormats != null
                ? new ArrayList<StreamFormat>(progressiveFormats)
                : new ArrayList<StreamFormat>();
        this.resolvedAtMillis = resolvedAtMillis;
        this.expiresAtMillis = expiresAtMillis;
        this.resolverSummary = resolverSummary != null ? resolverSummary : "";
    }

    public boolean isExpired(long safetyMarginMs) {
        return System.currentTimeMillis() + safetyMarginMs >= expiresAtMillis;
    }

    public boolean hasPlayableStreams() {
        return hlsManifestUrl != null
                || !hlsVariantUrls.isEmpty()
                || !progressiveFormats.isEmpty()
                || (!adaptiveAvcVideos.isEmpty() && !adaptiveAacAudios.isEmpty())
                || !adaptiveAacAudios.isEmpty();
    }

    public StreamFormat getBestAudioFormat() {
        for (StreamFormat format : adaptiveAacAudios) {
            if (format.getItag() == StreamFormat.ITAG_AAC_128K && format.isOriginalAudioTrack()) {
                return format;
            }
        }
        if (!adaptiveAacAudios.isEmpty()) {
            return adaptiveAacAudios.get(0);
        }
        if (!progressiveFormats.isEmpty()) {
            return progressiveFormats.get(0);
        }
        return null;
    }

    public String getHlsVariantUrl(String qualityLabel) {
        if (qualityLabel == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : hlsVariantUrls.entrySet()) {
            if (qualityLabel.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public StreamFormat findProgressiveByQuality(String qualityLabel) {
        if (QUALITY_360P.equalsIgnoreCase(qualityLabel)) {
            for (StreamFormat format : progressiveFormats) {
                if (format.getItag() == StreamFormat.ITAG_MP4_360P_PROGRESSIVE) {
                    return format;
                }
            }
        }
        for (StreamFormat format : progressiveFormats) {
            if (qualityLabel.equalsIgnoreCase(format.getQualityLabel())) {
                return format;
            }
        }
        return null;
    }

    public StreamFormat findAdaptiveVideoByQuality(String qualityLabel) {
        int preferredItag = getPreferredItagForQuality(qualityLabel);
        if (preferredItag != -1) {
            for (StreamFormat format : adaptiveAvcVideos) {
                if (format.getItag() == preferredItag) {
                    return format;
                }
            }
        }
        for (StreamFormat format : adaptiveAvcVideos) {
            if (qualityLabel.equalsIgnoreCase(format.getQualityLabel())) {
                return format;
            }
        }
        return null;
    }

    private static int getPreferredItagForQuality(String quality) {
        if (QUALITY_1080P60.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_1080P60;
        }
        if (QUALITY_1080P.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_1080P;
        }
        if (QUALITY_720P60.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_720P60;
        }
        if (QUALITY_720P.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_720P;
        }
        if (QUALITY_480P.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_480P;
        }
        if (QUALITY_360P.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_360P;
        }
        if (QUALITY_144P.equalsIgnoreCase(quality)) {
            return StreamFormat.ITAG_AVC_144P;
        }
        return -1;
    }

    public List<String> getAvailableQualities() {
        List<String> list = new ArrayList<String>();
        for (String q : ALL_QUALITIES_ORDERED) {
            if (resolveExactQuality(q, false) != null) {
                list.add(q);
            }
        }
        if (list.isEmpty() && hlsManifestUrl != null) {
            list.add(QUALITY_720P);
        }
        return list;
    }

    public PlaybackSource selectPlaybackSource(String requestedQuality, boolean preferAdaptiveForHd) {
        String normalized = normalizeQuality(requestedQuality);
        PlaybackSource exact = resolveExactQuality(normalized, preferAdaptiveForHd);
        if (exact != null) {
            return exact;
        }

        if (QUALITY_720P60.equalsIgnoreCase(normalized)) {
            PlaybackSource q720 = resolveExactQuality(QUALITY_720P, preferAdaptiveForHd);
            if (q720 != null) {
                return q720;
            }
        }
        if (QUALITY_1080P60.equalsIgnoreCase(normalized)) {
            PlaybackSource q1080 = resolveExactQuality(QUALITY_1080P, preferAdaptiveForHd);
            if (q1080 != null) {
                return q1080;
            }
        }

        String[] fallbackOrder = new String[] {
                QUALITY_720P60,
                QUALITY_720P,
                QUALITY_1080P60,
                QUALITY_1080P,
                QUALITY_480P,
                QUALITY_360P,
                QUALITY_144P,
                QUALITY_AUDIO_ONLY
        };
        for (String candidate : fallbackOrder) {
            PlaybackSource source = resolveExactQuality(candidate, preferAdaptiveForHd);
            if (source != null) {
                return source;
            }
        }
        if (hlsManifestUrl != null) {
            return PlaybackSource.forHls(QUALITY_720P, hlsManifestUrl, false, hlsUserAgent);
        }
        return null;
    }

    public PlaybackSource resolveExactQuality(String quality, boolean preferAdaptiveForHd) {
        StreamFormat bestAudio = getBestAudioFormat();

        if (QUALITY_AUDIO_ONLY.equalsIgnoreCase(quality)) {
            if (bestAudio != null) {
                return PlaybackSource.forAudioOnly(bestAudio);
            }
            return null;
        }

        if (QUALITY_360P.equalsIgnoreCase(quality)) {
            StreamFormat prog360 = findProgressiveByQuality(QUALITY_360P);
            if (prog360 != null) {
                return PlaybackSource.forProgressive(QUALITY_360P, prog360);
            }
            String hls360 = getHlsVariantUrl(QUALITY_360P);
            if (!preferAdaptiveForHd && hls360 != null) {
                return PlaybackSource.forHls(QUALITY_360P, hls360, true, hlsUserAgent);
            }
            StreamFormat v360 = findAdaptiveVideoByQuality(QUALITY_360P);
            if (v360 != null && bestAudio != null) {
                return PlaybackSource.forDualAdaptive(QUALITY_360P, v360, bestAudio);
            }
            if (hls360 != null) {
                return PlaybackSource.forHls(QUALITY_360P, hls360, true, hlsUserAgent);
            }
            return null;
        }

        String hlsVariant = getHlsVariantUrl(quality);
        StreamFormat adaptiveVideo = findAdaptiveVideoByQuality(quality);
        StreamFormat progressiveVideo = findProgressiveByQuality(quality);

        if (preferAdaptiveForHd) {
            if (adaptiveVideo != null && bestAudio != null) {
                return PlaybackSource.forDualAdaptive(quality, adaptiveVideo, bestAudio);
            }
            if (progressiveVideo != null) {
                return PlaybackSource.forProgressive(quality, progressiveVideo);
            }
            if (hlsVariant != null) {
                return PlaybackSource.forHls(quality, hlsVariant, true, hlsUserAgent);
            }
        } else {
            if (hlsVariant != null) {
                return PlaybackSource.forHls(quality, hlsVariant, true, hlsUserAgent);
            }
            if (progressiveVideo != null) {
                return PlaybackSource.forProgressive(quality, progressiveVideo);
            }
            if (adaptiveVideo != null && bestAudio != null) {
                return PlaybackSource.forDualAdaptive(quality, adaptiveVideo, bestAudio);
            }
            if (hlsManifestUrl != null && hlsVariantUrls.isEmpty()
                    && (QUALITY_720P.equalsIgnoreCase(quality) || QUALITY_1080P.equalsIgnoreCase(quality))) {
                return PlaybackSource.forHls(quality, hlsManifestUrl, false, hlsUserAgent);
            }
        }

        return null;
    }

    public static String normalizeQuality(String q) {
        if (q == null) {
            return QUALITY_720P60;
        }
        String trimmed = q.trim();
        if (trimmed.toUpperCase().startsWith("MPEG-4 ")) {
            trimmed = trimmed.substring(7).trim();
        }
        for (String known : ALL_QUALITIES_ORDERED) {
            if (known.equalsIgnoreCase(trimmed)) {
                return known;
            }
        }
        if (trimmed.toLowerCase().contains("audio") || trimmed.toLowerCase().startsWith("m4a")) {
            return QUALITY_AUDIO_ONLY;
        }
        return QUALITY_720P60;
    }

    public Map<String, PlaybackSource> buildQualitySourceMap(boolean preferAdaptiveForHd) {
        Map<String, PlaybackSource> map = new LinkedHashMap<String, PlaybackSource>();
        for (String q : ALL_QUALITIES_ORDERED) {
            PlaybackSource src = resolveExactQuality(q, preferAdaptiveForHd);
            if (src != null) {
                map.put(q, src);
            }
        }
        return map;
    }

    public String getVideoId() {
        return videoId;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getChannelId() {
        return channelId;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public long getViewCount() {
        return viewCount;
    }

    public boolean isLiveContent() {
        return liveContent;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getHlsManifestUrl() {
        return hlsManifestUrl;
    }

    public String getHlsUserAgent() {
        return hlsUserAgent;
    }

    public Map<String, String> getHlsVariantUrls() {
        return Collections.unmodifiableMap(hlsVariantUrls);
    }

    public List<StreamFormat> getAdaptiveAvcVideos() {
        return Collections.unmodifiableList(adaptiveAvcVideos);
    }

    public List<StreamFormat> getAdaptiveAacAudios() {
        return Collections.unmodifiableList(adaptiveAacAudios);
    }

    public List<StreamFormat> getProgressiveFormats() {
        return Collections.unmodifiableList(progressiveFormats);
    }

    public long getResolvedAtMillis() {
        return resolvedAtMillis;
    }

    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public String getResolverSummary() {
        return resolverSummary;
    }
}
