package project.study.planner.record;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 공통 계정 병합·탈퇴 fixture가 새 사용자 참조 컬럼 두 개를 빠뜨리지 않게 한다. */
@Component
@RequiredArgsConstructor
public class PlannerRecordFixture {
    private final JdbcClient jdbc;

    public void seed(long userId) {
        jdbc.sql("""
                        INSERT INTO planner_record_edit(user_id,record_key,source,started_at,ended_at)
                        VALUES (:u,'m:2030-03-01T12:00:00Z','MANUAL',
                                TIMESTAMPTZ '2030-03-01 12:00:00Z',TIMESTAMPTZ '2030-03-01 13:00:00Z')
                        """).param("u", userId).update();
        jdbc.sql("""
                        INSERT INTO planner_record_operation(user_id,operation_id,payload_hash,result_json)
                        VALUES (:u,'fixture-record-operation',repeat('a',64),
                            '{"operationId":"fixture-record-operation","success":true,"key":"m:2030-03-01T12:00:00Z","status":200,"code":null,"message":null}')
                        """).param("u", userId).update();
    }
}
