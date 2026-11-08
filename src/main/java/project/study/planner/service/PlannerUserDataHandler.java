package project.study.planner.service;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.subject.repository.StudySubjectLockRepository;
import project.study.user.account.UserDataHandler;

/** 과목 이름 병합(order 200) 전에 계획의 과목 참조도 같은 대상으로 옮긴다. */
@Component
@RequiredArgsConstructor
public class PlannerUserDataHandler implements UserDataHandler {
    private final JdbcClient jdbc;
    private final StudySubjectLockRepository locks;

    @Override
    public Set<String> columns() {
        return Set.of("planner_series.user_id", "planner_day_subject.user_id");
    }

    @Override
    public int order() {
        return 190;
    }

    @Override
    public void lockBeforeUsers(long userId) {
        locks.lockUser(userId);
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        Map<String, Long> target = new HashMap<>();
        liveSubjects(targetUserId).forEach(s -> target.putIfAbsent(normalize(s.name()), s.id()));
        for (Subject source : liveSubjects(sourceUserId)) {
            Long keep = target.get(normalize(source.name()));
            if (keep != null) {
                jdbc.sql("UPDATE planner_series SET subject_id=:keep WHERE user_id=:u AND subject_id=:s")
                        .param("keep", keep)
                        .param("u", sourceUserId)
                        .param("s", source.id())
                        .update();
                jdbc.sql(
                                "UPDATE planner_record_edit SET subject_id=:keep, revision=revision+1 WHERE user_id=:u AND subject_id=:s")
                        .param("keep", keep)
                        .param("u", targetUserId)
                        .param("s", source.id())
                        .update();
                // 분 범위 수정본의 조각 과목도 같은 기준으로 합친다(BY-1204).
                jdbc.sql("""
                                UPDATE planner_record_edit SET pieces = (
                                    SELECT jsonb_agg(CASE WHEN p->'subjectId' = to_jsonb(CAST(:s AS bigint))
                                        THEN jsonb_set(p, '{subjectId}', to_jsonb(CAST(:keep AS bigint))) ELSE p END
                                        ORDER BY ord)
                                    FROM jsonb_array_elements(pieces) WITH ORDINALITY AS t(p, ord)),
                                    revision = revision + 1
                                WHERE user_id=:u AND pieces @> jsonb_build_array(jsonb_build_object('subjectId', CAST(:s AS bigint)))
                                """)
                        .param("keep", keep)
                        .param("u", targetUserId)
                        .param("s", source.id())
                        .update();
                moveSelections(sourceUserId, targetUserId, source.id(), keep);
            } else {
                target.put(normalize(source.name()), source.id());
            }
        }
        jdbc.sql(
                        "INSERT INTO planner_day_subject SELECT :t,plan_date,subject_id,sort_order FROM planner_day_subject WHERE user_id=:s ON CONFLICT DO NOTHING")
                .param("s", sourceUserId)
                .param("t", targetUserId)
                .update();
        jdbc.sql("DELETE FROM planner_day_subject WHERE user_id=:s")
                .param("s", sourceUserId)
                .update();
        jdbc.sql("UPDATE planner_series SET user_id=:t WHERE user_id=:s")
                .param("s", sourceUserId)
                .param("t", targetUserId)
                .update();
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM planner_day_subject WHERE user_id=:u")
                .param("u", userId)
                .update();
        jdbc.sql("DELETE FROM planner_series WHERE user_id=:u")
                .param("u", userId)
                .update();
    }

    private void moveSelections(long sourceUser, long targetUser, long subject, long keep) {
        jdbc.sql(
                        "INSERT INTO planner_day_subject SELECT :target,plan_date,:keep,sort_order FROM planner_day_subject WHERE user_id=:source AND subject_id=:subject ON CONFLICT DO NOTHING")
                .param("source", sourceUser)
                .param("target", targetUser)
                .param("subject", subject)
                .param("keep", keep)
                .update();
        jdbc.sql("DELETE FROM planner_day_subject WHERE user_id=:u AND subject_id=:s")
                .param("u", sourceUser)
                .param("s", subject)
                .update();
    }

    private record Subject(long id, String name) {}

    private java.util.List<Subject> liveSubjects(long userId) {
        return jdbc.sql(
                        "SELECT id,name FROM study_subject WHERE user_id=:u AND deleted_at IS NULL ORDER BY sort_order,id")
                .param("u", userId)
                .query((rs, n) -> new Subject(rs.getLong(1), rs.getString(2)))
                .list();
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name.strip(), Normalizer.Form.NFC);
    }
}
