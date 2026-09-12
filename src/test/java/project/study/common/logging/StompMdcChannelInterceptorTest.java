package project.study.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/** inbound 채널 실행 스레드에서 핸들러 전후로 MDC가 채워지고 비워지는지만 본다. */
class StompMdcChannelInterceptorTest {

    private final StompMdcChannelInterceptor interceptor = new StompMdcChannelInterceptor();

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static Message<byte[]> sendFrom(Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/room/1/snapshot");
        if (user != null) accessor.setUser(user);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void 핸들러_실행_전에_프린시펄의_userId를_MDC에_싣는다() {
        Message<byte[]> message = sendFrom(() -> "42");

        interceptor.beforeHandle(message, null, null);

        assertThat(MDC.get(LogContext.USER_ID)).isEqualTo("42");
    }

    @Test
    void 프린시펄이_없으면_MDC를_건드리지_않는다() {
        interceptor.beforeHandle(sendFrom(null), null, null);

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }

    @Test
    void 핸들러가_끝나면_userId를_지운다() {
        Message<byte[]> message = sendFrom(() -> "42");
        interceptor.beforeHandle(message, null, null);

        interceptor.afterMessageHandled(message, null, null, null);

        assertThat(MDC.get(LogContext.USER_ID)).isNull();
    }
}
