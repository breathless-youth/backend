package project.study.room.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.room.dto.LegacyRoomCreateRequest;
import project.study.room.dto.LegacyRoomJoinRequest;
import project.study.room.dto.RoomCreateResponse;
import project.study.room.dto.RoomJoinResponse;

/**
 * 구 앱(v1.2.x) 전용 룸 API — API-Version 헤더가 없거나 1인 요청만 여기로 온다 (ADR-0020).
 *
 * <p>v1.2.1 계약 그대로 본문·쿼리의 userId로 식별하고, 토큰 계약 핸들러({@link RoomController})에 userId만 바꿔
 * 위임하는 어댑터라 로직이 없다. 강제 업데이트(BY-531) 뒤 contract 시 이 클래스와 Legacy DTO를 삭제한다.
 */
// Swagger에 싣지 않는다 — springdoc은 같은 경로+메서드를 하나로 합쳐 토큰 계약(v2) 문서를 덮어쓴다.
// 구 앱 계약은 v1.2.1 그대로이며 ADR-0020의 표가 명세다
@Hidden
@RestController
@RequestMapping(value = "/api/rooms", version = "1")
@RequiredArgsConstructor
public class LegacyRoomController {

    private final RoomController roomController;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoomCreateResponse create(@Valid @RequestBody LegacyRoomCreateRequest request) {
        return roomController.create(request.userId());
    }

    @PostMapping("/join")
    public RoomJoinResponse join(@Valid @RequestBody LegacyRoomJoinRequest request) {
        return roomController.join(request.userId(), request.toRequest());
    }

    @PostMapping("/{roomId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable Long roomId, @RequestParam Long userId) {
        roomController.leave(userId, roomId);
    }
}
