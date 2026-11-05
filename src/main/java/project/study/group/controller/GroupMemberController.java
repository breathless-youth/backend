package project.study.group.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.group.dto.GroupInviteResponse;
import project.study.group.dto.GroupJoinRequest;
import project.study.group.dto.GroupJoinResponse;
import project.study.group.dto.GroupMembersResponse;
import project.study.group.dto.GroupOwnerTransferRequest;
import project.study.group.dto.GroupRoomJoinResponse;
import project.study.group.dto.ReportRequest;
import project.study.group.service.GroupMemberService;

/** 그룹 멤버·초대·공부방 자리·멤버 신고. 태그 설명은 {@link GroupController}에만 둔다 — 컨트롤러마다 적으면 springdoc이 합치다 어긋난다. */
@Tag(name = "그룹")
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupMemberController {

    private final GroupMemberService groupMemberService;

    @Operation(summary = "멤버 목록", description = """
                    그룹 멤버를 들어간 순서로 준다 — 멤버만 볼 수 있다. 지금 공부 중인지 같은 상태는 그룹 홈의 멤버 책상이 준다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @GetMapping(value = "/{groupId}/members", version = "1")
    public GroupMembersResponse members(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        return groupMemberService.members(userId, groupId);
    }

    @Operation(summary = "그룹장 넘기기", description = """
                    그룹장을 다른 멤버에게 넘긴다 — 그룹장만 할 수 있다. 넘기면 나는 일반 멤버가 되고, 새 그룹장에게 푸시가 간다. \
                    공부방이 열려 있으면 방 토픽에 `OWNER_CHANGED`가 나간다 (공부방 메시지는 /docs/websocket.html).""")
    @ApiResponse(responseCode = "204", description = "넘겨짐")
    @ApiResponse(
            responseCode = "400",
            description = "`VALIDATION_FAILED` `userId` 누락 / `BAD_REQUEST` 읽을 수 없는 본문, 숫자가 아닌 userId / "
                    + "`BAD_REQUEST` 대상이 자기 자신")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 그룹장이 아님")
    @ApiResponse(
            responseCode = "404",
            description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹 / `GROUP_MEMBER_NOT_FOUND` 넘길 대상이 이 그룹 멤버가 아님")
    @PutMapping(value = "/{groupId}/owner", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transferOwner(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @Valid @RequestBody GroupOwnerTransferRequest request) {
        groupMemberService.transferOwner(userId, groupId, request.userId());
    }

    @Operation(summary = "멤버 내보내기", description = """
                    멤버를 그룹에서 내보낸다 — 그룹장만 할 수 있고, 그룹 홈과 공부방 모두 이 경로를 쓴다. 자기 자신은 내보낼 수 없다(나가려면 나가기). \
                    바로 멤버가 아니게 되고, 내보내진 사람은 3일 동안 같은 그룹에 신청·코드 참여를 할 수 없다(409 `RECENTLY_KICKED`). \
                    비공개 그룹이면 같은 처리에서 초대코드가 새로 발급되고, 예전 코드·링크로 참여하면 INVITE_CODE_NOT_FOUND다. \
                    응답 형식은 그대로이고 FE는 초대코드 보기(GET /api/groups/{groupId}/invite)를 다시 받아 새 코드를 보여 준다. \
                    내보내진 사람은 새 코드를 받아도 3일 동안 RECENTLY_KICKED다. \
                    내보내진 사람에게 푸시가 가고, 공부방에 있었다면 개인 큐로 `KICKED`, 방 토픽에 `MEMBER_LEFT`가 나간다 (공부방 메시지는 /docs/websocket.html).""")
    @ApiResponse(responseCode = "204", description = "내보내짐")
    @ApiResponse(responseCode = "400", description = "`BAD_REQUEST` 자기 자신을 내보내려 함")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 그룹장이 아님")
    @ApiResponse(
            responseCode = "404",
            description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹 / `GROUP_MEMBER_NOT_FOUND` 내보낼 대상이 이 그룹 멤버가 아님")
    @DeleteMapping(value = "/{groupId}/members/{userId}", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void kick(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @Parameter(description = "내보낼 멤버의 userId", example = "42") @PathVariable("userId") long targetUserId) {
        groupMemberService.kick(userId, groupId, targetUserId);
    }

    @Operation(summary = "그룹 나가기", description = """
                    그룹에서 나간다 — 멤버만 할 수 있다. 다른 멤버가 있는 그룹장은 나갈 수 없다(409 `OWNER_CANNOT_LEAVE`) — 먼저 그룹장을 넘긴다. \
                    혼자 남은 그룹장이 나가면 그룹이 사라지고, 그 그룹에 답을 기다리던 신청은 닫혀 신청한 사람에게 푸시가 간다. \
                    공부방에 있었다면 자리가 지워지고 방 토픽에 `MEMBER_LEFT`가 나간다 (공부방 메시지는 /docs/websocket.html).""")
    @ApiResponse(responseCode = "204", description = "나감")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @ApiResponse(responseCode = "409", description = "`OWNER_CANNOT_LEAVE` 다른 멤버가 있는 그룹장")
    @PostMapping(value = "/{groupId}/leave", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        groupMemberService.leave(userId, groupId);
    }

    @Operation(summary = "초대코드 보기", description = """
                    비공개 그룹의 지금 초대코드를 준다 — 멤버만 볼 수 있다. 초대 링크는 서버가 만들지 않고 FE가 이 코드로 만든다. \
                    공개 그룹에는 초대코드가 없어 400이다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "400", description = "`BAD_REQUEST` 공개 그룹이라 초대코드가 없음")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @GetMapping(value = "/{groupId}/invite", version = "1")
    public GroupInviteResponse invite(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        return groupMemberService.invite(userId, groupId);
    }

    @Operation(summary = "초대코드 새로 만들기", description = """
                    비공개 그룹의 초대코드를 새로 만든다 — 그룹장만 할 수 있다. 이전 코드는 바로 막혀, 이전 코드로 들어오려 하면 404 `INVITE_CODE_NOT_FOUND`다. \
                    공개 그룹에는 초대코드가 없어 400이다.""")
    @ApiResponse(responseCode = "200", description = "새 코드가 만들어짐")
    @ApiResponse(responseCode = "400", description = "`BAD_REQUEST` 공개 그룹이라 초대코드가 없음")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 그룹장이 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @PostMapping(value = "/{groupId}/invite", version = "1")
    public GroupInviteResponse regenerateInvite(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        return groupMemberService.regenerateInvite(userId, groupId);
    }

    @Operation(summary = "초대코드·링크로 들어오기", description = """
                    비공개 그룹의 초대코드로 멤버가 된다 — 코드를 입력하든 링크를 누르든 이 API 하나다(`via`로만 구분). \
                    **이미 멤버면 409 대신 joined=false로 200**을 준다 — 링크를 두 번 눌러도 그룹 홈으로 보내면 된다. \
                    이미 멤버인지를 내보냄·정원·한도 검사보다 먼저 본다 — 이미 멤버면 RECENTLY_KICKED·GROUP_FULL·GROUP_SLOT_LIMIT 대신 joined=false로 200이다. \
                    코드는 영문 대문자·숫자 6자리(0·O·1·I·L 제외), 대소문자 무시이고 서버는 형식을 따로 검사하지 않는다 — 없는 코드·틀린 형식·새로 만들어져 막힌 코드·사라진 그룹의 코드는 모두 404 `INVITE_CODE_NOT_FOUND`다. \
                    들어가면 내보낸 지 3일, "멤버 6명", "들어간 그룹과 답을 기다리는 신청 5개" 순서로 본다. \
                    409가 여러 개 걸리면 RECENTLY_KICKED → GROUP_FULL → GROUP_SLOT_LIMIT 순으로 먼저 걸린 하나를 준다.""")
    @ApiResponse(
            responseCode = "200",
            description = "`joined` true — 멤버가 됨 / `joined` false — 이미 멤버였음. 둘 다 `groupId`의 그룹 홈으로 보낸다")
    @ApiResponse(
            responseCode = "400",
            description = "`VALIDATION_FAILED` `inviteCode`가 비었거나 `via` 누락 / `BAD_REQUEST` 없는 `via` 값, 읽을 수 없는 본문")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "404", description = "`INVITE_CODE_NOT_FOUND` 없거나 막힌 초대코드")
    @ApiResponse(
            responseCode = "409",
            description = "`RECENTLY_KICKED` 이 그룹에서 내보내진 지 3일이 안 됨 / "
                    + "`GROUP_FULL` 멤버가 이미 6명 / "
                    + "`GROUP_SLOT_LIMIT` 들어간 그룹과 답을 기다리는 신청이 합쳐 이미 5개")
    @PostMapping(value = "/join", version = "1")
    public GroupJoinResponse join(@AuthenticationPrincipal Long userId, @Valid @RequestBody GroupJoinRequest request) {
        return groupMemberService.join(userId, request);
    }

    @Operation(summary = "그룹 공부방 자리 잡기", description = """
                    그룹 공부방에 자리를 잡는다 — 멤버만 할 수 있다. 응답은 자리 예약(30초) + ICE 서버 목록이며, \
                    **응답의 roomId로 /topic/room/{roomId}를 구독한다 — 공부방 메시지는 /docs/websocket.html**. \
                    30초 안에 구독해야 입장이 확정된다. 이미 다른 방에 있으면 기존 방에서 자동 퇴장한다(동시 1룸). \
                    그룹마다 열린 공부방은 하나이고, 마지막 사람이 나가면 닫혀 다음 입장 때 새로 열린다.""")
    @ApiResponse(responseCode = "200", description = "자리 확보 — 카메라 프리뷰 뒤 STOMP 연결로 확정")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @PostMapping(value = "/{groupId}/room/join", version = "1")
    public GroupRoomJoinResponse joinRoom(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        return groupMemberService.joinRoom(userId, groupId);
    }

    @Operation(summary = "그룹 공부방 나가기", description = """
                    그룹 공부방에서 나간다 — 멤버만 할 수 있다. 앱 종료 등으로 호출하지 못해도 WebSocket 끊김 30초 뒤 자동으로 나간다. \
                    공부방에 자리가 없어도 204이고, 마지막 사람이 나가면 공부방이 닫힌다. 그룹 멤버 자격은 그대로다.""")
    @ApiResponse(responseCode = "204", description = "나감")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @PostMapping(value = "/{groupId}/room/leave", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leaveRoom(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        groupMemberService.leaveRoom(userId, groupId);
    }

    @Operation(summary = "공부방 멤버 신고", description = """
                    같은 그룹 멤버를 신고한다 — 멤버만 할 수 있다. **신고하면 차단도 된다**(이미 차단했으면 그대로). \
                    서버가 신고를 남기고 운영 채널에 알린다. 차단된 두 사람 사이는 다음 공부방 입장부터 영상·소리가 오가지 않고, \
                    같은 공부방에 있으면 두 사람 각자의 개인 큐로 `PEER_BLOCKED`가 나가 이미 맺은 연결도 닫힌다 (공부방 메시지는 /docs/websocket.html). \
                    그룹 멤버 자격·책상·순위는 그대로다. 사유는 선택이지만 본문은 필수라, 사유가 없으면 `{}`를 보낸다.""")
    @ApiResponse(responseCode = "204", description = "신고되고 차단됨")
    @ApiResponse(
            responseCode = "400",
            description = "`VALIDATION_FAILED` 사유가 100자 초과 / `BAD_REQUEST` 읽을 수 없는 본문 / `BAD_REQUEST` 대상이 자기 자신")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 내가 이 그룹 멤버가 아님")
    @ApiResponse(
            responseCode = "404",
            description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹 / `GROUP_MEMBER_NOT_FOUND` 신고할 대상이 이 그룹 멤버가 아님")
    @PostMapping(value = "/{groupId}/members/{userId}/report", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportMember(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @Parameter(description = "신고할 멤버의 userId", example = "42") @PathVariable("userId") long targetUserId,
            @Valid @RequestBody ReportRequest request) {
        groupMemberService.reportMember(userId, groupId, targetUserId, request);
    }
}
