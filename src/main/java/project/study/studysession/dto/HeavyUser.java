package project.study.studysession.dto;

/**
 * 헤비유저 조회 결과 — 최근 7일 중 스트릭 인정일(ADR-0009)이 기준치 이상인 유저.
 *
 * @param userId 유저 ID
 * @param activeDays 조회 구간 안에서 스트릭 인정 기준을 만족한 날짜 수
 */
public record HeavyUser(Long userId, Long activeDays) {}
