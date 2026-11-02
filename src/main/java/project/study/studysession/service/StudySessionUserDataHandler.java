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

    /** 세션의 실효 끝. ended_at이 없으면 study_sec로 추정하고, 시작보다 앞서지 않게 한다. */
    private static final String SESSION_END =
            "GREATEST(%1$s.started_at, COALESCE(%1$s.ended_at, %1$s.started_at + COALESCE(%1$s.study_sec, 0) * interval '1 second'))";

    /** 진행 중 기록의 끝은 마지막으로 본 시각이다. */
    private static final String DRAFT_END = "GREATEST(%1$s.started_at, %1$s.last_seen_at)";

    /**
     * 같은 시간을 두 번 세지 않도록 시작이 같거나 기간이 겹치면 충돌이다. 맞닿은 경계(s 시작 = t 끝)는 겹침이 아니다.
     * 길이 0인 세션도 다른 세션 기간의 안쪽이면 겹친다.
     */
    private static final String CONFLICT = """
            EXISTS (SELECT 1 FROM study_session t
                    WHERE t.user_id = :target
                      AND (t.started_at = s.started_at
                           OR (s.started_at < %s AND t.started_at < %s)))""".formatted(SESSION_END.formatted("t"), SESSION_END.formatted("s"));

    /** 옮기는 쪽 진행 중 기록(a)이 남는 쪽 완료 세션이나 진행 중 기록과 시간이 겹치거나 시작이 같은 경우. */
    private static final String DRAFT_CONFLICT = """
            EXISTS (SELECT 1 FROM study_session t
                    WHERE t.user_id = :target
                      AND (t.started_at = a.started_at
                           OR (a.started_at < %s AND t.started_at < %s)))
            OR EXISTS (SELECT 1 FROM active_study_session t
                    WHERE t.user_id = :target
                      AND (t.started_at = a.started_at
                           OR (a.started_at < %s AND t.started_at < %s)))""".formatted(
            SESSION_END.formatted("t"), DRAFT_END.formatted("a"), DRAFT_END.formatted("t"), DRAFT_END.formatted("a"));

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
        // 완료 세션을 옮기기 전에 판단해야 옮긴 세션 때문에 자기 진행 중 기록이 지워지지 않는다
        int droppedDrafts = jdbc.sql(
                        "DELETE FROM active_study_session a WHERE a.user_id = :source AND (" + DRAFT_CONFLICT + ")")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
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
