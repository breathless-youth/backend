package project.study.room.lease;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import project.study.room.repository.TaskLeaseRepository;

/**
 * heartbeat 전용 1커넥션 풀. HTTP·STOMP·cleanup이 기본 Hikari 풀을 다 점유해도 리스는 기다리지 않는다.
 * DataSource 빈으로 등록하지 않는다 — 두 번째 DataSource 빈이 생기면 Boot의 기본 DataSource 자동 구성이 물러난다.
 */
@Component
@RequiredArgsConstructor
public class LeaseConnectionFactory {

    public record LeaseConnection(TaskLeaseRepository leases, Runnable closer) implements AutoCloseable {
        @Override
        public void close() {
            closer.run();
        }
    }

    private final JdbcConnectionDetails connectionDetails;

    public LeaseConnection open() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("lease");
        config.setJdbcUrl(connectionDetails.getJdbcUrl());
        config.setUsername(connectionDetails.getUsername());
        config.setPassword(connectionDetails.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        // 멈춘 소켓이 단 하나뿐인 heartbeat 스레드를 영영 붙잡으면 이 태스크는 reclaimed_at을 볼 수 없어
        // 끝내 펜싱하지 못한다 — 회수당한 뒤에도 살아 있는 소켓이 남는다. (pgjdbc 속성, socketTimeout은 초)
        config.addDataSourceProperty("socketTimeout", "5");
        config.addDataSourceProperty("tcpKeepAlive", "true");
        HikariDataSource dataSource = new HikariDataSource(config);
        return new LeaseConnection(new TaskLeaseRepository(JdbcClient.create(dataSource)), dataSource::close);
    }
}
