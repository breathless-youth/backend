package project.study.room.websocket;

import java.security.Principal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import project.study.room.dto.SignalPayload;
import project.study.room.dto.StateUpdatePayload;
import project.study.room.service.RoomService;

@Controller
@RequiredArgsConstructor
public class RoomStompHandler {

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/room/{roomId}/signal")
    public void handleSignal(@DestinationVariable Long roomId, SignalPayload payload, Principal principal) {
        if (principal == null || payload.toUserId() == null) return;

        Long fromUserId = Long.valueOf(principal.getName());
        messagingTemplate.convertAndSendToUser(payload.toUserId().toString(), "/queue/room", (Object) Map.of(
                "type", "SIGNAL", "fromUserId", fromUserId, "kind", payload.kind(), "payload", payload.payload()));
    }

    @MessageMapping("/room/{roomId}/state")
    public void handleState(@DestinationVariable Long roomId, StateUpdatePayload payload, Principal principal) {
        if (principal == null) return;

        Long userId = Long.valueOf(principal.getName());
        String destination = "/topic/room/" + roomId;

        if (payload.cameraOn() != null) {
            roomService.updateCamera(roomId, userId, payload.cameraOn());
            messagingTemplate.convertAndSend(destination, (Object)
                    Map.of("type", "CAMERA_CHANGED", "userId", userId, "cameraOn", payload.cameraOn()));
        }

        if (payload.focusState() != null) {
            roomService.updateFocusState(roomId, userId, payload.focusState());
            messagingTemplate.convertAndSend(destination, (Object)
                    Map.of("type", "FOCUS_CHANGED", "userId", userId, "focusState", payload.focusState()));
        }

        if (payload.studySeconds() != null) {
            messagingTemplate.convertAndSend(destination, (Object)
                    Map.of("type", "STUDY_TIME", "userId", userId, "studySeconds", payload.studySeconds()));
        }
    }
}
