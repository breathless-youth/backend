package project.study.ranking.dto;

import java.time.LocalDate;

/** 역대 마감 최고 순위 한 행 (BY-828). */
public record RankingBestRow(int rank, String boardKey, LocalDate periodStart) {}
