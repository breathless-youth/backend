package project.study.room.event;

import java.time.Instant;
import java.util.UUID;

// focusSec: 퇴장 시점에 서버가 마지막으로 본 순공 타이머 값(초) — 클라이언트가 state의 studySeconds로
// 주기 보고하는 값(실질 순공시간)이라 실제보다 뒤처질 수 있다
public record ParticipantLeftEvent(UUID roomUid, Long userId, Instant leftAt, LeaveReason reason, int focusSec) {}
