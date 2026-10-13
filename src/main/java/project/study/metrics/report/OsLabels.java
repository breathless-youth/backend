package project.study.metrics.report;

import java.util.Locale;

/** Amplitude `os_name`(예: "ios", "Android") → 메시지 라벨. 값이 없으면 null. */
public final class OsLabels {

    public static final String MISSING = "미수집";

    private OsLabels() {}

    public static String label(String osName) {
        if (osName == null || osName.isBlank()) {
            return null;
        }
        String lower = osName.toLowerCase(Locale.ROOT);
        if (lower.contains("ios") || lower.contains("iphone") || lower.contains("ipad")) {
            return "iOS";
        }
        return lower.contains("android") ? "Android" : osName.strip();
    }
}
