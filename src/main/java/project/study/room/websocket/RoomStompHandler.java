package project.study.room.websocket;

import java.security.Principal;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import project.study.room.dto.SignalPayload;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.service.RoomService;

@Controller
@RequiredArgsConstructor
public class RoomStompHandler {

    private static final Set<String> SIGNAL_KINDS = Set.of("OFFER", "ANSWER", "CANDIDATE");
    private static final Set<String> FOCUS_STATES = Set.of("FOCUS", "DISTRACTED");

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

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
            return;
        }

        Long fromUserId = Long.valueOf(principal.getName());
        // 발신자의 "현재" 세션에서 온 메시지인지 + 수신자가 방 멤버인지 검사 — 옛 세션·비멤버의 시그널 주입 차단
        if (!roomService.isActiveSession(roomId, fromUserId, accessor.getSessionId())
                || !roomService.isConfirmedMember(roomId, payload.toUserId())) {
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
        if (!roomService.isActiveSession(roomId, userId, accessor.getSessionId())) {
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
        if (studySeconds == null || studySeconds < 0) return;
        messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object)
                Map.of("type", "STUDY_TIME", "userId", userId, "studySeconds", studySeconds));
    }
}
