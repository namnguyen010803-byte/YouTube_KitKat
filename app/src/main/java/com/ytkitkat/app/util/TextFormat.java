package com.ytkitkat.app.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Định dạng số liệu, thời lượng, thời gian theo phong cách YouTube tiếng Việt.
 * Ví dụ: "1,2 Tr lượt xem", "3 ngày trước", "12:05", "1:02:33".
 */
public final class TextFormat {

    private static final long SECOND_MS = 1000L;
    private static final long MINUTE_MS = 60L * SECOND_MS;
    private static final long HOUR_MS = 60L * MINUTE_MS;
    private static final long DAY_MS = 24L * HOUR_MS;
    private static final long WEEK_MS = 7L * DAY_MS;
    private static final long MONTH_MS = 30L * DAY_MS;
    private static final long YEAR_MS = 365L * DAY_MS;

    private TextFormat() {
    }

    // ------------------------------------------------------------------
    // Thời lượng ISO 8601 (contentDetails.duration)
    // ------------------------------------------------------------------

    /**
     * Phân tích "PT1H2M3S", "PT45S", "P1DT2H", "P0D" thành số giây.
     * Trả về 0 nếu chuỗi rỗng hoặc sai định dạng (ví dụ video trực tiếp).
     */
    public static long parseIsoDuration(String iso) {
        if (iso == null || iso.length() < 2 || iso.charAt(0) != 'P') {
            return 0;
        }
        long total = 0;
        long number = 0;
        boolean inTimePart = false;
        boolean hasDigits = false;
        for (int i = 1; i < iso.length(); i++) {
            char c = iso.charAt(i);
            if (c >= '0' && c <= '9') {
                number = number * 10 + (c - '0');
                hasDigits = true;
                continue;
            }
            if (c == '.' || c == ',') {
                // Bỏ phần thập phân của giây (hiếm gặp)
                while (i + 1 < iso.length() && Character.isDigit(iso.charAt(i + 1))) {
                    i++;
                }
                continue;
            }
            switch (c) {
                case 'T':
                    inTimePart = true;
                    break;
                case 'Y':
                    total += number * 365L * 86400L;
                    break;
                case 'W':
                    total += number * 7L * 86400L;
                    break;
                case 'D':
                    total += number * 86400L;
                    break;
                case 'H':
                    total += number * 3600L;
                    break;
                case 'M':
                    total += inTimePart ? number * 60L : number * 30L * 86400L;
                    break;
                case 'S':
                    total += number;
                    break;
                default:
                    return hasDigits ? total : 0;
            }
            number = 0;
        }
        return total;
    }

