package project.study.room.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.handler.annotation.MessageMapping;
import project.study.room.dto.RoomMember;

/**
 * docs/websocket.html이 코드의 계약을 빠짐없이 싣고 있는지 대조한다 (BY-667). 메시지 type·발행 목적지·구독 목적지·
 * 시그널 kind·focusState·RoomMember 필드가 기준이다. 필드 타입과 수치까지는 잡지 못한다 — 그건 리뷰 몫이다.
 */
class WebSocketDocsContractTest {

    private static final String DOC = "docs/websocket.html";
    // 구독 목적지는 코드에서 정규식·리터럴(WebSocketConfig.UserIdChannelInterceptor)이라 여기 상수로 둔다
    private static final List<String> SUBSCRIBE_DESTINATIONS = List.of("/topic/room/{roomId}", "/user/queue/room");

    private static String html;

    @BeforeAll
    static void loadHtml() throws IOException {
        try (InputStream in = WebSocketDocsContractTest.class.getClassLoader().getResourceAsStream(DOC)) {
            assertThat(in).as("%s 가 클래스패스에 있어야 한다", DOC).isNotNull();
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void 서버_메시지_type이_모두_문서에_있다() {
        for (RoomMessageType type : RoomMessageType.values()) {
            String token = "\"type\": \"" + type.name() + "\"";
            assertThat(html).as(missing(token)).contains(token);
        }
    }

    @Test
    void 클라이언트_발행_목적지가_모두_문서에_있다() {
        List<String> mappings = Arrays.stream(RoomStompHandler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(MessageMapping.class))
                .flatMap(
                        m -> Arrays.stream(m.getAnnotation(MessageMapping.class).value()))
                .toList();
        assertThat(mappings).as("@MessageMapping이 하나도 없다").isNotEmpty();
        for (String mapping : mappings) {
            String destination = "/app" + mapping;
            assertThat(html).as(missing(destination)).contains(destination);
        }
    }

    @Test
    void 구독_목적지가_모두_문서에_있다() {
        for (String destination : SUBSCRIBE_DESTINATIONS) {
            assertThat(html).as(missing(destination)).contains(destination);
        }
    }

    @Test
    void 시그널_kind와_focusState_값이_모두_문서에_있다() {
        for (String kind : RoomStompHandler.SIGNAL_KINDS) {
            assertThat(html).as(missing(kind)).contains(kind);
        }
        for (String state : RoomStompHandler.FOCUS_STATES) {
            assertThat(html).as(missing(state)).contains(state);
        }
    }

    @Test
    void RoomMember_필드가_모두_문서에_있다() {
        for (RecordComponent component : RoomMember.class.getRecordComponents()) {
            String token = "\"" + component.getName() + "\"";
            assertThat(html).as(missing(token)).contains(token);
        }
    }

    private static String missing(String token) {
        return "docs/websocket.html에 %s 가 없다 — 계약을 바꿨으면 문서와 변경 이력을 같은 PR에서 갱신한다".formatted(token);
    }
}
