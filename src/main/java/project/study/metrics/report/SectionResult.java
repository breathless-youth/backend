package project.study.metrics.report;

import io.sentry.Sentry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** 섹션 하나의 계산 결과. 한 섹션이 실패해도 나머지는 보낸다 — 실패한 섹션은 "계산 실패"로 쓰고 Sentry에 남긴다 (스펙 §10). */
@Slf4j
public final class SectionResult<T> {

    private final T value;

    private SectionResult(T value) {
        this.value = value;
    }

    public static <T> SectionResult<T> of(T value) {
        return new SectionResult<>(Objects.requireNonNull(value));
    }

    public static <T> SectionResult<T> failed() {
        return new SectionResult<>(null);
    }

    public static <T> SectionResult<T> compute(String section, Supplier<T> supplier) {
        try {
            return of(supplier.get());
        } catch (RuntimeException e) {
            log.error("일일 리포트 섹션 계산 실패 (섹션={})", section, e);
            Sentry.captureException(e);
            return failed();
        }
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }
}
