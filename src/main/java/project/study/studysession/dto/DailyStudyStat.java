package project.study.studysession.dto;

import java.time.LocalDate;

/** period 조회의 일별 집계 한 줄 — group-by 프로젝션이자 응답 버킷으로 재사용한다. sum() 결과가 Long이라 박싱 타입을 쓴다. */
public record DailyStudyStat(LocalDate date, Long studySec, Long focusSec) {}
