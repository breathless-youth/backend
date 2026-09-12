package project.study.common.logging;

import java.security.Principal;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.ExecutorChannelInterceptor;

/**
 * STOMP inbound 메시지 핸들러 전후로 MDC의 userId를 채우고 지운다.
 *
 * <p>서블릿 필터를 타지 않는 경로라 {@link RequestLoggingFilter}가 닿지 않는다. {@code beforeHandle}과
 * {@code afterMessageHandled}는 둘 다 핸들러를 실행하는 inbound 실행 스레드에서 불리므로, 같은 스레드에서
 * 넣고 지워 누수가 없다. {@code preSend}는 WebSocket 수신 스레드에서 불려 여기 쓰면 안 된다.
 *
 * <p>SessionSubscribeEvent 같은 세션 이벤트 리스너는 이 경로 밖(수신 스레드)에서 실행돼 MDC가 없다 —
 * {@code StompEventListener}가 userId를 메시지에 직접 찍는 이유다.
 */
public class StompMdcChannelInterceptor implements ExecutorChannelInterceptor {

    @Override
    public Message<?> beforeHandle(Message<?> message, MessageChannel channel, MessageHandler handler) {
        Principal user = SimpMessageHeaderAccessor.getUser(message.getHeaders());
        if (user != null) {
            LogContext.putUserId(user.getName());
        }
        return message;
    }

    @Override
    public void afterMessageHandled(Message<?> message, MessageChannel channel, MessageHandler handler, Exception ex) {
        MDC.remove(LogContext.USER_ID);
    }
}
