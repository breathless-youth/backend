package project.study.ranking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** 마감 기록 한 행 (BY-828). value는 초 또는 집중률(%). */
public record RankingRecordRow(
        long id, String boardKey, LocalDate periodStart, Instant closesAt, int rank, BigDecimal value) {}
