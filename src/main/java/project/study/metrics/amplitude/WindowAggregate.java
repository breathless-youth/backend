package project.study.metrics.amplitude;

import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 창 하나의 집계 — 유저×날짜×이벤트 키 횟수와 유저 속성. 파서만 채운다. */
public final class WindowAggregate {

    private final Map<DailyEventKey, Integer> counts = new HashMap<>();
    private final Map<Long, UserAttributeObservation> attributes = new HashMap<>();
    private int eventCount;
    private int anonymous;
    private int malformed;

    public static WindowAggregate empty() {
        return new WindowAggregate();
    }

    void addEvent(long userId, LocalDate eventDate, List<String> eventKeys, UserAttributeObservation attribute) {
        eventCount++;
        for (String eventKey : eventKeys) {
            counts.merge(new DailyEventKey(userId, eventDate, eventKey), 1, Integer::sum);
        }
        attributes.merge(userId, attribute, UserAttributeObservation::merge);
    }

    /** user_id가 없는 이벤트 — 가입 전 웹 방문(초대 링크·스토어 이동 등). 평소 7% 안팎이다. */
    void skipAnonymous() {
        anonymous++;
    }

    /** 깨진 JSON, event_type·event_time 없음·형식 다름, 숫자가 아닌 user_id. 많으면 Export 형식이 바뀐 것이다. */
    void skipMalformed() {
        malformed++;
    }

    public Map<DailyEventKey, Integer> counts() {
        return Collections.unmodifiableMap(counts);
    }

    public Map<Long, UserAttributeObservation> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /** 적재한 이벤트 수(user_id가 있는 것). */
    public int eventCount() {
        return eventCount;
    }

    /** 건너뛴 수 — 익명 + 깨진 줄. */
    public int skipped() {
        return anonymous + malformed;
    }

    int malformed() {
        return malformed;
    }
}
