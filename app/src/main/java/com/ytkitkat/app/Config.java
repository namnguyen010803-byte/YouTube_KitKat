package com.ytkitkat.app;

/**
 * Cấu hình tập trung của ứng dụng.
 *
 * Lưu ý: API key đặt trực tiếp ở đây theo thỏa thuận. Nếu key được giới hạn
 * "Android apps" trên Google Cloud Console, YouTubeApi sẽ tự gửi kèm
 * header X-Android-Package và X-Android-Cert (SHA-1 chữ ký APK) nên vẫn hoạt động.
 */
public final class Config {

    private Config() {
    }

    // ===================== YouTube Data API v3 =====================

    /** Thay bằng API key YouTube Data API v3 của bạn (không bắt buộc vì đã có InnerTube). */
    public static final String YOUTUBE_API_KEY = "YOUR_YOUTUBE_DATA_API_V3_KEY";

    /** Giá trị giữ chỗ dùng để phát hiện chưa cấu hình key. */
    private static final String API_KEY_PLACEHOLDER = "YOUR_YOUTUBE_DATA_API_V3_KEY";

    public static final String REGION_CODE = "VN";
    public static final String LANGUAGE_CODE = "vi";

    public static final String YOUTUBE_DATA_API_BASE = "https://www.googleapis.com/youtube/v3/";
    public static final String SEARCH_SUGGEST_URL = "https://suggestqueries.google.com/complete/search";
    public static final int PAGE_SIZE = 20;

    // ===================== Phát video / Trích xuất luồng InnerTube =====================

    /**
     * Chất lượng mặc định: "720p60" (nếu video không có 720p60 sẽ tự động lùi về "720p").
     * Hỗ trợ: "1080p60", "1080p", "720p60", "720p", "480p", "360p", "144p", "Audio Only".
     */
    public static final String DEFAULT_QUALITY = "720p60";

    /**
     * Mặc định ưu tiên HLS .m3u8 con (false) của client IOS cho 720p60/1080p60 vì đã mux sẵn H.264+AAC.
     * Có thể chuyển đổi trực tiếp sang Dual Adaptive H.264+AAC (true) ngay trong menu Chất lượng của Player.
     */
    public static final boolean PREFER_ADAPTIVE_OVER_HLS_FOR_HD = false;

    /** Endpoint InnerTube player. */
    public static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

    /** Endpoint InnerTube browse/search/visitor_id không cần API Key. */
    public static final String INNERTUBE_BROWSE_URL =
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    public static final String INNERTUBE_SEARCH_URL =
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
    public static final String INNERTUBE_VISITOR_ID_URL =
            "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false";
    public static final String YOUTUBE_SW_JS_DATA_URL =
            "https://www.youtube.com/sw.js_data";

    /**
     * Chuỗi visitorData hiện tại dùng chung giữa YouTubeApi (/search, /browse) và StreamResolver (/player)
     * để vượt qua cơ chế kiểm tra anti-bot ("Đăng nhập để xác nhận bạn không phải là robot").
     */
    private static volatile String visitorData = null;

    public static String getVisitorData() {
        return visitorData;
    }

    public static void setVisitorData(String newVisitorData) {
        if (newVisitorData != null) {
            String trimmed = newVisitorData.trim();
            if (trimmed.length() > 0) {
                visitorData = trimmed;
            }
        }
    }

    /** Danh sách các instance Invidious & Piped dự phòng đang hoạt động khi mạng chặn IP. */
    public static final String[] FALLBACK_INVIDIOUS_AND_PIPED_ENDPOINTS = new String[] {
            "https://inv.nadeko.net/api/v1/videos/",
            "https://invidious.nerdvpn.de/api/v1/videos/",
            "https://pipedapi.adminforge.de/streams/"
    };

    public static final String FALLBACK_PIPED_API = "https://pipedapi.adminforge.de";
    public static final boolean ENABLE_PIPED_FALLBACK = true;

    /** Thời gian chờ tối đa cho chuỗi giải mã luồng InnerTube. */
    public static final long INNERTUBE_CLIENT_TIMEOUT_MS = 25000L;

    /** Số StreamInfo được giữ trong LruCache. */
    public static final int STREAM_CACHE_SIZE = 16;

    /** TTL dự phòng nếu URL googlevideo không có tham số expire (5 giờ). */
    public static final long STREAM_CACHE_FALLBACK_TTL_MS = 5L * 60L * 60L * 1000L;

    /** Trừ hao trước thời điểm URL hết hạn (10 phút). */
    public static final long STREAM_EXPIRY_SAFETY_MARGIN_MS = 10L * 60L * 1000L;

    /** Chu kỳ kiểm tra đồng bộ hình/tiếng của DualMediaPlayerController. */
    public static final long AV_SYNC_CHECK_INTERVAL_MS = 1000L;

    /** Ngưỡng lệch hình/tiếng tối đa trước khi căn chỉnh lại. */
    public static final int AV_SYNC_TOLERANCE_MS = 150;

    // ===================== Mạng & Bộ nhớ đệm ảnh =====================

    public static final int CONNECT_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 20000;
    public static final int NETWORK_THREAD_COUNT = 4;
    public static final int IMAGE_LOADER_THREAD_COUNT = 3;
    /** Dung lượng tối đa Disk Cache ảnh thumbnail (40 MB). */
    public static final long IMAGE_DISK_CACHE_MAX_BYTES = 40L * 1024L * 1024L;
    /** Số lần thử lại khi lỗi IO / 5xx (không tính lần đầu). */
    public static final int HTTP_MAX_RETRIES = 1;

    public static final boolean DEBUG_LOG = true;

    /** @return true nếu YOUTUBE_API_KEY đã được thay bằng key thật. */
    public static boolean isApiKeyConfigured() {
        String key = YOUTUBE_API_KEY;
        return key != null
                && key.trim().length() > 0
                && !API_KEY_PLACEHOLDER.equals(key.trim());
    }
}
