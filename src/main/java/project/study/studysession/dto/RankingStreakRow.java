package project.study.studysession.dto;

import java.time.Instant;
import java.time.LocalDate;

/** 최장 연속 공부일 한 줄 (BY-828) — 그 길이를 처음 찍은 구간과, 끝 날에 기준(10분)을 처음 넘긴 조각의 종료 시각. */
public record RankingStreakRow(
        long userId, String nickname, int days, LocalDate startDate, LocalDate endDate, Instant achievedAt) {}
