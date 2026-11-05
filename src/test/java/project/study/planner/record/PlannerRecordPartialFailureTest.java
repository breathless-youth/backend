package project.study.planner.record;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import project.study.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlannerRecordPartialFailureTest {
    @Autowired
    private PlannerRecordBatchService service;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private PlannerRecordMutationService mutations;

    @Test
    void unexpectedItemFailureDoesNotPreventLaterIndependentSuccess() {
        long user = jdbc.queryForObject(
                "INSERT INTO users(provider,provider_user_id,nickname) VALUES('test',?,?) RETURNING id",
                Long.class,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
        PlannerRecordOperation first = operation("first", "10:00", "11:00");
        PlannerRecordOperation failed = operation("failed", "12:00", "13:00");
        PlannerRecordOperation last = operation("last", "14:00", "15:00");
        // 데이터베이스 등 예측하지 못한 하위 실패를 주입하되 나머지는 실제 트랜잭션과 저장을 사용한다.
        doThrow(new IllegalStateException("simulated transient failure"))
                .when(mutations)
                .mutate(user, failed);
        PlannerRecordBatchResponse response =
                service.batch(user, new PlannerRecordBatchRequest(List.of(first, failed, last)));
        assertThat(response.results()).extracting(PlannerRecordResult::status).containsExactly(200, 500, 200);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM planner_record_edit WHERE user_id=? AND NOT deleted",
                        Integer.class,
                        user))
                .isEqualTo(2);
    }

    private static PlannerRecordOperation operation(String id, String start, String end) {
        return new PlannerRecordOperation(
                id,
                "CREATE",
                null,
                Instant.parse("2026-10-01T" + start + ":00Z"),
                Instant.parse("2026-10-01T" + end + ":00Z"),
                null,
                null);
    }
}
