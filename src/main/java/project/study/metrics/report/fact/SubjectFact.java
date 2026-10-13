package project.study.metrics.report.fact;

import java.time.Instant;

/** 과목 하나의 생성·삭제 시각 — subject 도메인이 채운다. 지운 과목도 첫 주 등록 수에 센다 (BY-904). */
public record SubjectFact(long userId, Instant createdAt, Instant deletedAt) {}
