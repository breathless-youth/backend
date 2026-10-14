package project.study.studysession.dto;

import java.time.Instant;

/** 랭킹 집계 한 줄 (BY-828) — 사용자별 순공·총공부 합과 그 값을 만든 마지막 조각의 종료 시각(동점 판정용). */
public record RankingTotalRow(long userId, String nickname, long focusSec, long studySec, Instant achievedAt) {}
