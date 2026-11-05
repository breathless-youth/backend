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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.group.dto.GroupApplicationCreateRequest;
import project.study.group.dto.GroupApplicationCreateResponse;
import project.study.group.dto.GroupApplicationsResponse;
import project.study.group.dto.MyApplicationsResponse;
import project.study.group.dto.ReportRequest;
import project.study.group.service.GroupApplicationService;

/**
 * 공개 그룹 신청·수락·거절·신고. 경로가 {@code /api/groups/{groupId}/applications}와 {@code /api/group-applications/...}로
 * 갈려 클래스 경로를 두지 않는다. 태그 설명은 {@link GroupController}에만 둔다 — 컨트롤러마다 적으면 springdoc이 합치다 어긋난다.
 */
@Tag(name = "그룹")
@RestController
@RequiredArgsConstructor
public class GroupApplicationController {

    private final GroupApplicationService groupApplicationService;

    @Operation(summary = "그룹에 신청하기", description = """
        APPROVAL 공개 그룹만. OPEN 그룹은 409 `GROUP_JOIN_OPEN`이므로 바로 입장 API를 쓴다. 답이 없으면 3일 뒤 취소된다(`expiresAt`). 비공개 그룹은 신청 없이 초대코드로 들어온다 — 비공개 그룹에 신청하면 공개 그룹 보기처럼 404 `GROUP_NOT_FOUND`이고, 비공개 그룹이 있는지는 알려 주지 않는다. \
        신청하면 그룹장에게 푸시가 가고, 그룹장이 수락하면 멤버가 된다. \
        다 찬 그룹에는 신청할 수 없다(GROUP_FULL). 신청한 뒤에 자리가 차면 신청은 기한까지 남고 수락만 GROUP_FULL로 막힌다. \
        신청은 "들어간 그룹과 답을 기다리는 신청 5개"와 "답을 기다리는 신청 3개" 한도에 들어가고, 같은 그룹에 답을 기다리는 신청은 하나만 둘 수 있다. \
        신청 소개 글에 연락처나 링크가 있으면 막는다. \
        409가 겹치면 GROUP_JOIN_OPEN → ALREADY_MEMBER → ALREADY_APPLIED → RECENTLY_KICKED → GROUP_FULL → GROUP_SLOT_LIMIT → APPLICATION_LIMIT 순서로 먼저 걸린 하나만 준다.""")
    @ApiResponse(responseCode = "201", description = "신청됨 — `{applicationId, expiresAt}`")
    @ApiResponse(
            responseCode = "400",
            description = "`VALIDATION_FAILED` 신청 소개 글 1~50자 위반, 필수값 누락 / "
                    + "`BAD_REQUEST` 읽을 수 없는 본문 / "
                    + "`CONTACT_NOT_ALLOWED` 신청 소개 글에 연락처·링크가 있음")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹, 또는 비공개 그룹")
    @ApiResponse(
            responseCode = "409",
            description = "`GROUP_JOIN_OPEN` 신청 없이 바로 입장하는 그룹 / "
                    + "`ALREADY_MEMBER` 이미 이 그룹의 멤버 / "
                    + "`ALREADY_APPLIED` 이 그룹에 답을 기다리는 신청이 이미 있음 / "
                    + "`RECENTLY_KICKED` 이 그룹에서 내보내진 지 3일이 안 됨 / "
                    + "`GROUP_FULL` 멤버가 이미 6명 / "
                    + "`GROUP_SLOT_LIMIT` 들어간 그룹과 답을 기다리는 신청이 합쳐 이미 5개 / "
                    + "`APPLICATION_LIMIT` 답을 기다리는 신청이 이미 3곳")
    @PostMapping(value = "/api/groups/{groupId}/applications", version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupApplicationCreateResponse apply(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "신청할 그룹 ID", example = "10") @PathVariable long groupId,
            @Valid @RequestBody GroupApplicationCreateRequest request) {
        return groupApplicationService.apply(userId, groupId, request);
    }

