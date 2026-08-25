package project.study.common;

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

    /** Spring MVC 표준 4xx (405·415 등) */
    REQUEST_FAILED,

    /** 서버 내부 오류 */
    INTERNAL_ERROR
}
