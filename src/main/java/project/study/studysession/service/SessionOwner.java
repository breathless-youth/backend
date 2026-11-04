package project.study.studysession.service;

import java.time.ZoneId;

/**
 * 세션 조각의 주인과 그 조각을 나눌 시간대.
 *
 * 자정·구간 경계와 statDate를 정하는 시간대는 늘 사용자와 함께 정해져 검증·분할 진입점에 한 값으로 넘긴다.
 */
record SessionOwner(Long userId, ZoneId zone) {}
