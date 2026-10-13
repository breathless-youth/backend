package project.study.metrics.report.rule;

/** 노출 조건 판정 (스펙 §6). 라벨은 탐색 스레드 꼬리의 "제외" 이유다. */
public enum Exposure {
    PASS("통과"),
    TOO_FEW("사람 부족"),
    TOO_COMMON("너무 흔함"),
    WEAK("상관 약함"),
    NOT_COLLECTED("Amplitude 미수집");

    private final String label;

    Exposure(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
