package project.study.point.dto;

import java.time.LocalDate;

/** ranking_record 테이블 한 행에서 포인트에 필요한 값. */
public record MedalRow(long recordId, String boardKey, LocalDate periodStart, int rank) {}
