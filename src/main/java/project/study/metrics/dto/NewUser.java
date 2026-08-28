package project.study.metrics.dto;

/**
 * 신규 가입 유저 한 명 — 유저 ID와 가입 시각(KST HH:mm).
 *
 * @param userId 유저 ID
 * @param joinedAt 가입 시각(KST, "HH:mm")
 */
public record NewUser(long userId, String joinedAt) {}
