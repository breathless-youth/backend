package project.study.studysession.dto;

import java.time.Instant;

/**
 * 막 시작된 공부 세션 하나 (BY-1124) — 새 draft가 처음 저장돼 시작 알림 대상으로 꺼내진 것. 세션은 (사용자, 시작 시각)으로 가리킨다.
 *
 * @param startedAt 앱이 보낸 세션 시작 시각(클라 시계)
 */
public record NewDraft(long userId, Instant startedAt) {}
