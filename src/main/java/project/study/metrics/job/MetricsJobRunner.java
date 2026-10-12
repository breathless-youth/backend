package project.study.metrics.job;

import io.sentry.Sentry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 지표 작업(Amplitude 적재·일일 리포트)을 도는 단일 스레드. 스케줄 메서드는 여기에 제출만 하고 바로 돌아간다 — prod 스케줄러
 * 풀(2개)을 몇 분씩 붙잡아 룸 정리·랭킹 마감이 밀리지 않게 (ADR-0030 결정 7).
 *
 * <p>실행기를 {@code TaskScheduler}·{@code Executor} 타입 빈으로 만들지 않는다. 그런 빈이 생기면 Boot의 기본 스케줄러·실행기
 * 자동 구성이 물러나 다른 {@code @Scheduled}까지 이 스레드로 돈다.
 *
 * <p>예외를 직접 잡아 Sentry로 올린다 — 실행기 안에서 난 예외는 {@code Future}에 갇혀 아무 데도 남지 않는다.
 */
@Slf4j
@Component
public class MetricsJobRunner {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("metrics-job").daemon(true).factory());

    public Future<?> submit(String jobName, Runnable job) {
        return executor.submit(() -> {
            try {
                job.run();
            } catch (Exception e) {
                log.error("지표 작업 실패 (작업={})", jobName, e);
                Sentry.captureException(e);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
