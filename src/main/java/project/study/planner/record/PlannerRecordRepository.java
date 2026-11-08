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
import tools.jackson.core.type.TypeReference;
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
                        rs.getBoolean("preserve_original_totals"),
                        pieces(rs.getString("pieces")),
                        rs.getLong("revision")))
                .list();
    }

    /**
     * 저장 후 기록 버전을 반환한다. 기존 행은 1씩 오른다(삭제 후 같은 키를 다시 만들어도 이전 버전과 겹치지 않음).
     * 새 행은 1이고, 계정 병합으로 옮기는 행은 전달한 버전을 유지한다.
     */
    long saveEdit(Long userId, PlannerRecordEdit edit) {
        return jdbc.sql("""
                        INSERT INTO planner_record_edit(user_id,record_key,source,started_at,ended_at,subject_id,deleted,
                            preserve_original_totals,pieces,revision)
                        VALUES (:u,:k,:source,:s,:e,:subject,:deleted,:preserve,CAST(:pieces AS jsonb),GREATEST(:revision,1))
                        ON CONFLICT (user_id,record_key) DO UPDATE SET started_at=EXCLUDED.started_at,
                        ended_at=EXCLUDED.ended_at,subject_id=EXCLUDED.subject_id,deleted=EXCLUDED.deleted,
                        preserve_original_totals=EXCLUDED.preserve_original_totals,pieces=EXCLUDED.pieces,
                        revision=planner_record_edit.revision+1
                        RETURNING revision
                        """)
                .param("u", userId)
                .param("k", edit.key())
                .param("source", edit.source().name())
                .param("s", Timestamp.from(edit.startedAt()))
                .param("e", Timestamp.from(edit.endedAt()))
                .param("subject", edit.subjectId(), Types.BIGINT)
                .param("deleted", edit.deleted())
                .param("preserve", edit.preserveOriginalTotals())
                .param("revision", edit.revision())
                .param("pieces", edit.pieces() == null ? null : mapper.writeValueAsString(edit.pieces()), Types.VARCHAR)
                .query(Long.class)
                .single();
    }

    private List<PlannerRecordPiece> pieces(String json) {
        return json == null
                ? null
                : List.copyOf(mapper.readValue(json, new TypeReference<List<PlannerRecordPiece>>() {}));
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
