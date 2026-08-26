package project.study.room.websocket;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import project.study.room.dto.RoomMember;
import project.study.room.dto.SignalPayload;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.service.RoomService;

@Controller
@RequiredArgsConstructor
public class RoomStompHandler {

    private static final Logger log = LoggerFactory.getLogger(RoomStompHandler.class);
    private static final Set<String> SIGNAL_KINDS = Set.of("OFFER", "ANSWER", "CANDIDATE");
    private static final Set<String> FOCUS_STATES = Set.of("FOCUS", "DISTRACTED");

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    // SNAPSHOT 재요청 (BY-442) — 구독 등록 전에 발사된 SNAPSHOT이 증발하는 레이스를 클라 재시도로 복구한다.
    // body는 무시하고, 방 상태를 바꾸지 않으며, 비멤버·옛 세션은 에러 프레임 없이 조용히 무시한다
    // (재시도가 조용히 소진되게). 레이스로 이번 요청이 세션 확정보다 먼저 도착해도 다음 재시도가 성공한다
    @MessageMapping("/room/{roomId}/snapshot")
    public void handleSnapshotRequest(
            @DestinationVariable Long roomId, Principal principal, SimpMessageHeaderAccessor accessor) {
        if (principal == null) return;

        Long userId = Long.valueOf(principal.getName());
        List<RoomMember> members = roomService.getMembersForActiveSession(roomId, userId, accessor.getSessionId());
        if (members.isEmpty()) {
            log.debug("snapshot 재요청 무시(비멤버 또는 비활성 세션): roomId={}, userId={}", roomId, userId);
            return;
        }

        log.debug("snapshot 재발송: roomId={}, userId={}, 인원={}", roomId, userId, members.size());
        // 세션 스코프 발송 — 같은 유저의 남은 옛 세션까지 배달되지 않도록 요청 세션에만 보낸다
        SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setSessionId(accessor.getSessionId());
        headers.setLeaveMutable(true);
        messagingTemplate.convertAndSendToUser(
                principal.getName(),
                "/queue/room",
                Map.of("type", "SNAPSHOT", "members", members),
                headers.getMessageHeaders());
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
        log.debug(
                "signal 요청: roomId={}, fromUserId={}, toUserId={}, kind={}",
                roomId,
                fromUserId,
                payload.toUserId(),
                payload.kind());
        // 발신자의 "현재" 세션에서 온 메시지인지 + 수신자가 방 멤버인지 검사 — 옛 세션·비멤버의 시그널 주입 차단
        if (!roomService.isActiveSession(roomId, fromUserId, accessor.getSessionId())
                || !roomService.isConfirmedMember(roomId, payload.toUserId())) {
            log.debug(
                    "signal 인가 실패(비활성 세션 또는 비멤버): roomId={}, fromUserId={}, toUserId={}",
                    roomId,
                    fromUserId,
                    payload.toUserId());
            return;
        }

        messagingTemplate.convertAndSendToUser(payload.toUserId().toString(), "/queue/room", (Object) Map.of(
                "type", "SIGNAL", "fromUserId", fromUserId, "kind", payload.kind(), "payload", payload.payload()));
    }

    @MessageMapping("/room/{roomId}/state")
    public void handleState(
            @DestinationVariable Long roomId,
            StateUpdatePayload payload,
            Principal principal,
            SimpMessageHeaderAccessor accessor) {
        if (principal == null || payload == null) return;

        Long userId = Long.valueOf(principal.getName());
        log.debug(
                "state 요청: roomId={}, userId={}, cameraOn={}, focusState={}, studySeconds={}",
                roomId,
                userId,
                payload.cameraOn(),
                payload.focusState(),
                payload.studySeconds());
        if (!roomService.isActiveSession(roomId, userId, accessor.getSessionId())) {
            log.debug("state 인가 실패(비활성 세션): roomId={}, userId={}", roomId, userId);
            return;
        }

        broadcastCameraChange(roomId, userId, payload.cameraOn());
        broadcastFocusChange(roomId, userId, payload.focusState());
        broadcastStudyTime(roomId, userId, payload.studySeconds());
    }

    private void broadcastCameraChange(Long roomId, Long userId, Boolean cameraOn) {
        if (cameraOn == null || !roomService.updateCamera(roomId, userId, cameraOn)) return;
        messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object)
                Map.of("type", "CAMERA_CHANGED", "userId", userId, "cameraOn", cameraOn));
    }

    private void broadcastFocusChange(Long roomId, Long userId, String focusState) {
        if (focusState == null
                || !FOCUS_STATES.contains(focusState)
                || !roomService.updateFocusState(roomId, userId, focusState)) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object)
                Map.of("type", "FOCUS_CHANGED", "userId", userId, "focusState", focusState));
    }

    private void broadcastStudyTime(Long roomId, Long userId, Integer studySeconds) {
        // camera/focus와 동일하게 저장이 성공했을 때만 브로드캐스트 — 마지막 값은 SNAPSHOT에 실린다
        if (studySeconds == null || studySeconds < 0 || !roomService.updateStudyTime(roomId, userId, studySeconds)) {
            return;
        }
        messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object)
                Map.of("type", "STUDY_TIME", "userId", userId, "studySeconds", studySeconds));
    }
}
