package project.study.metrics.dto;

import java.util.List;

/**
 * 소셜(룸) 세션과 싱글 세션의 순공시간 비교 — 10분 이상 세션만 대상.
 */
public record RoomVsSingleFocus(FocusStat social, FocusStat single) {

    public static RoomVsSingleFocus from(List<QualifyingSession> sessions) {
        return new RoomVsSingleFocus(
                FocusStat.of(sessions.stream().filter(QualifyingSession::social).toList()),
                FocusStat.of(
                        sessions.stream().filter(session -> !session.social()).toList()));
    }
}
