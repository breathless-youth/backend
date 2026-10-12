package project.study.metrics.amplitude;

import java.time.Instant;

/**
 * 한 유저에 대해 본 Amplitude 속성. 값마다 그 값을 본 이벤트 발생 시각을 따로 둔다 — 마지막 이벤트 하나의 시각으로 고르면,
 * 값 없는 늦은 이벤트가 끼었을 때 더 늦게 본 값을 놓친다.
 *
 * @param utmSource 최초 유입 UTM 소스(없으면 null)
 * @param utmSeenAt utmSource를 본 이벤트 발생 시각(값이 없으면 null)
 * @param adClick 광고 클릭 ID를 가지고 들어왔는지
 * @param osName 기기 OS
 * @param osSeenAt osName을 본 이벤트 발생 시각(값이 없으면 null)
 * @param firstSeenAt 처음 본 이벤트 발생 시각
 * @param lastSeenAt 마지막으로 본 이벤트 발생 시각
 */
public record UserAttributeObservation(
        String utmSource,
        Instant utmSeenAt,
        boolean adClick,
        String osName,
        Instant osSeenAt,
        Instant firstSeenAt,
        Instant lastSeenAt) {

    /** 이벤트 하나에서 본 속성. 값이 있는 것만 관측 시각을 붙인다. */
    public static UserAttributeObservation observed(Instant seenAt, String utmSource, boolean adClick, String osName) {
        return new UserAttributeObservation(
                utmSource,
                utmSource == null ? null : seenAt,
                adClick,
                osName,
                osName == null ? null : seenAt,
                seenAt,
                seenAt);
    }

    /** 두 관측을 합친다 — 값마다 더 늦게 본 non-null이 이기고, 광고 클릭은 한 번이라도 있으면 남는다. */
    public UserAttributeObservation merge(UserAttributeObservation other) {
        boolean utmNewer = isNewer(other.utmSeenAt, utmSeenAt);
        boolean osNewer = isNewer(other.osSeenAt, osSeenAt);
        return new UserAttributeObservation(
                utmNewer ? other.utmSource : utmSource,
                utmNewer ? other.utmSeenAt : utmSeenAt,
                adClick || other.adClick,
                osNewer ? other.osName : osName,
                osNewer ? other.osSeenAt : osSeenAt,
                other.firstSeenAt.isBefore(firstSeenAt) ? other.firstSeenAt : firstSeenAt,
                other.lastSeenAt.isAfter(lastSeenAt) ? other.lastSeenAt : lastSeenAt);
    }

    // 같은 시각이면 먼저 있던 값을 둔다 — 저장소 upsert(SQL)와 같은 규칙이라 창 안·창 사이 병합 결과가 같다
    private static boolean isNewer(Instant candidate, Instant current) {
        return candidate != null && (current == null || candidate.isAfter(current));
    }
}
