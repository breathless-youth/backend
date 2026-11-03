package project.study.room.websocket;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import project.study.room.dto.RoomMember;
import project.study.room.dto.SignalPayload;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.service.RoomStateService;
import project.study.room.service.RoomStateService.StateUpdate;

@Controller
@RequiredArgsConstructor
@Slf4j
public class RoomStompHandler {

    // 대조 테스트(WebSocketDocsContractTest)가 문서와 비교하므로 패키지 가시성으로 둔다
    static final Set<String> SIGNAL_KINDS = Set.of("OFFER", "ANSWER", "CANDIDATE");
    static final Set<String> FOCUS_STATES = Set.of("FOCUS", "DISTRACTED");

    private final RoomStateService roomStateService;
    private final RoomMessenger roomMessenger;
    private final SimpMessagingTemplate messagingTemplate;

    // SNAPSHOT 재요청 — 구독 등록 전에 발사된 SNAPSHOT이 증발하는 레이스를 클라 재시도로 복구한다.
    // body는 무시하고, 방 상태를 바꾸지 않으며, 비멤버·옛 세션은 에러 프레임 없이 조용히 무시한다
    // (재시도가 조용히 소진되게). 레이스로 이번 요청이 세션 확정보다 먼저 도착해도 다음 재시도가 성공한다
    @MessageMapping("/room/{roomId}/snapshot")
    public void handleSnapshotRequest(
            @DestinationVariable Long roomId, Principal principal, SimpMessageHeaderAccessor accessor) {
        if (principal == null) return;

        Long userId = Long.valueOf(principal.getName());
        List<RoomMember> members = roomStateService.getMembersForActiveSession(roomId, userId, accessor.getSessionId());
        if (members.isEmpty()) {
            log.debug("snapshot 재요청 무시(비멤버 또는 비활성 세션): roomId={}, userId={}", roomId, userId);
            return;
        }

        log.debug("snapshot 재발송: roomId={}, userId={}, 인원={}", roomId, userId, members.size());
        // 세션 스코프 발송 — 같은 유저의 남은 옛 세션까지 배달되지 않도록 요청 세션에만 보낸다.
        // 헤더 구성은 RoomMessenger 한 곳에만 둔다 (ROOM_UNAVAILABLE과 같은 경로)
        roomMessenger.toSession(
                principal.getName(),
                accessor.getSessionId(),
                Map.of("type", RoomMessageType.SNAPSHOT.name(), "members", members));
    }

    @MessageMapping("/room/{roomId}/signal")
    public void handleSignal(
            @DestinationVariable Long roomId,
            SignalPayload payload,
            Principal principal,
            SimpMessageHeaderAccessor accessor) {
        if (principal == null
                || payload == null
                || payload.toUserId() == null
                || payload.payload() == null
                || payload.kind() == null
                || !SIGNAL_KINDS.contains(payload.kind())) {
            log.debug("signal 요청 형식 검증 실패: roomId={}, principal={}", roomId, principal);
            return;
        }

        Long fromUserId = Long.valueOf(principal.getName());
        // 발신자의 "현재" 세션 + 수신자가 확정 멤버인지를 한 번의 조회로 — 옛 세션·비멤버의 시그널 주입 차단
        if (!roomStateService.authorizeSignal(roomId, fromUserId, accessor.getSessionId(), payload.toUserId())) {
            log.debug("signal 인가 실패: roomId={}, fromUserId={}, toUserId={}", roomId, fromUserId, payload.toUserId());
            return;
        }

        messagingTemplate.convertAndSendToUser(payload.toUserId().toString(), "/queue/room", (Object) Map.of(
                "type", RoomMessageType.SIGNAL.name(),
                "fromUserId", fromUserId,
                "kind", payload.kind(),
                "payload", payload.payload()));
    }

    // 필드별 검증은 SQL 전에 지금처럼 한다 — 무효한 필드만 무시(null)하고 나머지는 반영한다 (스펙 §2.7).
    // 그룹 공부방 필드(micOn·onBreak)가 없으면 지금 문장 그대로 간다 — 초대코드 방·옛 앱의 핫패스는 바뀌지 않는다.
    // 있으면 저장 문장이 방 종류도 함께 돌려주고, 그룹 공부방일 때만 두 필드를 저장·방송한다 (BY-1124, websocket.html §11.3)
    @MessageMapping("/room/{roomId}/state")
    public void handleState(
            @DestinationVariable Long roomId,
            StateUpdatePayload payload,
            Principal principal,
            SimpMessageHeaderAccessor accessor) {
        if (principal == null || payload == null) return;

        Long userId = Long.valueOf(principal.getName());
        StateUpdatePayload change = sanitize(payload);
        boolean groupRoom = false;
        if (change.micOn() == null && change.onBreak() == null) {
            // 갱신 1행 = 현재 세션의 확정 멤버 인가 + 저장. 저장이 성공했을 때만 브로드캐스트한다
            if (!roomStateService.updateState(
                    roomId,
                    userId,
                    accessor.getSessionId(),
                    change.cameraOn(),
                    change.focusState(),
                    change.focusSec())) {
                log.debug("state 인가 실패 또는 갱신 없음: roomId={}, userId={}", roomId, userId);
                return;
            }
        } else {
            StateUpdate result = roomStateService.updateState(roomId, userId, accessor.getSessionId(), change);
            if (!result.saved()) {
                log.debug("state 인가 실패 또는 갱신 없음: roomId={}, userId={}", roomId, userId);
                return;
            }
            groupRoom = result.groupRoom();
        }
        broadcastChanges(roomId, userId, change, groupRoom);
    }

    private static StateUpdatePayload sanitize(StateUpdatePayload payload) {
        String focusState = payload.focusState() != null && FOCUS_STATES.contains(payload.focusState())
                ? payload.focusState()
                : null;
        Integer focusSec = payload.focusSec() != null && payload.focusSec() >= 0 ? payload.focusSec() : null;
        return new StateUpdatePayload(payload.cameraOn(), focusState, focusSec, payload.micOn(), payload.onBreak());
    }

    // 초대코드 방에서 온 micOn·onBreak는 저장하지 않았으므로 알리지도 않는다
    private void broadcastChanges(Long roomId, Long userId, StateUpdatePayload change, boolean groupRoom) {
        String topic = "/topic/room/" + roomId;
        if (change.cameraOn() != null) {
            messagingTemplate.convertAndSend(topic, (Object) Map.of(
                    "type", RoomMessageType.CAMERA_CHANGED.name(), "userId", userId, "cameraOn", change.cameraOn()));
        }
        if (change.focusState() != null) {
            messagingTemplate.convertAndSend(topic, (Object) Map.of(
                    "type", RoomMessageType.FOCUS_CHANGED.name(), "userId", userId, "focusState", change.focusState()));
        }
        if (change.focusSec() != null) {
            messagingTemplate.convertAndSend(topic, (Object)
                    Map.of("type", RoomMessageType.STUDY_TIME.name(), "userId", userId, "focusSec", change.focusSec()));
        }
        if (groupRoom && change.micOn() != null) {
            messagingTemplate.convertAndSend(topic, (Object)
                    Map.of("type", RoomMessageType.MIC_CHANGED.name(), "userId", userId, "micOn", change.micOn()));
        }
        if (groupRoom && change.onBreak() != null) {
            messagingTemplate.convertAndSend(topic, (Object) Map.of(
                    "type", RoomMessageType.BREAK_CHANGED.name(), "userId", userId, "onBreak", change.onBreak()));
        }
    }
}
