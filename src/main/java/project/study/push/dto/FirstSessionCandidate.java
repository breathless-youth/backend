package project.study.push.dto;

import java.time.Instant;
import java.time.ZoneId;

/**
 * 첫 세션 유도 푸시 후보 한 명.
 *
 * 야간 시간, 가입한 날, 발송 날짜를 사용자 현지 시각으로 판단하려고 가입 시각과 시간대를 함께 싣는다.
 */
public record FirstSessionCandidate(long userId, Instant createdAt, ZoneId zone) {}
