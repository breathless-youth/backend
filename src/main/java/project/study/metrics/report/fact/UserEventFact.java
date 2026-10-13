package project.study.metrics.report.fact;

import java.time.Instant;

/** 유저가 무언가를 한 시각 하나(방 참여·할 일 완료·D-day 설정) — 각 도메인이 채운다 (BY-904). */
public record UserEventFact(long userId, Instant at) {}
