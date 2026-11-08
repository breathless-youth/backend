package project.study.group.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import project.study.common.category.StudyCategory;
import project.study.group.dto.GroupCreateRequest;
import project.study.group.dto.GroupCreateResponse;
import project.study.group.dto.GroupHomeResponse;
import project.study.group.dto.GroupNotificationRequest;
import project.study.group.dto.GroupUpdateRequest;
import project.study.group.dto.MyGroupsResponse;
import project.study.group.dto.PublicGroupDetailResponse;
import project.study.group.dto.PublicGroupPageResponse;
import project.study.group.service.GroupService;
import project.study.group.translation.LocalizedGroupReader;

@Tag(name = "그룹", description = """
    그룹 스터디 API — 그룹 만들기·내 그룹·그룹 홈·설정, 공개 그룹 찾기, 멤버 공부 알림 (BY-1124). \
    그룹은 6명까지, 그룹장은 그룹마다 한 명이다.

    - 구 앱 대응이 없는 새 경로라 모두 `API-Version: 1`이고 인증이 필요하다
    - 못 하는 이유는 응답 본문 `{code, message}`의 `code`로만 가른다 — message는 바뀔 수 있다
    - 한도: 들어간 그룹과 답을 기다리는 신청을 합쳐 5개, 신청 3개, 멤버 6명""")
@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
public class GroupController {

    private final GroupService groupService;
    private final LocalizedGroupReader localizedGroups;

    @Operation(summary = "그룹 만들기", description = """
        그룹을 만든다. 만든 사람은 멤버이자 그룹장이 되므로 "그룹+신청 5개" 한도를 본다. \
        공개 여부(`visibility`)는 만든 뒤 바꿀 수 없다. 공개 그룹은 joinPolicy로 신청 후 입장(APPROVAL, 기본값)·바로 입장(OPEN)을 정한다. 이름·소개에 연락처나 링크가 있으면 막는다. \
        공개 그룹은 살아 있는 다른 공개 그룹과 이름이 같으면(공백·대소문자는 무시) 막는다. 비공개 그룹은 이름이 겹쳐도 된다.""")
    @ApiResponse(responseCode = "201", description = "만들어짐 — `{groupId}`")
    @ApiResponse(
            responseCode = "400",
            description = "`VALIDATION_FAILED` 이름 1~20자·소개 0~500자 위반, 필수값 누락, 비공개 그룹의 OPEN / "
                    + "`BAD_REQUEST` 없는 시험·목표·공개 여부·입장 방식 값, 읽을 수 없는 본문 / "
                    + "`CONTACT_NOT_ALLOWED` 이름이나 소개에 연락처·링크가 있음")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(
            responseCode = "409",
            description = "`GROUP_SLOT_LIMIT` 들어간 그룹과 답을 기다리는 신청이 합쳐 이미 5개 / "
                    + "`GROUP_NAME_TAKEN` 같은 이름의 공개 그룹이 이미 있음. 둘 다 걸리면 `GROUP_SLOT_LIMIT`을 먼저 준다")
    @PostMapping(version = "1")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupCreateResponse create(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody GroupCreateRequest request) {
        return new GroupCreateResponse(groupService.create(userId, request));
    }

