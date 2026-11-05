package project.study.common.exception;

/**
 * 클라이언트가 분기에 쓰는 기계 판독용 에러 식별자.
 *
 * <p>상태코드만으로는 갈라지지 않는 경우를 구분하기 위해 둔다 — 예를 들어 404 하나에
 * "없는 초대코드 / 소멸된 방 / 없는 사용자"가 섞여 있으면 클라이언트가 안내 문구를 고를 수 없다 (BY-436).
 * 메시지 문구는 언제든 바뀔 수 있으므로 클라이언트는 message가 아니라 이 코드로만 분기한다.
 */
public enum ErrorCode {

    /** 특정 코드를 부여하지 않은 400 */
    BAD_REQUEST,

    /** 요청 본문 검증 실패 (@Valid) */
    VALIDATION_FAILED,

    /** 닉네임에 욕설·성적 표현·혐오 표현이 있거나 운영자로 보이는 이름이다 (400) — 어느 단어에 걸렸는지는 알려주지 않는다 */
    NICKNAME_NOT_ALLOWED,

    /** access 토큰 누락·위조·만료 (401) — 클라이언트는 refresh 후 1회 재시도한다 */
    UNAUTHORIZED,

    /** refresh 토큰 재사용·만료·미존재 (401) — 클라이언트는 저장 토큰을 지우고 기기 재등록으로 복구한다 */
    INVALID_REFRESH_TOKEN,

    /**
     * 소셜 ID 토큰 위조·만료·다른 앱용, nonce 불일치·누락, 지원하지 않는 프로바이더 (400) — 클라이언트는 소셜 로그인부터 다시 한다.
     * 401이 아니다: 앱은 401이면 refresh부터 하므로 우리 토큰 문제가 아닌 실패에 401을 쓰면 헛된 refresh가 돈다
     */
    INVALID_SOCIAL_TOKEN,

    /** 특정 코드를 부여하지 않은 404 */
    NOT_FOUND,

    /** 발급된 적 없는 초대코드 — 오타일 가능성이 높다 */
    INVITE_CODE_NOT_FOUND,

    /** 참가자가 모두 나가거나 만료돼 소멸한 방의 초대코드 */
    ROOM_CLOSED,

    /** 존재하지 않는 사용자 */
    USER_NOT_FOUND,

    /** 특정 코드를 부여하지 않은 409 */
    CONFLICT,

    /** 이미 소셜 계정인 사용자가 link를 불렀다 (409) — 이미 로그인 상태이므로 클라이언트는 로그인 화면을 닫는다 */
    ALREADY_LINKED,

    /** 이 그룹에서 내보내진 지 3일이 안 됐다 (409) */
    RECENTLY_KICKED,

    /** 들어간 그룹과 답을 기다리는 신청이 합쳐 5개다 (409) */
    GROUP_SLOT_LIMIT,

    /** 답을 기다리는 신청이 3곳이다 (409) */
    APPLICATION_LIMIT,

    /** 그룹 멤버가 6명이다 (409) */
    GROUP_FULL,

    /** 그룹장인 그룹이 2개다 (409) */
    OWNED_GROUP_LIMIT,

    /** 이름·소개·신청 소개 글에 연락처나 링크가 있다 (400) */
    CONTACT_NOT_ALLOWED,

    /** 다른 멤버가 있는 그룹장은 나갈 수 없다 (409) */
    OWNER_CANNOT_LEAVE,

    /** 이 그룹에 답을 기다리는 신청이 이미 있다 (409) */
    ALREADY_APPLIED,

    /** 신청하려는 그룹에 이미 멤버로 들어가 있다 (409) */
    ALREADY_MEMBER,

    /** 이미 답했거나 취소·만료된 신청이다 (409) */
    APPLICATION_NOT_PENDING,

    /** 없거나 사라진 그룹, 또는 비공개 그룹을 공개 그룹 보기로 열었다 (404) */
    GROUP_NOT_FOUND,

    /** 그 사람은 이 그룹 멤버가 아니다 — 넘기기·내보내기·신고 대상 (404) */
    GROUP_MEMBER_NOT_FOUND,

    /** 그룹 멤버만 할 수 있다 (403) */
    NOT_GROUP_MEMBER,

    /** 그룹장만 할 수 있다 (403) */
    NOT_GROUP_OWNER,

    /** Spring MVC 표준 4xx (405·415 등) */
    REQUEST_FAILED,

    /** 서버 내부 오류 */
    INTERNAL_ERROR,

    /**
     * 소셜 프로바이더 검증 서버가 응답하지 않거나 5xx를 줬다 (503) — 토큰 문제가 아니므로 클라이언트는 같은 소셜 로그인을 잠시 뒤 다시 시도한다.
     * 400 {@code INVALID_SOCIAL_TOKEN}과 갈라 두는 것은 장애 때 사용자를 소셜 로그인 처음으로 돌려보내지 않기 위해서다
     */
    SOCIAL_PROVIDER_UNAVAILABLE
}
