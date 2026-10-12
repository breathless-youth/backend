package project.study.metrics.amplitude;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Amplitude Export 적재 창 — KST 날짜 하루에 해당하는 수신 시각(server_upload_time) 24시간.
 *
 * <p>Export의 기간은 이벤트 발생 시각이 아니라 Amplitude 서버 수신 시각 기준이라, 창은 수신 시각으로 자르고 이벤트 날짜는
 * 발생 시각으로 매긴다. 늦게 올라온 이벤트는 나중 창에서 제 날짜로 들어간다 (ADR-0030 결정 5).
 *
 * @param date 창이 덮는 KST 날짜
 */
public record ExportWindow(LocalDate date) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // Export 파라미터는 UTC 시 단위 `YYYYMMDDTHH`이고 양끝을 포함한다
    private static final DateTimeFormatter HOUR =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HH").withZone(ZoneOffset.UTC);
    // Amplitude는 수신 후 최대 2시간 뒤에 Export에 내놓는다 — 1시간 여유를 더 둔다
    private static final Duration EXPORT_DELAY = Duration.ofHours(3);

    public Instant startInclusive() {
        return date.atStartOfDay(KST).toInstant();
    }

    public Instant endExclusive() {
        return date.plusDays(1).atStartOfDay(KST).toInstant();
    }

    public String startParam() {
        return HOUR.format(startInclusive());
    }

    public String endParam() {
        return HOUR.format(endExclusive().minus(Duration.ofHours(1)));
    }

    public boolean isReady(Instant now) {
        return !now.isBefore(endExclusive().plus(EXPORT_DELAY));
    }
}