    @Operation(summary = "내 그룹 탭", description = """
        내가 들어간 그룹 카드(들어간 순서)와 답을 기다리는 신청 수, 그룹장인 그룹 수, 한도를 준다. \
        카드의 멤버 책상으로 지금 누가 공부 중인지 보인다. 화면이 보이는 동안 30초마다, 화면으로 돌아오면 바로 다시 받는 것을 권장한다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @GetMapping(value = "/me", version = "1")
    public ResponseEntity<MyGroupsResponse> myGroups(
            @AuthenticationPrincipal Long userId,
            @RequestHeader(name = "Accept-Language", required = false) String language) {
        return localized(localizedGroups.myGroups(userId, language));
    }

    @Operation(summary = "그룹 홈", description = """
        그룹 정보와 멤버 책상(들어간 순서)을 준다 — 멤버만 볼 수 있다. 이번 주 순위는 `members[].weekFocusSec`로 FE가 그린다. \
        `newApplicationCount`는 APPROVAL 공개 그룹 그룹장에게만 값이 있고 나머지는 null이다. \
        화면이 보이는 동안 30초마다, 화면으로 돌아오면 바로 다시 받는 것을 권장한다.""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @GetMapping(value = "/{groupId}", version = "1")
    public ResponseEntity<GroupHomeResponse> home(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @RequestHeader(name = "Accept-Language", required = false) String language) {
        return localized(localizedGroups.home(userId, groupId, language));
    }

    @Operation(summary = "그룹 설정 수정", description = """
        이름·시험·목표·소개·입장 방식을 바꾼다 — 그룹장만 할 수 있다. 공개 여부는 바꿀 수 없어 받지 않는다. \
        이름·시험·소개는 전체 교체다. joinPolicy는 생략하거나 null이면 유지한다. 공개 그룹만 OPEN으로 바꿀 수 있고, APPROVAL에서 OPEN으로 바꾸면 모든 PENDING 신청을 같은 트랜잭션에서 JOIN_POLICY_OPEN으로 닫으며 알리지 않는다. 이름·소개에 연락처나 링크가 있으면 막는다. \
        공개 그룹의 새 이름이 다른 공개 그룹과 같으면(공백·대소문자는 무시) 막는다. 이름을 바꾸지 않는 수정은 막지 않는다.""")
    @ApiResponse(responseCode = "204", description = "수정됨")
    @ApiResponse(
            responseCode = "400",
            description = "`VALIDATION_FAILED` 이름 1~20자·소개 0~500자 위반, 필수값 누락, 비공개 그룹의 OPEN / "
                    + "`BAD_REQUEST` 없는 시험·목표·입장 방식 값, 읽을 수 없는 본문 / "
                    + "`CONTACT_NOT_ALLOWED` 이름이나 소개에 연락처·링크가 있음")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_OWNER` 그룹장이 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @ApiResponse(responseCode = "409", description = "`GROUP_NAME_TAKEN` 같은 이름의 공개 그룹이 이미 있음")
    @PatchMapping(value = "/{groupId}", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @Valid @RequestBody GroupUpdateRequest request) {
        groupService.update(userId, groupId, request);
    }

    @Operation(summary = "공개 그룹 목록", description = """
        공개 그룹을 찾는다. `query`는 이름에 들어간 글자(대소문자 무시), `category`는 시험·목표 하나로 거른다. 둘 다 생략하면 전체다.

        - 정렬: 다 차지 않은 그룹 → 평균 많은 순 → 그룹 ID 작은 순, 쪽마다 순서가 바뀔 수 있어 FE가 groupId로 중복을 거른다
        - 쪽 번호 방식 — `page`는 0부터 10000까지, `size`는 기본 20·최대 50. 응답은 다음 쪽이 있는지(`hasNext`)만 준다
        - `query`는 앞뒤 공백을 떼고, 비면 검색어가 없는 것으로 본다. `%`·`_`도 그냥 글자다. 100자까지(UTF-16 기준이라 이모지 등은 2자로 센다)
        - 카드의 `weeklyAverageFocusSec`: 지금 멤버들의 이번 주(월요일 0시 KST부터) 앱 전체 순공 합 ÷ 지금 멤버 수. \
        주 중간에 들어온 멤버도 이번 주 순공이 모두 들어가고 나간 멤버는 빠진다. \
        측정 세션만, 1분 미만 조각 제외, 진행 중 포함, 약 1분마다 갱신
        - 내가 멤버인 그룹도 나오며 `member`가 true다""")
    @ApiResponse(responseCode = "200", description = "조회 성공 — 결과가 없으면 `groups`가 빈 배열")
    @ApiResponse(
            responseCode = "400",
            description = "`REQUEST_FAILED` query가 100자 초과(UTF-16 기준이라 이모지 등은 2자), page가 0~10000 밖, size가 1~50 밖 /"
                    + " `BAD_REQUEST` 없는 category 값")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @GetMapping(value = "/public", version = "1")
    public ResponseEntity<PublicGroupPageResponse> publicGroups(
            @AuthenticationPrincipal Long userId,
            @Parameter(
                            description =
                                    "그룹 이름 검색어 — 이름에 들어간 것(대소문자 무시), 100자까지(UTF-16 기준, 이모지 등은 2자). 생략하거나 공백뿐이면 전체",
                            example = "공시")
                    @RequestParam(required = false)
                    @Size(max = 100)
                    String query,
            @Parameter(description = "시험·목표로 거르기 — 하나만. 생략하면 전체", example = "CIVIL_SERVICE")
                    @RequestParam(required = false)
                    StudyCategory category,
            @Parameter(description = "쪽 번호 — 0부터 10000까지", example = "0")
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    @Max(10000)
                    int page,
            @Parameter(description = "쪽 크기 — 1~50", example = "20") @RequestParam(defaultValue = "20") @Min(1) @Max(50)
                    int size,
            @RequestHeader(name = "Accept-Language", required = false) String language) {
        return localized(localizedGroups.publicGroups(userId, query, category, page, size, language));
    }

    @Operation(summary = "공개 그룹 보기", description = """
        신청하기 전에 공개 그룹을 살펴본다 — 소개·그룹장·멤버 책상과 내가 할 수 있는 것(`applyState`)을 준다. \
        비공개 그룹을 열면 404 `GROUP_NOT_FOUND`다. 멤버가 아니어도 볼 수 있다.

        - `applyState`는 `MEMBER` > `APPLIED` > `FULL` > `UNAVAILABLE` > `CAN_APPLY` 순으로 먼저 맞는 것 하나다
        - `UNAVAILABLE`이면 `unavailableReason`이 `RECENTLY_KICKED` > `GROUP_SLOT_LIMIT` > `APPLICATION_LIMIT` 순으로 먼저 맞는 것 하나다
        - `APPLIED`이면 `application`에 내 신청 ID와 만료 시각이 있다
        - `weeklyAverageFocusSec`: 지금 멤버들의 이번 주(월요일 0시 KST부터) 앱 전체 순공 합 ÷ 지금 멤버 수. \
        주 중간에 들어온 멤버도 이번 주 순공이 모두 들어가고 나간 멤버는 빠진다. \
        측정 세션만, 1분 미만 조각 제외, 진행 중 포함, 약 1분마다 갱신""")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹, 또는 비공개 그룹")
    @GetMapping(value = "/public/{groupId}", version = "1")
    public ResponseEntity<PublicGroupDetailResponse> publicGroup(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @RequestHeader(name = "Accept-Language", required = false) String language) {
        return localized(localizedGroups.publicGroup(userId, groupId, language));
    }

    @Operation(summary = "멤버 공부 알림 켜기·끄기", description = """
        이 그룹 멤버가 공부를 시작하면 푸시로 알려 받을지 정한다 — 멤버만 할 수 있고, 처음엔 꺼져 있다. \
        그룹마다 따로 정하며 같은 값을 다시 보내도 그대로 204다.""")
    @ApiResponse(responseCode = "204", description = "바뀜")
    @ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED` `enabled` 누락")
    @ApiResponse(responseCode = "401", description = "토큰 없음·만료 (`UNAUTHORIZED`)")
    @ApiResponse(responseCode = "403", description = "`NOT_GROUP_MEMBER` 이 그룹 멤버가 아님")
    @ApiResponse(responseCode = "404", description = "`GROUP_NOT_FOUND` 없거나 사라진 그룹")
    @PutMapping(value = "/{groupId}/notifications", version = "1")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setNotification(
            @AuthenticationPrincipal Long userId,
            @Parameter(description = "그룹 ID", example = "10") @PathVariable long groupId,
            @Valid @RequestBody GroupNotificationRequest request) {
        groupService.setNotification(userId, groupId, request.enabled());
    }

    private static <T> ResponseEntity<T> localized(T body) {
        return ResponseEntity.ok().varyBy(HttpHeaders.ACCEPT_LANGUAGE).body(body);
    }
}
