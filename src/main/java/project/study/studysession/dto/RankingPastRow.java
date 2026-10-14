package project.study.studysession.dto;

import java.time.Instant;

/**
 * 기간 순공을 since 시점으로 되돌린 한 사용자의 합계 (BY-828 §7.3). achievedAt은 since까지 쌓인 마지막 순간(그 전에 공부가 없으면
 * null), studiedFrom은 since 뒤에 처음 공부한 시각(since에 공부 중이었으면 since, since 뒤 공부가 없으면 null)이다.
 */
public record RankingPastRow(long userId, String nickname, long focusSec, Instant achievedAt, Instant studiedFrom) {}
