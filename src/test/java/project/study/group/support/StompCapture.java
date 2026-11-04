package project.study.group.support;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서버가 브로커 채널로 내보내는 STOMP 메시지를 직렬화된 JSON 그대로 모은다 (BY-1124). {@code SimpMessagingTemplate}은 payload를 바이트로
 * 바꾼 뒤 브로커 채널에 싣으므로, 여기서 보는 JSON이 소켓으로 나가는 것과 같다 — 초대코드 방 멤버에 새 키가 "아예 없는지"는 record가 아니라 이
 * 바이트로 확인해야 한다.
 *
 * <p>공유 컨텍스트의 채널에 인터셉터를 붙이므로 테스트가 끝나면 {@link #close}로 반드시 뗀다.
 */
public final class StompCapture implements ChannelInterceptor, AutoCloseable {

    /** 나간 메시지 하나. {@code sessionId}는 세션 하나에만 보낸 메시지(SNAPSHOT 등)에만 있다. */
    public record Sent(String destination, String sessionId, JsonNode body) {
        public String type() {
            return body.path("type").asString();
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AbstractSubscribableChannel channel;
    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    private StompCapture(AbstractSubscribableChannel channel) {
        this.channel = channel;
    }

    public static StompCapture on(AbstractSubscribableChannel brokerChannel) {
        StompCapture capture = new StompCapture(brokerChannel);
        brokerChannel.addInterceptor(capture);
        return capture;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel target) {
        if (message.getPayload() instanceof byte[] bytes && bytes.length > 0) {
            sent.add(new Sent(
                    SimpMessageHeaderAccessor.getDestination(message.getHeaders()),
                    SimpMessageHeaderAccessor.getSessionId(message.getHeaders()),
                    JSON.readTree(new String(bytes, StandardCharsets.UTF_8))));
        }
        return message;
    }

    /** 방 토픽으로 나간 것, 나간 순서대로. */
    public List<Sent> topic(long roomId) {
        return to("/topic/room/" + roomId);
    }

    /** 이 사용자의 개인 큐로 나간 것(세션 지정 여부와 상관없이), 나간 순서대로. */
    public List<Sent> queue(long userId) {
        return to("/user/" + userId + "/queue/room");
    }

    /** 이 사용자 개인 큐로 간 것 중 이 type의 마지막 메시지 본문. */
    public JsonNode lastToUser(long userId, String type) {
        return queue(userId).stream()
                .filter(s -> type.equals(s.type()))
                .reduce((first, second) -> second)
                .map(Sent::body)
                .orElseThrow(() -> new AssertionError(userId + "에게 " + type + "이 나가지 않았다: " + sent));
    }

    /** 방 토픽으로 간 것 중 이 type의 마지막 메시지 본문. */
    public JsonNode lastOnTopic(long roomId, String type) {
        return topic(roomId).stream()
                .filter(s -> type.equals(s.type()))
                .reduce((first, second) -> second)
                .map(Sent::body)
                .orElseThrow(() -> new AssertionError(roomId + "번 방에 " + type + "이 나가지 않았다: " + sent));
    }

    public List<Sent> to(String destination) {
        return sent.stream().filter(s -> destination.equals(s.destination())).toList();
    }

    /** 나간 것 전부, 나간 순서대로 — 목적지가 다른 메시지 사이의 순서를 볼 때 쓴다. */
    public List<Sent> all() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    @Override
    public void close() {
        channel.removeInterceptor(this);
    }
}
