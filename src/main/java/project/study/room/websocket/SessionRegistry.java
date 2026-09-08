package project.study.room.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * 이 태스크가 쥔 WebSocket 세션 핸들과 오픈 시각. 룸 상태가 아니라 소켓 핸들이라 태스크 메모리에 산다.
 * confirmStomp의 사전/사후 검사(isOpen)와 펜싱(fence), 옛 세션 confirm 차단용 오픈 시각(openedAt)에 쓴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SessionRegistry {

    private record Entry(WebSocketSession session, Instant openedAt, AtomicBoolean open) {}

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final Clock clock;

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), new Entry(session, clock.instant(), new AtomicBoolean(true)));
    }

    public void remove(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public boolean isOpen(String sessionId) {
        Entry entry = sessionId == null ? null : sessions.get(sessionId);
        return entry != null && entry.open().get();
    }

    public Optional<Instant> openedAt(String sessionId) {
        return Optional.ofNullable(sessionId == null ? null : sessions.get(sessionId))
                .map(Entry::openedAt);
    }

    public int size() {
        return sessions.size();
    }

    /** 펜싱 — 모든 세션을 먼저 닫힘으로 표시한 뒤 GOING_AWAY로 닫는다. 표시가 먼저라 이후 confirm은 사전 검사에서 거절된다. */
    public int fence() {
        int closed = 0;
        for (Entry entry : sessions.values()) {
            if (!entry.open().compareAndSet(true, false)) {
                continue;
            }
            closed++;
            try {
                entry.session().close(CloseStatus.GOING_AWAY);
            } catch (IOException e) {
                log.warn("세션 닫기 실패: sessionId={}", entry.session().getId(), e);
            }
        }
        return closed;
    }
}
