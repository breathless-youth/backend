package project.study.studysession.dto;

import java.time.Instant;

/**
 * 세션 제출의 완료 할 일 1건 — 소유 검증을 통과한 뒤 과목 도메인이 만들어 세션 도메인에 넘기는 값 (ADR-0022).
 * 세션 도메인은 과목 엔티티를 모른다. doneAt은 자정 분할 귀속에만 쓰이고, 앱이 완료 해제한 뒤 제출했다면 null일 수 있다.
 */
public record CompletedTask(Long taskId, Instant doneAt) {}
