package project.study.subject.service;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.user.account.UserDataHandler;

/**
 * 계정 병합·탈퇴 시 과목·할 일·완료 기록 처리. 이름(NFC + 앞뒤 공백 제거)이 같은 살아 있는 과목은 남는 쪽 과목으로 합치고,
 * 나머지는 남는 쪽 과목 뒤 순서로 옮긴다. 세션(order 100)이 먼저 옮겨져 있어야 하므로 order 200.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubjectUserDataHandler implements UserDataHandler {

    private final JdbcClient jdbc;

    @Override
    public Set<String> columns() {
        return Set.of("study_subject.user_id", "daily_completion.user_id");
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void merge(long sourceUserId, long targetUserId) {
        Map<String, Long> targetByName = new HashMap<>();
        for (Row row : liveSubjects(targetUserId)) {
            targetByName.putIfAbsent(normalize(row.name()), row.id());
        }
        int nextOrder = jdbc.sql(
                        "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM study_subject WHERE user_id = :u AND deleted_at IS NULL")
                .param("u", targetUserId)
                .query(Integer.class)
                .single();

        int merged = 0;
        int moved = 0;
        for (Row row : liveSubjects(sourceUserId)) {
            Long keep = targetByName.get(normalize(row.name()));
            if (keep != null) {
                mergeInto(row.id(), keep, sourceUserId, targetUserId);
                merged++;
            } else {
                jdbc.sql("UPDATE study_subject SET user_id = :target, sort_order = :order WHERE id = :id")
                        .param("target", targetUserId)
                        .param("order", nextOrder++)
                        .param("id", row.id())
                        .update();
                targetByName.put(normalize(row.name()), row.id());
                moved++;
            }
        }
        // 지운 과목은 이력이 참조할 수 있어 그대로 옮긴다
        int movedDeleted = jdbc.sql("UPDATE study_subject SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();

        jdbc.sql("DELETE FROM daily_completion d WHERE d.user_id = :source AND EXISTS ("
                        + "SELECT 1 FROM daily_completion t WHERE t.user_id = :target "
                        + "AND t.completed_date = d.completed_date AND t.target_type = d.target_type "
                        + "AND t.target_id = d.target_id)")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();
        jdbc.sql("UPDATE daily_completion SET user_id = :target WHERE user_id = :source")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .update();

        log.info(
                "subjects merged: source={}, target={}, mergedByName={}, moved={}, deletedMoved={}",
                sourceUserId,
                targetUserId,
                merged,
                moved,
                movedDeleted);
    }

    @Override
    public void erase(long userId) {
        jdbc.sql("DELETE FROM daily_completion WHERE user_id = :id")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM study_task WHERE subject_id IN (SELECT id FROM study_subject WHERE user_id = :id)")
                .param("id", userId)
                .update();
        jdbc.sql("DELETE FROM study_subject WHERE user_id = :id")
                .param("id", userId)
                .update();
    }

    private void mergeInto(long sourceSubjectId, long keepSubjectId, long sourceUserId, long targetUserId) {
        jdbc.sql("UPDATE study_session_subject_segment SET subject_id = :keep WHERE subject_id = :src")
                .param("keep", keepSubjectId)
                .param("src", sourceSubjectId)
                .update();
        jdbc.sql("UPDATE study_task SET subject_id = :keep WHERE subject_id = :src")
                .param("keep", keepSubjectId)
                .param("src", sourceSubjectId)
                .update();
        // 같은 날 완료 기록이 이미 합쳐질 과목에 있으면(남는 쪽 것이든 앞서 합쳐진 것이든) 옮기는 쪽 행을 버린다
        jdbc.sql("DELETE FROM daily_completion d WHERE d.user_id = :source AND d.target_type = 'SUBJECT' "
                        + "AND d.target_id = :src AND EXISTS (SELECT 1 FROM daily_completion t "
                        + "WHERE t.user_id IN (:source, :target) AND t.target_type = 'SUBJECT' "
                        + "AND t.target_id = :keep AND t.completed_date = d.completed_date)")
                .param("source", sourceUserId)
                .param("target", targetUserId)
                .param("src", sourceSubjectId)
                .param("keep", keepSubjectId)
                .update();
        jdbc.sql("UPDATE daily_completion SET target_id = :keep "
                        + "WHERE user_id = :source AND target_type = 'SUBJECT' AND target_id = :src")
                .param("keep", keepSubjectId)
                .param("source", sourceUserId)
                .param("src", sourceSubjectId)
                .update();
        jdbc.sql("DELETE FROM study_subject WHERE id = :id")
                .param("id", sourceSubjectId)
                .update();
    }

    private List<Row> liveSubjects(long userId) {
        return jdbc.sql("SELECT id, name FROM study_subject WHERE user_id = :u AND deleted_at IS NULL "
                        + "ORDER BY sort_order, id")
                .param("u", userId)
                .query((rs, n) -> new Row(rs.getLong("id"), rs.getString("name")))
                .list();
    }

    private static String normalize(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).strip();
    }

    private record Row(long id, String name) {}
}
