package project.study.user.service;

/** 점으로 나눈 숫자 버전 비교 (예: 26.47.0). */
public final class AppVersions {

    private static final int MAX_PARTS = 6;

    private AppVersions() {}

    /** version이 base보다 높으면 true. 둘 중 하나라도 읽을 수 없으면 false다. */
    public static boolean isNewer(String version, String base) {
        int[] left = parse(version);
        int[] right = parse(base);
        if (left == null || right == null) {
            return false;
        }
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    /** version이 base 이상이면 true. 둘 중 하나라도 읽을 수 없으면 false다. */
    public static boolean atLeast(String version, String base) {
        return parse(version) != null && parse(base) != null && !isNewer(base, version);
    }

    private static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.trim().split("\\.", -1);
        if (parts.length == 0 || parts.length > MAX_PARTS) {
            return null;
        }
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("\\d{1,6}")) {
                return null;
            }
            numbers[i] = Integer.parseInt(parts[i]);
        }
        return numbers;
    }
}
