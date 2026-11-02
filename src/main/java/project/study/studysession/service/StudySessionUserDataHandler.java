package project.study.studysession.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/**
 * 계정 병합·탈퇴 시 공부 기록 처리. 병합에서는 옮기는 쪽 세션 중 남는 쪽 세션과 시간이 겹치거나 시작 시각이 같은 것을 버리고 나머지를 옮긴다.
 * 과목 구간이 세션을 따라 움직이므로 과목 처리기(order 200)보다 먼저 실행된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StudySessionUserDataHandler implements UserDataHandler {

    /** 반열린 구간. ended_at이 없으면 길이 0(빈 구간)이라 시작 시각이 같은 경우만 충돌로 본다. */
    private static final String SESSION_RANGE =
            "tstzrange(%1$s.started_at, GREATEST(COALESCE(%1$s.ended_at, %1$s.started_at), %1$s.started_at), '[)')";

    private static final String CONFLICT = """
            EXISTS (SELECT 1 FROM study_session t
                    WHERE t.user_id = :target
                      AND (t.started_at = s.started_at OR %s && %s))""".formatted(SESSION_RANGE.formatted("t"), SESSION_RANGE.formatted("s"));

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("study_session.user_id", "active_study_session.user_id");
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        jdbc.sql("DELETE FROM status_event WHERE session_id IN "
                        + "(SELECT s.id FROM study_session s WHERE s.user_id = :source AND " + CONFLICT + ")")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        int dropped = jdbc.sql("DELETE FROM study_session s WHERE s.user_id = :source AND " + CONFLICT)
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        int moved = jdbc.sql("UPDATE study_session SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();

        int droppedDrafts = jdbc.sql("DELETE FROM active_study_session a WHERE a.user_id = :source "
                        + "AND EXISTS (SELECT 1 FROM active_study_session t "
                        + "WHERE t.user_id = :target AND t.started_at = a.started_at)")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        int movedDrafts = jdbc.sql("UPDATE active_study_session SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();

        log.info(
                "study sessions merged: source={}, target={}, sessionsMoved={}, sessionsDropped={}, draftsMoved={}, draftsDropped={}",
                sourceUserId,
                targetUserId,
                moved,
                dropped,
                movedDrafts,
                droppedDrafts);
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM status_event WHERE session_id IN (SELECT id FROM study_session WHERE user_id = :id)")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM study_session WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM active_study_session WHERE user_id = :id")
                .param("id", userId)
                .update();
    }
}
