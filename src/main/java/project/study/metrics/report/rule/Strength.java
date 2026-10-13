package project.study.metrics.report.rule;

/** 상관 강도 — Amplitude Compass 구간(0.4 강함 / 0.3 보통 / 0.2 약함). */
public enum Strength {
    STRONG("강함"),
    MODERATE("보통"),
    WEAK("약함"),
    NONE("거의 없음");

    private final String label;

    Strength(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Strength of(double phi) {
        if (Double.isNaN(phi) || phi < 0.2) {
            return NONE;
        }
        if (phi >= 0.4) {
            return STRONG;
        }
        return phi >= 0.3 ? MODERATE : WEAK;
    }
}
