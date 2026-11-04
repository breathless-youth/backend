package project.study.group.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import project.study.room.dto.RoomJoinResponse.IceServer;

/** 그룹 공부방 자리 잡기 응답 — 초대코드 방의 {@code RoomJoinResponse}와 같은 30초 예약이고, 마이크·휴식 값이 더해졌다. */
public record GroupRoomJoinResponse(
        @Schema(
                description = "공부방 roomId — `/topic/room/{roomId}`를 구독하고 `/app/room/{roomId}/...`로 보낸다",
                example = "123")
        long roomId,

        @Schema(
                description = "같은 공부방에 내 자리가 아직 살아 있어(끊김 유예 중 등) 다시 들어온 경우 true. "
                        + "새로 들어오거나, 이미 확정된 멤버가 ICE 자격을 갱신하려고 다시 부른 경우는 false다 — false가 자리를 잃었다는 뜻은 아니다",
                example = "false")
        boolean graceRejoin,

        @Schema(description = "카메라 켬 여부 — `graceRejoin`이 true일 때만 이전 값이고 아니면 null", example = "true")
        Boolean cameraOn,

        @Schema(description = "마이크 켬 여부 — `graceRejoin`이 true일 때만 이전 값이고 아니면 null (새로 들어오면 꺼진 상태다)", example = "false")
        Boolean micOn,

        @Schema(
                description = "휴식(뽀모도로) 중인지 — `graceRejoin`이 true일 때만 이전 값이고 아니면 null (새로 들어오면 휴식 아님)",
                example = "false")
        Boolean onBreak,

        @Schema(description = "WebRTC ICE 서버(STUN·TURN) 목록 — 각 항목 `{urls, username, credential}`을 연결 설정에 그대로 넣는다")
        List<IceServer> iceServers,

        @Schema(
                description = "`iceServers` 자격의 유효 시간(초) — 환경마다 다르다. 지나기 전에 다시 호출해 새 자격을 받는다 (자리는 유지된다)",
                example = "86400")
        int iceTtlSeconds) {}
