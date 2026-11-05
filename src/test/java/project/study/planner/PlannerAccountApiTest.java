package project.study.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import project.study.subject.service.SubjectUserDataHandler;
import project.study.user.account.UserDataHandler;

class PlannerAccountApiTest extends PlannerApiSupport {
    @Autowired
    List<UserDataHandler> handlers;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void mergedPersonalRecordUsesCanonicalSubject() {
        long source = user;
        jdbc.update(
                "INSERT INTO planner_record_edit(user_id,record_key,source,started_at,ended_at,subject_id,deleted) VALUES (?, 'm:2026-10-01T10:00:00Z','MANUAL','2026-10-01T10:00:00Z','2026-10-01T11:00:00Z',?,false)",
                user,
                subject);
        long target = newUser();
        long keep = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?, '수학') RETURNING id", Long.class, target);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> handlers.stream()
                        .filter(h -> h.columns().contains("planner_series.user_id")
                                || h.columns().contains("planner_record_edit.user_id")
                                || h instanceof SubjectUserDataHandler)
                        .sorted(java.util.Comparator.comparingInt(UserDataHandler::order))
                        .forEach(h -> h.merge(source, target)));
        user = target;
        var result = day(java.time.LocalDate.of(2026, 10, 1));
        assertThat(result.get("subjects")).hasSize(1);
        assertThat(result.at("/subjects/0/id").asLong()).isEqualTo(keep);
        assertThat(result.at("/subjects/0/focusSec").asLong()).isEqualTo(3600);
    }

    @Test
    void accountMergeKeepsPlanAndRecurrenceOnCanonicalSubject() {
        long sourceUser = user;
        long sourceSubject = subject;
        String key = add(today.plusDays(1), "계획");
        call("PUT", "/api/planner/days/" + today.plusDays(1) + "/subjects/" + subject, null, 204);
        call(
                "PUT",
                "/api/planner/tasks/" + key + "/repeat",
                "{\"weekdays\":[\"MONDAY\",\"TUESDAY\",\"WEDNESDAY\",\"THURSDAY\",\"FRIDAY\",\"SATURDAY\",\"SUNDAY\"]}",
                200);
        long target = newUser();
        long keep = jdbc.queryForObject(
                "INSERT INTO study_subject(user_id,name) VALUES (?, '수학') RETURNING id", Long.class, target);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        transaction.executeWithoutResult(status -> handlers.stream()
                .filter(h -> h.columns().contains("planner_series.user_id") || h instanceof SubjectUserDataHandler)
                .sorted(java.util.Comparator.comparingInt(UserDataHandler::order))
                .forEach(h -> h.merge(sourceUser, target)));
        user = target;
        assertThat(day(today.plusDays(1)).get("subjects")).hasSize(1);
        assertThat(day(today.plusDays(1)).at("/subjects/0/id").asLong()).isEqualTo(keep);
        assertThat(day(today.plusDays(2)).at("/subjects/0/id").asLong()).isEqualTo(keep);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM planner_series WHERE user_id=?", Integer.class, sourceUser))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM planner_day_subject WHERE user_id=?", Integer.class, sourceUser))
                .isZero();
    }
}
