package project.study.studysession.dto;

import java.time.Instant;

/** 누적 공부일 한 줄 (BY-828) — achievedAt은 마지막 공부일에 기준(1분)을 처음 넘긴 조각의 종료 시각. */
public record RankingDaysRow(long userId, String nickname, int days, Instant achievedAt) {}
