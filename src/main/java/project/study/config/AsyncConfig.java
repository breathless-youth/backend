package project.study.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import project.study.common.logging.MdcTaskDecorator;

/**
 * 룸 이력 기록 전용 비동기 실행기.
 *
 * <p>스레드 1개로 고정한다 — 이벤트가 발행 순서대로 처리되어야 "방 생성 기록보다 입장 기록이
 * 먼저 도착해 FK가 깨지는" 순서 역전이 생기지 않는다. 병렬성이 필요해지면 roomUid 단위
 * 파티셔닝 없이는 스레드를 늘릴 수 없다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "roomHistoryExecutor")
    public ThreadPoolTaskExecutor roomHistoryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("room-history-");
        // 발행 스레드(HTTP/STOMP)의 MDC를 넘겨 이력 기록 로그에도 userId·requestId가 붙게 한다
        executor.setTaskDecorator(new MdcTaskDecorator());
        return executor;
    }
}
