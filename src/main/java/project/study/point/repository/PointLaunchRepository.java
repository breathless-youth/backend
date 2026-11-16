package project.study.point.repository;

import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 포인트를 쌓기 시작한 날 (BY-1314). */
@Repository
@RequiredArgsConstructor
public class PointLaunchRepository {

    private final JdbcClient jdbc;

    public Optional<LocalDate> startedOn(String version) {
        return jdbc.sql("SELECT started_on FROM point_launch WHERE version = :v")
                .param("v", version)
                .query(LocalDate.class)
                .optional();
    }

    /** 처음 적었으면 true. 이미 있으면 그대로 둔다. */
    public boolean start(String version, LocalDate startedOn) {
        return jdbc.sql("""
                                INSERT INTO point_launch (version, started_on) VALUES (:v, :d)
                                ON CONFLICT (version) DO NOTHING""").param("v", version).param("d", startedOn).update() == 1;
    }
}
