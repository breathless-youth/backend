package project.study.room.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import project.study.room.support.MutableClock;

class SessionRegistryTest {

    private static final Instant T0 = Instant.parse("2026-09-09T00:00:00Z");

    private static WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }

    @Test
    void 등록된_세션은_열림이고_오픈_시각을_기억한다() {
        MutableClock clock = MutableClock.at(T0);
        SessionRegistry registry = new SessionRegistry(clock);

        registry.register(session("s1"));
        clock.advance(Duration.ofSeconds(3));
        registry.register(session("s2"));

        assertThat(registry.isOpen("s1")).isTrue();
        assertThat(registry.isOpen("nope")).isFalse();
        assertThat(registry.isOpen(null)).isFalse();
        assertThat(registry.openedAt("s2")).contains(T0.plusSeconds(3));
        registry.remove("s1");
        assertThat(registry.isOpen("s1")).isFalse();
    }

    @Test
    void 펜싱은_모든_세션을_닫힘으로_표시하고_GOING_AWAY로_닫는다() throws Exception {
        SessionRegistry registry = new SessionRegistry(MutableClock.at(T0));
        WebSocketSession s1 = session("s1");
        WebSocketSession s2 = session("s2");
        registry.register(s1);
        registry.register(s2);

        assertThat(registry.fence()).isEqualTo(2);

        verify(s1).close(CloseStatus.GOING_AWAY);
        verify(s2).close(CloseStatus.GOING_AWAY);
        assertThat(registry.isOpen("s1")).isFalse();
        assertThat(registry.fence()).as("이미 닫힌 세션은 다시 세지 않는다").isZero();
    }
}