    /** 65 -> "1:05", 3723 -> "1:02:03", 0 -> "". */
    public static String formatDuration(long totalSeconds) {
        if (totalSeconds <= 0) {
            return "";
        }
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + ":" + pad2(minutes) + ":" + pad2(seconds);
        }
        return minutes + ":" + pad2(seconds);
    }

    /** Định dạng mili-giây cho thanh tiến trình trình phát. */
    public static String formatPlaybackTime(long millis) {
        long seconds = Math.max(0, millis / 1000);
        if (seconds == 0) {
            return "0:00";
        }
        return formatDuration(seconds);
    }

    private static String pad2(long value) {
        return value < 10 ? "0" + value : String.valueOf(value);
    }

    // ------------------------------------------------------------------
    // Số đếm rút gọn
    // ------------------------------------------------------------------

    /** 999 -> "999", 1234 -> "1,2 N", 15300 -> "15 N", 2500000 -> "2,5 Tr", 1.1e9 -> "1,1 T". */
    public static String formatCompactCount(long count) {
        if (count < 0) {
            return "";
        }
        if (count < 1000L) {
            return String.valueOf(count);
        }
        if (count < 1000000L) {
            return compact(count, 1000L, "N");
        }
        if (count < 1000000000L) {
            return compact(count, 1000000L, "Tr");
        }
        return compact(count, 1000000000L, "T");
    }

    private static String compact(long count, long unit, String suffix) {
        if (count / unit >= 10) {
            return (count / unit) + " " + suffix;
        }
        long tenths = (count * 10L) / unit;
        long whole = tenths / 10;
        long fraction = tenths % 10;
        if (fraction == 0) {
            return whole + " " + suffix;
        }
        return whole + "," + fraction + " " + suffix;
    }

    public static String formatViews(long views) {
        if (views < 0) {
            return "";
        }
        return formatCompactCount(views) + " lượt xem";
    }

    public static String formatSubscribers(long subscribers) {
        if (subscribers < 0) {
            return "";
        }
        return formatCompactCount(subscribers) + " người đăng ký";
    }

    public static String formatLikes(long likes) {
        if (likes < 0) {
            return "Thích";
        }
        return formatCompactCount(likes);
    }

    // ------------------------------------------------------------------
    // Thời gian
    // ------------------------------------------------------------------

    /**
     * Phân tích ngày ISO 8601: "2024-05-01T10:20:30Z", "2024-05-01T10:20:30.123Z",
     * "2024-05-01T10:20:30+07:00". Trả về 0 nếu lỗi.
     */
    public static long parseIsoDate(String iso) {
        if (iso == null || iso.length() < 19) {
            return 0;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            long millis = format.parse(iso.substring(0, 19)).getTime();

            String rest = iso.substring(19);
            if (rest.startsWith(".")) {
                int idx = 1;
                while (idx < rest.length() && Character.isDigit(rest.charAt(idx))) {
                    idx++;
                }
                rest = rest.substring(idx);
            }
            if (rest.length() >= 6 && (rest.charAt(0) == '+' || rest.charAt(0) == '-')) {
                int sign = rest.charAt(0) == '+' ? 1 : -1;
                int hours = Integer.parseInt(rest.substring(1, 3));
                int minutes = Integer.parseInt(rest.substring(4, 6));
                millis -= sign * (hours * HOUR_MS + minutes * MINUTE_MS);
            }
            return millis;
        } catch (ParseException e) {
            return 0;
        } catch (NumberFormatException e) {
            return 0;
        } catch (StringIndexOutOfBoundsException e) {
            return 0;
        }
    }

    /** "vừa xong", "5 phút trước", "2 giờ trước", "3 ngày trước", "2 tuần trước", "4 tháng trước", "1 năm trước". */
    public static String formatRelativeTime(long timeMillis) {
        if (timeMillis <= 0) {
            return "";
        }
        long diff = System.currentTimeMillis() - timeMillis;
        if (diff < MINUTE_MS) {
            return "vừa xong";
        }
        if (diff < HOUR_MS) {
            return (diff / MINUTE_MS) + " phút trước";
        }
        if (diff < DAY_MS) {
            return (diff / HOUR_MS) + " giờ trước";
        }
        if (diff < WEEK_MS) {
            return (diff / DAY_MS) + " ngày trước";
        }
        if (diff < MONTH_MS) {
            return (diff / WEEK_MS) + " tuần trước";
        }
        if (diff < YEAR_MS) {
            return (diff / MONTH_MS) + " tháng trước";
        }
        return (diff / YEAR_MS) + " năm trước";
    }

    // ------------------------------------------------------------------
    // Kỹ thuật (cho chẩn đoán / bảng chất lượng)
    // ------------------------------------------------------------------

    /** 128000 -> "128 kbps", 2500000 -> "2,5 Mbps". */
    public static String formatBitrate(long bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            return "? kbps";
        }
        if (bitsPerSecond < 1000000L) {
            return (bitsPerSecond / 1000L) + " kbps";
        }
        long tenths = bitsPerSecond / 100000L;
        long fraction = tenths % 10;
        return (tenths / 10) + (fraction > 0 ? "," + fraction : "") + " Mbps";
    }

    /** 1536 -> "1,5 KB", 12582912 -> "12 MB". */
    public static String formatFileSize(long bytes) {
        if (bytes <= 0) {
            return "?";
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB"};
        double value = bytes / 1024.0;
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        long tenths = Math.round(value * 10.0);
        if (tenths >= 100 || tenths % 10 == 0) {
            return (tenths / 10) + " " + units[unitIndex];
        }
        return (tenths / 10) + "," + (tenths % 10) + " " + units[unitIndex];
    }

    /** Parse long an toàn từ chuỗi (statistics.viewCount trả về dạng chuỗi). */
    public static long parseLongSafe(String value, long fallback) {
        if (value == null || value.length() == 0) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static int parseIntSafe(String value, int fallback) {
        if (value == null || value.length() == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
