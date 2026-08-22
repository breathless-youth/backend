package project.study.metrics.dto;

import java.time.Instant;

/**
 * 일일 리포트의 신규 가입 항목 — 기준일(KST)에 가입한 유저.
 *
 * @param userId 유저 ID
 * @param registeredAt 가입 시각 (users.created_at)
 */
public record NewUser(Long userId, Instant registeredAt) {}
