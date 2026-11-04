package project.study.common.time;

import java.time.ZoneId;

/**
 * 시간대를 한 번도 보내지 않은 사용자와 그 세션의 기본 시간대.
 *
 * 컬럼 기본값('Asia/Seoul')과 같아야 옛 사용자의 결과가 예전과 같다.
 * 엔티티와 서비스가 함께 읽으므로 공통 패키지에 둔다.
 */
public final class DefaultTimeZone {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private DefaultTimeZone() {}
}