    @Operation(summary = "그룹장용 신청 목록", description = """
        이 그룹에서 답을 기다리는 신청을 신청한 순서(오래된 것부터)로 준다 — 그룹장만 볼 수 있다. \
        기한이 지난 신청은 만료 작업이 돌기 전이라도 나오지 않는다. \
        `remainingSeats`가 0이면 수락할 수 없다(`GROUP_FULL`).""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 신청이 없으면 `applications`가 빈 배열")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 그룹장이 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @GetMapping(value = "/api/groups/{groupId}/applications", version = "1")
    public GroupApplicationsResponse applications(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId) {
        return groupApplicationService.applications(userId, groupId);
    }

    @Operation(summary = "내 신청 목록", description = """
        내가 보낸 신청 중 답을 기다리는 것만 신청한 순서(오래된 것부터)로 준다. \
        수락·거절·취소·만료된 신청은 나오지 않고, 기한이 지난 신청은 만료 작업이 돌기 전이라도 나오지 않는다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 신청이 없으면 `applications`가 빈 배열")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @GetMapping(value = "/api/group-applications/me", version = "1")
    public MyApplicationsResponse myApplications(@AuthenticationPrincipal Long userId) {
        return groupApplicationService.myApplications(userId);
    }

    @Operation(summary = "신청 취소", description = """
        답을 기다리는 내 신청을 취소한다 — 신청한 사람만 할 수 있다. 취소하면 "들어간 그룹과 신청 5개"·"신청 3개" 한도에서 바로 빠진다. \
        남의 신청이거나 없는 신청이면 404 `NOT_FOUND`다 — 남의 신청이 있는지는 알려 주지 않는다.""")
    @ApiResponse(responseCode = "204", description = "취소됨")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND` 없는 신청이거나 내 신청이 아님")
    @ApiResponse(responseCode = "409", description = "`APPLICATION_NOT_PENDING` 이미 답했거나 취소·만료된 신청")
    @DeleteMapping(value = "/api/group-applications/{applicationId}", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "신청 ID", example = "55") @PathVariable long applicationId) {
        groupApplicationService.cancel(userId, applicationId);
    }

    @Operation(summary = "신청 수락", description = """
        신청을 수락해 신청한 사람을 멤버로 만든다 — 신청받은 그룹의 그룹장만 할 수 있다. \
        자리가 없으면 GROUP_FULL, 신청은 기한까지 남는다 — 자리가 나면 같은 신청을 다시 수락할 수 있다. \
        수락하면 신청한 사람에게 푸시가 간다.""")
    @ApiResponse(responseCode = "204", description = "수락됨 — 신청한 사람이 멤버가 됨")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 이 신청이 간 그룹의 그룹장이 아님")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND` 없는 신청 — 그룹이 사라진 신청 포함")
    @ApiResponse(
            responseCode = "409",
            description = "`GROUP_FULL` 멤버가 이미 6명 — 신청은 기한까지 남는다 / `APPLICATION_NOT_PENDING` 이미 답했거나 취소·만료된 신청")
    @PostMapping(value = "/api/group-applications/{applicationId}/accept", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void accept(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "신청 ID", example = "55") @PathVariable long applicationId) {
        groupApplicationService.accept(userId, applicationId);
    }

    @Operation(summary = "신청 거절", description = """
        신청을 거절한다 — 신청받은 그룹의 그룹장만 할 수 있다. 신청한 사람에게 거절 푸시가 간다.""")
    @ApiResponse(responseCode = "204", description = "거절됨")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 이 신청이 간 그룹의 그룹장이 아님")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND` 없는 신청 — 그룹이 사라진 신청 포함")
    @ApiResponse(responseCode = "409", description = "`APPLICATION_NOT_PENDING` 이미 답했거나 취소·만료된 신청")
    @PostMapping(value = "/api/group-applications/{applicationId}/decline", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "신청 ID", example = "55") @PathVariable long applicationId) {
        groupApplicationService.decline(userId, applicationId);
    }

    @Operation(summary = "신청 신고", description = """
        부적절한 신청을 신고한다 — 신청받은 그룹의 그룹장만 할 수 있다. \
        신고하면 바로 거절되고 신청한 사람에게는 거절 알림만 간다. 신청한 사람을 차단하지 않는다. \
        서버가 신고를 남기고 운영 채널에 알린다. 사유는 선택이지만 본문은 필수라, 사유가 없으면 `{}`를 보낸다.""")
    @ApiResponse(responseCode = "204", description = "신고되고 거절됨")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` 사유가 100자 초과 / `BAD_REQUEST` 읽을 수 없는 본문")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 이 신청이 간 그룹의 그룹장이 아님")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND` 없는 신청 — 그룹이 사라진 신청 포함")
    @ApiResponse(responseCode = "409", description = "`APPLICATION_NOT_PENDING` 이미 답했거나 취소·만료된 신청")
    @PostMapping(value = "/api/group-applications/{applicationId}/report", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "신청 ID", example = "55") @PathVariable long applicationId,
            @Valid @RequestBody ReportRequest request) {
        groupApplicationService.report(userId, applicationId, request);
    }
}
