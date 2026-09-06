package project.study.room.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 소멸한 방의 초대코드 묘비 (BY-436).
 *
 * <p>방이 사라지면 초대코드도 함께 지워지므로, 그 코드로 들어오려는 시도는 "발급된 적 없는 코드"와
 * 구분되지 않는다. 동시 퇴장으로 방이 막 사라진 참가자에게까지 "코드를 다시 확인해 주세요"라고
 * 답하게 되므로, 소멸한 코드를 일정 기간 기억해 "방이 종료되었어요"로 갈라 답한다.
 *
 * <p>같은 기간 동안 그 코드의 재발급도 막는다 — 묘비가 남은 코드를 새 방에 다시 내주면
 * 종료된 방을 찾던 사람이 엉뚱한 방에 입장한다.
 *
 * <p>동기화는 하지 않는다 — 소유자인 {@link RoomService}가 모든 진입점을 인스턴스 락으로 직렬화한다.
 */
class ClosedInviteCodes {

    static final long TTL_SECONDS = 600;

    private final Map<String, Instant> closedAtByCode = new HashMap<>();

    void record(String inviteCode, Instant closedAt) {
        closedAtByCode.put(inviteCode, closedAt);
    }

    boolean contains(String inviteCode, Instant now) {
        Instant closedAt = closedAtByCode.get(inviteCode);
        return closedAt != null && isAlive(closedAt, now);
    }

    void purgeExpired(Instant now) {
        closedAtByCode.values().removeIf(closedAt -> !isAlive(closedAt, now));
    }

    private static boolean isAlive(Instant closedAt, Instant now) {
        return closedAt.plusSeconds(TTL_SECONDS).isAfter(now);
    }
}
