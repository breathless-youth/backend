package project.study.room.service;

/** 서버가 자동으로 내보낸 자리 — 호출자(컨트롤러·스케줄러)가 옛 방 토픽에 MEMBER_LEFT를 브로드캐스트한다. */
public record AutoLeave(Long roomId, Long userId) {}
