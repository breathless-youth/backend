package project.study.studysession.dto;

import java.time.Instant;

/**
 * 한 사람의 앱 전체 공부 상태와 순공 (BY-1124) — 그룹 멤버 책상이 쓴다. 순공은 랭킹과 같은 기준이다: 측정 세션만, 1분 미만 조각 제외, 확정되지 않은
 * 진행 중 draft 포함(집중 중이면 asOf까지 늘린 값). 오늘은 asOf의 KST 날짜, 이번 주는 그 주 월요일부터 오늘까지다.
 *
 * @param accumulating 지금 순공이 쌓이는 중인지 — 마지막 수신이 60초 안이고 진행 중인 이벤트가 없다(랭킹의 집중 중과 같다)
 * @param lastStudiedAt 앱에서 마지막으로 공부한 때 — 1분 이상 측정 세션의 끝과 draft의 마지막 수신 중 늦은 것. 둘 다 없으면 null
 */
public record MemberStudy(
        long userId,
        Presence presence,
        boolean accumulating,
        long todayFocusSec,
        long weekFocusSec,
        Instant lastStudiedAt) {

    /** 마지막 수신이 가장 최근인 draft로 본 지금 상태. */
    public enum Presence {
        /** draft가 없거나 마지막 수신이 60초보다 오래됐다 */
        IDLE,
        /** 측정 중이다 — 폰·자리 비움 같은 이벤트가 진행 중이어도 세션은 이어지고 있다 */
        STUDYING,
        /** 진행 중인 이벤트가 일시정지다 */
        PAUSED
    }
}
