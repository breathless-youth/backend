package project.study.metrics.report.fact;

import java.time.Instant;

/**
 * 푸시 실험 배정 한 명 — push 도메인이 채운다 (BY-1070).
 *
 * @param arm {@code PUSH} · {@code CONTROL}
 * @param firstSessionAt 첫 측정 세션 시작 시각, 아직 없으면 null
 * @param firstFocusSec 첫 측정 세션 묶음의 순공 합, 첫 세션이 없으면 null
 * @param daySevenReturned 가입 7일 뒤 그날 세션을 시작했는지, 아직 집계 전이면 null
 * @param hasToken 지금 푸시 토큰이 남아 있는지 — 발송이 UNREGISTERED로 지운 경우(앱 삭제 추정) false
 * @param notificationsEnabled 지금 알림 권한이 켜진 토큰이 있는지
 */
public record ExperimentFact(
        long userId,
        String arm,
        Instant assignedAt,
        Instant firstSessionAt,
        Integer firstFocusSec,
        Boolean daySevenReturned,
        boolean hasToken,
        boolean notificationsEnabled) {}
