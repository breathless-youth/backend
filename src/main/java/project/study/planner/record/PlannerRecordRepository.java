package project.study.planner.record;

import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import project.study.common.exception.ConflictException;
import project.study.studysession.entity.StudySessionSource;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
class PlannerRecordRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    List<PlannerRecordEdit> edits(Long userId) {
        return jdbc.sql("SELECT * FROM planner_record_edit WHERE user_id = :u")
                .param("u", userId)
                .query((rs, row) -> new PlannerRecordEdit(
                        rs.getString("record_key"),
                        StudySessionSource.valueOf(rs.getString("source")),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("ended_at").toInstant(),
                        rs.getObject("subject_id", Long.class),
                        rs.getBoolean("deleted"),
                        rs.getBoolean("preserve_original_totals")))
                .list();
    }

    void saveEdit(Long userId, PlannerRecordEdit edit) {
        jdbc.sql("""
                        INSERT INTO planner_record_edit(user_id,record_key,source,started_at,ended_at,subject_id,deleted,preserve_original_totals)
                        VALUES (:u,:k,:source,:s,:e,:subject,:deleted,:preserve)
                        ON CONFLICT (user_id,record_key) DO UPDATE SET started_at=EXCLUDED.started_at,
                        ended_at=EXCLUDED.ended_at,subject_id=EXCLUDED.subject_id,deleted=EXCLUDED.deleted,
                        preserve_original_totals=EXCLUDED.preserve_original_totals
                        """)
                .param("u", userId)
                .param("k", edit.key())
                .param("source", edit.source().name())
                .param("s", Timestamp.from(edit.startedAt()))
                .param("e", Timestamp.from(edit.endedAt()))
                .param("subject", edit.subjectId(), Types.BIGINT)
                .param("deleted", edit.deleted())
                .param("preserve", edit.preserveOriginalTotals())
                .update();
    }

    Optional<PlannerRecordResult> replay(Long userId, PlannerRecordOperation operation, String hash) {
        return jdbc.sql(
                        "SELECT payload_hash,result_json FROM planner_record_operation WHERE user_id=:u AND operation_id=:op")
                .param("u", userId)
                .param("op", operation.operationId())
                .query((rs, row) -> {
                    if (!rs.getString("payload_hash").equals(hash)) {
                        throw new ConflictException("operationId에 다른 요청을 사용할 수 없습니다");
                    }
                    return mapper.readValue(rs.getString("result_json"), PlannerRecordResult.class);
                })
                .optional();
    }

    void saveResult(Long userId, PlannerRecordOperation operation, String hash, PlannerRecordResult result) {
        jdbc.sql(
                        "INSERT INTO planner_record_operation(user_id,operation_id,payload_hash,result_json) VALUES (:u,:op,:hash,:result)")
                .param("u", userId)
                .param("op", operation.operationId())
                .param("hash", hash)
                .param("result", mapper.writeValueAsString(result))
                .update();
    }
}
