package project.study.studysession.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.studysession.dto.NewDraft;

/**
 * 새 draft를 알림 대상으로 꺼내는 문장 (BY-1124). 하트비트 UPSERT(버퍼·{@link ActiveStudySessionRepository})는 {@code start_notified}를
 * 쓰지 않아 새 행은 열 기본값(false)으로 들어오고, 여기서만 true로 바뀐다 — 체크포인트 경로에 아무것도 더하지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class NewDraftQueries {

    private final JdbcClient jdbc;

    /**
     * 아직 꺼내지 않은 draft를 전부 꺼낸 것으로 표시하고, 그중 알릴 만한 것만 돌려준다. 표시는 한 문장의 조건부 UPDATE라 여러 태스크가 동시에
     * 불러도 각 행은 한 태스크만 가져간다(READ COMMITTED에서 기다린 쪽은 바뀐 행을 다시 보고 건너뛴다).
     *
     * <p>돌려주지 않는 행도 표시는 한다 — 늦게 올라온 draft({@code started_at < freshSince})나 이미 확정된 세션(같은 사용자·{@code
     * submission_started_at = started_at})의 draft가 뒤늦은 하트비트로 되살아난 것은 "지금 시작한 공부"가 아니다. 표시하지 않으면 매번 다시
     * 걸러야 한다.
     */
    public List<NewDraft> claim(Instant freshSince) {
        return jdbc.sql("""
                        WITH claimed AS (
                            UPDATE active_study_session SET start_notified = true
                             WHERE NOT start_notified
                            RETURNING user_id, started_at)
                        SELECT c.user_id, c.started_at
                          FROM claimed c
                         WHERE c.started_at >= :freshSince
                           AND NOT EXISTS (
                               SELECT 1 FROM study_session s
                                WHERE s.user_id = c.user_id AND s.submission_started_at = c.started_at)""")
                .param("freshSince", Timestamp.from(freshSince))
                .query((rs, n) -> new NewDraft(
                        rs.getLong("user_id"), rs.getTimestamp("started_at").toInstant()))
                .list();
    }
}
