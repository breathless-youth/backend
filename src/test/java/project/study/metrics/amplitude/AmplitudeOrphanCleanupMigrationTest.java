package project.study.metrics.amplitude;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V61 상태의 DB에 남은 Amplitude 고아 행을 V62가 지우고 회원의 행은 남기는지 본다. Spring의 이미 이전된 DB와 분리한다. */
class AmplitudeOrphanCleanupMigrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void users에_없는_id의_행만_지운다() {
        String schema = "amplitude_cleanup_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dataSource.setSchema(schema);
        JdbcClient jdbc = JdbcClient.create(dataSource);
        flyway(dataSource, schema, "61").migrate();
        long member = jdbc.sql("INSERT INTO users (provider, provider_user_id) VALUES ('DEVICE', 'm') RETURNING id")
                .query(Long.class)
                .single();
        long orphan = member + 1000;
        for (long userId : new long[] {member, orphan}) {
            jdbc.sql("INSERT INTO amplitude_daily_event (user_id, event_date, event_key, count)"
                            + " VALUES (:u, DATE '2026-10-01', 'app_launched', 1)")
                    .param("u", userId)
                    .update();
            jdbc.sql("INSERT INTO amplitude_user_attribute (user_id, first_seen_at, last_seen_at)"
                            + " VALUES (:u, now(), now())")
                    .param("u", userId)
                    .update();
        }

        flyway(dataSource, schema, "62").migrate();

        assertThat(jdbc.sql("SELECT user_id FROM amplitude_daily_event")
                        .query(Long.class)
                        .list())
                .containsExactly(member);
        assertThat(jdbc.sql("SELECT user_id FROM amplitude_user_attribute")
                        .query(Long.class)
                        .list())
                .containsExactly(member);
    }

    private static Flyway flyway(DataSource dataSource, String schema, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
