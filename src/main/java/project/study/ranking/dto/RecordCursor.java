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

    public String encode() {
        String raw = closesAt.getEpochSecond() + ":" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 형식이 틀리면 400. */
    public static RecordCursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = raw.indexOf(':');
            return new RecordCursor(
                    Instant.ofEpochSecond(Long.parseLong(raw.substring(0, separator))),
                    Long.parseLong(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | IndexOutOfBoundsException | DateTimeException e) {
            throw new BadRequestException("cursor 형식이 올바르지 않습니다");
        }
    }
}
