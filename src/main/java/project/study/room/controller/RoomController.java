package project.study.room.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.room.dto.RoomEnterRequest;
import project.study.room.dto.RoomEnterResponse;
import project.study.room.service.RoomService;

@Tag(name = "Room", description = "실시간 공부방 입퇴장 API — WebRTC P2P 시그널링은 STOMP WebSocket(/ws)으로 처리한다")
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    @Operation(summary = "룸 입장", description = """
                    공부방에 입장한다. 서버가 자리를 예약하고 TURN 서버 자격을 발급한다. \
                    이후 30초 내에 STOMP WebSocket으로 `/topic/room/{roomId}`를 구독해야 입장이 확정된다. \
                    다른 룸에 입장 중이면 기존 룸에서 자동 퇴장 처리된다.""")
    @ApiResponse(responseCode = "200", description = "입장 성공 — ICE 서버 정보와 함께 응답")
    @ApiResponse(responseCode = "409", description = "정원 초과 — 룸에 이미 8명이 있다")
    @PostMapping("/{roomId}/enter")
    public RoomEnterResponse enter(@PathVariable Long roomId, @Valid @RequestBody RoomEnterRequest request) {
        RoomService.EnterResult result = roomService.enter(roomId, request.userId());

        if (result.autoLeave() != null) {
            RoomService.AutoLeave al = result.autoLeave();
            messagingTemplate.convertAndSend(
                    "/topic/room/" + al.roomId(), (Object) Map.of("type", "MEMBER_LEFT", "userId", al.userId()));
        }

        return result.response();
    }

    @Operation(summary = "룸 퇴장", description = "공부방에서 명시적으로 퇴장한다. 앱 종료 등으로 호출하지 못해도 WebSocket 끊김 30초 후 자동 퇴장된다.")
    @ApiResponse(responseCode = "204", description = "퇴장 완료")
    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long roomId, @RequestParam Long userId) {
        boolean removed = roomService.leave(roomId, userId);
        if (removed) {
            messagingTemplate.convertAndSend(
                    "/topic/room/" + roomId, (Object) Map.of("type", "MEMBER_LEFT", "userId", userId));
        }
    }
}
