package project.study.metrics.amplitude;

import java.time.LocalDate;

/**
 * 적재 횟수의 키.
 *
 * @param userId 우리 DB userId (Amplitude user_id)
 * @param eventDate 이벤트 발생 시각의 KST 날짜
 * @param eventKey event_type 또는 속성값 파생 키("event_type:값")
 */
public record DailyEventKey(long userId, LocalDate eventDate, String eventKey) {}
