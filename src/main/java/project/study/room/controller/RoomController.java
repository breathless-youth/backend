package project.study.room.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.ErrorResponse;
import project.study.room.dto.RoomCreateRequest;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomJoinRequest;
import project.study.room.dto.RoomJoinResponse;
import project.study.room.service.RoomService;

@Tag(name = "Room", description = "실시간 공부방 API — 초대코드 기반 일회성 방. WebRTC 시그널링은 STOMP WebSocket(/ws)으로 처리한다")
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    @Operation(summary = "방 생성", description = """
                    일회성 공부방을 만들고 초대코드(숫자 4자리)를 발급받는다. \
                    **생성만으로는 입장 상태가 아니다** — 생성자도 join으로만 입장한다. \
                    생성 후 10분 내 아무도 입장하지 않으면 방과 코드가 자동 소멸한다.""")
    @ApiResponse(responseCode = "201", description = "생성 성공 — 방 ID와 초대코드")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomCreateResponse create(@Valid @RequestBody RoomCreateRequest request) {
        return roomService.create(request.userId());
    }

    @Operation(summary = "초대코드 입장", description = """
                    초대코드로 방에 입장한다 — 생성자와 초대받은 사람 모두 이 API 하나로 입장한다. \
                    응답은 자리 예약(30초 TTL) + ICE 서버 목록이며, 30초 내에 STOMP로 \
                    `/topic/room/{roomId}`를 구독해야 입장이 확정된다. \
                    이미 다른 방에 입장 중이면 기존 방에서 자동 퇴장 처리된다 (동시 1룸).""")
    @ApiResponse(responseCode = "200", description = "자리 확보 — 카메라 프리뷰 후 STOMP 연결로 확정")
    @ApiResponse(
            responseCode = "400",
            description = "초대코드 형식 위반 — 숫자 4자리가 아님",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = "{\"message\": \"초대코드는 숫자 4자리여야 합니다\"}")))
    @ApiResponse(
            responseCode = "404",
            description = "없는 코드 또는 소멸된 방 (구분하지 않음)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = "{\"message\": \"코드를 다시 확인해 주세요\"}")))
    @ApiResponse(
            responseCode = "409",
            description = "정원 초과 — 방에 이미 6명이 있다 (대기열 없음)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = "{\"message\": \"방이 가득 찼어요\"}")))
    @PostMapping("/join")
    public RoomJoinResponse join(@Valid @RequestBody RoomJoinRequest request) {
        RoomService.JoinResult result = roomService.join(request.userId(), request.inviteCode());

        if (result.autoLeave() != null) {
            RoomService.AutoLeave al = result.autoLeave();
            messagingTemplate.convertAndSend(
                    "/topic/room/" + al.roomId(), (Object) Map.of("type", "MEMBER_LEFT", "userId", al.userId()));
        }

        return result.response();
    }

    @Operation(summary = "방 퇴장", description = """
                    방에서 명시적으로 퇴장한다. 앱 종료 등으로 호출하지 못해도 WebSocket 끊김 30초 후 자동 퇴장된다. \
                    마지막 1명이 나가면 방과 초대코드가 소멸한다.""")
    @ApiResponse(responseCode = "204", description = "퇴장 완료")
    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long roomId, @RequestParam Long userId) {
        boolean removed = roomService.leave(roomId, userId);
        if (removed && roomService.roomExists(roomId)) {
            messagingTemplate.convertAndSend(
                    "/topic/room/" + roomId, (Object) Map.of("type", "MEMBER_LEFT", "userId", userId));
        }
    }
}
