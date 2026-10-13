package project.study.metrics.report.rule;

/** 규칙이 읽는 데이터 — Amplitude 규칙은 판정 기간이 미수집이면 그날 평가에서 빠진다. */
public enum RuleSource {
    SERVER,
    AMPLITUDE
}
