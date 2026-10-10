package project.study.ranking.dto;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import project.study.common.exception.BadRequestException;

/**
 * 기록 목록의 다음 페이지 위치 (BY-828) — (closesAt, id) 내림차순의 마지막 항목. FE에는 불투명한 문자열로 준다. 마감 시각은
 * 정각(00시·04시)이라 초 단위로 담는다.
 */
public record RecordCursor(Instant closesAt, long id) {

    /** 1970-01-01T00:00:00Z ~ 9999-12-31T23:59:59Z — 이 밖의 시각은 쿼리 파라미터로 못 쓴다. */
    private static final long MIN_EPOCH_SECOND = 0;

    private static final long MAX_EPOCH_SECOND = 253_402_300_799L;

    public String encode() {
        String raw = closesAt.getEpochSecond() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 형식이 틀리면 400. */
    public static RecordCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            long epochSecond = Long.parseLong(raw.substring(0, separator));
            if (epochSecond < MIN_EPOCH_SECOND || epochSecond > MAX_EPOCH_SECOND) {
                throw invalid();
            }
            return new RecordCursor(Instant.ofEpochSecond(epochSecond), Long.parseLong(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeException e) {
            throw invalid();
        }
    }

    private static BadRequestException invalid() {
        return new BadRequestException("cursor 형식이 올바르지 않습니다");
    }
}
