package project.study.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

/**
 * WS 커넥션·송신버퍼 상태 관측 (BY-491). {@code /actuator/wsstats}
 *
 * <p>{@code sendLimitExceededSessions}가 송신버퍼 한도(sendBufferSizeLimit/sendTimeLimit) 초과로 강제 종료된
 * 세션 수다 — 부하 중 이 값이 증가하면 "CPU 포화 → 송신 큐 적체 → 메모리 팽창" 가설의 직접 증거가 된다.
 * 부하테스트 스냅샷 스크립트가 이 엔드포인트를 주기 수집한다.
 */
@Component
@Endpoint(id = "wsstats")
public class WebSocketStatsEndpoint {

    private static final long LOGGING_PERIOD_MS = 30_000L;

    private final WebSocketMessageBrokerStats stats;

    public WebSocketStatsEndpoint(WebSocketMessageBrokerStats stats) {
        this.stats = stats;
        // 기본 30분 주기 로그를 30초로 — 부하 중 로그로도 추이가 남게 한다
        stats.setLoggingPeriod(LOGGING_PERIOD_MS);
    }

    @ReadOperation
    public Map<String, Object> wsStats() {
        Map<String, Object> info = new LinkedHashMap<>();
        SubProtocolWebSocketHandler.Stats ws = stats.getWebSocketSessionStats();
        if (ws != null) {
            info.put("currentWsSessions", ws.getWebSocketSessions());
            info.put("totalSessions", ws.getTotalSessions());
            info.put("sendLimitExceededSessions", ws.getLimitExceededSessions()); // ★ 송신버퍼 초과 종료 수
            info.put("transportErrorSessions", ws.getTransportErrorSessions());
            info.put("noMessagesReceivedSessions", ws.getNoMessagesReceivedSessions());
        }
        StompSubProtocolHandler.Stats stomp = stats.getStompSubProtocolStats();
        if (stomp != null) {
            info.put("stompConnect", stomp.getTotalConnect());
            info.put("stompConnected", stomp.getTotalConnected());
            info.put("stompDisconnect", stomp.getTotalDisconnect());
        }
        info.put("clientInboundExecutor", stats.getClientInboundExecutorStatsInfo());
        info.put("clientOutboundExecutor", stats.getClientOutboundExecutorStatsInfo());
        return info;
    }
}
