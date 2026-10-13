package project.study.metrics.report.fact;

import java.time.Instant;

/** 리포트가 읽는 유저 한 명의 가입 정보 — user 도메인이 채운다 (BY-904). */
public record UserFact(long userId, String nickname, Instant createdAt, String category, String goal) {}
