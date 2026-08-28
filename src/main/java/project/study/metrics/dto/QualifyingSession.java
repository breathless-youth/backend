package project.study.metrics.dto;

/**
 * 10분 이상(순공 인정) 세션 한 건의 지표 집계용 표현.
 *
 * @param userId 세션 주인
 * @param focusSec 순공 시간(초)
 * @param social 세션 시간이 그 유저의 룸 참여구간과 겹치면 true(소셜), 아니면 false(싱글)
 */
public record QualifyingSession(long userId, int focusSec, boolean social) {}
